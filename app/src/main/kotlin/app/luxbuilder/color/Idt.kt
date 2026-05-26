package app.luxbuilder.color

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Iterative Distribution Transfer (Pitié & Kokaram 2005, 2007).
 *
 * IDT is N-dimensional optimal transport reduced to a sequence of 1-D
 * problems: rotate to a new orthogonal basis, match marginals per axis
 * via 1-D histogram specification, rotate back, repeat. Converges to a
 * distribution-matching map for non-Gaussian distributions where MKL's
 * single affine transform falls short.
 *
 * luxbuilder uses IDT as the **"High Quality" toggle** for color match:
 * MKL warm-start → IDT refinement → Ferradans smoothing → 33³ residual.
 * Runs at LUT-bake time only; live preview always uses the MKL+tone+HSL
 * cascade (Phases C/D/E).
 *
 * Cost on Pixel 6 Pro: 20k source × 20k target × 13 rotations × ≈ 200 ms
 * on Dispatchers.Default for CPU-bound float math.
 *
 * Sources:
 *  - Pitié, Kokaram, Dahyot. "Automated colour grading using colour
 *    distribution transfer." CVIU 107(1-2), 2007.
 *  - Bonneel et al. "Sliced and Radon Wasserstein Barycenters of Measures."
 *    JMIV 51, 2015 (formalised IDT as sliced-OT gradient descent).
 */
object Idt {

    private const val BINS = 256
    private const val CDF_SMOOTH_SIGMA = 1.5f

