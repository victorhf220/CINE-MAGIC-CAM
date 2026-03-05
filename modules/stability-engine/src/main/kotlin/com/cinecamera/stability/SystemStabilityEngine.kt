package com.cinecamera.stability

import kotlinx.coroutines.flow.StateFlow

/**
 * SystemStabilityEngine
 *
 * Monitors CPU temperature, CPU utilization, memory pressure, and thermal
 * throttle state to protect recording quality under sustained load.
 *
 * Thermal state machine:
 *   Normal (< 42°C) → Warning (42–48°C) → Critical (48–52°C) → Emergency (> 52°C)
 *
 * Callbacks from this engine drive two feedback loops in CameraViewModel:
 *   Critical → reduce encoding bitrate by 30%
 *   Emergency → force stop recording to protect the device
 */
interface SystemStabilityEngine {

    val systemHealth: StateFlow<SystemHealth>
    val thermalAlert: StateFlow<ThermalAlert>

    /** Starts the 1 Hz system monitoring loop. */
    fun startMonitoring()

    /** Stops monitoring and releases resources. */
    fun stopMonitoring()
}

data class SystemHealth(
    val cpuUsagePct: Float       = 0f,
    val cpuTemperatureC: Float   = 0f,
    val memoryUsedMb: Int        = 0,
    val memoryTotalMb: Int       = 0,
    val isThermallyThrottled: Boolean = false,
    val batteryLevelPct: Int     = 100,
    val isCharging: Boolean      = false
)

sealed class ThermalAlert {
    /** CPU temperature below 42°C — no action required. */
    object Normal : ThermalAlert()

    /** 42–48°C — display warning, log to telemetry. */
    data class Warning(val tempC: Float) : ThermalAlert()

    /** 48–52°C — reduce bitrate by 30% immediately. */
    data class Critical(val tempC: Float) : ThermalAlert()

    /** > 52°C — stop recording to prevent hardware damage. */
    data class Emergency(val tempC: Float) : ThermalAlert()
}
