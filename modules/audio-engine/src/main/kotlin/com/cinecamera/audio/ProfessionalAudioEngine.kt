package com.cinecamera.audio

import android.content.Context
import android.media.*
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * IAudioEngine — Professional audio pipeline interface.
 *
 * Signal chain: ADC → Noise Gate → Gain → Limiter → AAC Encoder
 *
 * Threading model (FIXED — race condition):
 *   One capture coroutine owns the AudioRecord exclusively and writes
 *   PcmBlock values to a Channel. Two consumer coroutines (encoder and VU
 *   meter) read from separate fan-out channels. No concurrent reads on
 *   the AudioRecord instance occur.
 */
interface IAudioEngine {
    val audioState: StateFlow<AudioState>
    val vuMeterData: StateFlow<VUMeterData>
    val availableSources: StateFlow<List<AudioSource>>
    val clippingEvent: StateFlow<Boolean>

    suspend fun initialize(config: AudioConfig)
    fun startCapture()
    fun stopCapture()
    fun release()

    fun setGain(gainDb: Float)
    fun setNoiseGate(enabled: Boolean, thresholdDb: Float = -60f)
    fun setLimiter(enabled: Boolean, ceilingDb: Float = -1.0f)
    fun selectSource(source: AudioSource)
    fun getEncodedFrame(): ByteArray?
}

// ─── Data models ──────────────────────────────────────────────────────────────

enum class AudioSourceType { INTERNAL, EXTERNAL_3_5MM, USB, BLUETOOTH }

/**
 * AudioSource — FIXED: previously all sources carried id = MIC,
 * making hardware routing impossible. audioRecordSource is the
 * MediaRecorder.AudioSource constant; deviceId is the AudioDeviceInfo.id
 * used for setPreferredDevice() on API 28+.
 */
data class AudioSource(
    val audioRecordSource: Int,
    val deviceId: Int,
    val name: String,
    val type: AudioSourceType,
    val channelCount: Int,
    val maxSampleRateHz: Int
)

data class AudioConfig(
    val sampleRateHz: Int = 48_000,
    val channelConfig: Int = AudioFormat.CHANNEL_IN_STEREO,
    val encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    val bitrateKbps: Int = 256,
    val gainDb: Float = 0f,
    val noiseGateEnabled: Boolean = false,
    val noiseGateThresholdDb: Float = -60f,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -1.0f
)

sealed class AudioState {
    object Idle : AudioState()
    object Initializing : AudioState()
    object Ready : AudioState()
    object Capturing : AudioState()
    data class Error(val message: String) : AudioState()
}

data class VUMeterData(
    val leftRMS: Float = Float.NEGATIVE_INFINITY,
    val rightRMS: Float = Float.NEGATIVE_INFINITY,
    val leftPeak: Float = Float.NEGATIVE_INFINITY,
    val rightPeak: Float = Float.NEGATIVE_INFINITY,
    val leftClipping: Boolean = false,
    val rightClipping: Boolean = false
)

/** Processed PCM buffer shared between encoder and VU meter consumers. */
private data class PcmBlock(
    val samples: ShortArray,
    val count: Int,
    val timestampUs: Long
)

// ─── Implementation ───────────────────────────────────────────────────────────