    /**
     * The fitted IDT map: a list of (rotation, three 1-D LUTs). Apply each
     * tuple in order to push a source point into the target distribution.
     */
    data class IdtMap(
        val rotation: Array<FloatArray>,
        val lutA: FloatArray,
        val lutB: FloatArray,
        val lutC: FloatArray,
        val axisMin: FloatArray, // per-axis projection min for [axisMin, axisMax] bin range
        val axisMax: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdtMap) return false
            return rotation.contentDeepEquals(other.rotation) &&
                lutA.contentEquals(other.lutA) &&
                lutB.contentEquals(other.lutB) &&
                lutC.contentEquals(other.lutC) &&
                axisMin.contentEquals(other.axisMin) &&
                axisMax.contentEquals(other.axisMax)
        }
        override fun hashCode(): Int {
            var h = rotation.contentDeepHashCode()
            h = 31 * h + lutA.contentHashCode()
            h = 31 * h + lutB.contentHashCode()
            h = 31 * h + lutC.contentHashCode()
            h = 31 * h + axisMin.contentHashCode()
            h = 31 * h + axisMax.contentHashCode()
            return h
        }
    }

    /**
     * Fit the IDT map sequence aligning [source] toward [target] in OKLab.
     *
     * Both pixel buffers are interleaved (L, a, b, L, a, b, …) and should be
     * pre-warmed by the chroma MKL stage (i.e. source already mapped through
     * the 2-D chromatic match before IDT runs).
     *
     * @param source  warm-started source samples (typically synthetic prior
     *                pixels post-chroma-MKL)
     * @param target  pooled moodboard pixels in OKLab (post-WB-strip)
     * @param iters   how many rotations to apply (≤ IdtRotations.SEQUENCE.size)
     */
    fun fit(
        source: FloatArray,
        target: FloatArray,
        iters: Int = 8,
    ): List<IdtMap> {
        require(source.size % 3 == 0 && target.size % 3 == 0)
        val rotations = IdtRotations.SEQUENCE.take(iters.coerceAtMost(IdtRotations.SEQUENCE.size))
        val maps = ArrayList<IdtMap>(rotations.size)
        // Make a mutable working copy of source; iterations apply in place
        val s = source.copyOf()
        for (R in rotations) {
            val map = fitOneIteration(s, target, R)
            applyOneIterationInPlace(s, map)
            maps += map
        }
        return maps
    }

    /**
     * Apply a pre-fit IDT map sequence to a single OKLab point in place.
     * Used by the bake step on each 33³ grid cell.
     */
    fun apply(point: FloatArray, maps: List<IdtMap>) {
        require(point.size == 3)
        for (m in maps) applyMapTo(point, m)
    }

    /**
     * Bake the IDT map sequence into a `[gridSize × gridSize × gridSize × 3]`
     * **residual** array in OKLab — i.e. (post-IDT − post-chroma-MKL) at each
     * grid cell. Stored as Δ from the chroma-MKL'd OKLab so it composes
     * additively after the chroma stage at runtime.
     *
     * Because IDT was fit on chroma-MKL'd source samples, evaluating it on
     * raw OKLab gives garbage — the bake must apply chroma MKL inside the
     * grid walk before running the IDT chain.
     *
     * @param maps          IDT chain produced by [fit]
     * @param gridSize      cubic edge length (33 for `.cube`, 17 for `.vlt`)
     * @param chromaMatrix  2×2 chroma MKL matrix (state.mklChromaMatrix)
     * @param chromaBias    chroma MKL bias (state.mklChromaBias)
     * @param sourceAbMean  source (a, b) mean used by the MKL solver
     */
    fun bakeResidual(
        maps: List<IdtMap>,
        gridSize: Int,
        chromaMatrix: FloatArray,
        chromaBias: FloatArray,
        sourceAbMean: FloatArray,
    ): FloatArray {
        require(chromaMatrix.size == 4 && chromaBias.size == 2 && sourceAbMean.size == 2)
        val n = gridSize
        val out = FloatArray(n * n * n * 3)
        val denom = (n - 1).toFloat()
        val tmp = FloatArray(3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            // Sample at cell center in linear sRGB → OKLab
            val lab = OkLab.fromLinearSrgb(r / denom, g / denom, b / denom)
            // Apply chroma MKL on (a, b) — must match ColorPipeline's stage
            val a0 = lab[1] - sourceAbMean[0]
            val b0 = lab[2] - sourceAbMean[1]
            val postA = chromaMatrix[0] * a0 + chromaMatrix[1] * b0 + chromaBias[0]
            val postB = chromaMatrix[2] * a0 + chromaMatrix[3] * b0 + chromaBias[1]
            // Store post-chroma OKLab as the residual baseline
            val baseL = lab[0]; val baseA = postA; val baseB = postB
            tmp[0] = baseL; tmp[1] = baseA; tmp[2] = baseB
            for (m in maps) applyMapTo(tmp, m)
            val idx = ((b * n + g) * n + r) * 3
            out[idx]     = tmp[0] - baseL
            out[idx + 1] = tmp[1] - baseA
            out[idx + 2] = tmp[2] - baseB
        }
        return out
    }

    // ─── per-iteration fit ───

    private fun fitOneIteration(
        sourceMutable: FloatArray,
        target: FloatArray,
        R: Array<FloatArray>,
    ): IdtMap {
        val ns = sourceMutable.size / 3
        val nt = target.size / 3

        // Project source and target onto R's three rows
        val sP = Array(3) { FloatArray(ns) }
        val tP = Array(3) { FloatArray(nt) }
        for (i in 0 until ns) {
            val L = sourceMutable[i * 3]; val a = sourceMutable[i * 3 + 1]; val b = sourceMutable[i * 3 + 2]
            sP[0][i] = R[0][0] * L + R[0][1] * a + R[0][2] * b
            sP[1][i] = R[1][0] * L + R[1][1] * a + R[1][2] * b
            sP[2][i] = R[2][0] * L + R[2][1] * a + R[2][2] * b
        }
        for (i in 0 until nt) {
            val L = target[i * 3]; val a = target[i * 3 + 1]; val b = target[i * 3 + 2]
            tP[0][i] = R[0][0] * L + R[0][1] * a + R[0][2] * b
            tP[1][i] = R[1][0] * L + R[1][1] * a + R[1][2] * b
            tP[2][i] = R[2][0] * L + R[2][1] * a + R[2][2] * b
        }

        // Determine joint axis range so source and target share bin edges
        val axisMin = FloatArray(3)
        val axisMax = FloatArray(3)
        for (k in 0..2) {
            var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
            for (i in 0 until ns) { lo = min(lo, sP[k][i]); hi = max(hi, sP[k][i]) }
            for (i in 0 until nt) { lo = min(lo, tP[k][i]); hi = max(hi, tP[k][i]) }
            // Tiny padding to avoid degenerate ranges
            val pad = max((hi - lo) * 1e-3f, 1e-4f)
            axisMin[k] = lo - pad
            axisMax[k] = hi + pad
        }

        // Build 1-D match LUTs per axis
        val lutA = fit1d(sP[0], tP[0], axisMin[0], axisMax[0])
        val lutB = fit1d(sP[1], tP[1], axisMin[1], axisMax[1])
        val lutC = fit1d(sP[2], tP[2], axisMin[2], axisMax[2])
        return IdtMap(R, lutA, lutB, lutC, axisMin, axisMax)
    }

    /**
     * 1-D match LUT mapping source values onto target values via
     * smoothed-CDF inverse-lookup. Returns BINS-sample LUT representing
     * f(x) over [axisMin, axisMax].
     */
    private fun fit1d(s: FloatArray, t: FloatArray, lo: Float, hi: Float): FloatArray {
        val hs = histogram(s, lo, hi)
        val ht = histogram(t, lo, hi)
        val hsSm = gaussianSmooth1D(hs, CDF_SMOOTH_SIGMA)
        val htSm = gaussianSmooth1D(ht, CDF_SMOOTH_SIGMA)
        val cdfS = cumulative(hsSm)
        val cdfT = cumulative(htSm)
        // For each bin center, find target value t such that cdfT(t) = cdfS(binCenter)
        val lut = FloatArray(BINS)
        for (i in 0 until BINS) {
            val u = cdfS[i]
            lut[i] = inverseCdf(cdfT, u, lo, hi)
        }
        return lut
    }

    private fun histogram(values: FloatArray, lo: Float, hi: Float): FloatArray {
        val out = FloatArray(BINS)
        val span = (hi - lo).coerceAtLeast(1e-9f)
        val invSpan = 1f / span
        for (v in values) {
            val t = (v - lo) * invSpan
            if (t < 0f || t >= 1f) {
                out[if (t < 0f) 0 else BINS - 1] += 1f
            } else {
                out[(t * BINS).toInt()] += 1f
            }
        }
        // Normalize
        var sum = 0f
        for (x in out) sum += x
        if (sum > 0f) for (i in 0 until BINS) out[i] /= sum
        return out
    }

    private fun gaussianSmooth1D(h: FloatArray, sigma: Float): FloatArray {
        val radius = (3f * sigma).toInt().coerceAtLeast(1)
        val ker = FloatArray(2 * radius + 1)
        val invTwoSigSq = 1f / (2f * sigma * sigma)
        var ks = 0f
        for (i in -radius..radius) {
            val v = exp(-(i * i) * invTwoSigSq)
            ker[i + radius] = v; ks += v
        }
        for (i in ker.indices) ker[i] /= ks
        val out = FloatArray(h.size)
        for (i in h.indices) {
            var v = 0f
            for (k in -radius..radius) {
                v += h[(i + k).coerceIn(0, h.size - 1)] * ker[k + radius]
            }
            out[i] = v
        }
        return out
    }

    private fun cumulative(h: FloatArray): FloatArray {
        val out = FloatArray(h.size)
        var acc = 0f
        for (i in h.indices) { acc += h[i]; out[i] = acc }
        val last = max(out[out.size - 1], 1e-12f)
        for (i in out.indices) out[i] /= last
        return out
    }

    /** Linear-interpolation inverse of a monotonic CDF onto axis range [lo, hi]. */
    private fun inverseCdf(cdf: FloatArray, u: Float, lo: Float, hi: Float): Float {
        var l = 0; var r = cdf.size - 1
        while (l < r) {
            val mid = (l + r) ushr 1
            if (cdf[mid] < u) l = mid + 1 else r = mid
        }
        val n = cdf.size
        if (l == 0) return lo + (0.5f / n) * (hi - lo)
        val prev = cdf[l - 1]; val curr = cdf[l]
        val span = curr - prev
        val t = if (span > 1e-12f) (u - prev) / span else 0f
        val xNorm = (l - 0.5f + t) / n
        return lo + xNorm * (hi - lo)
    }

    // ─── per-iteration apply ───

    private fun applyOneIterationInPlace(samples: FloatArray, m: IdtMap) {
        val n = samples.size / 3
        val R = m.rotation
        val tmp = FloatArray(3)
        for (i in 0 until n) {
            tmp[0] = samples[i * 3]; tmp[1] = samples[i * 3 + 1]; tmp[2] = samples[i * 3 + 2]
            applyMapTo(tmp, m)
            samples[i * 3] = tmp[0]; samples[i * 3 + 1] = tmp[1]; samples[i * 3 + 2] = tmp[2]
        }
    }

    /** Apply one IDT iteration to a single point (in place). */
    private fun applyMapTo(point: FloatArray, m: IdtMap) {
        val R = m.rotation
        // Project to rotated frame
        val pA = R[0][0] * point[0] + R[0][1] * point[1] + R[0][2] * point[2]
        val pB = R[1][0] * point[0] + R[1][1] * point[1] + R[1][2] * point[2]
        val pC = R[2][0] * point[0] + R[2][1] * point[1] + R[2][2] * point[2]
        // 1-D LUT lookup per axis
        val newA = lutSample(m.lutA, pA, m.axisMin[0], m.axisMax[0])
        val newB = lutSample(m.lutB, pB, m.axisMin[1], m.axisMax[1])
        val newC = lutSample(m.lutC, pC, m.axisMin[2], m.axisMax[2])
        // Displacement
        val dA = newA - pA; val dB = newB - pB; val dC = newC - pC
        // Rotate displacement back via R^T (R is orthogonal so R^{-1} = R^T)
        point[0] += R[0][0] * dA + R[1][0] * dB + R[2][0] * dC
        point[1] += R[0][1] * dA + R[1][1] * dB + R[2][1] * dC
        point[2] += R[0][2] * dA + R[1][2] * dB + R[2][2] * dC
    }

    /** Linear-interpolation LUT lookup over a known axis range. */
    private fun lutSample(lut: FloatArray, x: Float, lo: Float, hi: Float): Float {
        val span = (hi - lo).coerceAtLeast(1e-9f)
        val t = ((x - lo) / span).coerceIn(0f, 1f) * (lut.size - 1)
        val i = t.toInt().coerceIn(0, lut.size - 2)
        val f = t - i
        return lut[i] * (1f - f) + lut[i + 1] * f
    }
}
