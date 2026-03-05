# CineCamera — Enterprise Architecture & Technical Reference
**Version 2.0 | Confidential Technical Documentation**

---

## 1. Executive Summary

CineCamera is a professional-grade Android camera application built for broadcast-quality video recording and streaming. The architecture is structured around 12 independent engine modules communicating through clean interfaces, enabling independent testing, graduated feature release, and future extraction of individual modules as commercial SDKs.

The technology stack is: Kotlin (primary application and business logic), C++17 via NDK (performance-critical paths including YUV conversion, GPU rendering, audio DSP, and network I/O), OpenGL ES 3.0 (image processing pipeline), and MediaCodec (hardware video encoding). External libraries libsrt and FFmpeg are compiled from source for Android.

---

## 2. Module Architecture

The project follows Clean Architecture layered on top of MVVM. Dependency direction is strictly inward: the UI layer depends on ViewModels, ViewModels depend on engine interfaces, and engine implementations are provided via Hilt dependency injection.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Application Shell (:app)                      │
│   UI Layer → ViewModels → Hilt DI Module → Engine Interfaces            │
└──────────────────┬──────────────────────────────────────────────────────┘
                   │ depends on
┌──────────────────▼──────────────────────────────────────────────────────┐
│                          Engine Modules                                  │
│                                                                          │
│  :camera-engine      :encoding-engine    :image-processing-engine       │
│  :audio-engine       :streaming-engine   :stabilization-engine          │
│  :stability-engine   :recovery-engine    :preset-engine                 │
│  :telemetry-engine   :monetization-engine                               │
└──────────────────┬──────────────────────────────────────────────────────┘
                   │ JNI
