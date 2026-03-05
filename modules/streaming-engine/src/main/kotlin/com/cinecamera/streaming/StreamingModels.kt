package com.cinecamera.streaming

/**
 * StreamConfig — configuration for an SRT streaming session.
 */
data class StreamConfig(
    val host: String        = "0.0.0.0",
    val port: Int           = 9000,
    val mode: SRTMode       = SRTMode.CALLER,
    val latencyMs: Int      = 120,
    val bitrateKbps: Int    = 8_000,
    val passphrase: String  = "",       // Empty = no encryption
    val pbkeylen: Int       = 16        // AES-128 when passphrase set
)

enum class SRTMode { CALLER, LISTENER, RENDEZVOUS }

/**
 * RTMPConfig — configuration for an RTMP/RTMPS streaming session.
 */
data class RTMPConfig(
    val host: String           = "a.rtmp.youtube.com",
    val port: Int              = 1935,
    val app: String            = "live2",
    val streamKey: String      = "",
    val useTLS: Boolean        = false,
    val connectTimeoutMs: Long = 10_000L,
    val bitrateKbps: Int       = 8_000
)

/**
 * RTMPState — live state of an RTMP session.
 */
sealed class RTMPState {
    object Idle : RTMPState()
    object Connecting : RTMPState()
    data class Live(val host: String, val maskedKey: String) : RTMPState()
    object Reconnecting : RTMPState()
    data class Error(val message: String) : RTMPState()
}

/**
 * RTMPStats — real-time streaming quality metrics.
 */
data class RTMPStats(
    val rttMs: Float           = 0f,
    val packetLossPct: Float   = 0f,
    val droppedFrames: Int     = 0,
    val sentBytesTotal: Long   = 0L
)

/**
 * StreamingState — unified state for SRT sessions.
 */
sealed class StreamingState {
    object Idle : StreamingState()
    object Connecting : StreamingState()
    data class Live(val host: String, val port: Int) : StreamingState()
    data class Reconnecting(val attemptNumber: Int) : StreamingState()
    data class Error(val message: String) : StreamingState()
}

/**
 * StreamStats — real-time quality metrics for SRT sessions.
 */
data class StreamStats(
    val rttMs: Float           = 0f,
    val packetLossPct: Float   = 0f,
    val bandwidthMbps: Float   = 0f,
    val retransmittedPkts: Int = 0
)

/**
 * BroadcastResult — result of a broadcast connection attempt.
 * Sealed class consumed by CameraViewModel after StartBroadcastUseCase runs.
 */
sealed class BroadcastResult {
    data class Success(val protocol: String) : BroadcastResult()
    data class Failure(val reason: String) : BroadcastResult()
    data class FeatureGated(val requiredFeature: com.cinecamera.monetization.Feature) : BroadcastResult()
}
