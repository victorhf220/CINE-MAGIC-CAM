package com.cinecamera.ui.camera.usecase

import android.content.Context
import com.cinecamera.audio.AudioConfig
import com.cinecamera.audio.IAudioEngine
import com.cinecamera.encoding.EncoderConfig
import com.cinecamera.encoding.EncoderProfile
import com.cinecamera.encoding.IEncodingEngine
import com.cinecamera.monetization.MonetizationEngine
import com.cinecamera.recovery.RecoveryEngine
import com.cinecamera.recovery.SessionConfig
import com.cinecamera.telemetry.TelemetryEngine
import com.cinecamera.ui.camera.CameraUIState
import com.cinecamera.utils.MediaStoreHelper
import com.cinecamera.utils.VideoFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

/**
 * StartRecordingUseCase
 *
 * FIXES audit finding #5 (ViewModel complexity):
 * Extracts all recording startup concerns from CameraViewModel.startRecording()
 * into a single, independently testable unit.
 *
 * Responsibilities:
 *   1. Generate a MediaStore-safe output file (fixes /sdcard hardcoding)
 *   2. Write the recovery journal before any recording begins
 *   3. Configure and start the MediaCodec encoder
 *   4. Start audio capture
 *   5. Notify telemetry
 *   6. Return a RecordingSession describing the active session
 *
 * The ViewModel becomes a thin delegator. Each concern is mockable
 * individually in unit tests without a physical device.
 */
class StartRecordingUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encodingEngine: IEncodingEngine,
    private val audioEngine: IAudioEngine,
    private val recoveryEngine: RecoveryEngine,
    private val telemetry: TelemetryEngine,
    private val monetization: MonetizationEngine
) {
    /**
     * Executes the recording startup sequence atomically.
     *
     * @param uiState Current UI state carrying codec, resolution, fps, bitrate selections.
     * @return RecordingSession on success, or throws with a descriptive message on failure.
     */
    suspend operator fun invoke(uiState: CameraUIState): RecordingSession {
        // Step 1: Create output file via MediaStore (API 29+) or legacy path
        val fileDescriptor = MediaStoreHelper.createVideoFile(context)
        Timber.d("Recording output: ${fileDescriptor.identifier}")

        // Step 2: Build encoder configuration clamped to subscription bitrate limit
        val config = buildEncoderConfig(uiState)
        Timber.d("Encoder config: ${config.codec} ${config.width}x${config.height} " +
                "@${config.fps}fps ${config.bitrateKbps}kbps")

        // Step 3: Write recovery journal — must precede any file writes
        val sessionConfig = SessionConfig(
            codec        = config.codec.name,
            bitrateKbps  = config.bitrateKbps,
            fps          = config.fps,
            width        = config.width,
            height       = config.height
        )
        val safePath = recoveryEngine.onRecordingStarting(
            fileDescriptor.identifier, sessionConfig
        )

        // Step 4: Configure and start encoder
        encodingEngine.configure(config)
        encodingEngine.startRecording(fileDescriptor)

        // Step 5: Start audio capture
        audioEngine.startCapture()

        // Step 6: Notify telemetry
        telemetry.onRecordingStart(mapOf(
            "codec"         to config.codec.name,
            "resolution"    to "${config.width}x${config.height}",
            "fps"           to config.fps,
            "bitrate_mbps"  to (config.bitrateKbps / 1000),
            "output"        to fileDescriptor.displayName
        ))

        return RecordingSession(
            fileDescriptor = fileDescriptor,
            encoderConfig  = config,
            sessionPath    = safePath
        )
    }

    private fun buildEncoderConfig(state: CameraUIState) = EncoderConfig(
        codec        = state.selectedCodec,
        width        = state.resolution.width,
        height       = state.resolution.height,
        fps          = state.selectedFps,
        bitrateKbps  = minOf(state.bitrateKbps, monetization.getMaxBitrateKbps()),
        bitrateMode  = state.bitrateMode,
        gopSeconds   = 2,
        profile      = EncoderProfile.HIGH
    )
}

/**
 * StopRecordingUseCase — symmetric teardown to StartRecordingUseCase.
 * Handles encoder drain, audio stop, MediaStore commit, recovery cleanup,
 * and telemetry finalization in the correct order.
 */
class StopRecordingUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encodingEngine: IEncodingEngine,
    private val audioEngine: IAudioEngine,
    private val recoveryEngine: RecoveryEngine,
    private val telemetry: TelemetryEngine
) {
    /**
     * @param session The RecordingSession returned by StartRecordingUseCase.
     */
    suspend operator fun invoke(session: RecordingSession) {
        // Stop encoder first — ensures all remaining frames are flushed to disk
        encodingEngine.stopRecording()
        // Stop audio capture
        audioEngine.stopCapture()

        // Mark MediaStore entry as ready — makes file visible in gallery
        MediaStoreHelper.markFileReady(context, session.fileDescriptor)

        // Record final metrics before closing telemetry session
        val metrics = encodingEngine.encodingMetrics.value
        telemetry.onRecordingEnd(mapOf(
            "total_frames"        to metrics.totalFrames,
            "dropped_frames"      to metrics.droppedFrames,
            "actual_bitrate_kbps" to metrics.actualBitrateKbps,
            "file_size_mb"        to (metrics.fileSizeBytes / 1024 / 1024)
        ))

        // Clean up recovery journal — session completed successfully
        recoveryEngine.onRecordingComplete(session.sessionPath)

        Timber.i("Recording stopped: ${session.fileDescriptor.displayName} " +
                "(${metrics.fileSizeBytes / 1024 / 1024}MB, " +
                "${metrics.droppedFrames} dropped frames)")
    }
}

/**
 * RecordingSession — carries all mutable state for an active recording.
 * Passed from StartRecordingUseCase to StopRecordingUseCase, and held
 * by the ViewModel for the duration of the recording.
 */
data class RecordingSession(
    val fileDescriptor: VideoFileDescriptor,
    val encoderConfig: EncoderConfig,
    val sessionPath: String
)
