package com.cinecamera.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cinecamera.R
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * StreamingService
 *
 * Foreground service that manages SRT/RTMP streaming sessions.
 * Uses CAMERA, MICROPHONE, and CONNECTED_DEVICE foreground service types.
 */
@AndroidEntryPoint
class StreamingService : Service() {

    companion object {
        const val CHANNEL_ID = "streaming_channel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_START_STREAM = "com.cinecamera.START_STREAM"
        const val ACTION_STOP_STREAM = "com.cinecamera.STOP_STREAM"
    }

    private val binder = LocalBinder()
    private var isStreaming = false

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.d("StreamingService created")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAM -> startStreaming()
            ACTION_STOP_STREAM -> stopStreaming()
        }
        return START_STICKY
    }

    private fun startStreaming() {
        if (isStreaming) return

        val notification = createStreamingNotification()
        startForeground(NOTIFICATION_ID, notification)
        isStreaming = true
        Timber.d("Streaming started in foreground service")
    }

    private fun stopStreaming() {
        if (!isStreaming) return

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isStreaming = false
        Timber.d("Streaming stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Streaming session notification"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createStreamingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CineMagic Camera")
            .setContentText("Streaming live...")
            .setSmallIcon(R.drawable.ic_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("StreamingService destroyed")
    }
}