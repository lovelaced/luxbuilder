package app.luxbuilder.color

import app.luxbuilder.state.LuxState
import org.junit.Test
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.max

/**
 * Unit tests for [LutBaker] — identity invariance is the load-bearing property
 * (any drift here breaks the `.cube`/`.vlt` byte-equality CI gate).
 *
 * Invariants:
 *   - Neutral [LuxState] produces an identity LUT at 17³ and 33³ (within fp
 *     tolerance for the OKLab round-trip + RGC numerical noise)
 *   - bakeSupersampled produces a LUT byte-similar to direct bake at identity
 *     (the supersample + decimate path should converge to direct bake for a
 *     pipeline that's the identity transform)
 */
class LutBakerTest {

    @Test
    fun directBakeIdentityClose() {
        val lut = LutBaker.bake(LuxState(), 33)
        assertLutCloseToIdentity(lut, 33, eps = 5e-3f, label = "33³ direct bake")
    }

    @Test
    fun directBake17IdentityClose() {
        val lut = LutBaker.bake(LuxState(), 17)
        assertLutCloseToIdentity(lut, 17, eps = 5e-3f, label = "17³ direct bake")
    }

    @Test
    fun supersampleBake33GrayAxisIsIdentity() {
        // The gray axis (r = g = b) has per-channel distance from max = 0
        // for all channels, so ACES 1.3 RGC is a strict no-op. The bake on
        // the gray axis should be identity within float drift.
        val lut = LutBaker.bakeSupersampled(LuxState(), 33)
        assertGrayAxisCloseToIdentity(lut, 33, eps = 5e-3f, label = "33³ gray axis")
    }

    @Test
    fun supersampleBake17GrayAxisIsIdentity() {
        val lut = LutBaker.bakeSupersampled(LuxState(), 17)
        assertGrayAxisCloseToIdentity(lut, 17, eps = 5e-3f, label = "17³ gray axis")
    }

    @Test
    fun supersampleBakeCompressesSaturatedCornersInBounds() {
        // RGC is supposed to pull (1, 0, 0) inward. Verify it doesn't blow
        // up: all values stay in [0, 1] and the compression is reasonable
        // (≤ 0.30 sRGB-encoded drift at the worst-case corners; this number
        // depends on the OETF steepness near 0, not on RGC parameters).
        val lut = LutBaker.bakeSupersampled(LuxState(), 33)
        val pureRed = lut.at(32, 0, 0)
        assertTrue("R stays ≈ 1 for pure red, got ${pureRed[0]}",
            abs(pureRed[0] - 1f) < 0.05f)
        assertTrue("G slightly lifted by RGC for pure red, got ${pureRed[1]}",
            pureRed[1] in 0f..0.30f)
        assertTrue("B slightly lifted by RGC for pure red, got ${pureRed[2]}",
            pureRed[2] in 0f..0.30f)
        // The compressed pure-red is also a valid sRGB color: all in [0, 1]
        for (b in 0..32) for (g in 0..32) for (r in 0..32) {
            val cell = lut.at(r, g, b)
            for (ch in 0..2) {
                assertTrue("$r,$g,$b channel $ch out of [0,1]: ${cell[ch]}",
                    cell[ch] in 0f..1f)
            }
        }
    }

    private fun assertGrayAxisCloseToIdentity(
        lut: LutBaker.Lut, size: Int, eps: Float, label: String,
    ) {
        val denom = (size - 1).toFloat()
        for (i in 0 until size) {
            // Gray axis: r = g = b. RGC should never touch these (d_c = 0 for all).
            val cell = lut.at(i, i, i)
            val expected = i / denom
            assertCloseEnough(expected, cell[0], eps, "$label R at i=$i")
            assertCloseEnough(expected, cell[1], eps, "$label G at i=$i")
            assertCloseEnough(expected, cell[2], eps, "$label B at i=$i")
        }
    }

    @Test
    fun identityLutFactoryIsExactIdentity() {
        // Sanity check the LutBaker.Lut.identity factory produces literal
        // identity values — this is the byte-comparison reference for the
        // CI regression gate.
        val lut = LutBaker.Lut.identity(33)
        val denom = 32f
        for (b in 0..32) for (g in 0..32) for (r in 0..32) {
            val cell = lut.at(r, g, b)
            assertCloseEnough(r / denom, cell[0], 1e-6f, "r at ($r,$g,$b)")
            assertCloseEnough(g / denom, cell[1], 1e-6f, "g at ($r,$g,$b)")
            assertCloseEnough(b / denom, cell[2], 1e-6f, "b at ($r,$g,$b)")
        }
    }

    private fun assertLutCloseToIdentity(
        lut: LutBaker.Lut, size: Int, eps: Float, label: String,
    ) {
        val denom = (size - 1).toFloat()
        var maxErr = 0f
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val cell = lut.at(r, g, b)
            val er = abs(cell[0] - r / denom)
            val eg = abs(cell[1] - g / denom)
            val eb = abs(cell[2] - b / denom)
            maxErr = max(maxErr, max(er, max(eg, eb)))
        }
        assertTrue(
            "$label: max identity error $maxErr exceeds $eps",
            maxErr <= eps,
        )
    }

    private fun assertCloseEnough(expected: Float, actual: Float, eps: Float, label: String) {
        assertTrue(
            "$label: expected $expected got $actual (|Δ|=${abs(expected - actual)} > $eps)",
            abs(expected - actual) <= eps,
        )
    }
}
