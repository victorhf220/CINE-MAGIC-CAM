cmake_minimum_required(VERSION 3.22.1)
project("cinecamera_native" LANGUAGES CXX)

# ─── Language & compiler standard ────────────────────────────────────────────
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ─── Compiler flags ───────────────────────────────────────────────────────────
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -fno-exceptions -fvisibility=hidden")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -DNDEBUG -flto")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections")

# NEON SIMD on ARM64
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+simd+crypto -mfpu=neon-fp-armv8")
endif()

# ─── Source files ─────────────────────────────────────────────────────────────
add_library(
    cinecamera_native
    SHARED

    # ── Camera Engine JNI ─────────────────────────────────────────────────────
    camera/CameraJNI.cpp
    camera/LensDiscovery.cpp
    camera/ExposureSmoother.cpp

    # ── Image Processing ─────────────────────────────────────────────────────
    imageprocessing/ImageProcessingJNI.cpp
    imageprocessing/YUVConverter.cpp         # NEON-accelerated YUV→RGB
    imageprocessing/LUTEngine.cpp            # 3D LUT trilinear interpolation
    imageprocessing/NoiseReduction.cpp       # Bilateral filter
    imageprocessing/WaveformGenerator.cpp   # Luminance column sampling
    imageprocessing/HistogramAnalyzer.cpp   # 256-bucket RGB histogram
    imageprocessing/GLRenderer.cpp          # OpenGL ES 3.0 render loop
    imageprocessing/ShaderManager.cpp       # GLSL compilation and uniform binding

    # ── Audio Engine ─────────────────────────────────────────────────────────
    audio/AudioJNI.cpp
    audio/DSPChain.cpp                      # Noise gate, gain, limiter
    audio/SyncManager.cpp                   # A/V PTS alignment

    # ── Stabilization ────────────────────────────────────────────────────────
    stabilization/StabilizationJNI.cpp
    stabilization/MotionVectorSmoother.cpp
    stabilization/KalmanFilter3D.cpp
    stabilization/RollingShutterCorrection.cpp

    # ── Streaming: SRT ───────────────────────────────────────────────────────
    streaming/SRTEngine.cpp                 # libsrt socket management
    streaming/MPEGTSPacketizer.cpp          # 188-byte TS packet construction
    streaming/SRTJNIBridge.cpp
    streaming/NetworkMonitor.cpp

    # ── Streaming: RTMP ──────────────────────────────────────────────────────
    streaming/RTMPEngine.cpp                # librtmp or custom RTMP implementation
    streaming/FLVMuxer.cpp                  # FLV container assembly
    streaming/AMFSerializer.cpp             # AMF0/AMF3 metadata encoding
    streaming/RTMPJNIBridge.cpp

    # ── Recovery: FFmpeg remux ────────────────────────────────────────────────
    recovery/RecoveryJNI.cpp
    recovery/FFmpegRemux.cpp                # Crash recovery remux via libavformat

    # ── Encoding ─────────────────────────────────────────────────────────────
    encoding/EncodingJNI.cpp
    encoding/BitstreamParser.cpp            # NAL unit extraction from MediaCodec output
)

# ─── Include paths ────────────────────────────────────────────────────────────
target_include_directories(cinecamera_native PRIVATE
    ${CMAKE_SOURCE_DIR}/include
    ${CMAKE_SOURCE_DIR}/include/srt
    ${CMAKE_SOURCE_DIR}/include/ffmpeg
)

# ─── System libraries ─────────────────────────────────────────────────────────
find_library(log-lib log)
find_library(android-lib android)
find_library(EGL-lib EGL)
find_library(GLESv3-lib GLESv3)
find_library(camera2ndk-lib camera2ndk)
find_library(mediandk-lib mediandk)
find_library(sync-lib sync)

# ─── Pre-compiled static libraries ───────────────────────────────────────────
# Build via scripts/build_dependencies.sh
# Outputs to: app/src/main/cpp/libs/${ANDROID_ABI}/

set(LIB_DIR ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI})

# libsrt (Haivision SRT)
add_library(srt STATIC IMPORTED)
set_target_properties(srt PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libsrt.a)

# libssl / libcrypto (OpenSSL, required by libsrt for AES encryption)
add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libssl.a)
add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libcrypto.a)

# FFmpeg (libavformat, libavcodec, libavutil — for crash recovery remux)
add_library(avformat STATIC IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libavformat.a)
add_library(avcodec STATIC IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libavcodec.a)
add_library(avutil STATIC IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libavutil.a)
add_library(swresample STATIC IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libswresample.a)

# ─── Link ─────────────────────────────────────────────────────────────────────
target_link_libraries(
    cinecamera_native
    ${log-lib}
    ${android-lib}
    ${EGL-lib}
    ${GLESv3-lib}
    ${camera2ndk-lib}
    ${mediandk-lib}
    ${sync-lib}
    srt
    ssl
    crypto
    avformat
    avcodec
    avutil
    swresample
    atomic
    dl
    z
    m
)
