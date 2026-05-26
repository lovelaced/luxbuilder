package app.luxbuilder.color

import app.luxbuilder.state.HslAnchor
import app.luxbuilder.state.HslColor
import app.luxbuilder.state.HslPanel
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-hue-band residual decomposition in OKLCh.
 *
 * After the chroma MKL (Stage 2) has aligned (a, b) means and covariance, a
 * *band-localized* residual typically remains: a teal-orange look will still
 * push blue pixels cooler and red pixels warmer than the global linear
 * transform can capture. This stage extracts those residuals as six
 * (Δh, Δsat, ΔL) corrections, one per band, and writes them into the
 * existing user-editable HSL panel. Auto-fit becomes pre-positioned sliders
 * the user can see and tweak — on-thesis for transparency.
 *
 * Band centers (degrees): 30, 90, 150, 210, 270, 330  (red, yellow, green,
 * cyan, blue, magenta — matching the [HslColor] anchors).
 *
 * Algorithm (per band b):
 *   1. Triangular soft-assignment: w_b(h) = max(0, 1 − |Δh| / 60°)
 *      → partition of unity (Σ w_b(h) = 1 for any h)
 *   2. Chroma-gate: multiply w by clip(C / 0.02, 0, 1)
 *      → achromatic pixels don't vote for any band
 *   3. Per-band weighted (μ_L, μ_C, circ_mean(h)) using sin/cos pooling
 *   4. Raw residual deltas (target − source-after-MKL):
 *      Δh_b = wrap_pi(h_t − h_s)
 *      Δs_b = log(C_t / C_s)   (log so it composes multiplicatively)
 *      ΔL_b = L_t − L_s
 *   5. James-Stein shrinkage by band mass m_b:
 *      shrink = m_b / (m_b + m₀),   m₀ = 1% of total mass
 *      Δ_b *= shrink   (sparse-mass bands relax toward 0)
 *   6. Circulant Tikhonov smoothing on each of {Δh, Δs, ΔL}:
 *      x* = (W + α·D_cᵀD_c)⁻¹ W Δ̂
 *      with D_c the circulant second-difference operator (closed-form 6×6 solve)
 *   7. Map OKLCh-domain deltas to the HSL panel's normalized slider units
 *      via per-channel gain constants (calibrated for the existing UI scale)
 *
 * Reference: Storath & Weinmann 2021, "Tikhonov Regularization of
 * Circle-Valued Signals" (arXiv:2108.02602) for the circular Tikhonov form.
 */
object HueBandExtractor {

    private const val N_BANDS = 6
    /** Band centers in radians: 30°, 90°, 150°, 210°, 270°, 330°. */
    private val BAND_CENTERS_RAD = FloatArray(N_BANDS) {
        ((30f + 60f * it) * PI / 180f).toFloat()
    }
    /** Each band spans ±60° around its center (triangular falloff). */
    private val BAND_HALF_WIDTH_RAD = (60f * PI / 180f).toFloat()
    /** Chroma gate: pixels with C below this don't contribute. */
    private const val CHROMA_GATE = 0.02f
    /** Tikhonov regularization strength on the circular smoothness penalty. */
    private const val TIKHONOV_ALPHA = 0.3f
    /** James-Stein m₀ as fraction of total mass. */
    private const val JS_M0_FRAC = 0.01f

    /** Slider-unit calibration: OKLCh delta → HslAnchor field value. */
    // hueShift is stored as -1..+1 in HslAnchor and the shader maps it to ±30°.
    // So 1 unit of hueShift = 30° in shader degrees = (π/6) rad. To convert
    // an OKLCh hue delta (rad) to slider units: slider = Δh_rad / (π/6).
    private val HUE_SLIDER_PER_RAD = (180f / 30f / PI).toFloat()
    // satShift is -1..+1 → ±100% scale offset in shader. So 1 slider unit
    // = factor of 2 (= log 2). Convert log-chroma-delta to slider via /ln(2).
    private val SAT_SLIDER_PER_LOG = (1f / ln(2.0)).toFloat()
    // lumaShift is -1..+1 → ±100% scale; one slider unit corresponds to a
    // ~0.1 OKLab L offset. Calibrated to feel natural in the UI.
    private const val LUMA_SLIDER_PER_OKLAB_L = 10f

    /** Result of hue-band extraction — ready to overlay onto state.hsl. */
    data class ExtractedHsl(val panel: HslPanel)

