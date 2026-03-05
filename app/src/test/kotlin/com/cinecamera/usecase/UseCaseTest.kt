package com.cinecamera.usecase

import android.content.Context
import com.cinecamera.audio.IAudioEngine
import com.cinecamera.encoding.EncoderConfig
import com.cinecamera.encoding.EncodingMetrics
import com.cinecamera.encoding.IEncodingEngine
import com.cinecamera.encoding.VideoCodec
import com.cinecamera.monetization.Feature
import com.cinecamera.monetization.MonetizationEngine
import com.cinecamera.recovery.RecoveryEngine
import com.cinecamera.streaming.BroadcastResult
import com.cinecamera.streaming.RTMPConfig
import com.cinecamera.streaming.RTMPStreamingEngine
import com.cinecamera.streaming.SRTStreamingController
import com.cinecamera.streaming.StreamConfig
import com.cinecamera.telemetry.TelemetryEngine
import com.cinecamera.ui.camera.CameraUIState
import com.cinecamera.ui.camera.VideoResolution
import com.cinecamera.ui.camera.usecase.*
import com.cinecamera.utils.MediaStoreHelper
import com.cinecamera.utils.VideoFileDescriptor
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
class StartRecordingUseCaseTest {

    private val context        = mockk<Context>(relaxed = true)
    private val encodingEngine = mockk<IEncodingEngine>(relaxed = true)
    private val audioEngine    = mockk<IAudioEngine>(relaxed = true)
    private val recoveryEngine = mockk<RecoveryEngine>(relaxed = true)
    private val telemetry      = mockk<TelemetryEngine>(relaxed = true)
    private val monetization   = mockk<MonetizationEngine>(relaxed = true)

    private val mockDescriptor = VideoFileDescriptor(
        uri         = null,
        file        = java.io.File("/tmp/CINE_test.mp4"),
        displayName = "CINE_test.mp4"
    )

    private lateinit var useCase: StartRecordingUseCase

    @Before
    fun setUp() {
        mockkObject(MediaStoreHelper)
        every { MediaStoreHelper.createVideoFile(any(), any()) } returns mockDescriptor
        every { monetization.getMaxBitrateKbps() } returns 150_000
        every { recoveryEngine.onRecordingStarting(any(), any()) } returns "/tmp/CINE_test.mp4"

        useCase = StartRecordingUseCase(
            context        = context,
            encodingEngine = encodingEngine,
            audioEngine    = audioEngine,
            recoveryEngine = recoveryEngine,
            telemetry      = telemetry,
            monetization   = monetization
        )
    }

    @After
    fun tearDown() {
        unmockkObject(MediaStoreHelper)
    }

    @Test
    fun `invoke creates a MediaStore file before configuring encoder`() = runTest {
        val state = CameraUIState(selectedCodec = VideoCodec.H264, bitrateKbps = 50_000)

        val session = useCase(state)

        verify { MediaStoreHelper.createVideoFile(context, any()) }
        assertThat(session.fileDescriptor).isEqualTo(mockDescriptor)
    }

    @Test
    fun `invoke writes recovery journal before starting encoder`() = runTest {
        val state = CameraUIState()
        val captureOrder = mutableListOf<String>()

        every { recoveryEngine.onRecordingStarting(any(), any()) } answers {
            captureOrder.add("journal")
            "/tmp/test.mp4"
        }
        every { encodingEngine.configure(any()) } answers { captureOrder.add("configure") }
        every { encodingEngine.startRecording(any()) } answers { captureOrder.add("start") }

        useCase(state)

        assertThat(captureOrder.indexOf("journal"))
            .isLessThan(captureOrder.indexOf("configure"))
        assertThat(captureOrder.indexOf("configure"))
            .isLessThan(captureOrder.indexOf("start"))
    }

