package com.cinecamera

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * CineMagic Camera Application entry point
 * 
 * Initialized with:
 * - Hilt Dependency Injection
 * - Timber logging
 * - Configuration for production/debug modes
 */
@HiltAndroidApp
class CineCameraApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize logging
        if (BuildConfig.ENABLE_LOGGING) {
            Timber.plant(Timber.DebugTree())
        }
        
        Timber.d("CineCamera initialized - Tier: %s", BuildConfig.APP_TIER)
    }
}
