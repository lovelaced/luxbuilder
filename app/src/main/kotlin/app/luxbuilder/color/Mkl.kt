package app.luxbuilder.color

import kotlin.math.max

/**
 * Monge-Kantorovich Linearization (Pitié & Kokaram 2007) — closed-form
 * affine color-transfer that aligns first two moments of two distributions.
 *
 * Given source (μ_s, Σ_s) and target (μ_t, Σ_t), the unique linear map T
 * such that T(X_s) has the same mean and covariance as X_t is:
 *
 *     T   = Σ_t^{½} (Σ_t^{½} Σ_s Σ_t^{½})^{-½} Σ_t^{½}
 *     f(x) = T (x − μ_s) + μ_t
 *
 * v1.3 introduces a **chroma-only** variant on OKLab (a, b) — preserves
 * luminance (handled separately by the tone-curve stage), avoids the
 * brittle 3×3 inverse-sqrt cliff on low-variance references, and gives
 * the user a "match the colour, not the exposure" mental model that
 * mirrors how pros think about reference grades.
 *
 * Numerical stabilization: **OAS shrinkage** (Chen et al. 2010,
 * arXiv:0907.4698) on the target covariance. Computes ρ in closed form
 * for p=2 and shrinks Σ_t toward (tr Σ_t / 2) · I, eliminating the
 * inverse-sqrt failure mode on near-singular Σ_t.
 *
 * The v1.0–v1.2 3D solver is retained as [solve3D] (deprecated) until
 * Phase F rewires MainActivity to the chroma path.
 */
object Mkl {

    // ──────────── 2D chroma transform ────────────

