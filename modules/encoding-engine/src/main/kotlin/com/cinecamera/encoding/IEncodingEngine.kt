package com.cinecamera.encoding

import com.cinecamera.audio.IAudioEngine
import com.cinecamera.utils.VideoFileDescriptor
import kotlinx.coroutines.flow.StateFlow

/**
 * IEncodingEngine
 *
 * Contract for the MediaCodec-based video/audio encoding pipeline.
 *
 * NAL callback design (FIXED — audit finding #5):
 *   Previously the ViewModel called setNALCallback() with a single lambda,
 *   overwriting any previously registered callback. This made multi-stream
 *   (Enterprise) impossible — starting a second stream would silently drop
 *   the first. The interface now provides addNALCallback / clearNALCallbacks
 *   with a thread-safe CopyOnWriteArrayList internally, allowing unlimited
 *   concurrent consumers (SRT, RTMP, recovery buffer) to receive every NAL.
 *
 * Dynamic bitrate:
 *   setDynamicBitrate() applies in real time without encoder reconfiguration
 *   via MediaCodec.setParameters() with PARAMETER_KEY_VIDEO_BITRATE.
 *   Called by CameraViewModel when thermal alerts fire.
 */
interface IEncodingEngine {

    val encodingMetrics: StateFlow<EncodingMetrics>

    /**
     * Configures codec parameters. Must be called before startRecording().
     * Safe to call repeatedly — reconfigures the encoder without restarting.
     */
    fun configure(config: EncoderConfig)

    /**
     * Begins recording to the provided VideoFileDescriptor.
     * On API 29+: opens the MediaStore URI via ParcelFileDescriptor.
     * On API 26–28: opens the legacy File path.
     */
    fun startRecording(descriptor: VideoFileDescriptor)

    /**
     * Drains the encoder, finalizes the MP4 container, and closes the output.
     * Blocks until the drain is complete (runs on Dispatchers.IO internally).
     */
    fun stopRecording()

    /**
     * Adjusts the target encoding bitrate in real time without stopping the
     * encoder. Internally calls MediaCodec.setParameters(). Used by the
     * thermal feedback loop in CameraViewModel.
     *
     * @param bitrateKbps New target bitrate. Will be clamped to the range
     *                    [500, MAX_BITRATE_MBPS * 1000].
     */
    fun setDynamicBitrate(bitrateKbps: Int)

    /**
     * Registers a callback to receive every encoded NAL unit.
     * Thread-safe: multiple callbacks can be registered concurrently.
     * All registered callbacks are called synchronously in the encoding thread
     * for each NAL — implementations must be non-blocking.
     */
    fun addNALCallback(callback: NALCallback)

    /**
     * Removes a specific previously registered callback.
     */
    fun removeNALCallback(callback: NALCallback)

    /**
     * Removes all registered NAL callbacks atomically.
     * Called by StopBroadcastUseCase when the stream session ends.
     */
    fun clearNALCallbacks()

    /** Releases all MediaCodec and surface resources. */
    fun release()
}

/** Callback type for receiving encoded NAL units from the encoder. */
typealias NALCallback = (data: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean) -> Unit

// ─── Configuration models ─────────────────────────────────────────────────────

data class EncoderConfig(
    val codec: VideoCodec           = VideoCodec.H264,
    val width: Int                  = 1920,
    val height: Int                 = 1080,
    val fps: Int                    = 30,
    val bitrateKbps: Int            = 50_000,
    val bitrateMode: BitrateMode    = BitrateMode.CBR,
    val gopSeconds: Int             = 2,
    val profile: EncoderProfile     = EncoderProfile.HIGH
)

enum class VideoCodec(val mimeType: String) {
    H264("video/avc"),
    H265("video/hevc")
}

enum class BitrateMode { CBR, VBR }

enum class EncoderProfile { BASELINE, MAIN, HIGH }

// ─── Metrics ─────────────────────────────────────────────────────────────────

data class EncodingMetrics(
    val actualBitrateKbps: Int   = 0,
    val droppedFrames: Int       = 0,
    val totalFrames: Int         = 0,
    val recordingDurationMs: Long = 0L,
    val fileSizeBytes: Long      = 0L,
    val encoderQueueDepth: Int   = 0,
    val keyFrameIntervalMs: Long = 0L
)