    @Test
    fun `invoke starts audio capture after encoder`() = runTest {
        val captureOrder = mutableListOf<String>()
        every { encodingEngine.startRecording(any()) } answers { captureOrder.add("encoder") }
        every { audioEngine.startCapture() } answers { captureOrder.add("audio") }

        useCase(CameraUIState())

        assertThat(captureOrder.indexOf("encoder"))
            .isLessThan(captureOrder.indexOf("audio"))
    }

    @Test
    fun `invoke notifies telemetry with codec and resolution info`() = runTest {
        val state = CameraUIState(
            selectedCodec = VideoCodec.H265,
            resolution    = VideoResolution.UHD_4K,
            selectedFps   = 24,
            bitrateKbps   = 150_000
        )

        useCase(state)

        verify {
            telemetry.onRecordingStart(withArg { props ->
                assertThat(props["codec"]).isEqualTo("H265")
                assertThat(props["fps"]).isEqualTo(24)
            })
        }
    }

    @Test
    fun `invoke clamps bitrate to subscription maximum`() = runTest {
        every { monetization.getMaxBitrateKbps() } returns 30_000  // Free tier
        val capturedConfig = slot<EncoderConfig>()
        every { encodingEngine.configure(capture(capturedConfig)) } just runs

        useCase(CameraUIState(bitrateKbps = 150_000))

        assertThat(capturedConfig.captured.bitrateKbps).isEqualTo(30_000)
    }

