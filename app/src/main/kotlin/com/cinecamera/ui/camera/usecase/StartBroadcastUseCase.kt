package com.cinecamera.ui.camera.usecase

import com.cinecamera.encoding.IEncodingEngine
import com.cinecamera.monetization.Feature
import com.cinecamera.monetization.MonetizationEngine
import com.cinecamera.recovery.RecoveryEngine
import com.cinecamera.streaming.RTMPConfig
import com.cinecamera.streaming.RTMPStreamingEngine
import com.cinecamera.streaming.SRTStreamingController
import com.cinecamera.streaming.StreamConfig
import com.cinecamera.telemetry.TelemetryEngine
import timber.log.Timber
import javax.inject.Inject

/**
 * StartBroadcastUseCase
 *
 * FIXES audit finding #5: removes broadcast startup logic from ViewModel.
 *
 * Handles feature gate verification, NAL callback wiring (with thread-safe
 * CopyOnWriteArrayList for multi-stream), reconnection buffer registration,
 * and telemetry notification.
 *
 * The ViewModel previously duplicated NAL callback wiring for both RTMP
 * and SRT, and had no error handling if connect() threw. This UseCase
 * encapsulates error handling and returns a typed BroadcastResult.
 */
class StartBroadcastUseCase @Inject constructor(
    private val srtController: SRTStreamingController,
    private val rtmpEngine: RTMPStreamingEngine,
    private val encodingEngine: IEncodingEngine,
    private val recoveryEngine: RecoveryEngine,
    private val monetization: MonetizationEngine,
    private val telemetry: TelemetryEngine
) {
    /**
     * Starts an SRT broadcast session.
     * Returns FeatureGated if the subscription does not include SRT.
     * Returns Failure with a reason string if connection fails.
     */
    suspend fun startSRT(config: StreamConfig): BroadcastResult {
        if (!monetization.hasFeature(Feature.SRT)) return BroadcastResult.FeatureGated(Feature.SRT)

        return try {
            srtController.connect(config)

            encodingEngine.addNALCallback { data, pts, isKey ->
                srtController.sendNALUnit(data, pts, isKey)
                recoveryEngine.bufferStreamFrame(data, pts, isKey)
            }

            telemetry.onStreamingEvent("srt_started", mapOf(
                "host"       to config.host,
                "port"       to config.port,
                "latency_ms" to config.latencyMs
            ))

            Timber.i("SRT broadcast started → ${config.host}:${config.port}")
            BroadcastResult.Success(protocol = "SRT")
        } catch (e: Exception) {
            Timber.e(e, "SRT connection failed")
            telemetry.logError(e, "StartBroadcastUseCase.startSRT")
            BroadcastResult.Failure(e.message ?: "SRT connection failed")
        }
    }

    /**
     * Starts an RTMP broadcast session.
     * Returns FeatureGated if the subscription does not include RTMP.
     */
    suspend fun startRTMP(config: RTMPConfig): BroadcastResult {
        if (!monetization.hasFeature(Feature.RTMP)) return BroadcastResult.FeatureGated(Feature.RTMP)

        return try {
            rtmpEngine.connect(config)

            encodingEngine.addNALCallback { data, pts, isKey ->
                rtmpEngine.sendVideoNAL(data, pts, isKey)
                recoveryEngine.bufferStreamFrame(data, pts, isKey)
            }

            telemetry.onStreamingEvent("rtmp_started", mapOf(
                "host"     to config.host,
                "port"     to config.port,
                "use_tls"  to config.useTLS
            ))

            Timber.i("RTMP broadcast started → ${config.host}:${config.port}")
            BroadcastResult.Success(protocol = "RTMP")
        } catch (e: Exception) {
            Timber.e(e, "RTMP connection failed")
            telemetry.logError(e, "StartBroadcastUseCase.startRTMP")
            BroadcastResult.Failure(e.message ?: "RTMP connection failed")
        }
    }
}

/**
 * StopBroadcastUseCase — symmetric teardown.
 */
class StopBroadcastUseCase @Inject constructor(
    private val srtController: SRTStreamingController,
    private val rtmpEngine: RTMPStreamingEngine,
    private val encodingEngine: IEncodingEngine,
    private val telemetry: TelemetryEngine
) {
    operator fun invoke() {
        srtController.disconnect()
        rtmpEngine.disconnect()
        encodingEngine.clearNALCallbacks()
        telemetry.onStreamingEvent("broadcast_stopped")
        Timber.i("Broadcast stopped")
    }
}

sealed class BroadcastResult {
    data class Success(val protocol: String) : BroadcastResult()
    data class Failure(val reason: String) : BroadcastResult()
    data class FeatureGated(val requiredFeature: Feature) : BroadcastResult()
}
