package com.cinecamera.viewmodel

import app.cash.turbine.test
import com.cinecamera.audio.AudioConfig
import com.cinecamera.audio.AudioState
import com.cinecamera.audio.IAudioEngine
import com.cinecamera.audio.VUMeterData
import com.cinecamera.camera.CameraState
import com.cinecamera.camera.CaptureMetrics
import com.cinecamera.camera.ICameraEngine
import com.cinecamera.camera.LensDescriptor
import com.cinecamera.encoding.EncodingMetrics
import com.cinecamera.encoding.IEncodingEngine
import com.cinecamera.encoding.VideoCodec
import com.cinecamera.imageprocessing.HistogramData
import com.cinecamera.imageprocessing.IImageProcessingEngine
import com.cinecamera.imageprocessing.WaveformData
import com.cinecamera.monetization.Feature
import com.cinecamera.monetization.MonetizationEngine
import com.cinecamera.preset.PresetEngine
import com.cinecamera.recovery.RecoveryEngine
import com.cinecamera.stabilization.IStabilizationEngine
import com.cinecamera.stability.SystemHealth
import com.cinecamera.stability.SystemStabilityEngine
import com.cinecamera.stability.ThermalAlert
import com.cinecamera.streaming.RTMPStreamingEngine
import com.cinecamera.streaming.SRTStreamingController
import com.cinecamera.telemetry.TelemetryEngine
import com.cinecamera.ui.camera.CameraUIState
import com.cinecamera.ui.camera.CameraViewModel
import com.cinecamera.ui.camera.VideoResolution
import com.cinecamera.ui.camera.usecase.*
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class CameraViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope      = TestScope(testDispatcher)

    // Engine mocks
    private val cameraEngine           = mockk<ICameraEngine>(relaxed = true)
    private val encodingEngine         = mockk<IEncodingEngine>(relaxed = true)
    private val imageProcessingEngine  = mockk<IImageProcessingEngine>(relaxed = true)
    private val audioEngine            = mockk<IAudioEngine>(relaxed = true)
    private val stabilizationEngine    = mockk<IStabilizationEngine>(relaxed = true)
    private val stabilityEngine        = mockk<SystemStabilityEngine>(relaxed = true)
    private val recoveryEngine         = mockk<RecoveryEngine>(relaxed = true)
    private val presetEngine           = mockk<PresetEngine>(relaxed = true)
    private val telemetry              = mockk<TelemetryEngine>(relaxed = true)
    private val monetization           = mockk<MonetizationEngine>(relaxed = true)

    // UseCase mocks
    private val startRecordingUseCase  = mockk<StartRecordingUseCase>(relaxed = true)
    private val stopRecordingUseCase   = mockk<StopRecordingUseCase>(relaxed = true)
    private val startBroadcastUseCase  = mockk<StartBroadcastUseCase>(relaxed = true)
    private val stopBroadcastUseCase   = mockk<StopBroadcastUseCase>(relaxed = true)

    // StateFlows for engines
    private val cameraStateFlow    = MutableStateFlow<CameraState>(CameraState.Closed)
    private val captureMetricsFlow = MutableStateFlow(CaptureMetrics())
    private val lensesFlow         = MutableStateFlow<List<LensDescriptor>>(emptyList())
    private val encodingMetricFlow = MutableStateFlow(EncodingMetrics())
    private val audioStateFlow     = MutableStateFlow<AudioState>(AudioState.Idle)
    private val vuMeterFlow        = MutableStateFlow(VUMeterData())
    private val clippingFlow       = MutableStateFlow(false)
    private val audioSourcesFlow   = MutableStateFlow(emptyList<com.cinecamera.audio.AudioSource>())
    private val systemHealthFlow   = MutableStateFlow(SystemHealth())
    private val thermalAlertFlow   = MutableStateFlow<ThermalAlert>(ThermalAlert.Normal)
    private val recoveryStateFlow  = MutableStateFlow<com.cinecamera.recovery.RecoveryState>(
        com.cinecamera.recovery.RecoveryState.Idle)
    private val sessionReportFlow  = MutableStateFlow<com.cinecamera.telemetry.SessionReport?>(null)
    private val histogramFlow      = MutableStateFlow(HistogramData())
    private val waveformFlow       = MutableStateFlow(WaveformData())

    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        // Wire all engine StateFlows
        every { cameraEngine.cameraState }       returns cameraStateFlow
        every { cameraEngine.captureMetrics }    returns captureMetricsFlow
        every { cameraEngine.availableLenses }   returns lensesFlow
        every { encodingEngine.encodingMetrics } returns encodingMetricFlow
        every { audioEngine.audioState }         returns audioStateFlow
        every { audioEngine.vuMeterData }        returns vuMeterFlow
        every { audioEngine.clippingEvent }      returns clippingFlow
        every { audioEngine.availableSources }   returns audioSourcesFlow
        every { stabilityEngine.systemHealth }   returns systemHealthFlow
        every { stabilityEngine.thermalAlert }   returns thermalAlertFlow
        every { recoveryEngine.recoveryState }   returns recoveryStateFlow
        every { telemetry.sessionReport }        returns sessionReportFlow
        every { imageProcessingEngine.histogramData } returns histogramFlow
        every { imageProcessingEngine.waveformData }  returns waveformFlow
        every { monetization.getMaxBitrateKbps() }    returns 150_000

        viewModel = CameraViewModel(
            cameraEngine           = cameraEngine,
            encodingEngine         = encodingEngine,
            imageProcessingEngine  = imageProcessingEngine,
            audioEngine            = audioEngine,
            stabilizationEngine    = stabilizationEngine,
            stabilityEngine        = stabilityEngine,
            recoveryEngine         = recoveryEngine,
            presetEngine           = presetEngine,
            telemetry              = telemetry,
            monetization           = monetization,
            startRecording         = startRecordingUseCase,
            stopRecording          = stopRecordingUseCase,
            startBroadcast         = startBroadcastUseCase,
            stopBroadcast          = stopBroadcastUseCase
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ── Initial state ──────────────────────────────────────────────────────────

    @Test
    fun `initial uiState has isRecording false`() {
        assertThat(viewModel.uiState.value.isRecording).isFalse()
    }

    @Test
    fun `initial uiState has isBroadcasting false`() {
        assertThat(viewModel.uiState.value.isBroadcasting).isFalse()
    }

    @Test
    fun `initial codec defaults to H264`() {
        assertThat(viewModel.uiState.value.selectedCodec).isEqualTo(VideoCodec.H264)
    }

    @Test
    fun `initial resolution defaults to FHD_1080`() {
        assertThat(viewModel.uiState.value.resolution).isEqualTo(VideoResolution.FHD_1080)
    }

    @Test
    fun `initial fps defaults to 30`() {
        assertThat(viewModel.uiState.value.selectedFps).isEqualTo(30)
    }

    // ── Recording state transitions ────────────────────────────────────────────

    @Test
    fun `onRecordButtonPressed when idle starts recording`() = testScope.runTest {
        val session = RecordingSession(
            fileDescriptor = mockk(relaxed = true),
            encoderConfig  = mockk(relaxed = true),
            sessionPath    = "/tmp/test.mp4"
        )
        coEvery { startRecordingUseCase(any()) } returns session

        viewModel.onRecordButtonPressed()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isRecording).isTrue()
        coVerify { startRecordingUseCase(any()) }
    }

    @Test
    fun `onRecordButtonPressed when recording stops recording`() = testScope.runTest {
        // Establish recording state
        val session = RecordingSession(mockk(relaxed = true), mockk(relaxed = true), "/tmp/test.mp4")
        coEvery { startRecordingUseCase(any()) } returns session
        coEvery { stopRecordingUseCase(any())  } just runs

        viewModel.onRecordButtonPressed()   // start
        advanceUntilIdle()
        viewModel.onRecordButtonPressed()   // stop
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isRecording).isFalse()
        coVerify { stopRecordingUseCase(session) }
    }

    @Test
    fun `recording failure sets recordingError in uiState`() = testScope.runTest {
        coEvery { startRecordingUseCase(any()) } throws RuntimeException("Disk full")

        viewModel.onRecordButtonPressed()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isRecording).isFalse()
        assertThat(viewModel.uiState.value.recordingError).isNotNull()
        assertThat(viewModel.uiState.value.recordingError).contains("Disk full")
    }

    // ── Thermal feedback loop ──────────────────────────────────────────────────

    @Test
    fun `critical thermal alert reduces bitrate by 30 percent`() = testScope.runTest {
        val initialBitrate = viewModel.uiState.value.bitrateKbps
        val expected = (initialBitrate * 0.7f).toInt()

        thermalAlertFlow.value = ThermalAlert.Critical(49f)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.bitrateKbps).isEqualTo(expected)
        verify { encodingEngine.setDynamicBitrate(expected) }
    }

    @Test
    fun `emergency thermal alert stops recording if active`() = testScope.runTest {
        // Start a recording first
        val session = RecordingSession(mockk(relaxed = true), mockk(relaxed = true), "/tmp/t.mp4")
        coEvery { startRecordingUseCase(any()) } returns session
        coEvery { stopRecordingUseCase(any())  } just runs

        viewModel.onRecordButtonPressed()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isRecording).isTrue()

        // Fire emergency
        thermalAlertFlow.value = ThermalAlert.Emergency(53f)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isRecording).isFalse()
        coVerify { stopRecordingUseCase(any()) }
    }

    @Test
    fun `normal thermal state does not change bitrate`() = testScope.runTest {
        val initialBitrate = viewModel.uiState.value.bitrateKbps

        thermalAlertFlow.value = ThermalAlert.Normal
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.bitrateKbps).isEqualTo(initialBitrate)
        verify(exactly = 0) { encodingEngine.setDynamicBitrate(any()) }
    }

    // ── Feature gating ─────────────────────────────────────────────────────────

    @Test
    fun `loading LUT without feature sets featureGateEvent`() {
        every { monetization.hasFeature(Feature.LUT_ENGINE) } returns false

        viewModel.loadLUT("/sdcard/test.cube")

        assertThat(viewModel.uiState.value.featureGateEvent).isEqualTo(Feature.LUT_ENGINE)
        verify(exactly = 0) { imageProcessingEngine.loadLUT(any()) }
    }

    @Test
    fun `loading LUT with feature calls engine`() {
        every { monetization.hasFeature(Feature.LUT_ENGINE) } returns true
        every { imageProcessingEngine.loadLUT(any()) } returns true

        viewModel.loadLUT("/sdcard/test.cube")

        verify { imageProcessingEngine.loadLUT("/sdcard/test.cube") }
        assertThat(viewModel.uiState.value.featureGateEvent).isNull()
    }

    @Test
    fun `setColorProfile CINELOG without feature sets featureGateEvent`() {
        every { monetization.hasFeature(Feature.LOG_PROFILE) } returns false

        viewModel.setColorProfile(com.cinecamera.imageprocessing.ColorProfile.CINELOG)

        assertThat(viewModel.uiState.value.featureGateEvent)
            .isEqualTo(Feature.LOG_PROFILE)
    }

    @Test
    fun `consumeFeatureGateEvent clears the event`() {
        every { monetization.hasFeature(Feature.LUT_ENGINE) } returns false
        viewModel.loadLUT("/test.cube")
        assertThat(viewModel.uiState.value.featureGateEvent).isNotNull()

        viewModel.consumeFeatureGateEvent()

        assertThat(viewModel.uiState.value.featureGateEvent).isNull()
    }

    // ── Camera parameter controls ──────────────────────────────────────────────

    @Test
    fun `setISO delegates to camera engine`() {
        viewModel.setISO(800)
        verify { cameraEngine.setISO(800) }
    }

    @Test
    fun `setShutterSpeed delegates to camera engine`() {
        val ns = 16_666_667L  // 1/60s
        viewModel.setShutterSpeed(ns)
        verify { cameraEngine.setShutterSpeed(ns) }
    }

    @Test
    fun `setFrameRate updates uiState and delegates to camera`() {
        viewModel.setFrameRate(60)

        assertThat(viewModel.uiState.value.selectedFps).isEqualTo(60)
        verify { cameraEngine.setFrameRate(60) }
    }

    @Test
    fun `setBitrate is clamped to subscription maximum`() {
        every { monetization.getMaxBitrateKbps() } returns 30_000  // Free tier

        viewModel.setBitrate(150_000)  // Request 150 Mbps

        assertThat(viewModel.uiState.value.bitrateKbps).isEqualTo(30_000)
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    @Test
    fun `startRTMPBroadcast on FeatureGated sets featureGateEvent`() = testScope.runTest {
        coEvery {
            startBroadcastUseCase.startRTMP(any())
        } returns BroadcastResult.FeatureGated(Feature.RTMP)

        viewModel.startRTMPBroadcast(mockk(relaxed = true))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.featureGateEvent).isEqualTo(Feature.RTMP)
        assertThat(viewModel.uiState.value.isBroadcasting).isFalse()
    }

    @Test
    fun `startRTMPBroadcast on Success sets isBroadcasting true`() = testScope.runTest {
        coEvery {
            startBroadcastUseCase.startRTMP(any())
        } returns BroadcastResult.Success("RTMP")

        viewModel.startRTMPBroadcast(mockk(relaxed = true))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isBroadcasting).isTrue()
        assertThat(viewModel.uiState.value.broadcastProtocol).isEqualTo("RTMP")
    }

    @Test
    fun `startRTMPBroadcast on Failure sets broadcastError`() = testScope.runTest {
        coEvery {
            startBroadcastUseCase.startRTMP(any())
        } returns BroadcastResult.Failure("Connection refused")

        viewModel.startRTMPBroadcast(mockk(relaxed = true))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.broadcastError).contains("Connection refused")
        assertThat(viewModel.uiState.value.isBroadcasting).isFalse()
    }

    @Test
    fun `stopAllBroadcasts resets isBroadcasting`() = testScope.runTest {
        coEvery {
            startBroadcastUseCase.startRTMP(any())
        } returns BroadcastResult.Success("RTMP")
        every { stopBroadcastUseCase.invoke() } just runs

        viewModel.startRTMPBroadcast(mockk(relaxed = true))
        advanceUntilIdle()
        viewModel.stopAllBroadcasts()

        assertThat(viewModel.uiState.value.isBroadcasting).isFalse()
        assertThat(viewModel.uiState.value.broadcastProtocol).isEmpty()
    }

    // ── Dropped frames ────────────────────────────────────────────────────────

    @Test
    fun `dropped frames over 5 logs warning to telemetry`() = testScope.runTest {
        encodingMetricFlow.value = EncodingMetrics(droppedFrames = 6)
        advanceUntilIdle()

        verify { telemetry.logWarning("dropped_frames", any()) }
    }

    @Test
    fun `dropped frames at 5 or below does not trigger warning`() = testScope.runTest {
        encodingMetricFlow.value = EncodingMetrics(droppedFrames = 5)
        advanceUntilIdle()

        verify(exactly = 0) { telemetry.logWarning("dropped_frames", any()) }
    }
}
