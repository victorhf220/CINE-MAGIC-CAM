package com.cinecamera.streaming

import kotlinx.coroutines.flow.StateFlow

/**
 * SRTStreamingController
 *
 * Contract for the SRT (Secure Reliable Transport) streaming pipeline.
 * SRT operates at the transport layer and supports Caller, Listener, and
 * Rendezvous connection modes. The native libsrt 1.5.3 implementation is
 * wrapped by SRTEngine.cpp via JNI.
 *
 * Adaptive bitrate: the controller monitors RTT and packet loss and signals
 * the encoding engine to reduce bitrate when the network cannot sustain the
 * configured rate. Hysteresis prevents rapid oscillation.
 */
interface SRTStreamingController {

    val streamingState: StateFlow<StreamingState>
    val streamStats: StateFlow<StreamStats>

    /**
     * Opens an SRT connection. Suspends until connected or throws on failure.
     * Internally handles MPEG-TS packetization and AES encryption when
     * a passphrase is configured in StreamConfig.
     */
    suspend fun connect(config: StreamConfig)

    /** Sends a single encoded video NAL unit to the remote SRT endpoint. */
    fun sendNALUnit(data: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean)

    /**
     * Closes the SRT connection gracefully, draining pending packets first.
     * Reconnection is handled by the ReconnectionManager, not this method.
     */
    fun disconnect()
}
