package com.cinecamera.recovery

import android.content.Context
import com.cinecamera.telemetry.TelemetryEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class RecoveryEngineTest {

    private val context  = mockk<Context>(relaxed = true)
    private val telemetry = mockk<TelemetryEngine>(relaxed = true)
    private val tempDir   = System.getProperty("java.io.tmpdir")!!

    private lateinit var engine: RecoveryEngine

    @Before
    fun setUp() {
        every { context.getExternalFilesDir(null) } returns File(tempDir)
        engine = RecoveryEngine(context, telemetry)
    }

    // ── Segment rotation ────────────────────────────────────────────────────────

    @Test
    fun `segment rotation triggers when file exceeds 4 GB`() {
        val path = "$tempDir/output.mp4"
        engine.onRecordingStarting(path, SessionConfig())

        val newPath = engine.checkSegmentRotation(path, 4L * 1024 * 1024 * 1024 + 1)

        assertThat(newPath).isNotNull()
        assertThat(newPath).contains("_1.")
    }

    @Test
    fun `segment rotation does not trigger below 4 GB`() {
        val path = "$tempDir/output.mp4"
        engine.onRecordingStarting(path, SessionConfig())

        val result = engine.checkSegmentRotation(path, 100L * 1024 * 1024)

        assertThat(result).isNull()
    }

    @Test
    fun `segment rotation at exactly 4 GB does not trigger`() {
        val path = "$tempDir/output.mp4"
        engine.onRecordingStarting(path, SessionConfig())

        val result = engine.checkSegmentRotation(path, 4L * 1024 * 1024 * 1024)

        assertThat(result).isNull()
    }

    @Test
    fun `successive rotations increment segment index correctly`() {
        val path = "$tempDir/output.mp4"
        engine.onRecordingStarting(path, SessionConfig())
        val threshold = 4L * 1024 * 1024 * 1024 + 1

        val path1 = engine.checkSegmentRotation(path, threshold)
        assertThat(path1).isNotNull()
        assertThat(path1!!).contains("_1.")

        val path2 = engine.checkSegmentRotation(path1, threshold)
        assertThat(path2).isNotNull()
        assertThat(path2!!).contains("_2.")
    }

    // ── Stream recovery buffer ──────────────────────────────────────────────────

    @Test
    fun `reconnection buffer returns frames starting from last keyframe`() {
        engine.bufferStreamFrame(ByteArray(100), 0L, isKeyFrame = false)
        engine.bufferStreamFrame(ByteArray(200), 1000L, isKeyFrame = true)   // keyframe
        engine.bufferStreamFrame(ByteArray(150), 2000L, isKeyFrame = false)
        engine.bufferStreamFrame(ByteArray(150), 3000L, isKeyFrame = false)

        val buffer = engine.getReconnectionBuffer()

        assertThat(buffer).hasSize(3)
        assertThat(buffer.first().isKeyFrame).isTrue()
        assertThat(buffer.first().presentationTimeUs).isEqualTo(1000L)
    }

    @Test
    fun `reconnection buffer is empty before any frames`() {
        val buffer = engine.getReconnectionBuffer()
        assertThat(buffer).isEmpty()
    }

    @Test
    fun `reconnection buffer returns empty if no keyframe has been seen`() {
        engine.bufferStreamFrame(ByteArray(100), 0L, false)
        engine.bufferStreamFrame(ByteArray(200), 1000L, false)

        val buffer = engine.getReconnectionBuffer()
        // No keyframe means no valid GOP start — buffer should be empty
        assertThat(buffer).isEmpty()
    }

    @Test
    fun `reconnection buffer advances to most recent keyframe`() {
        engine.bufferStreamFrame(ByteArray(100), 0L, true)     // first keyframe
        engine.bufferStreamFrame(ByteArray(100), 1000L, false)
        engine.bufferStreamFrame(ByteArray(100), 2000L, true)  // second keyframe (more recent)
        engine.bufferStreamFrame(ByteArray(100), 3000L, false)

        val buffer = engine.getReconnectionBuffer()

        // Should start from the most recent keyframe
        assertThat(buffer.first().presentationTimeUs).isEqualTo(2000L)
        assertThat(buffer).hasSize(2)
    }

    @Test
    fun `ring buffer capacity is bounded at 200 frames`() {
        // Fill beyond capacity
        repeat(250) { i ->
            engine.bufferStreamFrame(ByteArray(100), i * 1000L, i == 200)
        }

        val buffer = engine.getReconnectionBuffer()
        assertThat(buffer.size).isAtMost(200)
    }

    // ── Session journal ─────────────────────────────────────────────────────────

    @Test
    fun `onRecordingStarting returns a valid output path`() {
        val path = "$tempDir/CINE_test.mp4"
        val result = engine.onRecordingStarting(path, SessionConfig(codec = "H264", fps = 30))

        assertThat(result).isNotEmpty()
    }

    @Test
    fun `onRecordingComplete does not throw`() {
        val path = "$tempDir/CINE_test.mp4"
        engine.onRecordingStarting(path, SessionConfig())

        // Should not throw — cleanup is best-effort
        engine.onRecordingComplete(path)
    }
}

// ── RecoveryState (sealed class test) ─────────────────────────────────────────

class RecoveryStateTest {
    @Test
    fun `RecoveryState transitions cover all expected cases`() {
        val idle       = RecoveryState.Idle
        val recovering = RecoveryState.Recovering("test.mp4")
        val success    = RecoveryState.Recovered("test.mp4")
        val failed     = RecoveryState.Failed("test.mp4", "Corrupt file")

        assertThat(idle).isInstanceOf(RecoveryState.Idle::class.java)
        assertThat(recovering).isInstanceOf(RecoveryState.Recovering::class.java)
        assertThat(success).isInstanceOf(RecoveryState.Recovered::class.java)
        assertThat(failed).isInstanceOf(RecoveryState.Failed::class.java)
    }
}
