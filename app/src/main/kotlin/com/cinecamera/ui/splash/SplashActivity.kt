package com.cinecamera.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.cinecamera.ui.camera.CameraActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash screen activity with app initialization
 */
@AndroidEntryPoint
class SplashActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install SplashScreen before calling super.onCreate()
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        
        lifecycleScope.launch {
            // Simulate initialization delay
            delay(1500)
            
            // Navigate to main camera activity
            startActivity(Intent(this@SplashActivity, CameraActivity::class.java))
            finish()
        }
    }
}
