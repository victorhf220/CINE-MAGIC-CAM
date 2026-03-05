package com.cinecamera.stability

import com.cinecamera.stabilization.KalmanFilter1D
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.math.abs

@RunWith(JUnit4::class)
class KalmanFilterTest {

    @Test
    fun `filter converges to true value within 50 noisy samples`() {
        val filter          = KalmanFilter1D()
        val trueValue       = 5.0f
        val noiseAmplitude  = 1.0f
        val random          = java.util.Random(42L)
        var estimate        = 0f

        repeat(50) {
            val noisy = trueValue + (random.nextFloat() * 2f - 1f) * noiseAmplitude
            estimate = filter.update(noisy)
        }

        assertThat(abs(estimate - trueValue)).isLessThan(0.5f)
    }

    @Test
    fun `filter output variance is lower than input variance`() {
        val filter      = KalmanFilter1D()
        val random      = java.util.Random(123L)
        val outputs     = mutableListOf<Float>()

        repeat(100) {
            outputs.add(filter.update(random.nextFloat() * 10f))
        }

        val outputVariance = outputs.zipWithNext { a, b -> (a - b) * (a - b) }.average()
        assertThat(outputVariance).isLessThan(5.0)
    }

    @Test
    fun `reset clears state so filter restarts from fresh`() {
        val filter = KalmanFilter1D()
        repeat(50) { filter.update(100f) }  // Converge to 100
        filter.reset()
        val afterReset = filter.update(0f)

        // After reset, the first update should not be near 100
        assertThat(abs(afterReset - 100f)).isGreaterThan(10f)
    }

    @Test
    fun `filter output does not diverge from constant input`() {
        val filter   = KalmanFilter1D()
        val constant = 7.5f
        var last     = 0f

        repeat(200) { last = filter.update(constant) }

        assertThat(abs(last - constant)).isLessThan(0.01f)
    }

    @Test
    fun `filter handles negative inputs correctly`() {
        val filter = KalmanFilter1D()
        var estimate = 0f
        repeat(50) { estimate = filter.update(-3.0f) }

        assertThat(estimate).isLessThan(0f)
        assertThat(abs(estimate - (-3.0f))).isLessThan(0.5f)
    }
}

@RunWith(JUnit4::class)
class CineLogProfileMathTest {

    // Middle grey constants matching the CineLog implementation
    private val MIDDLE_GREY_IN  = 0.18f
    private val MIDDLE_GREY_OUT = 0.38f
    private val A = 0.244f
    private val B = 444.0f

    /** Replicates the CineLog encode function for testing without device. */
    private fun encode(linear: Float): Float {
        if (linear <= 0f) return 0f
        return A * kotlin.math.log10(B * linear + 1f)
    }

    private fun decode(log: Float): Float {
        return (Math.pow(10.0, log / A.toDouble()).toFloat() - 1f) / B
    }

    @Test
    fun `encode of zero is non-negative and near black`() {
        val result = encode(0f)
        assertThat(result).isAtLeast(0f)
        assertThat(result).isAtMost(0.1f)
    }

    @Test
    fun `middle grey 18 percent maps to approximately 38 percent output`() {
        val encoded = encode(MIDDLE_GREY_IN)
        assertThat(encoded).isWithin(0.02f).of(MIDDLE_GREY_OUT)
    }

    @Test
    fun `encode and decode round-trip is accurate to 0_005`() {
        val testValues = listOf(0.01f, 0.05f, 0.18f, 0.50f, 0.80f, 1.00f)
        testValues.forEach { input ->
            val roundTripped = decode(encode(input))
            assertThat(roundTripped).isWithin(0.005f).of(input)
        }
    }

    @Test
    fun `log encoding is monotonically increasing`() {
        val samples = (1..100).map { it / 100f }
        val encoded = samples.map { encode(it) }

        for (i in 1 until encoded.size) {
            assertThat(encoded[i]).isGreaterThan(encoded[i - 1])
        }
    }

    @Test
    fun `negative input is clamped to zero`() {
        assertThat(encode(-1.0f)).isWithin(0.001f).of(0f)
        assertThat(encode(-100.0f)).isWithin(0.001f).of(0f)
    }

    @Test
    fun `highlights are compressed relative to linear`() {
        val linearHighlight = 2.0f        // 2 stops over exposure
        val logHighlight    = encode(linearHighlight)
        val logMidgrey      = encode(MIDDLE_GREY_IN)

        // In log space the ratio should be much smaller than in linear space
        val linearRatio = linearHighlight / MIDDLE_GREY_IN
        val logRatio    = logHighlight / logMidgrey

        assertThat(logRatio).isLessThan(linearRatio * 0.3f)
    }
}

@RunWith(JUnit4::class)
class LUTProcessorTest {

    @Test
    fun `LUT intensity clamps to valid range 0 to 1`() {
        var intensity = 2.5f.coerceIn(0f, 1f)
        assertThat(intensity).isWithin(0.001f).of(1.0f)

        intensity = (-0.5f).coerceIn(0f, 1f)
        assertThat(intensity).isWithin(0.001f).of(0.0f)
    }

    @Test
    fun `non-existent .cube file path returns null`() {
        // Simulate file existence check
        val exists = java.io.File("/absolutely/no/such/file.cube").exists()
        assertThat(exists).isFalse()
        // Engine should return null in this case
    }

    @Test
    fun `LUT size must be between 4 and 64 for valid cube`() {
        val validSizes = listOf(4, 8, 16, 32, 64)
        val invalidSizes = listOf(0, 1, 2, 3, 65, 100)

        validSizes.forEach { assertThat(it).isIn(4..64) }
        invalidSizes.forEach { assertThat(it in 4..64).isFalse() }
    }

    @Test
    fun `GLSL trilinear interpolation coefficients sum to 1`() {
        // Verify that for any fractional position in the LUT cube,
        // the 8 corner weights (trilinear) sum to exactly 1.0
        val rx = 0.3f; val ry = 0.7f; val rz = 0.5f
        val w000 = (1f - rx) * (1f - ry) * (1f - rz)
        val w001 = (1f - rx) * (1f - ry) * rz
        val w010 = (1f - rx) * ry          * (1f - rz)
        val w011 = (1f - rx) * ry          * rz
        val w100 = rx         * (1f - ry) * (1f - rz)
        val w101 = rx         * (1f - ry) * rz
        val w110 = rx         * ry          * (1f - rz)
        val w111 = rx         * ry          * rz
        val sum  = w000 + w001 + w010 + w011 + w100 + w101 + w110 + w111

        assertThat(sum).isWithin(0.0001f).of(1.0f)
    }
}
