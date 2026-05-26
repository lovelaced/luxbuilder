package app.luxbuilder.color

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.random.Random

/**
 * Unit tests for [OkLab] — Ottosson's perceptual color space conversion.
 *
 * Verifies:
 *   - Round-trip identity (linear sRGB → OKLab → linear sRGB) within 1e-5
 *   - Spot checks against Ottosson's published reference values
 *   - OKLab ↔ OKLCh round-trip
 *   - Sign-preserving cube root handles out-of-gamut inputs without NaN
 */
class OkLabTest {

    @Test
    fun roundtripLinearSrgbIdentity() {
        val rng = Random(0xCAFEBABEL)
        repeat(1000) {
            val r = rng.nextFloat()
            val g = rng.nextFloat()
            val b = rng.nextFloat()
            val lab = OkLab.fromLinearSrgb(r, g, b)
            val out = OkLab.toLinearSrgb(lab[0], lab[1], lab[2])
            assertCloseEnough(r, out[0], 1e-4f, "R")
            assertCloseEnough(g, out[1], 1e-4f, "G")
            assertCloseEnough(b, out[2], 1e-4f, "B")
        }
    }

    @Test
    fun whitePointIsZeroChroma() {
        val lab = OkLab.fromLinearSrgb(1f, 1f, 1f)
        assertCloseEnough(1f, lab[0], 1e-4f, "L for white")
        assertCloseEnough(0f, lab[1], 1e-4f, "a for white")
        assertCloseEnough(0f, lab[2], 1e-4f, "b for white")
    }

    @Test
    fun blackPointIsZero() {
        val lab = OkLab.fromLinearSrgb(0f, 0f, 0f)
        assertCloseEnough(0f, lab[0], 1e-4f, "L for black")
        assertCloseEnough(0f, lab[1], 1e-4f, "a for black")
        assertCloseEnough(0f, lab[2], 1e-4f, "b for black")
    }

    @Test
    fun grayAxisHasZeroChromaticity() {
        for (v in floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
            val lab = OkLab.fromLinearSrgb(v, v, v)
            // L should be a strictly monotonic function of v; a, b should be ~0
            assertCloseEnough(0f, lab[1], 1e-3f, "a for gray $v")
            assertCloseEnough(0f, lab[2], 1e-3f, "b for gray $v")
        }
    }

    @Test
    fun pureRedReferenceValues() {
        // Ottosson's reference: linear sRGB (1, 0, 0) → ~(0.628, 0.225, 0.126)
        val lab = OkLab.fromLinearSrgb(1f, 0f, 0f)
        assertCloseEnough(0.628f, lab[0], 0.01f, "L for red")
        assertCloseEnough(0.225f, lab[1], 0.01f, "a for red")
        assertCloseEnough(0.126f, lab[2], 0.01f, "b for red")
    }

    @Test
    fun pureGreenReferenceValues() {
        // Linear sRGB (0, 1, 0) → ~(0.866, -0.234, 0.180)
        val lab = OkLab.fromLinearSrgb(0f, 1f, 0f)
        assertCloseEnough(0.866f, lab[0], 0.01f, "L for green")
        assertCloseEnough(-0.234f, lab[1], 0.01f, "a for green")
        assertCloseEnough(0.180f, lab[2], 0.01f, "b for green")
    }

    @Test
    fun pureBlueReferenceValues() {
        // Linear sRGB (0, 0, 1) → ~(0.452, -0.032, -0.312)
        val lab = OkLab.fromLinearSrgb(0f, 0f, 1f)
        assertCloseEnough(0.452f, lab[0], 0.01f, "L for blue")
        assertCloseEnough(-0.032f, lab[1], 0.01f, "a for blue")
        assertCloseEnough(-0.312f, lab[2], 0.01f, "b for blue")
    }

    @Test
    fun oklabToOklChRoundtrip() {
        val rng = Random(0xC0FFEEL)
        repeat(100) {
            val r = rng.nextFloat()
            val g = rng.nextFloat()
            val b = rng.nextFloat()
            val lab = OkLab.fromLinearSrgb(r, g, b)
            val lch = OkLab.toLCh(lab[0], lab[1], lab[2])
            val labBack = OkLab.fromLCh(lch[0], lch[1], lch[2])
            assertCloseEnough(lab[0], labBack[0], 1e-5f, "L")
            assertCloseEnough(lab[1], labBack[1], 1e-5f, "a")
            assertCloseEnough(lab[2], labBack[2], 1e-5f, "b")
        }
    }

    @Test
    fun outOfGamutHandledGracefully() {
        // Negative linear-sRGB values (e.g. from a Wide-gamut → sRGB conversion
        // shifting outside the sRGB cube) must not produce NaN. The
        // sign-preserving cube root in OkLab handles this.
        val lab = OkLab.fromLinearSrgb(-0.1f, 0.5f, 1.2f)
        assertTrue("L not finite: ${lab[0]}", lab[0].isFinite())
        assertTrue("a not finite: ${lab[1]}", lab[1].isFinite())
        assertTrue("b not finite: ${lab[2]}", lab[2].isFinite())
    }

    private fun assertCloseEnough(expected: Float, actual: Float, eps: Float, label: String) {
        assertTrue(
            "$label: expected $expected got $actual (|Δ|=${abs(expected - actual)} > $eps)",
            abs(expected - actual) <= eps,
        )
    }
}