    /**
     * Extract per-band shifts comparing target pixels vs source pixels in OKLab.
     *
     * @param tgtLabSamples  interleaved (L, a, b, L, a, b, …) target pixel samples
     *                       — typically the moodboard's actual pixels after WB-strip
     *                       (NOT after chroma MKL, since the look INCLUDES the cast)
     * @param srcLabSamples  interleaved source pixel samples AFTER the chroma MKL
     *                       transform has been applied (so the residual is just what
     *                       the linear stage couldn't capture)
     */
    fun extract(
        tgtLabSamples: FloatArray,
        srcLabSamples: FloatArray,
    ): ExtractedHsl {
        require(tgtLabSamples.size % 3 == 0)
        require(srcLabSamples.size % 3 == 0)

        val tBands = bandStats(tgtLabSamples)
        val sBands = bandStats(srcLabSamples)

        // Raw deltas with James-Stein mass shrinkage
        val rawDh = FloatArray(N_BANDS)
        val rawDs = FloatArray(N_BANDS)
        val rawDl = FloatArray(N_BANDS)
        val shrink = FloatArray(N_BANDS)
        val totalMass = max(tBands.sumOf { it.mass.toDouble() }, 1e-12).toFloat()
        val m0 = JS_M0_FRAC * totalMass
        for (b in 0 until N_BANDS) {
            val mEff = minOf(tBands[b].mass, sBands[b].mass)
            val sh = mEff / (mEff + m0)
            shrink[b] = sh
            rawDh[b] = sh * wrapPi(tBands[b].hueRad - sBands[b].hueRad)
            rawDs[b] = sh * ln(max(tBands[b].chroma, 1e-4f) / max(sBands[b].chroma, 1e-4f))
            rawDl[b] = sh * (tBands[b].lightness - sBands[b].lightness)
        }

        // Circular Tikhonov smoothing on each channel
        val smoothDh = circulantTikhonov(rawDh, shrink, TIKHONOV_ALPHA)
        val smoothDs = circulantTikhonov(rawDs, shrink, TIKHONOV_ALPHA)
        val smoothDl = circulantTikhonov(rawDl, shrink, TIKHONOV_ALPHA)

        // Map OKLCh deltas to HslAnchor slider units (clamped to ±1)
        val anchorOrder = listOf(
            HslColor.RED, HslColor.YELLOW, HslColor.GREEN,
            HslColor.AQUA, HslColor.BLUE, HslColor.RED, // placeholder — magenta band
        )
        // luxbuilder's HslPanel has 6 anchors but they're laid out:
        //   RED@0°, ORANGE@30°, YELLOW@60°, GREEN@120°, AQUA@180°, BLUE@240°
        // Our extractor uses bands at 30°/90°/150°/210°/270°/330°, which align
        // with ORANGE / YELLOW (shifted) / GREEN / CYAN / BLUE / MAGENTA.
        // We map by nearest hue-center to the existing anchor set:
        //   30°  → ORANGE
        //   90°  → YELLOW
        //   150° → GREEN
        //   210° → AQUA
        //   270° → BLUE
        //   330° → RED (magenta wraps back to red side)
        val bandToAnchor = listOf(
            HslColor.ORANGE,  // 30°
            HslColor.YELLOW,  // 90°
            HslColor.GREEN,   // 150°
            HslColor.AQUA,    // 210°
            HslColor.BLUE,    // 270°
            HslColor.RED,     // 330°
        )

        val anchors = HslColor.entries.associateWith { HslAnchor() }.toMutableMap()
        for (b in 0 until N_BANDS) {
            val color = bandToAnchor[b]
            val hueU = (smoothDh[b] * HUE_SLIDER_PER_RAD).coerceIn(-1f, 1f)
            val satU = (smoothDs[b] * SAT_SLIDER_PER_LOG).coerceIn(-1f, 1f)
            val lumU = (smoothDl[b] * LUMA_SLIDER_PER_OKLAB_L).coerceIn(-1f, 1f)
            // Merge with whatever may already be assigned (sum, then clamp)
            val cur = anchors[color] ?: HslAnchor()
            anchors[color] = HslAnchor(
                hueShift  = (cur.hueShift  + hueU).coerceIn(-1f, 1f),
                satShift  = (cur.satShift  + satU).coerceIn(-1f, 1f),
                lumaShift = (cur.lumaShift + lumU).coerceIn(-1f, 1f),
            )
        }
        return ExtractedHsl(HslPanel(anchors.toMap()))
    }

    // ─────── internal stats ───────

