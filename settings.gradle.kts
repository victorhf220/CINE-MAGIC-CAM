pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "CineCamera"

// Application shell
include(":app")

// Engine modules — each independently testable and releasable as AAR
include(":modules:camera-engine")
include(":modules:encoding-engine")
include(":modules:image-processing-engine")
include(":modules:audio-engine")
include(":modules:streaming-engine")
include(":modules:stabilization-engine")
include(":modules:stability-engine")
include(":modules:recovery-engine")
include(":modules:preset-engine")
include(":modules:telemetry-engine")
include(":modules:monetization-engine")
