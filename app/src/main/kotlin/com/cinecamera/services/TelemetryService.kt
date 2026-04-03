package com.cinecamera.services

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * TelemetryService
 *
 * Background service that handles telemetry data flush to server.
 * Runs silently without foreground notification.
 */
@AndroidEntryPoint
class TelemetryService : Service() {

    companion object {
        const val ACTION_FLUSH_TELEMETRY = "com.cinecamera.FLUSH_TELEMETRY"
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): TelemetryService = this@TelemetryService
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("TelemetryService created")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FLUSH_TELEMETRY -> flushTelemetry()
        }
        return START_STICKY
    }

    private fun flushTelemetry() {
        Timber.d("Flushing telemetry data...")
        // TODO: Implement telemetry flush logic
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("TelemetryService destroyed")
    }
}