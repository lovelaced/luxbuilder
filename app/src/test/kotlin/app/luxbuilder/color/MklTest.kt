package app.luxbuilder.color

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

/**
 * Unit tests for the v1.3 chroma-only [Mkl.solveChroma].
 *
 * Invariants:
 *   - When src ≡ tgt, the transform is identity (within OAS-shrinkage drift)
 *   - A pure mean-shift between src and tgt yields a transform with identity
 *     matrix and bias = (μ_t − μ_s)
 *   - When applied to source pixels (μ_s, Σ_s), the post-transform mean and
 *     covariance match (μ_t, Σ_t) within the OAS shrinkage tolerance
 */
class MklTest {

    @Test
    fun identityWhenSrcEqualsTgt() {
        val mu = floatArrayOf(0.1f, -0.05f)
        val sigma = floatArrayOf(0.01f, 0.001f,
                                  0.001f, 0.008f)
        val t = Mkl.solveChroma(mu, sigma, mu, sigma, nTarget = 50_000)
        // Expect approximately identity matrix and zero bias.
        assertCloseEnough(1f, t.matrix[0], 0.02f, "m00")
        assertCloseEnough(0f, t.matrix[1], 0.02f, "m01")
        assertCloseEnough(0f, t.matrix[2], 0.02f, "m10")
        assertCloseEnough(1f, t.matrix[3], 0.02f, "m11")
        assertCloseEnough(0f, t.bias[0], 1e-3f, "bias_a")
        assertCloseEnough(0f, t.bias[1], 1e-3f, "bias_b")
    }

    @Test
    fun pureMeanShiftIdentifiesIdentityMatrixPlusBias() {
        val srcMu = floatArrayOf(0f, 0f)
        val tgtMu = floatArrayOf(0.05f, -0.02f)
        val sigma = floatArrayOf(0.01f, 0f,
                                  0f,     0.01f)
        val t = Mkl.solveChroma(srcMu, sigma, tgtMu, sigma, nTarget = 50_000)
        // Identity matrix + bias = (μ_t − μ_s)
        assertCloseEnough(1f, t.matrix[0], 0.05f, "m00")
        assertCloseEnough(0f, t.matrix[1], 0.05f, "m01")
        assertCloseEnough(0f, t.matrix[2], 0.05f, "m10")
        assertCloseEnough(1f, t.matrix[3], 0.05f, "m11")
        assertCloseEnough(tgtMu[0] - srcMu[0], t.bias[0], 0.01f, "bias_a")
        assertCloseEnough(tgtMu[1] - srcMu[1], t.bias[1], 0.01f, "bias_b")
    }

    @Test
    fun appliedTransformMatchesTargetMean() {
        // src μ=(0,0) Σ=0.01·I → tgt μ=(0.05,-0.03) Σ=0.02·I (broader spread).
        // Apply the transform to a synthetic source mean → must equal tgt mean.
        val srcMu = floatArrayOf(0f, 0f)
        val srcS  = floatArrayOf(0.01f, 0f, 0f, 0.01f)
        val tgtMu = floatArrayOf(0.05f, -0.03f)
        val tgtS  = floatArrayOf(0.02f, 0f, 0f, 0.02f)
        val t = Mkl.solveChroma(srcMu, srcS, tgtMu, tgtS, nTarget = 50_000)
        // Apply: out = M · (μ_s − μ_s) + μ_t  =  μ_t (because the transform
        // is centered: M · (μ_s − μ_s) = 0, then + μ_t).
        // But our solveChroma stores bias = μ_t − M·μ_s, so direct apply is:
        //   out = M·μ_s + bias = μ_t  by construction.
        val outA = t.matrix[0] * srcMu[0] + t.matrix[1] * srcMu[1] + t.bias[0]
        val outB = t.matrix[2] * srcMu[0] + t.matrix[3] * srcMu[1] + t.bias[1]
        assertCloseEnough(tgtMu[0], outA, 1e-4f, "post-transform μ_a")
        assertCloseEnough(tgtMu[1], outB, 1e-4f, "post-transform μ_b")
    }

    @Test
    fun closedFormStableOnDegenerateTargetCovariance() {
        // OAS shrinkage should rescue a near-singular target Σ.
        val srcMu = floatArrayOf(0f, 0f)
        val srcS  = floatArrayOf(0.01f, 0f, 0f, 0.01f)
        val tgtMu = floatArrayOf(0.02f, 0.01f)
        // Highly elongated target: very small variance on axis 2
        val tgtS  = floatArrayOf(0.05f, 0f, 0f, 1e-6f)
        val t = Mkl.solveChroma(srcMu, srcS, tgtMu, tgtS, nTarget = 50_000)
        // All matrix entries finite — no NaN from the inverse square root.
        for (i in 0..3) assertTrue("matrix[$i] not finite: ${t.matrix[i]}", t.matrix[i].isFinite())
        assertTrue("bias[0] not finite", t.bias[0].isFinite())
        assertTrue("bias[1] not finite", t.bias[1].isFinite())
    }

    private fun assertCloseEnough(expected: Float, actual: Float, eps: Float, label: String) {
        assertTrue(
            "$label: expected $expected got $actual (|Δ|=${abs(expected - actual)} > $eps)",
            abs(expected - actual) <= eps,
        )
    }
}
