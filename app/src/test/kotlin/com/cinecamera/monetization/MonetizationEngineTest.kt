package com.cinecamera.monetization

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class MonetizationEngineTest {

    private val context      = mockk<Context>(relaxed = true)
    private val prefs        = mockk<SharedPreferences>(relaxed = true)
    private val prefsEditor  = mockk<SharedPreferences.Editor>(relaxed = true)

    private lateinit var engine: MonetizationEngine

    @Before
    fun setUp() {
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() }                               returns prefsEditor
        every { prefsEditor.putString(any(), any()) }        returns prefsEditor
        every { prefsEditor.putLong(any(), any()) }          returns prefsEditor
        every { prefsEditor.apply() }                        just runs
        every { prefs.getString("cached_tier", "FREE") }    returns "FREE"
        every { prefs.getLong("cached_at", 0L) }            returns System.currentTimeMillis()

        engine = MonetizationEngine(context)
    }

    // ── Free tier feature gates ─────────────────────────────────────────────────

    @Test fun `free tier grants manual controls`()       = assertThat(engine.hasFeature(Feature.MANUAL_BASIC)).isTrue()
    @Test fun `free tier denies log profile`()           = assertThat(engine.hasFeature(Feature.LOG_PROFILE)).isFalse()
    @Test fun `free tier denies LUT engine`()            = assertThat(engine.hasFeature(Feature.LUT_ENGINE)).isFalse()
    @Test fun `free tier denies SRT streaming`()         = assertThat(engine.hasFeature(Feature.SRT)).isFalse()
    @Test fun `free tier denies RTMP streaming`()        = assertThat(engine.hasFeature(Feature.RTMP)).isFalse()
    @Test fun `free tier denies multi-stream`()          = assertThat(engine.hasFeature(Feature.MULTI_STREAM)).isFalse()

    @Test
    fun `free tier maximum bitrate is 30 Mbps`() {
        assertThat(engine.getMaxBitrateKbps()).isEqualTo(30_000)
    }

    @Test
    fun `free tier maximum presets is 3`() {
        assertThat(engine.getMaxPresets()).isEqualTo(3)
    }

    // ── Pro tier feature gates ─────────────────────────────────────────────────

    @Test
    fun `pro tier grants log profile and RTMP`() {
        every { prefs.getString("cached_tier", "FREE") } returns "PRO"
        val proEngine = MonetizationEngine(context)

        assertThat(proEngine.hasFeature(Feature.LOG_PROFILE)).isTrue()
        assertThat(proEngine.hasFeature(Feature.LUT_ENGINE)).isTrue()
        assertThat(proEngine.hasFeature(Feature.RTMP)).isTrue()
    }

    @Test
    fun `pro tier denies SRT and multi-stream`() {
        every { prefs.getString("cached_tier", "FREE") } returns "PRO"
        val proEngine = MonetizationEngine(context)

        assertThat(proEngine.hasFeature(Feature.SRT)).isFalse()
        assertThat(proEngine.hasFeature(Feature.MULTI_STREAM)).isFalse()
    }

    @Test
    fun `pro tier maximum bitrate is 150 Mbps`() {
        every { prefs.getString("cached_tier", "FREE") } returns "PRO"
        val proEngine = MonetizationEngine(context)

        assertThat(proEngine.getMaxBitrateKbps()).isEqualTo(150_000)
    }

    // ── Enterprise tier feature gates ──────────────────────────────────────────

    @Test
    fun `enterprise tier grants all features`() {
        every { prefs.getString("cached_tier", "FREE") } returns "ENTERPRISE"
        val entEngine = MonetizationEngine(context)

        Feature.values().forEach { feature ->
            assertThat(entEngine.hasFeature(feature)).isTrue()
        }
    }

    @Test
    fun `enterprise tier has unlimited presets`() {
        every { prefs.getString("cached_tier", "FREE") } returns "ENTERPRISE"
        val entEngine = MonetizationEngine(context)

        assertThat(entEngine.getMaxPresets()).isGreaterThan(100)
    }

    // ── Offline cache expiry ───────────────────────────────────────────────────

    @Test
    fun `expired cache falls back to FREE tier`() {
        val sevenDaysAgo = System.currentTimeMillis() - (8 * 24 * 3600 * 1000L)
        every { prefs.getString("cached_tier", "FREE") } returns "PRO"
        every { prefs.getLong("cached_at", 0L) }         returns sevenDaysAgo

        val expiredEngine = MonetizationEngine(context)

        // After expiry, should degrade to free tier conservatively
        assertThat(expiredEngine.hasFeature(Feature.LOG_PROFILE)).isFalse()
    }
}
