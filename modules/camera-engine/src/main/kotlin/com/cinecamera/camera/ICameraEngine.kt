package com.cinecamera.camera

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * ICameraEngine
 *
 * Contract for the Camera2 API hardware abstraction layer.
 * All parameters are expressed in physical units — ISO in sensor gain,
 * shutter speed in nanoseconds, white balance in Kelvin — to decouple
 * UI presentation from hardware primitives.
 *
 * Implementations must guarantee:
 *   - All camera session operations run on a dedicated HandlerThread,
 *     never on the main thread.
 *   - Parameter changes are applied via CaptureRequest without
 *     restarting the capture session (no preview interruption).
 *   - ExposureTransitionSmoother interpolates ISO/shutter changes
 *     over multiple frames to eliminate visible exposure jumping.
 */
interface ICameraEngine {

    val cameraState: StateFlow<CameraState>
    val captureMetrics: StateFlow<CaptureMetrics>
    val availableLenses: StateFlow<List<LensDescriptor>>

    suspend fun initialize(config: CameraConfig)
    fun startPreview(surface: Surface)
    fun stopPreview()

    // ── Exposure controls ─────────────────────────────────────────────────────
    fun setISO(iso: Int)
    fun setShutterSpeed(shutterNs: Long)
    fun lockAutoExposure(lock: Boolean)

    // ── White balance ─────────────────────────────────────────────────────────
    fun setWhiteBalance(kelvin: Int)

    // ── Focus ─────────────────────────────────────────────────────────────────
    fun setFocus(normalizedDistance: Float)     // 0.0 = near, 1.0 = hyperfocal/infinity
    fun lockAutoFocus(lock: Boolean)
    fun triggerAF()

    // ── Frame rate ────────────────────────────────────────────────────────────
    fun setFrameRate(fps: Int)

    // ── Lens and zoom ─────────────────────────────────────────────────────────
    fun selectLens(type: LensType)
    fun setZoomRatio(ratio: Float)              // 1.0 = no zoom

    fun release()
}

// ─── Data models ──────────────────────────────────────────────────────────────

data class CameraConfig(
    val preferredLens: LensType  = LensType.WIDE,
    val enableOIS: Boolean       = true,
    val requestFullHardwareLevel: Boolean = true
)

enum class LensType { ULTRA_WIDE, WIDE, TELE, MACRO }

data class LensDescriptor(
    val id: String,
    val type: LensType,
    val focalLengthMm: Float,
    val aperture: Float,
    val hasOIS: Boolean,
    val maxISO: Int,
    val minExposureNs: Long,
    val maxExposureNs: Long
)

sealed class CameraState {
    object Closed : CameraState()
    object Opening : CameraState()
    object Previewing : CameraState()
    data class Error(val code: Int, val message: String) : CameraState()
}

data class CaptureMetrics(
    val iso: Int              = 100,
    val shutterSpeedNs: Long  = 33_333_333L,   // 1/30s default
    val whiteBalanceKelvin: Int = 5600,
    val focusDistance: Float  = 0f,
    val afState: AfState      = AfState.SEARCHING,
    val aeState: AeState      = AeState.CONVERGED,
    val lux: Float            = 0f
)

enum class AfState  { SEARCHING, FOCUSED, LOCKED, FAILED }
enum class AeState  { SEARCHING, CONVERGED, LOCKED, FLASH_REQUIRED }
