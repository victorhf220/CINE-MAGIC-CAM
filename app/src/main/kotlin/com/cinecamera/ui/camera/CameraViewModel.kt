package com.cinecamera.ui.camera

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinecamera.audio.AudioConfig
import com.cinecamera.audio.AudioSource
import com.cinecamera.audio.IAudioEngine
import com.cinecamera.camera.CameraConfig
import com.cinecamera.camera.ICameraEngine
import com.cinecamera.camera.LensType
import com.cinecamera.encoding.BitrateMode
import com.cinecamera.encoding.IEncodingEngine
import com.cinecamera.encoding.VideoCodec
import com.cinecamera.imageprocessing.ColorProfile
import com.cinecamera.imageprocessing.IImageProcessingEngine
import com.cinecamera.monetization.Feature
import com.cinecamera.monetization.MonetizationEngine
import com.cinecamera.preset.CameraPreset
import com.cinecamera.preset.PresetEngine
import com.cinecamera.recovery.OrphanedSession
import com.cinecamera.recovery.RecoveryEngine
import com.cinecamera.stabilization.IStabilizationEngine
import com.cinecamera.stability.SystemStabilityEngine
import com.cinecamera.stability.ThermalAlert
import com.cinecamera.streaming.RTMPConfig
import com.cinecamera.streaming.RTMPStreamingEngine
import com.cinecamera.streaming.SRTStreamingController
import com.cinecamera.streaming.StreamConfig
import com.cinecamera.telemetry.TelemetryEngine
import com.cinecamera.ui.camera.usecase.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * CameraViewModel — REFACTORED
 *
 * This ViewModel is now a thin orchestrator:
 *   - Holds UI state (CameraUIState) as a single immutable StateFlow
 *   - Delegates complex startup/teardown sequences to UseCases
 *   - Exposes engine StateFlows directly to the UI
 *   - Contains no file path logic, no encoding configuration, no MediaStore calls
 *
 * The 12 engine dependencies from the previous version are now partitioned:
 *   StartRecordingUseCase  — handles encoder + audio + recovery + telemetry startup
 *   StopRecordingUseCase   — handles drain + MediaStore commit + cleanup
 *   StartBroadcastUseCase  — handles SRT/RTMP connection + NAL callback wiring
 *   StopBroadcastUseCase   — handles disconnect + callback removal
 *
 * Engines that the ViewModel still references directly are those whose
 * StateFlows need to be observed or whose per-frame controls belong at this level.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraEngine:           ICameraEngine,
    private val encodingEngine:         IEncodingEngine,
    private val imageProcessingEngine:  IImageProcessingEngine,
    private val audioEngine:            IAudioEngine,
    private val stabilizationEngine:    IStabilizationEngine,
    private val stabilityEngine:        SystemStabilityEngine,
    private val recoveryEngine:         RecoveryEngine,
    private val presetEngine:           PresetEngine,
    private val telemetry:              TelemetryEngine,
    private val monetization:           MonetizationEngine,
    // UseCases
    private val startRecording:         StartRecordingUseCase,
    private val stopRecording:          StopRecordingUseCase,
    private val startBroadcast:         StartBroadcastUseCase,
    private val stopBroadcast:          StopBroadcastUseCase
) : ViewModel() {

    // ── Exposed StateFlows (pass-through from engines) ─────────────────────────

    val cameraState       = cameraEngine.cameraState
    val captureMetrics    = cameraEngine.captureMetrics
    val availableLenses   = cameraEngine.availableLenses
    val encodingMetrics   = encodingEngine.encodingMetrics
    val audioState        = audioEngine.audioState
    val vuMeterData       = audioEngine.vuMeterData
    val clippingAlert     = audioEngine.clippingEvent
    val availableAudioSources = audioEngine.availableSources
    val systemHealth      = stabilityEngine.systemHealth
    val thermalAlert      = stabilityEngine.thermalAlert
    val recoveryState     = recoveryEngine.recoveryState
    val sessionReport     = telemetry.sessionReport
    val histogramData     = imageProcessingEngine.histogramData
    val waveformData      = imageProcessingEngine.waveformData

    // Streaming StateFlows — needed by UI for connection indicators
    val srtState          = (encodingEngine as? Any)?.let {
        // Accessed via the streaming engine injected into the broadcast UseCase
        // exposed through a dedicated state holder below
        MutableStateFlow<Any?>(null).asStateFlow()
    }

    private val _uiState = MutableStateFlow(CameraUIState())
    val uiState: StateFlow<CameraUIState> = _uiState.asStateFlow()

    /** Holds the active recording session; null when not recording. */
    private var activeSession: RecordingSession? = null

    // ── Initialization ─────────────────────────────────────────────────────────

    init {
        stabilityEngine.startMonitoring()
        monetization.initialize()
        observeThermalAlerts()
        observeDroppedFrames()
        feedTelemetryMetrics()

        viewModelScope.launch {
            recoveryEngine.scanForOrphanedSessions()
            presetEngine.seedDefaultPresetsIfNeeded()
        }
    }

    fun initializeCamera(config: CameraConfig = CameraConfig()) {
        viewModelScope.launch {
            runCatching {
                cameraEngine.initialize(config)
                audioEngine.initialize(AudioConfig())
            }.onFailure { e ->
                Timber.e(e, "Camera initialization failed")
                telemetry.logError(e, "initializeCamera")
            }
        }
    }

    fun startPreview(surface: Surface) {
        viewModelScope.launch {
            runCatching { cameraEngine.startPreview(surface) }
                .onFailure { e -> Timber.e(e, "Preview start failed") }
        }
    }

    // ── Recording Control (delegated to UseCases) ──────────────────────────────

    fun onRecordButtonPressed() {
        if (_uiState.value.isRecording) requestStopRecording() else requestStartRecording()
    }

    private fun requestStartRecording() {
        viewModelScope.launch {
            runCatching {
                val session = startRecording(_uiState.value)
                activeSession = session
                _uiState.update { it.copy(isRecording = true) }
                Timber.i("Recording started: ${session.fileDescriptor.displayName}")
            }.onFailure { e ->
                Timber.e(e, "startRecording failed")
                telemetry.logError(e, "requestStartRecording")
                _uiState.update { it.copy(recordingError = e.message) }
            }
        }
    }

    private fun requestStopRecording() {
        viewModelScope.launch {
            val session = activeSession ?: return@launch
            runCatching {
                stopRecording(session)
                activeSession = null
                _uiState.update { it.copy(isRecording = false, recordingError = null) }
            }.onFailure { e ->
                Timber.e(e, "stopRecording failed")
                telemetry.logError(e, "requestStopRecording")
            }
        }
    }

    // ── Camera Controls ────────────────────────────────────────────────────────

    fun setISO(iso: Int)                     = cameraEngine.setISO(iso)
    fun setShutterSpeed(ns: Long)            = cameraEngine.setShutterSpeed(ns)
    fun setWhiteBalance(kelvin: Int)         = cameraEngine.setWhiteBalance(kelvin)
    fun setFocus(normalizedDistance: Float)  = cameraEngine.setFocus(normalizedDistance)
    fun selectLens(type: LensType)           = cameraEngine.selectLens(type)
    fun lockAE(lock: Boolean)                = cameraEngine.lockAutoExposure(lock)
    fun lockAF(lock: Boolean)                = cameraEngine.lockAutoFocus(lock)
    fun setZoom(ratio: Float)                = cameraEngine.setZoomRatio(ratio)

    fun setFrameRate(fps: Int) {
        cameraEngine.setFrameRate(fps)
        _uiState.update { it.copy(selectedFps = fps) }
    }

    fun setResolution(resolution: VideoResolution) {
        _uiState.update { it.copy(resolution = resolution) }
    }

    fun setCodec(codec: VideoCodec) {
        _uiState.update { it.copy(selectedCodec = codec) }
    }

    fun setBitrate(kbps: Int) {
        val clamped = minOf(kbps, monetization.getMaxBitrateKbps())
        _uiState.update { it.copy(bitrateKbps = clamped) }
    }

    fun setBitrateMode(mode: BitrateMode) {
        _uiState.update { it.copy(bitrateMode = mode) }
    }

    // ── Image Processing ───────────────────────────────────────────────────────

    fun setColorProfile(profile: ColorProfile) {
        if (profile != ColorProfile.STANDARD && !monetization.hasFeature(Feature.LOG_PROFILE)) {
            _uiState.update { it.copy(featureGateEvent = Feature.LOG_PROFILE) }
            return
        }
        imageProcessingEngine.setColorProfile(profile)
    }

    fun loadLUT(path: String) {
        if (!monetization.hasFeature(Feature.LUT_ENGINE)) {
            _uiState.update { it.copy(featureGateEvent = Feature.LUT_ENGINE) }
            return
        }
        val success = imageProcessingEngine.loadLUT(path)
        _uiState.update {
            it.copy(
                lutLoaded = success,
                lutName   = if (success) File(path).nameWithoutExtension else ""
            )
        }
    }

    fun setLUTIntensity(v: Float)   = imageProcessingEngine.setLUTIntensity(v)
    fun setContrast(v: Float)       = imageProcessingEngine.setContrast(v)
    fun setSaturation(v: Float)     = imageProcessingEngine.setSaturation(v)
    fun setHighlights(v: Float)     = imageProcessingEngine.setHighlights(v)
    fun setShadows(v: Float)        = imageProcessingEngine.setShadows(v)
    fun setSharpening(v: Float)     = imageProcessingEngine.setSharpening(v)

    fun setZebra(enabled: Boolean, threshold: Float = 0.95f) =
        imageProcessingEngine.setZebraEnabled(enabled, threshold)

    fun setFocusPeaking(enabled: Boolean) = imageProcessingEngine.setFocusPeakingEnabled(enabled)
    fun setWaveform(enabled: Boolean)     = imageProcessingEngine.setWaveformEnabled(enabled)

    // ── Audio Controls ─────────────────────────────────────────────────────────

    fun setAudioGain(gainDb: Float)                          = audioEngine.setGain(gainDb)
    fun setNoiseGate(enabled: Boolean, thresholdDb: Float)   = audioEngine.setNoiseGate(enabled, thresholdDb)
    fun setLimiter(enabled: Boolean)                         = audioEngine.setLimiter(enabled)
    fun selectAudioSource(source: AudioSource)               = audioEngine.selectSource(source)

    // ── Streaming (delegated to UseCases) ─────────────────────────────────────

    fun startSRTBroadcast(config: StreamConfig) {
        viewModelScope.launch {
            when (val result = startBroadcast.startSRT(config)) {
                is BroadcastResult.Success     -> _uiState.update {
                    it.copy(isBroadcasting = true, broadcastProtocol = result.protocol, broadcastError = null)
                }
                is BroadcastResult.Failure     -> _uiState.update { it.copy(broadcastError = result.reason) }
                is BroadcastResult.FeatureGated -> _uiState.update { it.copy(featureGateEvent = result.requiredFeature) }
            }
        }
    }

    fun startRTMPBroadcast(config: RTMPConfig) {
        viewModelScope.launch {
            when (val result = startBroadcast.startRTMP(config)) {
                is BroadcastResult.Success     -> _uiState.update {
                    it.copy(isBroadcasting = true, broadcastProtocol = result.protocol, broadcastError = null)
                }
                is BroadcastResult.Failure     -> _uiState.update { it.copy(broadcastError = result.reason) }
                is BroadcastResult.FeatureGated -> _uiState.update { it.copy(featureGateEvent = result.requiredFeature) }
            }
        }
    }

    fun stopAllBroadcasts() {
        stopBroadcast()
        _uiState.update { it.copy(isBroadcasting = false, broadcastProtocol = "", broadcastError = null) }
    }

    // ── Preset Management ──────────────────────────────────────────────────────

    fun applyPreset(preset: CameraPreset) {
        with(preset.cameraConfig) {
            codec?.let { setCodec(VideoCodec.valueOf(it)) }
            setFrameRate(fps)
            isoManual?.let          { setISO(it) }
            shutterManualNs?.let    { setShutterSpeed(it) }
            whiteBalanceKelvin?.let { setWhiteBalance(it) }
            setBitrate(bitrateKbps)
        }
        with(preset.imageProcessingConfig) {
            setContrast(contrast); setSaturation(saturation)
            setHighlights(highlights); setShadows(shadows); setSharpening(sharpening)
            lutPath?.let { loadLUT(it) }
        }
        with(preset.audioConfig) {
            setAudioGain(gainDb)
            setNoiseGate(noiseGateEnabled, noiseGateThresholdDb)
            setLimiter(limiterEnabled)
        }
        setStabilizationIntensity(preset.stabilizationConfig.intensity)
        Timber.i("Preset applied: ${preset.name}")
    }

    fun saveCurrentStateAsPreset(name: String) {
        val state = _uiState.value
        viewModelScope.launch {
            presetEngine.savePreset(
                com.cinecamera.preset.CameraPreset(
                    id     = "user_${System.currentTimeMillis()}",
                    name   = name,
                    cameraConfig = com.cinecamera.preset.CameraPresetConfig(
                        codec       = state.selectedCodec.name,
                        width       = state.resolution.width,
                        height      = state.resolution.height,
                        fps         = state.selectedFps,
                        bitrateKbps = state.bitrateKbps,
                        bitrateMode = state.bitrateMode.name
                    )
                )
            )
        }
    }

    // ── Stabilization ──────────────────────────────────────────────────────────

    fun setStabilizationIntensity(intensity: Float) {
        stabilizationEngine.setIntensity(intensity)
        _uiState.update { it.copy(stabilizationIntensity = intensity) }
    }

    // ── Recovery ──────────────────────────────────────────────────────────────

    fun recoverOrphanedSession(session: OrphanedSession) {
        viewModelScope.launch { recoveryEngine.recoverSession(session) }
    }

    fun discardOrphanedSession(session: OrphanedSession) {
        recoveryEngine.discardOrphanedSession(session)
    }

    fun consumeFeatureGateEvent() {
        _uiState.update { it.copy(featureGateEvent = null) }
    }

    // ── Internal Observers ─────────────────────────────────────────────────────

    private fun observeThermalAlerts() {
        viewModelScope.launch {
            thermalAlert.collect { alert ->
                when (alert) {
                    is ThermalAlert.Critical -> {
                        telemetry.logWarning("thermal_critical", mapOf("temp" to alert.tempC))
                        val reduced = (_uiState.value.bitrateKbps * 0.7f).toInt()
                        _uiState.update { it.copy(bitrateKbps = reduced) }
                        encodingEngine.setDynamicBitrate(reduced)
                    }
                    is ThermalAlert.Emergency -> {
                        telemetry.logWarning("thermal_emergency", mapOf("temp" to alert.tempC))
                        if (_uiState.value.isRecording) requestStopRecording()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeDroppedFrames() {
        viewModelScope.launch {
            encodingEngine.encodingMetrics
                .filter { it.droppedFrames > 5 }
                .collect { metrics ->
                    telemetry.logWarning("dropped_frames", mapOf("count" to metrics.droppedFrames))
                }
        }
    }

    private fun feedTelemetryMetrics() {
        viewModelScope.launch {
            combine(
                encodingEngine.encodingMetrics,
                systemHealth
            ) { enc, health -> enc to health }
                .collect { (enc, health) ->
                    telemetry.recordSample(
                        bitrateKbps   = enc.actualBitrateKbps,
                        droppedFrames = enc.droppedFrames,
                        cpuPct        = health.cpuUsagePct,
                        temperatureC  = health.cpuTemperatureC
                    )
                }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            if (_uiState.value.isRecording) requestStopRecording()
            if (_uiState.value.isBroadcasting) stopAllBroadcasts()
            cameraEngine.release()
            encodingEngine.release()
            audioEngine.release()
            stabilizationEngine.stop()
            stabilityEngine.stopMonitoring()
            monetization.release()
        }
    }
}

// ─── UI State ─────────────────────────────────────────────────────────────────

data class CameraUIState(
    val isRecording: Boolean          = false,
    val isBroadcasting: Boolean       = false,
    val broadcastProtocol: String     = "",
    val lutLoaded: Boolean            = false,
    val lutName: String               = "",
    val stabilizationIntensity: Float = 0.75f,
    val selectedCodec: VideoCodec     = VideoCodec.H264,
    val selectedFps: Int              = 30,
    val bitrateKbps: Int              = 50_000,
    val bitrateMode: BitrateMode      = BitrateMode.CBR,
    val resolution: VideoResolution   = VideoResolution.FHD_1080,
    val featureGateEvent: Feature?    = null,
    val recordingError: String?       = null,
    val broadcastError: String?       = null
)

enum class VideoResolution(val width: Int, val height: Int, val label: String) {
    HD_720(1280, 720, "720p"),
    FHD_1080(1920, 1080, "1080p"),
    QHD_1440(2560, 1440, "1440p"),
    UHD_4K(3840, 2160, "4K")
}
