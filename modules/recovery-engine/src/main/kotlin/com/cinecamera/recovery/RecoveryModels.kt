package com.cinecamera.recovery

/**
 * RecoveryState — sealed class for recovery engine state transitions.
 * Consumed by CameraActivity to show recovery dialogs when orphaned sessions
 * are found at startup.
 */
sealed class RecoveryState {
    object Idle : RecoveryState()
    data class ScanComplete(val orphaned: List<OrphanedSession>) : RecoveryState()
    data class Recovering(val filePath: String) : RecoveryState()
    data class Recovered(val outputPath: String) : RecoveryState()
    data class Failed(val filePath: String, val reason: String) : RecoveryState()
}

/**
 * OrphanedSession — a recording session whose journal file was found at startup
 * but whose corresponding recording did not complete normally (app crash, OOM,
 * forced shutdown). The user is offered recovery or discard.
 */
data class OrphanedSession(
    val journalPath: String,
    val tempFilePath: String,
    val startedAtMs: Long,
    val durationEstimateMs: Long,
    val codec: String,
    val resolution: String
)

/**
 * SessionConfig — snapshot of recording parameters stored in the write-ahead
 * journal before any file writes occur. Used by crash recovery to reconstruct
 * the MP4 header if the main file is incomplete.
 */
data class SessionConfig(
    val codec: String        = "H264",
    val bitrateKbps: Int     = 50_000,
    val fps: Int             = 30,
    val width: Int           = 1920,
    val height: Int          = 1080,
    val audioEnabled: Boolean = true,
    val audioBitrateKbps: Int = 256
)

/**
 * StreamFrame — a single encoded NAL unit held in the reconnection buffer.
 * The buffer retains the last N frames beginning from the most recent keyframe,
 * which are retransmitted to the streaming server after a disconnect/reconnect.
 */
data class StreamFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean
)
