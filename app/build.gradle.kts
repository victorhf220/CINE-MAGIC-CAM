import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.navigation.safeargs)
}

// Load signing config from local.properties (never commit to VCS)
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.cinecamera"
    compileSdk = 34
    ndkVersion = "25.1.8937393"

    defaultConfig {
        applicationId = "com.cinecamera"
        minSdk = 26                 // Android 8.0 — Camera2 full support baseline
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "com.cinecamera.test.HiltTestRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-ffast-math", "-DNDEBUG")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // ── Signing ───────────────────────────────────────────────────────────────
    signingConfigs {
        create("release") {
            storeFile = localProperties.getProperty("keystore.path")?.let { file(it) }
            storePassword = localProperties.getProperty("keystore.password")
            keyAlias = localProperties.getProperty("key.alias")
            keyPassword = localProperties.getProperty("key.password")
        }
    }

    // ── Build Types ───────────────────────────────────────────────────────────
    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ENABLE_LOGGING", "true")
            buildConfigField("boolean", "ENABLE_STRICT_MODE", "true")
            buildConfigField("String", "TELEMETRY_ENDPOINT", "\"https://dev-telemetry.cinecamera.io/v1\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "ENABLE_LOGGING", "false")
            buildConfigField("boolean", "ENABLE_STRICT_MODE", "false")
            buildConfigField("String", "TELEMETRY_ENDPOINT", "\"https://telemetry.cinecamera.io/v1\"")
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
        }
    }

    // ── Product Flavors (Monetization tiers) ─────────────────────────────────
    flavorDimensions += "tier"
    productFlavors {
        create("free") {
            dimension = "tier"
            applicationIdSuffix = ".free"
            buildConfigField("String", "APP_TIER", "\"FREE\"")
            buildConfigField("int", "MAX_BITRATE_MBPS", "30")
            buildConfigField("boolean", "ENABLE_LOG_PROFILE", "false")
            buildConfigField("boolean", "ENABLE_LUT_ENGINE", "false")
            buildConfigField("boolean", "ENABLE_SRT", "false")
            buildConfigField("boolean", "ENABLE_RTMP", "false")
            buildConfigField("boolean", "ENABLE_MULTISTREAM", "false")
            buildConfigField("boolean", "ENABLE_AUDIO_PRO", "false")
            buildConfigField("int", "MAX_PRESETS", "3")
        }
        create("pro") {
            dimension = "tier"
            applicationIdSuffix = ".pro"
            buildConfigField("String", "APP_TIER", "\"PRO\"")
            buildConfigField("int", "MAX_BITRATE_MBPS", "150")
            buildConfigField("boolean", "ENABLE_LOG_PROFILE", "true")
            buildConfigField("boolean", "ENABLE_LUT_ENGINE", "true")
            buildConfigField("boolean", "ENABLE_SRT", "false")
            buildConfigField("boolean", "ENABLE_RTMP", "true")
            buildConfigField("boolean", "ENABLE_MULTISTREAM", "false")
            buildConfigField("boolean", "ENABLE_AUDIO_PRO", "true")
            buildConfigField("int", "MAX_PRESETS", "20")
        }
        create("enterprise") {
            dimension = "tier"
            applicationIdSuffix = ".enterprise"
            buildConfigField("String", "APP_TIER", "\"ENTERPRISE\"")
            buildConfigField("int", "MAX_BITRATE_MBPS", "150")
            buildConfigField("boolean", "ENABLE_LOG_PROFILE", "true")
            buildConfigField("boolean", "ENABLE_LUT_ENGINE", "true")
            buildConfigField("boolean", "ENABLE_SRT", "true")
            buildConfigField("boolean", "ENABLE_RTMP", "true")
            buildConfigField("boolean", "ENABLE_MULTISTREAM", "true")
            buildConfigField("boolean", "ENABLE_AUDIO_PRO", "true")
            buildConfigField("int", "MAX_PRESETS", "999")
        }
    }

    // ── Native Build ──────────────────────────────────────────────────────────
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ── Compile Options ───────────────────────────────────────────────────────
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Ensure consistent packaging for JNI
    packaging {
        jniLibs { keepDebugSymbols += "**/*.so" }
    }
}

dependencies {
    // ── Engine Modules ────────────────────────────────────────────────────────
    implementation(project(":modules:camera-engine"))
    implementation(project(":modules:encoding-engine"))
    implementation(project(":modules:image-processing-engine"))
    implementation(project(":modules:audio-engine"))
    implementation(project(":modules:streaming-engine"))
    implementation(project(":modules:stabilization-engine"))
    implementation(project(":modules:stability-engine"))
    implementation(project(":modules:recovery-engine"))
    implementation(project(":modules:preset-engine"))
    implementation(project(":modules:telemetry-engine"))
    implementation(project(":modules:monetization-engine"))

    // ── AndroidX ──────────────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.splash)

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.process)

    // ── Coroutines ────────────────────────────────────────────────────────────
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // ── Hilt ─────────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.work)

    // ── Room ─────────────────────────────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── Navigation ────────────────────────────────────────────────────────────
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // ── DataStore ─────────────────────────────────────────────────────────────
    implementation(libs.datastore.preferences)

    // ── Network ───────────────────────────────────────────────────────────────
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)

    // ── Billing ───────────────────────────────────────────────────────────────
    implementation(libs.billing)

    // ── WorkManager ───────────────────────────────────────────────────────────
    implementation(libs.workmanager)

    // ── UI ────────────────────────────────────────────────────────────────────
    implementation(libs.timber)
    implementation(libs.lottie)
    implementation(libs.coil)
    implementation(libs.gson)

    // ── Testing ───────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.hilt.android)
    kaptAndroidTest(libs.hilt.compiler)
}
