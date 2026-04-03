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
 * RecordingService
 *
 * Foreground service that keeps the recording session alive when the activity
 * goes to background (e.g., screen timeout during long takes).
 * Uses CAMERA and MICROPHONE foreground service types.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_RECORDING = "com.cinecamera.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.cinecamera.STOP_RECORDING"
    }

    private val binder = LocalBinder()
    private var isRecording = false

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.d("RecordingService created")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (isRecording) return

        val notification = createRecordingNotification()
        startForeground(NOTIFICATION_ID, notification)
        isRecording = true
        Timber.d("Recording started in foreground service")
    }

    private fun stopRecording() {
        if (!isRecording) return

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isRecording = false
        Timber.d("Recording stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Recording session notification"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createRecordingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CineMagic Camera")
            .setContentText("Recording in progress...")
            .setSmallIcon(R.drawable.ic_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("RecordingService destroyed")
    }
}