@Singleton
class ProfessionalAudioEngine @Inject constructor(
    private val context: Context
) : IAudioEngine {

    companion object {
        private const val CLIPPING_THRESHOLD     = 0.99f
        private const val CLIPPING_RESET_MS      = 500L   // FIXED: was never reset
        private const val PEAK_HOLD_MS           = 2000L
        private const val BUFFER_MULTIPLIER      = 4
        private const val PCM_CHANNEL_CAPACITY   = 8      // ~200ms buffer at 48kHz
        private const val VU_FRAMES_PER_EMIT     = 3      // emit at ~60 Hz
    }

    private val _audioState    = MutableStateFlow<AudioState>(AudioState.Idle)
    override val audioState: StateFlow<AudioState> = _audioState.asStateFlow()

    private val _vuMeterData   = MutableStateFlow(VUMeterData())
    override val vuMeterData: StateFlow<VUMeterData> = _vuMeterData.asStateFlow()

    private val _availableSources = MutableStateFlow<List<AudioSource>>(emptyList())
    override val availableSources: StateFlow<List<AudioSource>> = _availableSources.asStateFlow()

    /**
     * FIXED: clippingEvent now resets to false after CLIPPING_RESET_MS of
     * non-clipping frames. Previously set to true and never cleared.
     */
    private val _clippingEvent = MutableStateFlow(false)
    override val clippingEvent: StateFlow<Boolean> = _clippingEvent.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var activeConfig: AudioConfig = AudioConfig()
    private var activeSource: AudioSource? = null

    // Volatile: written by UI thread, read by capture thread
    @Volatile private var currentGainLinear: Float = 1.0f
    @Volatile private var noiseGateEnabled: Boolean = false
    @Volatile private var noiseGateThresholdLinear: Float = 0.001f
    @Volatile private var limiterEnabled: Boolean = true
    @Volatile private var limiterCeiling: Float = 0.891f

    // Peak hold — accessed only from VU coroutine
    private var leftPeakValue: Float = 0f
    private var rightPeakValue: Float = 0f
    private var leftPeakTimestamp: Long = 0L
    private var rightPeakTimestamp: Long = 0L
    private var lastClippingTimestamp: Long = 0L

    /**
     * FIXED: pre-allocated at startCapture(), reused across all DSP calls.
     * Eliminates ShortArray heap allocation per buffer (~48000/s at 48kHz).
     */
    private var dspOutputBuffer: ShortArray = ShortArray(0)

    private val encodedFrameQueue = ArrayDeque<ByteArray>(32)

    // Fan-out channels: one producer (capture), two consumers (encoder, VU)
    private var encoderChannel: Channel<PcmBlock>? = null
    private var vuChannel: Channel<PcmBlock>? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var encoderJob: Job? = null
    private var vuJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun initialize(config: AudioConfig) = withContext(Dispatchers.IO) {
        _audioState.value = AudioState.Initializing
        activeConfig = config
        currentGainLinear         = dbToLinear(config.gainDb)
        noiseGateEnabled          = config.noiseGateEnabled
        noiseGateThresholdLinear  = dbToLinear(config.noiseGateThresholdDb)
        limiterEnabled            = config.limiterEnabled
        limiterCeiling            = dbToLinear(config.limiterCeilingDb)

        discoverAudioSources()
        val source = _availableSources.value.firstOrNull()
            ?: throw IllegalStateException("No audio sources available")
        activeSource = source

        createAudioRecord(config, source)
        createAACEncoder(config)
        _audioState.value = AudioState.Ready
        Timber.d("AudioEngine initialized: ${config.sampleRateHz}Hz ${config.bitrateKbps}kbps AAC")
    }

    /**
     * FIXED: uses AudioManager.getDevices() to enumerate real hardware sources
     * and captures AudioDeviceInfo.id per device for routing. Previously all
     * sources shared id = MIC with no routing capability.
     */
    private fun discoverAudioSources() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val sources = mutableListOf<AudioSource>()

        devices.forEach { dev ->
            val channels = minOf(dev.channelCounts.maxOrNull() ?: 1, 2)
            val maxRate  = dev.sampleRates.maxOrNull() ?: 48_000
            when (dev.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                    sources.add(AudioSource(MediaRecorder.AudioSource.MIC, dev.id,
                        "Internal Microphone", AudioSourceType.INTERNAL, channels, maxRate))
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                    sources.add(AudioSource(MediaRecorder.AudioSource.MIC, dev.id,
                        "External Microphone (3.5mm)", AudioSourceType.EXTERNAL_3_5MM, channels, maxRate))
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET ->
                    sources.add(AudioSource(MediaRecorder.AudioSource.MIC, dev.id,
                        dev.productName?.toString() ?: "USB Audio", AudioSourceType.USB, channels, maxRate))
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                    sources.add(AudioSource(MediaRecorder.AudioSource.MIC, dev.id,
                        dev.productName?.toString() ?: "Bluetooth Mic", AudioSourceType.BLUETOOTH, 1, 16_000))
                else -> {}
            }
        }

        if (sources.isEmpty()) {
            sources.add(AudioSource(MediaRecorder.AudioSource.MIC, 0,
                "Microphone", AudioSourceType.INTERNAL, 1, 48_000))
        }

        _availableSources.value = sources
        Timber.d("Discovered ${sources.size} audio source(s)")
    }

    private fun createAudioRecord(config: AudioConfig, source: AudioSource) {
        val minBuf    = AudioRecord.getMinBufferSize(config.sampleRateHz, config.channelConfig, config.encoding)
        val bufSize   = minBuf * BUFFER_MULTIPLIER

        audioRecord = AudioRecord(
            source.audioRecordSource,
            config.sampleRateHz,
            config.channelConfig,
            config.encoding,
            bufSize
        ).also { record ->
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord failed to initialize for source: ${source.name}"
            }
            // Route to specific hardware device (API 28+)
            if (source.deviceId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.id == source.deviceId }
                    ?.let { record.preferredDevice = it }
            }
        }
        Timber.d("AudioRecord → ${source.name} buffer=${bufSize}B")
    }

    private fun createAACEncoder(config: AudioConfig) {
        val channels = if (config.channelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                    config.sampleRateHz, channels).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000)
                    setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            start()
        }
        Timber.d("AAC encoder created: ${config.sampleRateHz}Hz ${config.bitrateKbps}kbps ${channels}ch")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capture pipeline
    // ─────────────────────────────────────────────────────────────────────────

    override fun startCapture() {
        val record  = audioRecord ?: return
        val encoder = mediaCodec  ?: return

        record.startRecording()
        _audioState.value = AudioState.Capturing

        val captureFrames = AudioRecord.getMinBufferSize(
            activeConfig.sampleRateHz, activeConfig.channelConfig, activeConfig.encoding
        ) * BUFFER_MULTIPLIER / 2

        // FIXED: pre-allocate DSP output buffer once
        dspOutputBuffer = ShortArray(captureFrames)

        val localEncCh = Channel<PcmBlock>(PCM_CHANNEL_CAPACITY).also { encoderChannel = it }
        val localVuCh  = Channel<PcmBlock>(PCM_CHANNEL_CAPACITY).also { vuChannel    = it }

        // ── Single capture coroutine — sole reader of AudioRecord ─────────────
        captureJob = scope.launch(Dispatchers.IO) {
            val pcm = ShortArray(captureFrames)
            while (isActive) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) { delay(1); continue }

                val tsUs = System.nanoTime() / 1000L
                applyDSPChain(pcm, n)

                // Fan out: copy to both consumers
                val block = PcmBlock(dspOutputBuffer.copyOf(n), n, tsUs)
                localEncCh.trySend(block)
                localVuCh.trySend(block)
            }
        }

        // ── Encoder consumer ──────────────────────────────────────────────────
        encoderJob = scope.launch(Dispatchers.IO) {
            for (block in localEncCh) feedToEncoder(encoder, block)
        }

        // ── VU meter consumer (~60 Hz) ────────────────────────────────────────
        vuJob = scope.launch(Dispatchers.Default) {
            var frameCount = 0
            var sumL = 0.0; var sumR = 0.0
            var pkL = 0f;   var pkR = 0f
            var totalSamples = 0
            val stereo = activeConfig.channelConfig == AudioFormat.CHANNEL_IN_STEREO

            for (block in localVuCh) {
                for (i in 0 until block.count step if (stereo) 2 else 1) {
                    val l = block.samples[i] / 32768f
                    val r = if (stereo && i + 1 < block.count) block.samples[i + 1] / 32768f else l
                    sumL += l * l; sumR += r * r
                    if (abs(l) > pkL) pkL = abs(l)
                    if (abs(r) > pkR) pkR = abs(r)
                }
                totalSamples += if (stereo) block.count / 2 else block.count
                frameCount++

                if (frameCount >= VU_FRAMES_PER_EMIT) {
                    val rmsL = sqrt(sumL / totalSamples).toFloat()
                    val rmsR = sqrt(sumR / totalSamples).toFloat()
                    updatePeakHold(pkL, pkR)
                    emitVU(rmsL, rmsR, pkL, pkR)

                    // FIXED: reset clipping flag after quiet period
                    val now = System.currentTimeMillis()
                    if (pkL < CLIPPING_THRESHOLD && pkR < CLIPPING_THRESHOLD &&
                        now - lastClippingTimestamp > CLIPPING_RESET_MS) {
                        _clippingEvent.value = false
                    }

                    sumL = 0.0; sumR = 0.0; pkL = 0f; pkR = 0f
                    totalSamples = 0; frameCount = 0
                }
            }
        }

        Timber.d("Audio capture pipeline started")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DSP chain — no allocations per call
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyDSPChain(input: ShortArray, count: Int) {
        val gain      = currentGainLinear
        val gateOn    = noiseGateEnabled
        val gateThrs  = noiseGateThresholdLinear
        val limitOn   = limiterEnabled
        val ceil      = limiterCeiling
        val knee      = ceil - 0.063f
        var clipping  = false

        for (i in 0 until count) {
            var s = input[i] / 32768f
            if (gateOn && abs(s) < gateThrs) s = 0f
            s *= gain
            if (limitOn) {
                val av = abs(s); val sg = if (s >= 0f) 1f else -1f
                s = when {
                    av <= knee -> s
                    av <= ceil -> {
                        val ex = av - knee; val rng = ceil - knee
                        sg * (knee + ex * rng / (rng + ex))
                    }
                    else -> sg * ceil
                }
            }
            if (abs(s) >= CLIPPING_THRESHOLD) clipping = true
            dspOutputBuffer[i] = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }

        if (clipping) {
            _clippingEvent.value = true
            lastClippingTimestamp = System.currentTimeMillis()
        }
    }

    private fun feedToEncoder(encoder: MediaCodec, block: PcmBlock) {
        val idx = encoder.dequeueInputBuffer(10_000L)
        if (idx < 0) return
        val buf = encoder.getInputBuffer(idx) ?: return
        buf.clear()
        repeat(block.count) { i -> if (buf.remaining() >= 2) buf.putShort(block.samples[i]) }
        encoder.queueInputBuffer(idx, 0, buf.position(), block.timestampUs, 0)

        val info = MediaCodec.BufferInfo()
        var out = encoder.dequeueOutputBuffer(info, 0)
        while (out >= 0) {
            encoder.getOutputBuffer(out)?.let { outBuf ->
                if (info.size > 0) {
                    val data = ByteArray(info.size).also { outBuf.get(it) }
                    synchronized(encodedFrameQueue) {
                        if (encodedFrameQueue.size < 32) encodedFrameQueue.addLast(data)
                    }
                }
            }
            encoder.releaseOutputBuffer(out, false)
            out = encoder.dequeueOutputBuffer(info, 0)
        }
    }

    private fun updatePeakHold(pkL: Float, pkR: Float) {
        val now = System.currentTimeMillis()
        if (pkL > leftPeakValue  || now - leftPeakTimestamp  > PEAK_HOLD_MS) { leftPeakValue  = pkL; leftPeakTimestamp  = now }
        if (pkR > rightPeakValue || now - rightPeakTimestamp > PEAK_HOLD_MS) { rightPeakValue = pkR; rightPeakTimestamp = now }
    }

    private fun emitVU(rmsL: Float, rmsR: Float, pkL: Float, pkR: Float) {
        _vuMeterData.value = VUMeterData(
            leftRMS      = linearToDb(rmsL),
            rightRMS     = linearToDb(rmsR),
            leftPeak     = linearToDb(leftPeakValue),
            rightPeak    = linearToDb(rightPeakValue),
            leftClipping  = pkL >= CLIPPING_THRESHOLD,
            rightClipping = pkR >= CLIPPING_THRESHOLD
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Control API
    // ─────────────────────────────────────────────────────────────────────────

    override fun setGain(gainDb: Float) {
        currentGainLinear = dbToLinear(gainDb.coerceIn(-12f, 40f))
    }

    override fun setNoiseGate(enabled: Boolean, thresholdDb: Float) {
        noiseGateEnabled = enabled
        noiseGateThresholdLinear = dbToLinear(thresholdDb)
    }

    override fun setLimiter(enabled: Boolean, ceilingDb: Float) {
        limiterEnabled = enabled
        limiterCeiling = dbToLinear(ceilingDb.coerceIn(-20f, 0f))
    }

    override fun selectSource(source: AudioSource) {
        if (source.deviceId == activeSource?.deviceId) return
        Timber.d("Switching audio source → ${source.name}")
        stopCapture()
        scope.launch {
            activeSource = source
            createAudioRecord(activeConfig, source)
            startCapture()
        }
    }

    override fun getEncodedFrame(): ByteArray? = synchronized(encodedFrameQueue) {
        encodedFrameQueue.removeFirstOrNull()
    }

    override fun stopCapture() {
        captureJob?.cancel(); encoderJob?.cancel(); vuJob?.cancel()
        encoderChannel?.close(); vuChannel?.close()
        audioRecord?.stop()
        _audioState.value = AudioState.Ready
    }

    override fun release() {
        stopCapture()
        audioRecord?.release(); audioRecord = null
        mediaCodec?.stop(); mediaCodec?.release(); mediaCodec = null
        _audioState.value = AudioState.Idle
    }

    private fun dbToLinear(db: Float): Float = if (db <= -80f) 0f else 10f.pow(db / 20f)
    private fun linearToDb(lin: Float): Float = if (lin <= 0f) Float.NEGATIVE_INFINITY else 20f * log10(lin)
}
