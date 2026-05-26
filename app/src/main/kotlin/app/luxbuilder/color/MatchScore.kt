package app.luxbuilder.color

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Quality metric surfaced as the "MATCH N" badge in the EditScreen header.
 *
 * Computes the **2-Wasserstein (Bures) distance** between the source
 * distribution-after-chroma-MKL and the reference's target distribution,
 * both in OKLab. Bures-Wasserstein for Gaussians has closed form:
 *
 *   W₂² = ‖μ_1 − μ_2‖² + Tr(Σ_1 + Σ_2 − 2·(Σ_1^½ Σ_2 Σ_1^½)^½)
 *
 * No pixel-level rendering needed — we already have all the Gaussians from
 * [RobustAggregator] and [Mkl.solveChroma]. Score computes in microseconds.
 *
 * Composite formula:
 *   score = 100 · exp(−½ · W₂ / τ_W)
 *
 * τ_W = 0.05 calibrated so that "near-perfect match" → ≥ 95 and
 * "significantly different look" → < 60. Empirical only; v1.3 nightly CI
 * will recalibrate against FiveK Expert-C self-match (Phase N).
 *
 * Reference: Bhatia, Jain, Lim "On the Bures-Wasserstein distance between
 * positive definite matrices" (2018).
 */
object MatchScore {

    private const val TAU_W = 0.05f

    /**
     * Compute the match score given:
     * @param srcMu       full 3-D source mean in OKLab (e.g. natural prior)
     * @param srcSigma    full 3×3 source covariance in OKLab
     * @param chromaT     fitted chroma MKL transform (2×2 + 2-bias)
     * @param srcAbMean   source (a, b) mean used by the chroma solver
     * @param tgtMu       aggregated target mean (3-vector)
     * @param tgtSigma    aggregated target covariance (3×3)
     *
     * @return score in [0, 100]
     */
    fun compute(
        srcMu: FloatArray,
        srcSigma: Array<FloatArray>,
        chromaT: Mkl.Transform2D,
        srcAbMean: FloatArray,
        tgtMu: FloatArray,
        tgtSigma: Array<FloatArray>,
    ): Float {
        // Step 1: derive post-chroma source distribution.
        // Mean: L unchanged; (a, b) → T·(srcMu_ab − srcAbMean) + bias + ?
        // Actually for the source mean specifically, (a, b) shifts to bias
        // (since the transform is centered at srcAbMean by construction).
        val muPostA = chromaT.matrix[0] * (srcMu[1] - srcAbMean[0]) +
                      chromaT.matrix[1] * (srcMu[2] - srcAbMean[1]) +
                      chromaT.bias[0]
        val muPostB = chromaT.matrix[2] * (srcMu[1] - srcAbMean[0]) +
                      chromaT.matrix[3] * (srcMu[2] - srcAbMean[1]) +
                      chromaT.bias[1]
        val muPost = floatArrayOf(srcMu[0], muPostA, muPostB)

        // Covariance transform: Σ_post = M · Σ · M^T applied to the (a, b)
        // 2×2 block; L row/column unchanged in linear approximation.
        val sigmaPost = buildPostChromaCovariance(srcSigma, chromaT.matrix)

        // Step 2: Bures-Wasserstein on 3-D Gaussians
        val w2 = buresWasserstein(muPost, sigmaPost, tgtMu, tgtSigma)

        // Step 3: composite score
        return (100f * exp(-0.5f * w2 / TAU_W)).coerceIn(0f, 100f)
    }

    /**
     * Build post-chroma source covariance. The chroma transform acts on (a, b)
     * as a 2×2 linear map; L is untouched. So the post-transform Σ has:
     *   Σ[0][0] unchanged (L variance)
     *   Σ[0][1] = M · Σ[0][1..2]   (cross-L-a, cross-L-b)
     *   Σ[1..2][1..2] = M · Σ_{ab} · M^T
     */
    private fun buildPostChromaCovariance(
        srcSigma: Array<FloatArray>,
        chromaMat: FloatArray,
    ): Array<FloatArray> {
        val out = OkMat.copy3(srcSigma)
        // M = [[m00, m01], [m10, m11]]
        val m00 = chromaMat[0]; val m01 = chromaMat[1]
        val m10 = chromaMat[2]; val m11 = chromaMat[3]
        // ab covariance: out[1..2][1..2] ← M · srcSigma[1..2][1..2] · M^T
        val s11 = srcSigma[1][1]; val s12 = srcSigma[1][2]
        val s22 = srcSigma[2][2]
        // M · S = [[m00·s11+m01·s12, m00·s12+m01·s22], [m10·s11+m11·s12, m10·s12+m11·s22]]
        val ms11 = m00 * s11 + m01 * s12
        val ms12 = m00 * s12 + m01 * s22
        val ms21 = m10 * s11 + m11 * s12
        val ms22 = m10 * s12 + m11 * s22
        // (M·S) · M^T
        out[1][1] = ms11 * m00 + ms12 * m01
        out[1][2] = ms11 * m10 + ms12 * m11
        out[2][1] = out[1][2]
        out[2][2] = ms21 * m10 + ms22 * m11
        // Cross-L-ab: out[0][1..2] ← Σ[0][1..2] · M^T
        val l1 = srcSigma[0][1]; val l2 = srcSigma[0][2]
        out[0][1] = l1 * m00 + l2 * m01
        out[0][2] = l1 * m10 + l2 * m11
        out[1][0] = out[0][1]
        out[2][0] = out[0][2]
        return out
    }

    /**
     * Closed-form Bures-Wasserstein distance between two 3-D Gaussians.
     *
     * W₂(N(μ_1, Σ_1), N(μ_2, Σ_2))² =
     *   ‖μ_1 − μ_2‖² + Tr(Σ_1 + Σ_2 − 2·(Σ_1^½ Σ_2 Σ_1^½)^½)
     *
     * Returns sqrt(W₂²) which is the actual distance, not squared.
     */
    private fun buresWasserstein(
        mu1: FloatArray, sigma1: Array<FloatArray>,
        mu2: FloatArray, sigma2: Array<FloatArray>,
    ): Float {
        // ‖μ_1 − μ_2‖²
        val dM = floatArrayOf(mu1[0] - mu2[0], mu1[1] - mu2[1], mu1[2] - mu2[2])
        val muTerm = dM[0] * dM[0] + dM[1] * dM[1] + dM[2] * dM[2]

        // (Σ_1^½ Σ_2 Σ_1^½)^½
        val s1Half = OkMat.matrixSqrt3(sigma1)
        val inner = OkMat.matmul3(OkMat.matmul3(s1Half, sigma2), s1Half)
        val innerHalf = OkMat.matrixSqrt3(inner)
        // Tr(Σ_1 + Σ_2 − 2·innerHalf)
        val tr = OkMat.trace3(sigma1) + OkMat.trace3(sigma2) - 2f * OkMat.trace3(innerHalf)
        val w2Sq = muTerm + max(tr, 0f)
        return sqrt(max(w2Sq, 0f))
    }
}