    private data class BandStat(
        val mass: Float,
        val lightness: Float,
        val chroma: Float,
        val hueRad: Float,
    )

    /** Per-band stats over an interleaved (L, a, b) pixel buffer. */
    private fun bandStats(samples: FloatArray): Array<BandStat> {
        val n = samples.size / 3
        val sumW = FloatArray(N_BANDS)
        val sumWL = FloatArray(N_BANDS)
        val sumWC = FloatArray(N_BANDS)
        val sumWSin = FloatArray(N_BANDS)
        val sumWCos = FloatArray(N_BANDS)

        for (i in 0 until n) {
            val L = samples[i * 3]
            val a = samples[i * 3 + 1]
            val b = samples[i * 3 + 2]
            val C = sqrt(a * a + b * b)
            if (C < 1e-6f) continue
            val h = atan2(b, a)
            val chromaGate = (C / CHROMA_GATE).coerceIn(0f, 1f)
            for (k in 0 until N_BANDS) {
                val d = wrapPi(h - BAND_CENTERS_RAD[k])
                val absD = if (d < 0f) -d else d
                val triangle = max(0f, 1f - absD / BAND_HALF_WIDTH_RAD)
                val w = triangle * chromaGate
                if (w <= 0f) continue
                sumW[k]    += w
                sumWL[k]   += w * L
                sumWC[k]   += w * C
                sumWSin[k] += w * sin(h)
                sumWCos[k] += w * cos(h)
            }
        }
        return Array(N_BANDS) { k ->
            val m = sumW[k]
            if (m <= 1e-12f) BandStat(0f, 0f, 0f, BAND_CENTERS_RAD[k])
            else BandStat(
                mass = m,
                lightness = sumWL[k] / m,
                chroma = sumWC[k] / m,
                hueRad = atan2(sumWSin[k], sumWCos[k]),
            )
        }
    }

    /**
     * Solve the 6×6 system (W + α·DᵀD)·x = W·b in closed form, where W = diag(weights)
     * and D is the circulant second-difference operator. For N=6 we just build the
     * matrix and Gaussian-eliminate.
     */
    private fun circulantTikhonov(b: FloatArray, weights: FloatArray, alpha: Float): FloatArray {
        // Build A = W + α·DᵀD  (6×6 symmetric)
        // D is the circulant first-difference on 6 elements; DᵀD = circulant second-diff
        // with rows like [2, -1, 0, 0, 0, -1] (Laplacian on the cycle).
        // Multiplying by α gives the smoothness term.
        val n = N_BANDS
        val A = Array(n) { FloatArray(n) }
        for (i in 0 until n) {
            A[i][i] = weights[i] + 2f * alpha
            A[i][(i + 1) % n] = -alpha
            A[i][(i - 1 + n) % n] = -alpha
        }
        val rhs = FloatArray(n) { weights[it] * b[it] }
        return gaussianEliminate6(A, rhs)
    }

    /** Tiny 6×6 Gaussian elimination with partial pivoting. */
    private fun gaussianEliminate6(A: Array<FloatArray>, rhs: FloatArray): FloatArray {
        val n = A.size
        val a = Array(n) { A[it].copyOf() }
        val b = rhs.copyOf()
        for (i in 0 until n) {
            // Partial pivot
            var pivot = i
            for (k in i + 1 until n) if (kotlin.math.abs(a[k][i]) > kotlin.math.abs(a[pivot][i])) pivot = k
            if (pivot != i) {
                val tmp = a[i]; a[i] = a[pivot]; a[pivot] = tmp
                val tb = b[i]; b[i] = b[pivot]; b[pivot] = tb
            }
            val piv = a[i][i]
            if (kotlin.math.abs(piv) < 1e-12f) continue
            for (k in i + 1 until n) {
                val factor = a[k][i] / piv
                if (factor == 0f) continue
                for (j in i until n) a[k][j] -= factor * a[i][j]
                b[k] -= factor * b[i]
            }
        }
        // Back-substitute
        val x = FloatArray(n)
        for (i in n - 1 downTo 0) {
            var s = b[i]
            for (j in i + 1 until n) s -= a[i][j] * x[j]
            x[i] = if (kotlin.math.abs(a[i][i]) > 1e-12f) s / a[i][i] else 0f
        }
        return x
    }

    /** Wrap a radian angle into (-π, π]. */
    private fun wrapPi(x: Float): Float {
        var y = x
        while (y >  PI) y = (y - 2 * PI).toFloat()
        while (y <= -PI) y = (y + 2 * PI).toFloat()
        return y
    }
}
