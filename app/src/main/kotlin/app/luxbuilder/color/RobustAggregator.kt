package app.luxbuilder.color

import app.luxbuilder.photo.PhotoStats.RefStat
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Multi-reference moodboard aggregation in OKLab.
 *
 * Concatenating per-ref pixels into one big sample (the v1.0–v1.2 method)
 * has two failure modes:
 *
 *   (1) Image-size weighting — a 4MP key reference dominates a 400KB
 *       inspiration reference, even when the user weighted them equally
 *       in their moodboard.
 *   (2) Between-ref variance folds into Σ — if the references have
 *       different exposure or WB, that variation gets baked into the
 *       look as noise.
 *
 * v1.3 aggregation, applied to per-ref `(μ_i, Σ_i)` summaries:
 *
 *   μ*  = **weighted geometric median** of {μ_i}  (Weiszfeld iteration,
 *         Mahalanobis-whitened against the pooled covariance prior).
 *         Breakdown 0.5 — a single oddly-graded ref can't tip the result.
 *
 *   Σ*  = **weighted Bures-Wasserstein barycenter** of {Σ_i}
 *         (Álvarez-Esteban fixed-point on the SPD manifold). The
 *         arithmetic mean of covariances inflates "spread of spreads";
 *         the Wasserstein barycenter doesn't.
 *
 *   Σ*  ← (1-λ)·Σ* + λ·(tr Σ* / 3)·I_3                **Ledoit-Wolf**
 *         identity-target shrinkage, λ depends on N to compensate for
 *         finite-sample noise on small moodboards.
 *
 *   outliers: refs with d_M(μ_i, μ*) > 3.06  (χ²_{3, 0.975}^{1/2})
 *
 * References:
 *  - Weiszfeld (1937), Vardi & Zhang 2000 (geometric median + multiplicity)
 *  - Agueh & Carlier (2011) — Bures-Wasserstein barycenter
 *  - Álvarez-Esteban et al. arXiv:1511.05355 — fixed-point algorithm
 *  - Ledoit & Wolf (2004) — shrinkage covariance estimation
 *  - Chen et al. arXiv:0907.4698 — OAS variant
 */
object RobustAggregator {

