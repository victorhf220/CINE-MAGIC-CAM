cmake_minimum_required(VERSION 3.22.1)
project("cinecamera_native" LANGUAGES CXX)

# ─── Language & compiler standard ────────────────────────────────────────────
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ─── Compiler flags ───────────────────────────────────────────────────────────
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -fno-exceptions -fvisibility=hidden")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -DNDEBUG")

# NEON SIMD on ARM64
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+simd")
endif()

# ─── Source files (STUBS for build) ─────────────────────────────────────────
add_library(
    cinecamera_native
    SHARED

    # ── Camera Engine JNI ─────────────────────────────────────────────────────
    camera/CameraJNI.cpp
    camera/LensDiscovery.cpp
    camera/ExposureSmoother.cpp

    # ── Image Processing ─────────────────────────────────────────────────────
    imageprocessing/ImageProcessingJNI.cpp
    imageprocessing/YUVConverter.cpp
    imageprocessing/LUTEngine.cpp
    imageprocessing/NoiseReduction.cpp
    imageprocessing/WaveformGenerator.cpp
    imageprocessing/HistogramAnalyzer.cpp
    imageprocessing/GLRenderer.cpp
    imageprocessing/ShaderManager.cpp

    # ── Audio Engine ─────────────────────────────────────────────────────────
    audio/AudioJNI.cpp
    audio/DSPChain.cpp
    audio/SyncManager.cpp

    # ── Stabilization ────────────────────────────────────────────────────────
    stabilization/StabilizationJNI.cpp
    stabilization/MotionVectorSmoother.cpp
    stabilization/KalmanFilter3D.cpp
    stabilization/RollingShutterCorrection.cpp

    # ── Streaming ────────────────────────────────────────────────────────────
    streaming/SRTEngine.cpp
    streaming/MPEGTSPacketizer.cpp
    streaming/SRTJNIBridge.cpp
    streaming/NetworkMonitor.cpp
    streaming/RTMPEngine.cpp
    streaming/FLVMuxer.cpp
    streaming/AMFSerializer.cpp
    streaming/RTMPJNIBridge.cpp

    # ── Recovery ─────────────────────────────────────────────────────────────
    recovery/RecoveryJNI.cpp
    recovery/FFmpegRemux.cpp

    # ── Encoding ─────────────────────────────────────────────────────────────
    encoding/EncodingJNI.cpp
    encoding/BitstreamParser.cpp
)

# ─── Include paths ────────────────────────────────────────────────────────────
target_include_directories(cinecamera_native PRIVATE
    ${CMAKE_SOURCE_DIR}/include
)

# ─── System libraries ─────────────────────────────────────────────────────────
find_library(log-lib log)
find_library(android-lib android)

# ─── Link (simplified for stubs) ─────────────────────────────────────────────
target_link_libraries(
    cinecamera_native
    ${log-lib}
    ${android-lib}
)