┌──────────────────▼──────────────────────────────────────────────────────┐
│                    Native C++17 Layer (NDK)                              │
│  YUVConverter · GLRenderer · SRTEngine · RTMPEngine · FFmpegRemux       │
│  DSPChain · KalmanFilter3D · MPEGTSPacketizer · BitstreamParser         │
└─────────────────────────────────────────────────────────────────────────┘
```

Every module exposes a single Kotlin interface (e.g., `ICameraEngine`, `IAudioEngine`) backed by a `@Singleton` Hilt-injected implementation. Modules never import each other's implementation classes — inter-module communication flows exclusively through the `CameraViewModel` orchestrator.

---

## 3. Image Processing Pipeline

Each frame captured by Camera2 traverses the following pipeline stages:

**Stage 1 — YUV_420_888 Input.** Camera2 delivers raw frames in `YUV_420_888` planar format, providing unprocessed sensor data. The `YUVConverter.cpp` converts this to interleaved RGBA using BT.709 coefficients, accelerated on ARM64 with NEON SIMD intrinsics processing 8 pixels per clock cycle.

**Stage 2 — Noise Reduction.** A bilateral filter pass in the OpenGL fragment shader smooths chrominance noise while preserving luminance edges, which is critical for accurate focus peaking detection downstream.

**Stage 3 — CineLog™ Gamma Encoding.** The proprietary logarithmic curve encodes scene-linear light using a piecewise function: a linear segment below `x = 0.0088` (avoiding logarithm singularity near black) and a scaled `log₁₀` curve above. The 1D encoding LUT (4,096 entries) is pre-computed at initialization, eliminating per-pixel transcendental function evaluation in the shader.

| CineLog™ Parameter | Value |
|-------------------|-------|
| A (scale factor) | 0.244 |
| B (linear multiplier) | 444.0 |
| C (offset) | 0.0539 |
| D (lift) | 0.386 |
| Linear cut point | 0.0088 |
| Middle grey in → out | 18% → 38% |
| Estimated dynamic range | ~14 stops |

**Stage 4 — 3D LUT Application.** Parsed `.cube` files are uploaded as `GL_RGB32F` 3D textures. The fragment shader applies trilinear interpolation via a single `texture()` call, with intensity blending (`mix()`) for partial application. Supported LUT sizes are 4³ to 64³.

**Stage 5 — Tone Mapping.** ACES filmic tone mapping compresses wide-dynamic-range log-encoded content into display range, providing the characteristic highlight shoulder roll-off that mimics film stock response.

**Stage 6 — Color Grading Controls.** Contrast (pivoted at 18% middle grey), saturation (luma-weighted), highlights, and shadows are applied as fragment shader uniforms, updated without recompiling the shader.

**Stage 7 — Sharpening.** Unsharp mask computes a 4-neighbor blur, then adds back a user-scaled fraction of the difference, from 0.0 (bypass) to 1.0.

**Stage 8 — Monitoring Overlays.** Zebra stripes (Laplacian-threshold luma detection) and focus peaking (Laplacian edge filter with configurable color) are applied in the final shader pass, routing exclusively to the preview display surface. They are never present in the encoded video or broadcast stream.

---

## 4. Audio Engine

The professional audio pipeline implements a three-stage DSP signal chain per buffer in this order.

The **Noise Gate** compares the absolute value of each sample against a user-configurable threshold (default −60 dBFS). Samples below this level are zeroed, suppressing continuous background noise from internal microphones without coloring the signal above threshold.

The **Pre-Amplifier** applies user-controlled gain in the range −12 dBFS to +40 dBFS. All gain values are stored and applied as linear multipliers computed once per setGain() call, rather than converting to/from decibels per sample.

The **Brickwall Limiter** uses a soft-knee curve: below `ceiling − 6 dB`, signal passes unchanged; within the knee zone, progressive compression transitions from 1:1 to ∞:1 via a hyperbolic curve; above the ceiling, hard clipping applies as a safety backstop that should never be reached with correct gain staging.

The VU meter runs on a separate coroutine sampling the `AudioRecord` buffer at 60 Hz. RMS values are computed per channel using an SSE-style sum-of-squares loop, converted to dBFS for display. Peak hold tracks the maximum reading per channel over a 2-second window.

AAC encoding is performed by a MediaCodec instance in `CONFIGURE_FLAG_ENCODE` mode at the configured bitrate (128–320 kbps). Encoded frames are placed in a concurrent queue consumed by the muxer thread and optionally forwarded to the streaming engine.

Audio/video synchronization is maintained by using the same `System.nanoTime()` epoch for both the camera capture timestamps and the audio `AudioRecord` timestamps. PTS values written to the muxer are aligned to a common clock base established at session start.

---

## 5. Streaming Engine

The streaming architecture supports two concurrent output paths: SRT (Secure Reliable Transport) and RTMP/RTMPS.

**SRT Pipeline.** Encoded H.264 NAL units from `MediaCodecEncodingEngine` are passed to the `MPEGTSPacketizer`, which wraps each access unit in 188-byte MPEG-TS packets with proper PAT, PMT, and PES headers. TS packets are sent to the native `SRTEngine.cpp` layer, which writes them to a libsrt socket configured in Caller, Listener, or Rendezvous mode. The SRT protocol provides end-to-end reliability via ARQ retransmission, with configurable latency from 20 ms to 2000 ms. AES-128 and AES-256 encryption are available via the OpenSSL integration compiled into libsrt.

**RTMP Pipeline.** The `RTMPStreamingEngine` constructs FLV-encapsulated media before transmission. An `AVCDecoderConfigurationRecord` (SPS/PPS sequence header) is sent before the first media tag. Video data is wrapped in AVC NALU payloads with 4-byte length prefixes. Audio is wrapped in AAC audio tags with the AudioSpecificConfig sent as a separate sequence header. AMF0 metadata containing stream parameters (resolution, framerate, bitrate) is sent as a script tag at connection time, enabling downstream servers to allocate buffers appropriately.

**Adaptive Bitrate.** The `BitrateAdaptiveController` monitors SRT packet loss statistics and RTMP buffer depth at 1-second intervals. If loss exceeds 2%, it signals the encoding engine to reduce bitrate by 20%. Loss exceeding 5% triggers a 40% reduction. Return to 0% loss triggers gradual ramp-up over 10 seconds to avoid bandwidth oscillation.

**Multi-Stream (Enterprise).** Multiple `RTMPStreamingEngine` instances can operate concurrently. The encoding engine's NAL unit callback supports multiple registered consumers via a `CopyOnWriteArrayList<NalCallback>`, distributing the same encoded bytes to each stream without additional encoding passes.

**Reconnection.** `ReconnectionManager` implements exponential backoff with a 1-second base interval, doubling per attempt to a maximum of 30 seconds. During a disconnect, the `RecoveryEngine` maintains a 5-second ring buffer of encoded frames, retransmitting from the last keyframe upon reconnect to minimize visible stream interruption.

---

## 6. Recovery & Safety Engine

The recovery engine implements three layers of data protection.

**Write-Ahead Session Journal.** Before recording begins, a `.session` sidecar JSON file is written containing the output path, temp path, configuration, and segment list. At 10-second intervals, a heartbeat updates the current file size. On app restart after a crash, `scanForOrphanedSessions()` finds journals with existing temp files and offers recovery to the user.

**Crash Recovery via FFmpeg Remux.** When recovery is requested, `FFmpegRemux.cpp` uses `libavformat` to open the incomplete `.mp4` or `.tmp` file, copy all decodable video and audio packets, and write a new container with a valid `moov` atom. This typically recovers all content up to the last successful GOP boundary.

**File Segment Rotation.** For recordings exceeding 30 minutes or 4 GB, the engine signals the encoding engine to finalize the current segment and open a new output file. Each completed segment is atomically renamed and added to the session journal. Even if the final segment is lost in a crash, all prior segments are complete and playable.

---

## 7. Preset Engine

Presets are persisted to a Room database table (`presets`) with a JSON blob column containing the full `CameraPreset` domain object. This avoids a complex schema migration strategy while retaining the query benefits of SQLite for list operations.

Four built-in system presets (YouTube 1080p, Cinema LOG, SRT Broadcast 8 Mbps, Interview) are seeded at first launch via `seedDefaultPresetsIfNeeded()`. System presets are read-only; the delete operation returns false for any preset with `isSystem = true`.

Import and export use a versioned JSON wrapper (`PresetExportPackage`) with a `schemaVersion` field. When importing a preset created by a newer app version, the engine rejects the import with a clear error rather than silently applying unknown fields.

---

## 8. Telemetry Engine

The engine produces two artifacts: a rotating daily log file in the app's private storage and a post-session `SessionReport` containing a 1-second-resolution timeline of bitrate, temperature, dropped frames, and stream quality metrics.

Remote telemetry transmission is opt-in, anonymized (all device identifiers SHA-256 hashed), and targets an endpoint configurable per build flavor. The engine never reads file paths, camera images, stream content, or any form of PII. Users can export all local logs via the in-app diagnostic screen for submission to support.

---

## 9. Monetization Architecture

Feature gating operates at two levels to balance security with graceful degradation. At compile time, product flavors set `BuildConfig` constants that prevent premium feature code from being included in the Free APK. At runtime, the `MonetizationEngine` validates Google Play subscription receipts before enabling features in Pro/Enterprise builds.

The last verified subscription state is cached to `SharedPreferences` and used when the device is offline. Cached state expires after 7 days, after which the engine conservatively returns `SubscriptionState.Free`. A 3-day grace period configured in Play Console allows temporary lapses without interrupting premium features.

The `hasFeature(Feature)` API is the single enforcement point. All ViewModels call this before enabling any premium operation, emitting a `featureGateEvent` to the UI state when access is denied, which triggers the upgrade prompt screen.

---

## 10. Build Guide

### Prerequisites

Android Studio Iguana (2023.2.1) or later, NDK r26b or later (install via SDK Manager), CMake 3.22.1, Ninja 1.11, JDK 17, and for FFmpeg: `nasm` installed via the system package manager.

### Compile native dependencies

```bash
chmod +x scripts/build_dependencies.sh
./scripts/build_dependencies.sh all
# Builds OpenSSL, libsrt, and FFmpeg for arm64-v8a and x86_64
# Total build time: ~20 minutes on 8-core machine
```

### Assemble debug APK (Pro flavor)

```bash
./gradlew assembleProDebug
```

### Assemble release App Bundle for Play Store

```bash
./gradlew bundleProRelease
# Requires signing configuration in local.properties:
# keystore.path=...
# keystore.password=...
# key.alias=...
# key.password=...
```

### Run unit tests

```bash
./gradlew test
```

### Run instrumented tests on connected device

```bash
./gradlew connectedAndroidTest
```

---

## 11. Testing Strategy

**Unit tests** target all pure Kotlin logic: `CineLogProfile` encode/decode round-trip accuracy (tolerance ±0.005 across 6 representative values), `LUTProcessor` `.cube` file parsing including edge cases (missing entries, oversized LUT), `KalmanFilter1D` convergence to within 1% of true value after 50 noisy samples, `ExposureTransitionSmoother` EWMA alpha scaling, `RecoveryEngine` journal serialization round-trip, `MonetizationEngine` feature gate logic for all tier/feature combinations, and `PresetEngine` save/load/delete/duplicate operations. Tests use MockK for dependency isolation and Turbine for StateFlow assertion.

**Integration tests** run on physical devices and verify: Camera2 session opens and closes without resource leaks (verified via `LeakCanary` report absence), MediaCodec encoding produces a valid MP4 with `ffprobe` verification, SRT connection to a local test server (`srt-live-server` in CI Docker), and gyroscope stabilization compensation remaining within crop bounds across 30-second motion sequences.

**Performance benchmarks** (Jetpack Benchmark library) measure: YUV conversion throughput target of 1920×1080 at 60 fps on a device with Snapdragon 865 or equivalent; LUT shader frame time below 2 ms (98th percentile); encoding drain loop without stall under 100 Mbps H.264 sustained for 60 minutes; and audio DSP chain latency below 5 ms per 1024-sample buffer.

---

## 12. Dependency List

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Kotlin | 1.9.23 | Primary language |
| Hilt | 2.51 | Dependency injection |
| Coroutines | 1.8.0 | Structured concurrency |
| Room | 2.6.1 | Preset & telemetry persistence |
| Navigation | 2.7.7 | Fragment navigation with Safe Args |
| DataStore | 1.1.0 | Preferences & settings |
| Play Billing | 6.2.1 | Subscription management |
| WorkManager | 2.9.0 | Background sync tasks |
| OkHttp | 4.12.0 | Telemetry HTTP client |
| Retrofit | 2.11.0 | REST client for telemetry API |
| Timber | 5.0.1 | Structured logging |
| Lottie | 6.4.0 | Broadcast indicator animations |
| libsrt | 1.5.3 | SRT broadcast protocol |
| OpenSSL | 3.1.5 | AES encryption for SRT |
| FFmpeg | 6.1.1 | Crash recovery remux |
| MockK | 1.13.10 | Unit test mocking |
| Turbine | 1.1.0 | StateFlow assertion |
| Google Truth | 1.4.2 | Assertion library |
| Jetpack Benchmark | 1.2.4 | Performance profiling |

---

## 13. Optimization Roadmap

**Phase 1 — Stability validation (Months 1–2).** Validate encoding stability across 30+ Android device families including Samsung, Xiaomi, OnePlus, and Pixel lines. Profile memory allocation patterns during 60-minute recordings using Android Studio Memory Profiler. Tune Kalman filter constants per device gyroscope noise floor via an in-app calibration routine that runs a 10-second stability assessment.

**Phase 2 — Performance (Months 3–4).** Migrate the image processing pipeline from OpenGL ES to Vulkan Compute on devices reporting `VK_API_VERSION_1_1` support. Vulkan compute provides deterministic latency without the driver overhead inherent in GL state machines. Implement zero-copy frame passing between Camera2 `ImageReader` and the encoder's input surface using `HardwareBuffer` on API 29+, eliminating the YUV conversion entirely for the recording path.

**Phase 3 — Feature expansion (Months 5–8).** RAW sensor capture for devices reporting `REQUEST_AVAILABLE_CAPABILITIES_RAW`, output as Adobe DNG via `DngCreator`. 10-bit H.265 Main 10 encoding for devices with HEVC encoder hardware supporting `COLOR_FormatYUVP010`. LUT Marketplace — an in-app store backed by a REST API serving professionally graded `.cube` files as one-time purchases.

**Phase 4 — Platform expansion (Month 9+).** WebRTC streaming engine implementation. RIST protocol support alongside SRT for broadcast environments requiring forward error correction. iOS companion app sharing the same preset JSON schema and LUT format for cross-platform workflow compatibility.

---

## 14. Startup & Play Store Preparation

The application is structured as three separate Play Store listings (Free, Pro, Enterprise) rather than a single in-app purchase app. This approach provides independent review queues, separate keyword ranking, and allows Enterprise pricing to remain unlisted (distributed via direct link or Google Play Enterprise channels).

The feature gate architecture allows the Free listing to serve as a permanent lead-generation channel. Users who export presets from the Free tier receive a JSON file that imports cleanly into Pro/Enterprise, removing friction from the upgrade path.

For the startup phase, the telemetry engine provides session-level performance data sufficient to identify device fragmentation issues before they surface in App Store reviews. The daily log export feature gives enterprise customers a contractual support artifact for issue reproduction.
