package app.luxbuilder.color

import app.luxbuilder.photo.PhotoStats
import app.luxbuilder.photo.PhotoStats.RefStat
import app.luxbuilder.state.CurveChannel
import app.luxbuilder.state.CurvePoint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Reference-driven tone curve extraction in OKLab L.
 *
 * Given a moodboard's per-reference L histograms and the natural-image prior's
 * L distribution, produces a monotonic mapping f: L_src → L_tgt that, applied
 * pixel-wise to "the average photo," pushes its luminance distribution toward
 * the moodboard's. This is the **non-linear** part of the look that the 2D
 * chroma MKL stage cannot represent.
 *
 * Pipeline:
 *   1. Pool per-ref L histograms with equal per-image weight (256 bins)
 *   2. Gaussian-smooth pooled + source histograms (σ=3 bins)
 *   3. Compute monotonic CDFs from smoothed histograms
 *   4. Build raw map: f₀(x) = F_target⁻¹(F_source(x))
 *   5. Evidence-shrinkage toward identity in low-density source regions:
 *        f(x) = w(x)·f₀(x) + (1-w(x))·x,   w = min(1, h_src(x) / τ)
 *      with τ = N / (2·B); shrinks sparse-source regions toward y=x so the
 *      LUT doesn't wildly redirect colors the user rarely captures.
 *   6. Enforce f(0)=0, f(1)=1; ensure monotonicity via cumulative-max
 *   7. Smooth via Steffen monotonic cubic; resample to dense 256-point curve
 *   8. Pick 5 control points: endpoints + top-3 |curvature| extrema
 *      (equal-quantile fallback when curve is near-linear); snap to 1/32
 *
 * Sources:
 *  - Histogram specification: Gonzalez & Woods, "Digital Image Processing"
 *  - Steffen monotonic cubic: Steffen 1990, A&A 239:443
 *  - Knee detection by curvature extrema: standard scale-space result
 */
object ToneExtractor {

    private const val BINS = PhotoStats.L_HISTOGRAM_BINS   // 256
    private const val SMOOTH_SIGMA_BINS = 3f
    private const val CURVE_SAMPLES = 256