    @Test
    fun `invoke returns session with correct file descriptor`() = runTest {
        val session = useCase(CameraUIState())

        assertThat(session.fileDescriptor).isEqualTo(mockDescriptor)
        assertThat(session.sessionPath).isNotEmpty()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class StopRecordingUseCaseTest {

    private val context        = mockk<Context>(relaxed = true)
    private val encodingEngine = mockk<IEncodingEngine>(relaxed = true)
    private val audioEngine    = mockk<IAudioEngine>(relaxed = true)
    private val recoveryEngine = mockk<RecoveryEngine>(relaxed = true)
    private val telemetry      = mockk<TelemetryEngine>(relaxed = true)

    private val mockSession = RecordingSession(
        fileDescriptor = VideoFileDescriptor(null, java.io.File("/tmp/test.mp4"), "test.mp4"),
        encoderConfig  = mockk(relaxed = true),
        sessionPath    = "/tmp/test.mp4"
    )

    private lateinit var useCase: StopRecordingUseCase

    @Before
    fun setUp() {
        mockkObject(MediaStoreHelper)
        every { MediaStoreHelper.markFileReady(any(), any()) } just runs
        every { encodingEngine.encodingMetrics } returns MutableStateFlow(EncodingMetrics())

        useCase = StopRecordingUseCase(context, encodingEngine, audioEngine, recoveryEngine, telemetry)
    }

    @After
    fun tearDown() { unmockkObject(MediaStoreHelper) }

    @Test
    fun `invoke stops encoder before stopping audio`() = runTest {
        val order = mutableListOf<String>()
        every { encodingEngine.stopRecording() } answers { order.add("encoder") }
        every { audioEngine.stopCapture()      } answers { order.add("audio")   }

        useCase(mockSession)

        assertThat(order.first()).isEqualTo("encoder")
    }

    @Test
    fun `invoke marks MediaStore file ready after stopping encoder`() = runTest {
        val order = mutableListOf<String>()
        every { encodingEngine.stopRecording() } answers { order.add("stop") }
        every { MediaStoreHelper.markFileReady(any(), any()) } answers { order.add("mark") }

        useCase(mockSession)

        assertThat(order.indexOf("stop")).isLessThan(order.indexOf("mark"))
    }

    @Test
    fun `invoke cleans up recovery journal after success`() = runTest {
        useCase(mockSession)
        verify { recoveryEngine.onRecordingComplete(mockSession.sessionPath) }
    }

    @Test
    fun `invoke calls telemetry with final metrics`() = runTest {
        every { encodingEngine.encodingMetrics } returns MutableStateFlow(
            EncodingMetrics(droppedFrames = 3, totalFrames = 1800, fileSizeBytes = 50_000_000L)
        )

        useCase(mockSession)

        verify {
            telemetry.onRecordingEnd(withArg { props ->
                assertThat(props["dropped_frames"]).isEqualTo(3)
                assertThat(props["total_frames"]).isEqualTo(1800)
            })
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class StartBroadcastUseCaseTest {

    private val srtController  = mockk<SRTStreamingController>(relaxed = true)
    private val rtmpEngine     = mockk<RTMPStreamingEngine>(relaxed = true)
    private val encodingEngine = mockk<IEncodingEngine>(relaxed = true)
    private val recoveryEngine = mockk<RecoveryEngine>(relaxed = true)
    private val monetization   = mockk<MonetizationEngine>(relaxed = true)
    private val telemetry      = mockk<TelemetryEngine>(relaxed = true)

    private lateinit var useCase: StartBroadcastUseCase

    @Before
    fun setUp() {
        useCase = StartBroadcastUseCase(
            srtController  = srtController,
            rtmpEngine     = rtmpEngine,
            encodingEngine = encodingEngine,
            recoveryEngine = recoveryEngine,
            monetization   = monetization,
            telemetry      = telemetry
        )
    }

    @Test
    fun `startRTMP without feature returns FeatureGated`() = runTest {
        every { monetization.hasFeature(Feature.RTMP) } returns false

        val result = useCase.startRTMP(RTMPConfig())

        assertThat(result).isInstanceOf(BroadcastResult.FeatureGated::class.java)
        val gated = result as BroadcastResult.FeatureGated
        assertThat(gated.requiredFeature).isEqualTo(Feature.RTMP)
        coVerify(exactly = 0) { rtmpEngine.connect(any()) }
    }

    @Test
    fun `startRTMP with feature connects engine and returns Success`() = runTest {
        every { monetization.hasFeature(Feature.RTMP) } returns true
        coEvery { rtmpEngine.connect(any()) } just runs

        val result = useCase.startRTMP(RTMPConfig(host = "rtmp.youtube.com"))

        assertThat(result).isInstanceOf(BroadcastResult.Success::class.java)
        assertThat((result as BroadcastResult.Success).protocol).isEqualTo("RTMP")
        coVerify { rtmpEngine.connect(any()) }
    }

    @Test
    fun `startRTMP connection exception returns Failure`() = runTest {
        every { monetization.hasFeature(Feature.RTMP) } returns true
        coEvery { rtmpEngine.connect(any()) } throws RuntimeException("Network unreachable")

        val result = useCase.startRTMP(RTMPConfig())

        assertThat(result).isInstanceOf(BroadcastResult.Failure::class.java)
        assertThat((result as BroadcastResult.Failure).reason).contains("Network unreachable")
    }

    @Test
    fun `startRTMP registers NAL callback on success`() = runTest {
        every { monetization.hasFeature(Feature.RTMP) } returns true
        coEvery { rtmpEngine.connect(any()) } just runs

        useCase.startRTMP(RTMPConfig())

        verify { encodingEngine.addNALCallback(any()) }
    }

    @Test
    fun `startSRT without feature returns FeatureGated`() = runTest {
        every { monetization.hasFeature(Feature.SRT) } returns false

        val result = useCase.startSRT(StreamConfig())

        assertThat(result).isInstanceOf(BroadcastResult.FeatureGated::class.java)
    }

    @Test
    fun `startSRT with feature connects and returns Success`() = runTest {
        every { monetization.hasFeature(Feature.SRT) } returns true
        coEvery { srtController.connect(any()) } just runs

        val result = useCase.startSRT(StreamConfig(host = "srt.server.com"))

        assertThat(result).isInstanceOf(BroadcastResult.Success::class.java)
    }
}
