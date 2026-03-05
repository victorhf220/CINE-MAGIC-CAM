package com.cinecamera.test

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * HiltTestRunner
 *
 * Custom AndroidJUnitRunner that replaces the standard Application
 * with HiltTestApplication during instrumented test runs.
 *
 * FIXES audit finding #5: build.gradle.kts references this class as
 * testInstrumentationRunner, but the class was never created, causing
 * the entire instrumented test suite to fail at compilation.
 *
 * Registration in app/build.gradle.kts:
 *   defaultConfig {
 *     testInstrumentationRunner = "com.cinecamera.test.HiltTestRunner"
 *   }
 *
 * HiltTestApplication handles Hilt component generation for test APKs
 * without requiring a full production Application. Each @HiltAndroidTest
 * class then uses @HiltAndroidRule to inject the test-scoped component.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?
    ): Application = super.newApplication(
        classLoader,
        HiltTestApplication::class.java.name,
        context
    )
}
