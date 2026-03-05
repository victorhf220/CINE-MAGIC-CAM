package com.cinecamera.stabilization

import kotlinx.coroutines.flow.StateFlow

/**
 * IStabilizationEngine
 *
 * Contract for the gyroscope-based Electronic Image Stabilization (EIS) engine.
 * Processes gyroscope samples via a Kalman filter + Rodriguez rotation and
 * applies a compensating crop to the preview and recording frames.
 *
 * All stabilization math runs in native C++ via JNI (StabilizationJNI.cpp)
 * and is invoked from the camera frame callback thread, never from the main thread.
 */
interface IStabilizationEngine {

    val stabilizationState: StateFlow<StabilizationState>

    /** Start sampling the gyroscope. Must be called before recording begins. */
    fun start()

    /** Stop gyroscope sampling and flush internal state. */
    fun stop()

    /**
     * Sets stabilization aggressiveness.
     * 0.0 = no stabilization (pass-through)
     * 1.0 = maximum crop and smoothing
     */
    fun setIntensity(intensity: Float)

    /**
     * Reports the current crop rect as a normalized rectangle [0,1].
     * The camera preview surface must be cropped to this rect each frame
     * to apply the compensation.
     */
    fun getCropRect(): CropRect
}

data class CropRect(
    val left: Float   = 0f,
    val top: Float    = 0f,
    val right: Float  = 1f,
    val bottom: Float = 1f
)

sealed class StabilizationState {
    object Idle : StabilizationState()
    object Active : StabilizationState()
    /** Gyroscope unavailable on this device. */
    object Unavailable : StabilizationState()
}

/**
 * KalmanFilter1D — exposed from the stabilization module for unit testing
 * without requiring a physical device.
 */
class KalmanFilter1D(
    private var processNoise: Float = 1e-5f,
    private var measurementNoise: Float = 1e-3f
) {
    private var estimate = 0f
    private var errorCovariance = 1f

    fun update(measurement: Float): Float {
        // Predict
        val predictedError = errorCovariance + processNoise

        // Update
        val gain = predictedError / (predictedError + measurementNoise)
        estimate += gain * (measurement - estimate)
        errorCovariance = (1f - gain) * predictedError

        return estimate
    }

    fun reset() {
        estimate = 0f
        errorCovariance = 1f
    }
}
