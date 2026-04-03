package com.cinecamera.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * BootReceiver
 *
 * Receives BOOT_COMPLETED broadcast to perform any initialization
 * needed after device boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.d("Boot completed - initializing services")
            // TODO: Initialize any required services or schedule alarms
        }
    }
}