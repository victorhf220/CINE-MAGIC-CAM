package com.cinecamera.streaming

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RTMPStreamingEngine
 *
 * Implements the RTMP(S) push streaming pipeline, compatible with:
 *   - YouTube Live (rtmp://a.rtmp.youtube.com/live2)
 *   - Facebook Live (rtmps://live-api-s.facebook.com:443/rtmp/)
 *   - Twitch (rtmp://live.twitch.tv/app)
 *   - Custom Nginx/Wowza/SRS servers
 *
 * RTMP protocol implementation is handled by the native C++ layer (librtmp
 * compiled via NDK), exposed through JNI. Kotlin manages lifecycle,
 * reconnection scheduling, and state machine transitions.
 *
 * Stream container: FLV (Flash Video), standard RTMP encapsulation.
 * Video: H.264 AVC (RTMP does not support H.265 in the standard spec).
 * Audio: AAC-LC.
 *
 * Multi-stream support (Enterprise tier):
 *   Multiple RTMPStreamingEngine instances can run concurrently, each
 *   receiving the same encoded NAL units via broadcast from the encoding
 *   pipeline. Memory overhead is dominated by the socket buffers (~1 MB each).
 */
@Singleton
class RTMPStreamingEngine @Inject constructor(
    private val reconnectionManager: ReconnectionManager
) {
    private val _state = MutableStateFlow<RTMPState>(RTMPState.Idle)
    val state: StateFlow<RTMPState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(RTMPStats())
    val stats: StateFlow<RTMPStats> = _stats.asStateFlow()

    private val isStreaming = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null
    private var config: RTMPConfig = RTMPConfig()
    private var nativeHandle: Long = -1L

    // FLV sequence header — must be sent before any media data
    // Constructed once from SPS/PPS (video) and AudioSpecificConfig (audio)
    private var videoSequenceHeader: ByteArray? = null
    private var audioSequenceHeader: ByteArray? = null
    private var sequenceHeaderSent = false

    // ─────────────────────────────────────────────────────────────────────────
    // Connection management
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun connect(config: RTMPConfig) {
        this.config = config
        _state.value = RTMPState.Connecting

        val url = buildRTMPUrl(config)
        Timber.i("RTMP connect: $url")

        nativeHandle = nativeConnect(url, config.connectTimeoutMs, config.useTLS)

        if (nativeHandle < 0) {
            val error = "RTMP connection failed to ${config.host}"
            _state.value = RTMPState.Error(error)
            Timber.e(error)
            reconnectionManager.scheduleReconnect { connect(config) }
            return
        }

        // Send FLV metadata tag before media data
        sendMetadataTag(config)

        isStreaming.set(true)
        sequenceHeaderSent = false
        _state.value = RTMPState.Live(config.host, config.streamKey.take(8) + "***")
        startStatsMonitoring()
        Timber.i("RTMP connected successfully")
    }

    fun disconnect() {
        if (!isStreaming.compareAndSet(true, false)) return
        statsJob?.cancel()
        reconnectionManager.cancel()
        sequenceHeaderSent = false

        if (nativeHandle >= 0) {
            nativeDisconnect(nativeHandle)
            nativeHandle = -1L
        }
        _state.value = RTMPState.Idle
        Timber.i("RTMP disconnected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Media data ingestion
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Receives a codec config (SPS/PPS) and stores it for the sequence header.
     * Must be called before any media NAL units are sent.
     */
    fun onVideoCodecConfig(sps: ByteArray, pps: ByteArray) {
        videoSequenceHeader = buildAVCSequenceHeader(sps, pps)
    }

    fun onAudioCodecConfig(audioSpecificConfig: ByteArray) {
        audioSequenceHeader = buildAACSequenceHeader(audioSpecificConfig)
    }

    /**
     * Sends a video NAL unit as an FLV video tag.
     * If the sequence headers have not yet been sent, they are transmitted first.
     */
    fun sendVideoNAL(nalData: ByteArray, ptsUs: Long, isKeyFrame: Boolean) {
        if (!isStreaming.get() || nativeHandle < 0) return

        // Ensure sequence headers precede media data
        if (!sequenceHeaderSent) {
            videoSequenceHeader?.let { sendFLVTag(FLVTagType.VIDEO, it, 0, isSequenceHeader = true) }
            audioSequenceHeader?.let { sendFLVTag(FLVTagType.AUDIO, it, 0, isSequenceHeader = true) }
            sequenceHeaderSent = true
        }

        // Wrap NAL in AVC NALU (4-byte length prefix, no start code)
        val flvPayload = buildAVCNALUPayload(nalData, isKeyFrame)
        val timestampMs = (ptsUs / 1000).toInt()

        val result = sendFLVTag(FLVTagType.VIDEO, flvPayload, timestampMs)
        if (result < 0) handleSendFailure("Video send error: $result")
    }

    fun sendAudioFrame(aacData: ByteArray, ptsUs: Long) {
        if (!isStreaming.get() || nativeHandle < 0) return
        val flvPayload = buildAACPayload(aacData)
        val timestampMs = (ptsUs / 1000).toInt()
        val result = sendFLVTag(FLVTagType.AUDIO, flvPayload, timestampMs)
        if (result < 0) handleSendFailure("Audio send error: $result")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FLV packet construction
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendMetadataTag(config: RTMPConfig) {
        // onMetaData AMF0 object with stream parameters
        // This allows receiving servers to allocate buffers correctly
        val amfData = buildAMFMetadata(
            width = config.width,
            height = config.height,
            fps = config.fps.toDouble(),
            videoBitrateKbps = config.videoBitrateKbps.toDouble(),
            audioBitrateKbps = config.audioBitrateKbps.toDouble(),
            audioSampleRate = config.audioSampleRateHz.toDouble()
        )
        sendFLVTag(FLVTagType.SCRIPT, amfData, 0)
    }

    private fun sendFLVTag(
        type: FLVTagType, data: ByteArray, timestampMs: Int,
        isSequenceHeader: Boolean = false
    ): Int {
        val header = buildFLVTagHeader(type, data.size, timestampMs, isSequenceHeader)
        return nativeSendData(nativeHandle, header + data)
    }

    private fun buildFLVTagHeader(
        type: FLVTagType, dataSize: Int, timestampMs: Int, isSequenceHeader: Boolean
    ): ByteArray {
        val buf = ByteArray(11)
        // Tag type
        buf[0] = when (type) {
            FLVTagType.AUDIO -> if (isSequenceHeader) 0x08 else 0x08
            FLVTagType.VIDEO -> if (isSequenceHeader) 0x09 else 0x09
            FLVTagType.SCRIPT -> 0x12
        }
        // Data size (3 bytes big-endian)
        buf[1] = ((dataSize shr 16) and 0xFF).toByte()
        buf[2] = ((dataSize shr 8) and 0xFF).toByte()
        buf[3] = (dataSize and 0xFF).toByte()
        // Timestamp (3 bytes + 1 extended byte)
        buf[4] = ((timestampMs shr 16) and 0xFF).toByte()
        buf[5] = ((timestampMs shr 8) and 0xFF).toByte()
        buf[6] = (timestampMs and 0xFF).toByte()
        buf[7] = ((timestampMs shr 24) and 0xFF).toByte()
        // Stream ID (always 0 for RTMP)
        buf[8] = 0; buf[9] = 0; buf[10] = 0
        return buf
    }

    private fun buildAVCSequenceHeader(sps: ByteArray, pps: ByteArray): ByteArray {
        // ISO 14496-15 AVCDecoderConfigurationRecord
        val buf = mutableListOf<Byte>()
        buf.add(0x17.toByte())  // Frame type: key | codec AVC
        buf.add(0x00.toByte())  // AVC sequence header
        buf.add(0x00); buf.add(0x00); buf.add(0x00)  // Composition time offset
        // AVCDecoderConfigurationRecord
        buf.add(0x01.toByte())  // configurationVersion
        buf.add(sps[1])         // AVCProfileIndication
        buf.add(sps[2])         // profile_compatibility
        buf.add(sps[3])         // AVCLevelIndication
        buf.add(0xFF.toByte())  // lengthSizeMinusOne = 3 (4-byte NAL length)
        buf.add(0xE1.toByte())  // numSequenceParameterSets = 1
        buf.add(((sps.size shr 8) and 0xFF).toByte())
        buf.add((sps.size and 0xFF).toByte())
        buf.addAll(sps.toList())
        buf.add(0x01.toByte())  // numPictureParameterSets = 1
        buf.add(((pps.size shr 8) and 0xFF).toByte())
        buf.add((pps.size and 0xFF).toByte())
        buf.addAll(pps.toList())
        return buf.toByteArray()
    }

    private fun buildAVCNALUPayload(nal: ByteArray, isKeyFrame: Boolean): ByteArray {
        val buf = mutableListOf<Byte>()
        buf.add(if (isKeyFrame) 0x17.toByte() else 0x27.toByte())  // Frame type | AVC
        buf.add(0x01.toByte())  // AVC NALU
        buf.add(0x00); buf.add(0x00); buf.add(0x00)  // Composition time
        // 4-byte NALU length prefix
        buf.add(((nal.size shr 24) and 0xFF).toByte())
        buf.add(((nal.size shr 16) and 0xFF).toByte())
        buf.add(((nal.size shr 8) and 0xFF).toByte())
        buf.add((nal.size and 0xFF).toByte())
        buf.addAll(nal.toList())
        return buf.toByteArray()
    }

    private fun buildAACSequenceHeader(asc: ByteArray): ByteArray {
        // AAC audio tag with sequence header flag
        val buf = ByteArray(2 + asc.size)
        buf[0] = 0xAF.toByte()  // SoundFormat=10(AAC), 44100Hz, 16bit, stereo
        buf[1] = 0x00.toByte()  // AAC sequence header
        asc.copyInto(buf, 2)
        return buf
    }

    private fun buildAACPayload(aac: ByteArray): ByteArray {
        val buf = ByteArray(2 + aac.size)
        buf[0] = 0xAF.toByte()
        buf[1] = 0x01.toByte()  // AAC raw
        aac.copyInto(buf, 2)
        return buf
    }

    private fun buildAMFMetadata(
        width: Int, height: Int, fps: Double,
        videoBitrateKbps: Double, audioBitrateKbps: Double, audioSampleRate: Double
    ): ByteArray {
        // Simplified AMF0 metadata — full implementation via native AMF library
        // Values encoded as AMF0 Number (type 0x00) and String (type 0x02)
        return nativeBuildAMFMetadata(width, height, fps, videoBitrateKbps, audioBitrateKbps, audioSampleRate)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error handling & monitoring
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleSendFailure(reason: String) {
        Timber.w("RTMP send failure: $reason — scheduling reconnect")
        isStreaming.set(false)
        _state.value = RTMPState.Reconnecting
        reconnectionManager.scheduleReconnect { connect(config) }
    }

    private fun startStatsMonitoring() {
        statsJob = scope.launch {
            while (isStreaming.get()) {
                val rawStats = nativeGetStats(nativeHandle)
                _stats.value = rawStats
                delay(1000L)
            }
        }
    }

    private fun buildRTMPUrl(config: RTMPConfig): String {
        val scheme = if (config.useTLS) "rtmps" else "rtmp"
        return "$scheme://${config.host}:${config.port}/${config.appName}/${config.streamKey}"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JNI bridge — implemented in streaming/RTMPEngine.cpp
    // ─────────────────────────────────────────────────────────────────────────

    private external fun nativeConnect(url: String, timeoutMs: Int, tls: Boolean): Long
    private external fun nativeSendData(handle: Long, data: ByteArray): Int
    private external fun nativeDisconnect(handle: Long)
    private external fun nativeGetStats(handle: Long): RTMPStats
    private external fun nativeBuildAMFMetadata(
        width: Int, height: Int, fps: Double,
        videoBitrate: Double, audioBitrate: Double, audioSampleRate: Double
    ): ByteArray

    companion object { init { System.loadLibrary("cinecamera_native") } }
}

// ─── Data models ─────────────────────────────────────────────────────────────

data class RTMPConfig(
    val host: String = "",
    val port: Int = 1935,
    val appName: String = "live",
    val streamKey: String = "",
    val useTLS: Boolean = false,
    val connectTimeoutMs: Int = 5000,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val videoBitrateKbps: Int = 8000,
    val audioBitrateKbps: Int = 256,
    val audioSampleRateHz: Int = 48000
)

sealed class RTMPState {
    object Idle : RTMPState()
    object Connecting : RTMPState()
    data class Live(val host: String, val maskedKey: String) : RTMPState()
    object Reconnecting : RTMPState()
    data class Error(val message: String) : RTMPState()
}

data class RTMPStats(
    val bytesSent: Long = 0L,
    val framesDropped: Int = 0,
    val bufferMs: Int = 0,
    val rttMs: Float = 0f,
    val actualBitrateKbps: Int = 0
)

enum class FLVTagType { AUDIO, VIDEO, SCRIPT }
