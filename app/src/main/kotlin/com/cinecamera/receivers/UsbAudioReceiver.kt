package com.cinecamera.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import timber.log.Timber

/**
 * UsbAudioReceiver
 *
 * Receives USB device attachment broadcasts to detect USB audio devices.
 */
class UsbAudioReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        const val ACTION_USB_DEVICE_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_USB_DEVICE_ATTACHED -> {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                device?.let {
                    Timber.d("USB device attached: ${it.deviceName}")
                    // TODO: Handle USB audio device attachment
                }
            }
            ACTION_USB_DEVICE_DETACHED -> {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                device?.let {
                    Timber.d("USB device detached: ${it.deviceName}")
                    // TODO: Handle USB audio device detachment
                }
            }
        }
    }
}