    /**
     * 2×2 linear map + 2-vector bias on OKLab (a, b). The L channel is
     * untouched; the tone-curve stage handles luminance separately.
     *
     * @property matrix  flat [m00, m01, m10, m11] row-major
     * @property bias    [bias_a, bias_b]
     */
    data class Transform2D(val matrix: FloatArray, val bias: FloatArray) {
        init {
            require(matrix.size == 4) { "Transform2D matrix must be 4 floats (2×2 row-major)" }
            require(bias.size == 2) { "Transform2D bias must be 2 floats (a, b)" }
        }
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Transform2D) return false
            return matrix.contentEquals(other.matrix) && bias.contentEquals(other.bias)
        }
        override fun hashCode(): Int = 31 * matrix.contentHashCode() + bias.contentHashCode()
    }

    /** Identity 2D transform — no shift, no scale. */
    val Identity2D = Transform2D(
        matrix = floatArrayOf(1f, 0f, 0f, 1f),
        bias   = floatArrayOf(0f, 0f),
    )

    /**
     * Solve closed-form MKL on OKLab (a, b).
     *
     * @param muSrc_ab    2-vector (a, b) source mean
     * @param sigmaSrc_ab 2×2 source covariance (flat row-major)
     * @param muTgt_ab    2-vector (a, b) target mean
     * @param sigmaTgt_ab 2×2 target covariance (flat row-major)
     * @param nTarget     Target sample count for OAS shrinkage (typically
     *                    the per-ref pixel count or aggregated count). If
     *                    <= 1, OAS is skipped (no shrinkage).
     */
    fun solveChroma(
        muSrc_ab: FloatArray,
        sigmaSrc_ab: FloatArray,
        muTgt_ab: FloatArray,
        sigmaTgt_ab: FloatArray,
        nTarget: Int,
    ): Transform2D {
        require(muSrc_ab.size == 2 && muTgt_ab.size == 2)
        require(sigmaSrc_ab.size == 4 && sigmaTgt_ab.size == 4)

        // 1) OAS shrinkage on target covariance, p=2.
        //    τ = tr(Σ_t) / 2
        //    num = tr(Σ_t)²                                (1 - 2/p = 0 for p=2)
        //    den = (n + 0)·(tr(Σ_t²) − tr(Σ_t)²/2)
        //    ρ = min(1, num / max(den, ε))
        //    Σ̃_t = (1-ρ)·Σ_t + ρ·τ·I_2
        val sigmaT = if (nTarget > 1) oasShrink2D(sigmaTgt_ab, nTarget) else sigmaTgt_ab

        // 2) Closed-form 2×2 matrix sqrt: A^½ = (A + √det·I) / √(tr + 2√det)
        val sigmaT_half = OkMat.matrixSqrt2(sigmaT)

        // 3) inner = Σ_t^½ · Σ_s · Σ_t^½
        val inner = OkMat.matmul2(sigmaT_half, OkMat.matmul2(sigmaSrc_ab, sigmaT_half))

        // 4) (inner)^{-½}
        val innerHalfInv = OkMat.matrixInverse2(OkMat.matrixSqrt2(inner))

        // 5) T = Σ_t^½ · (inner)^{-½} · Σ_t^½
        val t = OkMat.matmul2(sigmaT_half, OkMat.matmul2(innerHalfInv, sigmaT_half))

        // 6) bias = μ_t − T · μ_s
        val tMu = OkMat.matvec2(t, muSrc_ab)
        val bias = floatArrayOf(muTgt_ab[0] - tMu[0], muTgt_ab[1] - tMu[1])

        return Transform2D(matrix = t, bias = bias)
    }

    /**
     * OAS shrinkage on a 2×2 symmetric covariance toward isotropic identity.
     *
     * Chen et al. 2010 closed-form ρ for p=2 simplifies to:
     *   τ   = tr(S) / 2
     *   num = tr(S)²
     *   den = n · (tr(S²) − tr(S)²/2)
     *   ρ   = min(1, num / max(den, ε))
     *   Σ̃  = (1-ρ)·S + ρ·τ·I
     */
    private fun oasShrink2D(s: FloatArray, n: Int): FloatArray {
        val tr = s[0] + s[3]
        // tr(S²) for symmetric 2×2 [[a,b],[b,d]] = a² + 2b² + d²
        val trSqr = s[0] * s[0] + 2f * s[1] * s[1] + s[3] * s[3]
        val num = tr * tr
        val den = n.toFloat() * (trSqr - 0.5f * tr * tr)
        val rho = (num / max(den, 1e-12f)).coerceIn(0f, 1f)
        val tau = 0.5f * tr
        val oneMinusRho = 1f - rho
        return floatArrayOf(
            oneMinusRho * s[0] + rho * tau,  oneMinusRho * s[1],
            oneMinusRho * s[2],              oneMinusRho * s[3] + rho * tau,
        )
    }

    // ──────────── Legacy 3D transform (deprecated) ────────────

    /**
     * 3×3 linear map + 3-vector bias. Kept for back-compat with persisted
     * presets and the current MainActivity LaunchedEffect. Will be removed
     * after Phase F rewires the caller to the chroma path.
     */
    @Deprecated("Use Transform2D + solveChroma for the v1.3 cascade.")
    data class Transform(val matrix: FloatArray, val bias: FloatArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Transform) return false
            return matrix.contentEquals(other.matrix) && bias.contentEquals(other.bias)
        }
        override fun hashCode(): Int = 31 * matrix.contentHashCode() + bias.contentHashCode()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use Identity2D for the v1.3 cascade.")
    val Identity = Transform(
        matrix = floatArrayOf(1f, 0f, 0f,  0f, 1f, 0f,  0f, 0f, 1f),
        bias = floatArrayOf(0f, 0f, 0f),
    )

    /** Legacy 3D MKL solver. Use [solveChroma] for the v1.3 OKLab cascade. */
    @Suppress("DEPRECATION")
    @Deprecated("Use solveChroma on OKLab (a, b) instead.")
    fun solve(
        muSrc: FloatArray, sigmaSrc: Array<FloatArray>,
        muTgt: FloatArray, sigmaTgt: Array<FloatArray>,
    ): Transform {
        require(muSrc.size == 3 && muTgt.size == 3)
        require(sigmaSrc.size == 3 && sigmaTgt.size == 3)

        val ssHalf    = OkMat.matrixSqrt3(sigmaSrc)
        val ssInvHalf = OkMat.matrixInverse3(ssHalf)
        val inner     = OkMat.matrixSqrt3(OkMat.matmul3(OkMat.matmul3(ssHalf, sigmaTgt), ssHalf))
        val t         = OkMat.matmul3(OkMat.matmul3(ssInvHalf, inner), ssInvHalf)

        val bias = FloatArray(3) { i ->
            muTgt[i] - (t[i][0] * muSrc[0] + t[i][1] * muSrc[1] + t[i][2] * muSrc[2])
        }

        val flat = FloatArray(9)
        for (i in 0..2) for (j in 0..2) flat[i * 3 + j] = t[i][j]
        return Transform(matrix = flat, bias = bias)
    }
}
