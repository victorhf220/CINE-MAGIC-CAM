package com.cinecamera.imageprocessing

import kotlinx.coroutines.flow.StateFlow

/**
 * IImageProcessingEngine
 *
 * Contract for the OpenGL ES 3.0 image processing pipeline.
 * Manages the full shader chain: YUV conversion → noise reduction →
 * CineLog encoding → LUT application → tone mapping → grading → sharpening → overlays.
 *
 * All OpenGL calls are issued from the GL thread owned by the engine.
 * Control methods (set*, enable*) are thread-safe — they update shader
 * uniforms asynchronously on the next GL frame.
 */
interface IImageProcessingEngine {

    val histogramData: StateFlow<HistogramData>
    val waveformData: StateFlow<WaveformData>

    // ── Color profile ─────────────────────────────────────────────────────────
    fun setColorProfile(profile: ColorProfile)

    // ── LUT engine ───────────────────────────────────────────────────────────
    /** Loads a .cube file. Returns true on success, false on parse error. */
    fun loadLUT(path: String): Boolean
    fun setLUTIntensity(intensity: Float)     // 0.0 = bypass, 1.0 = full
    fun clearLUT()

    // ── Grading controls ─────────────────────────────────────────────────────
    fun setContrast(value: Float)             // 0.5–2.0, default 1.0
    fun setSaturation(value: Float)           // 0.0–2.0, default 1.0
    fun setHighlights(value: Float)           // -1.0–1.0, default 0.0
    fun setShadows(value: Float)              // -1.0–1.0, default 0.0
    fun setSharpening(value: Float)           // 0.0–1.0, default 0.0

    // ── Assist tools ─────────────────────────────────────────────────────────
    fun setZebraEnabled(enabled: Boolean, threshold: Float = 0.95f)
    fun setFocusPeakingEnabled(enabled: Boolean)
    fun setWaveformEnabled(enabled: Boolean)
    fun setHistogramEnabled(enabled: Boolean)

    fun release()
}

// ─── Color profiles ───────────────────────────────────────────────────────────

enum class ColorProfile {
    /** Standard BT.709 output — no log encoding. */
    STANDARD,
    /** CineLog™ proprietary log profile — ~14 stops DR, middle grey 18%→38%. */
    CINELOG,
    /** S-Log3 compatible for Sony-workflow interoperability. */
    SLOG3,
    /** Log-C compatible for ARRI-workflow interoperability. */
    LOGC
}

// ─── Instrument data ─────────────────────────────────────────────────────────

/**
 * HistogramData — per-channel luminance distribution.
 * Each channel is a 256-element array of normalized bin counts (0.0–1.0).
 */
data class HistogramData(
    val red: FloatArray   = FloatArray(256),
    val green: FloatArray = FloatArray(256),
    val blue: FloatArray  = FloatArray(256),
    val luma: FloatArray  = FloatArray(256)
)

/**
 * WaveformData — horizontal waveform monitor values.
 * columns: number of horizontal columns sampled (typically 256).
 * Each column holds min/max/avg luma values for vertical aggregation.
 */
data class WaveformData(
    val columns: Int = 256,
    val minValues: FloatArray = FloatArray(256),
    val maxValues: FloatArray = FloatArray(256),
    val avgValues: FloatArray = FloatArray(256)
)