    /** Result of tone-curve extraction. */
    data class ExtractedTone(
        /** 5 control points suitable for `state.tone.luma` (Fritsch-Carlson UI). */
        val knots: List<CurvePoint>,
        /** Densely-sampled curve for verification / future smoothing decisions. */
        val curveSamples: FloatArray,
    ) {
        /** Convert to a [CurveChannel] ready for state ingestion. */
        fun toChannel(): CurveChannel {
            // Drop the implicit (0,0) and (1,1) endpoints — the UI manages those.
            val interior = knots.filter { it.x > 0f && it.x < 1f }
            return CurveChannel(interior)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ExtractedTone) return false
            return knots == other.knots && curveSamples.contentEquals(other.curveSamples)
        }
        override fun hashCode(): Int = 31 * knots.hashCode() + curveSamples.contentHashCode()
    }

    /**
     * Extract the tone curve from a list of per-reference statistics and a
     * source distribution (typically [NaturalImagePrior]).
     *
     * @param perRef     per-ref OKLab stats with populated L histograms
     * @param sourceMuL  source L-axis mean (e.g. NaturalImagePrior.MU_OKLAB[0])
     * @param sourceVarL source L-axis variance (e.g. NaturalImagePrior.SIGMA_OKLAB[0][0])
     */
    fun extract(
        perRef: List<RefStat>,
        sourceMuL: Float,
        sourceVarL: Float,
    ): ExtractedTone {
        if (perRef.isEmpty()) return identity()

        // 1) Pool per-ref histograms with equal per-image weight
        val hTgt = FloatArray(BINS)
        val n = perRef.size.toFloat()
        for (r in perRef) for (i in 0 until BINS) hTgt[i] += r.lHistogram[i] / n

        // 2) Source histogram: project the natural-image prior Gaussian onto L
        val hSrc = gaussianHistogram(BINS, sourceMuL, sqrt(max(sourceVarL, 1e-6f)))

        // 3) Smooth both
        val hSrcSm = gaussianSmooth(hSrc, SMOOTH_SIGMA_BINS)
        val hTgtSm = gaussianSmooth(hTgt, SMOOTH_SIGMA_BINS)

        // 4) Build CDFs
        val cdfSrc = cumulative(hSrcSm)
        val cdfTgt = cumulative(hTgtSm)

        // 5) Raw map f₀(x) = F_T⁻¹(F_S(x)) at each bin x
        val raw = FloatArray(BINS)
        for (i in 0 until BINS) {
            val x = (i + 0.5f) / BINS
            val u = cdfSrc[i]
            raw[i] = inverseCdf(cdfTgt, u)
        }

        // 6) Evidence shrinkage toward identity in sparse-source regions
        val tau = 1f / (2f * BINS)   // half-uniform density threshold
        val shrunk = FloatArray(BINS)
        for (i in 0 until BINS) {
            val x = (i + 0.5f) / BINS
            val w = (hSrcSm[i] / tau).coerceIn(0f, 1f)
            shrunk[i] = w * raw[i] + (1f - w) * x
        }

        // 7) Anchor endpoints and enforce monotonicity
        shrunk[0] = 0f
        shrunk[BINS - 1] = 1f
        var maxSoFar = 0f
        for (i in 0 until BINS) {
            if (shrunk[i] < maxSoFar) shrunk[i] = maxSoFar
            else maxSoFar = shrunk[i]
        }

        // 8) Smooth via Steffen monotonic cubic (resample to CURVE_SAMPLES)
        val xs = FloatArray(BINS) { (it + 0.5f) / BINS }
        val xs2 = FloatArray(BINS + 2)
        val ys2 = FloatArray(BINS + 2)
        // Add explicit (0,0) and (1,1) for boundary slope behavior
        xs2[0] = 0f; ys2[0] = 0f
        for (i in 0 until BINS) { xs2[i + 1] = xs[i]; ys2[i + 1] = shrunk[i] }
        xs2[BINS + 1] = 1f; ys2[BINS + 1] = 1f
        val curve = ToneCurve.steffenResample(xs2, ys2, CURVE_SAMPLES)

        // 9) Pick 5 knees
        val knots = ToneCurve.extractKneesFromCurve(curve, k = 5)
        return ExtractedTone(knots, curve)
    }

    /** Identity curve (no shift) — fallback when there are no refs. */
    fun identity(): ExtractedTone {
        val curve = FloatArray(CURVE_SAMPLES) { it.toFloat() / (CURVE_SAMPLES - 1) }
        val knots = listOf(
            CurvePoint(0f, 0f), CurvePoint(0.25f, 0.25f),
            CurvePoint(0.5f, 0.5f), CurvePoint(0.75f, 0.75f),
            CurvePoint(1f, 1f),
        )
        return ExtractedTone(knots, curve)
    }

    // ─────── internal helpers ───────

    /** Gaussian PDF sampled into [bins] normalized bins over [0,1]. */
    private fun gaussianHistogram(bins: Int, mu: Float, sigma: Float): FloatArray {
        val out = FloatArray(bins)
        val invTwoSigmaSq = 1f / (2f * sigma * sigma)
        var sum = 0f
        for (i in 0 until bins) {
            val x = (i + 0.5f) / bins
            val d = x - mu
            val v = exp(-d * d * invTwoSigmaSq)
            out[i] = v
            sum += v
        }
        if (sum > 0f) for (i in 0 until bins) out[i] /= sum
        return out
    }

    /**
     * In-place-free Gaussian smoothing on a 1D histogram. Uses a separable
     * 1D kernel sized to 3σ on each side; clamps at the boundary. Cheap.
     */
    private fun gaussianSmooth(h: FloatArray, sigmaBins: Float): FloatArray {
        val radius = (3f * sigmaBins).toInt().coerceAtLeast(1)
        val ker = FloatArray(2 * radius + 1)
        val invTwoSigmaSq = 1f / (2f * sigmaBins * sigmaBins)
        var sum = 0f
        for (i in -radius..radius) {
            val v = exp(-(i * i) * invTwoSigmaSq)
            ker[i + radius] = v
            sum += v
        }
        for (i in ker.indices) ker[i] /= sum
        val n = h.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            var v = 0f
            for (k in -radius..radius) {
                val idx = (i + k).coerceIn(0, n - 1)
                v += h[idx] * ker[k + radius]
            }
            out[i] = v
        }
        return out
    }

    /** Cumulative sum, normalized to end at 1.0 (monotonically nondecreasing). */
    private fun cumulative(h: FloatArray): FloatArray {
        val out = FloatArray(h.size)
        var acc = 0f
        for (i in h.indices) {
            acc += h[i]
            out[i] = acc
        }
        // Normalize to end at exactly 1.0 (the histogram should already be
        // normalized but guard against floating-point drift)
        val last = max(out[out.size - 1], 1e-12f)
        for (i in out.indices) out[i] /= last
        return out
    }

    /**
     * Inverse CDF lookup: find x such that cdf(x) ≥ u using linear interpolation
     * between bins. Returns a normalized [0,1] x-coordinate (bin-centered).
     */
    private fun inverseCdf(cdf: FloatArray, u: Float): Float {
        // Binary search for first index where cdf[i] >= u
        var lo = 0; var hi = cdf.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cdf[mid] < u) lo = mid + 1 else hi = mid
        }
        val n = cdf.size
        if (lo == 0) return 0.5f / n   // first bin centroid
        val prev = cdf[lo - 1]
        val curr = cdf[lo]
        // Linear interp between bin centers
        val xPrev = (lo - 0.5f) / n
        val xCurr = (lo + 0.5f) / n
        val span = curr - prev
        val t = if (span > 1e-12f) (u - prev) / span else 0f
        return (xPrev + t * (xCurr - xPrev)).coerceIn(0f, 1f)
    }
}