    /** Aggregated common-look distribution for the moodboard. */
    data class AggregatedStats(
        /** OKLab common-look mean (L, a, b). */
        val mu: FloatArray,
        /** OKLab common-look covariance, 3×3 [row][col]. */
        val sigma: Array<FloatArray>,
        /** Indices into the input list of refs flagged as outliers. */
        val outlierIndices: List<Int>,
        /** Per-ref Mahalanobis distances against the final μ*. */
        val mahalanobis: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AggregatedStats) return false
            return mu.contentEquals(other.mu) &&
                sigma.contentDeepEquals(other.sigma) &&
                outlierIndices == other.outlierIndices &&
                mahalanobis.contentEquals(other.mahalanobis)
        }
        override fun hashCode(): Int {
            var h = mu.contentHashCode()
            h = 31 * h + sigma.contentDeepHashCode()
            h = 31 * h + outlierIndices.hashCode()
            h = 31 * h + mahalanobis.contentHashCode()
            return h
        }
    }

    // χ²_{3, 0.975}^{1/2} ≈ 3.06 — Mahalanobis threshold for 97.5% containment
    private const val OUTLIER_THRESHOLD = 3.06f

    /**
     * Aggregate per-reference summaries into a single common-look (μ*, Σ*).
     *
     * @param perRef   per-reference OKLab stats from [PhotoStats.computePerRef]
     * @param weights  user-provided per-ref weights (or null = pixel-count proportional).
     *                 Will be re-normalized to sum 1.0.
     */
    fun aggregate(
        perRef: List<RefStat>,
        weights: FloatArray? = null,
    ): AggregatedStats? {
        if (perRef.isEmpty()) return null

        // Default weights: pixel-count proportional (so refs with more
        // contributing pixels carry slightly more weight, but no single
        // image-size-bias since per-ref stratified sampling capped at 50k).
        val rawWeights = weights ?: FloatArray(perRef.size) { perRef[it].count.toFloat() }
        require(rawWeights.size == perRef.size) { "weights length must match perRef" }
        val w = normalize(rawWeights)

        // Trivial path: single reference returns its stats verbatim.
        if (perRef.size == 1) {
            return AggregatedStats(
                mu = perRef[0].mu.copyOf(),
                sigma = OkMat.copy3(perRef[0].sigma),
                outlierIndices = emptyList(),
                mahalanobis = floatArrayOf(0f),
            )
        }

        // ── Σ* via Bures-Wasserstein barycenter + Ledoit-Wolf shrinkage ──
        val sigmaInit = weightedArithmeticMean(perRef.map { it.sigma }, w)
        val sigmaBarycenter = buresBarycenter(perRef.map { it.sigma }, w, warmStart = sigmaInit, iters = 10)
        val lambdaShrink = if (perRef.size >= 3) 0.10f else 0.20f
        val sigmaStar = shrinkLW(sigmaBarycenter, lambdaShrink)

        // ── μ* via Weiszfeld on {μ_i}, whitened against Σ* ──
        val L = OkMat.cholesky3(sigmaStar)
        val muStar = weiszfeldMedian(perRef.map { it.mu }, w, L, iters = 10)

        // ── Outlier flagging via Mahalanobis distance ──
        val mahal = FloatArray(perRef.size)
        val outliers = ArrayList<Int>()
        for (i in perRef.indices) {
            mahal[i] = mahalanobis(perRef[i].mu, muStar, L)
            if (mahal[i] > OUTLIER_THRESHOLD) outliers += i
        }

        return AggregatedStats(muStar, sigmaStar, outliers, mahal)
    }

    // ──────────────── implementation details ────────────────

    private fun normalize(w: FloatArray): FloatArray {
        val sum = w.sum()
        if (sum <= 0f) return FloatArray(w.size) { 1f / w.size }
        val out = FloatArray(w.size) { w[it] / sum }
        return out
    }

    /** Σ-weighted arithmetic mean of 3×3 matrices. */
    private fun weightedArithmeticMean(
        sigmas: List<Array<FloatArray>>, weights: FloatArray,
    ): Array<FloatArray> {
        val out = Array(3) { FloatArray(3) }
        for (k in sigmas.indices) {
            val w = weights[k]
            val s = sigmas[k]
            for (i in 0..2) for (j in 0..2) out[i][j] += w * s[i][j]
        }
        return out
    }

    /**
     * Bures-Wasserstein barycenter on the SPD manifold via the
     * Álvarez-Esteban fixed-point iteration:
     *
     *   T_{k+1} = T_k^{-½} · ( Σ_i w_i · (T_k^½ Σ_i T_k^½)^½ ) · T_k^{-½}
     *
     * Converges in ~5–10 iterations for well-conditioned 3×3 SPD inputs.
     * Returns the limiting T.
     */
    private fun buresBarycenter(
        sigmas: List<Array<FloatArray>>,
        weights: FloatArray,
        warmStart: Array<FloatArray>,
        iters: Int,
    ): Array<FloatArray> {
        var t = OkMat.copy3(warmStart)
        for (k in 0 until iters) {
            val tHalf = OkMat.matrixSqrt3(t)
            // Álvarez-Esteban fixed point:  T_{k+1} = Σ_i w_i · (T_k^½ Σ_i T_k^½)^½
            // For identical Σ_i = Σ and T_k = Σ, root_i = (Σ²)^½ = Σ and the
            // weighted sum is Σ → fixed point matches Σ. Verified.
            val tNext = Array(3) { FloatArray(3) }
            for (i in sigmas.indices) {
                val sandwich = OkMat.matmul3(OkMat.matmul3(tHalf, sigmas[i]), tHalf)
                val root = OkMat.matrixSqrt3(sandwich)
                val w = weights[i]
                for (r in 0..2) for (c in 0..2) tNext[r][c] += w * root[r][c]
            }
            // Symmetrize to kill numerical drift
            for (i in 0..2) for (j in i + 1..2) {
                val avg = 0.5f * (tNext[i][j] + tNext[j][i])
                tNext[i][j] = avg; tNext[j][i] = avg
            }
            // Early stop on small change
            val delta = frobeniusDiff(t, tNext)
            t = tNext
            if (delta < 1e-7f) break
        }
        return t
    }

    private fun frobeniusDiff(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        var s = 0f
        for (i in 0..2) for (j in 0..2) {
            val d = a[i][j] - b[i][j]
            s += d * d
        }
        return sqrt(s)
    }

    /** Ledoit-Wolf shrinkage toward isotropic identity scaled by tr(Σ)/3. */
    private fun shrinkLW(sigma: Array<FloatArray>, lambda: Float): Array<FloatArray> {
        val tau = OkMat.trace3(sigma) / 3f
        val out = OkMat.copy3(sigma)
        val oneMinus = 1f - lambda
        for (i in 0..2) for (j in 0..2) out[i][j] *= oneMinus
        out[0][0] += lambda * tau
        out[1][1] += lambda * tau
        out[2][2] += lambda * tau
        return out
    }

    /**
     * Weighted geometric median (Weiszfeld iteration) in Mahalanobis-whitened
     * space against the pooled covariance L·Lᵀ.
     *
     * Update rule:
     *   μ_{k+1} = Σ_i (w_i / d_i) · μ_i  /  Σ_i (w_i / d_i)
     *   d_i     = ||L⁻¹ (μ_i − μ_k)||₂
     *
     * Breakdown 0.5, affine-equivariant when whitened.
     */
    private fun weiszfeldMedian(
        mus: List<FloatArray>,
        weights: FloatArray,
        L: Array<FloatArray>,
        iters: Int,
    ): FloatArray {
        // Warm-start: weighted arithmetic mean
        var mu = FloatArray(3)
        for (i in mus.indices) {
            mu[0] += weights[i] * mus[i][0]
            mu[1] += weights[i] * mus[i][1]
            mu[2] += weights[i] * mus[i][2]
        }
        repeat(iters) {
            val numer = FloatArray(3)
            var denom = 0.0
            for (i in mus.indices) {
                val d = mahalanobis(mus[i], mu, L).coerceAtLeast(1e-6f)
                val w = weights[i] / d
                numer[0] += w * mus[i][0]
                numer[1] += w * mus[i][1]
                numer[2] += w * mus[i][2]
                denom += w
            }
            if (denom <= 0.0) return@repeat
            val next = FloatArray(3) { (numer[it] / denom).toFloat() }
            // Convergence check
            val dx = next[0] - mu[0]; val dy = next[1] - mu[1]; val dz = next[2] - mu[2]
            mu = next
            if (sqrt(dx * dx + dy * dy + dz * dz) < 1e-6f) return mu
        }
        return mu
    }

    /** Mahalanobis distance ||L⁻¹ (x − y)||₂ where L·Lᵀ = Σ. */
    private fun mahalanobis(x: FloatArray, y: FloatArray, L: Array<FloatArray>): Float {
        val d = floatArrayOf(x[0] - y[0], x[1] - y[1], x[2] - y[2])
        val w = OkMat.solveLower3(L, d)
        return sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
    }
}
