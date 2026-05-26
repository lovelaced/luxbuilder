package app.luxbuilder.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import app.luxbuilder.color.ColorPipeline
import app.luxbuilder.color.OkLab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Color statistics extraction for reference-driven LUT generation.
 *
 * v1.3 introduces a per-reference statistics path in **OKLab** post-WB-
 * neutralization, returned as [RefStats]. This replaces the v1.0–v1.2
 * "concatenate all pixels into one big set" approach, which image-size-
 * weighted references and folded between-ref WB variance into Σ.
 *
 *   computePerRef(uris, stripWb)  →  List<RefStat>  in OKLab
 *
 * The legacy [compute] API returning linear-sRGB ([Stats]) is retained as
 * a deprecated path until Phase F of the v1.3 refactor rewires the
 * MainActivity LaunchedEffect to consume per-ref stats + RobustAggregator.
 */
object PhotoStats {

    private const val MAX_LONGEST = 512
    private const val SAMPLES_PER_IMAGE = 50_000
    private const val SAMPLES_PER_IMAGE_LEGACY = 8_000

    // ─────────────── v1.3 per-reference OKLab stats ───────────────

    /** Number of bins in per-ref L-channel histogram (used by [ToneExtractor]). */
    const val L_HISTOGRAM_BINS = 256

    /**
     * Per-reference statistics in OKLab post-WB-neutralization.
     *
     * @property mu          OKLab (L, a, b) mean — 3-vector
     * @property sigma       OKLab covariance — 3×3 symmetric, [row][col]
     * @property count       Pixels contributing (≤ SAMPLES_PER_IMAGE)
     * @property gains       Illuminant gains applied; Neutral = no WB-strip
     * @property lHistogram  Normalized L-channel histogram, [L_HISTOGRAM_BINS]
     *                       float bins covering L ∈ [0,1]; Σ bins = 1.0.
     *                       Used by tone-curve extraction.
     * @property labSamples  Interleaved (L, a, b, L, a, b, …) pixel samples
     *                       in OKLab. Used by [HueBandExtractor] and IDT.
     *                       Lives only during the cascade closure; not
     *                       retained on app state.
     */
    data class RefStat(
        val mu: FloatArray,
        val sigma: Array<FloatArray>,
        val count: Int,
        val gains: IlluminantEstimator.Gains,
        val lHistogram: FloatArray,
        val labSamples: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RefStat) return false
            return mu.contentEquals(other.mu) &&
                sigma.contentDeepEquals(other.sigma) &&
                count == other.count && gains == other.gains &&
                lHistogram.contentEquals(other.lHistogram) &&
                labSamples.contentEquals(other.labSamples)
        }
        override fun hashCode(): Int {
            var h = mu.contentHashCode()
            h = 31 * h + sigma.contentDeepHashCode()
            h = 31 * h + count
            h = 31 * h + gains.hashCode()
            h = 31 * h + lHistogram.contentHashCode()
            // labSamples intentionally excluded from hash — it's bulky and
            // mu+sigma+lHistogram already discriminate per-ref well enough.
            return h
        }
    }

    /**
     * Compute per-reference OKLab statistics from a list of URIs.
     *
     * For each URI:
     *   1. Decode at ≤ 512 px longest into linear-sRGB RGBA_F16
     *   2. (if [stripShootingWb]) estimate illuminant via Shades-of-Gray p=6
     *      and apply von-Kries gains in linear sRGB
     *   3. Convert per-pixel linear sRGB → OKLab
     *   4. Stratified-sample up to [SAMPLES_PER_IMAGE] pixels
     *   5. Compute 3-vector μ and 3×3 Σ
     *
     * Pixels with any channel < 0 in linear sRGB (wide-gamut OOG into negative
     * territory) are passed through OKLab.fromLinearSrgb's sign-preserving
     * cube root and contribute normally.
     */
    suspend fun computePerRef(
        context: Context,
        uris: List<Uri>,
        stripShootingWb: Boolean,
    ): List<RefStat> = withContext(Dispatchers.Default) {
        val out = ArrayList<RefStat>(uris.size)
        for (uri in uris) {
            val bitmap = PhotoSource.decodeReferenceLinear(context, uri) ?: continue
            try {
                out += statsForOne(bitmap, stripShootingWb)
            } finally {
                bitmap.recycle()
            }
        }
        out
    }

    private fun statsForOne(bitmap: Bitmap, stripShootingWb: Boolean): RefStat {
        val w = bitmap.width
        val h = bitmap.height
        val n = SAMPLES_PER_IMAGE.coerceAtMost(w * h)

        // Two passes: first to estimate illuminant on a smaller subsample,
        // second to compute OKLab stats on the larger sample post-WB.
        val rng = Random(0xC0FFEEL)

        val gains = if (stripShootingWb) {
            val probeN = (n / 4).coerceAtLeast(2000)
            val probeBuf = FloatArray(probeN * 3)
            samplePixelsLinear(bitmap, rng, probeN, probeBuf)
            IlluminantEstimator.shadesOfGray(probeBuf)
        } else {
            IlluminantEstimator.Gains.Neutral
        }

        // Reset rng so the stat sample doesn't include the probe (different positions).
        val sampleRng = Random(0xCAFEBABEL)
        val labBuf = FloatArray(n * 3)
        sampleAndConvert(bitmap, sampleRng, n, gains, labBuf)

        // Compute μ, Σ, and L-histogram over the OKLab sample
        var muL = 0.0; var muA = 0.0; var muB = 0.0
        val hist = FloatArray(L_HISTOGRAM_BINS)
        val maxBin = L_HISTOGRAM_BINS - 1
        for (i in 0 until n) {
            val l = labBuf[i * 3]
            muL += l
            muA += labBuf[i * 3 + 1]
            muB += labBuf[i * 3 + 2]
            // L-histogram: clamp OOG pixels into endpoint bins
            val bin = (l * L_HISTOGRAM_BINS).toInt().coerceIn(0, maxBin)
            hist[bin] += 1f
        }
        muL /= n; muA /= n; muB /= n
        val invN = 1f / n
        for (i in 0 until L_HISTOGRAM_BINS) hist[i] *= invN

        var sLL = 0.0; var sLA = 0.0; var sLB = 0.0
        var sAA = 0.0; var sAB = 0.0
        var sBB = 0.0
        for (i in 0 until n) {
            val dL = labBuf[i * 3] - muL
            val dA = labBuf[i * 3 + 1] - muA
            val dB = labBuf[i * 3 + 2] - muB
            sLL += dL * dL; sLA += dL * dA; sLB += dL * dB
            sAA += dA * dA; sAB += dA * dB
            sBB += dB * dB
        }
        val invND = 1.0 / n
        val sigma = arrayOf(
            floatArrayOf((sLL * invND).toFloat(), (sLA * invND).toFloat(), (sLB * invND).toFloat()),
            floatArrayOf((sLA * invND).toFloat(), (sAA * invND).toFloat(), (sAB * invND).toFloat()),
            floatArrayOf((sLB * invND).toFloat(), (sAB * invND).toFloat(), (sBB * invND).toFloat()),
        )
        val mu = floatArrayOf(muL.toFloat(), muA.toFloat(), muB.toFloat())
        return RefStat(mu, sigma, n, gains, hist, labBuf)
    }

    /**
     * Sample [count] linear-sRGB triples from [bitmap] into [out].
     * Bitmap is expected to be RGBA_F16 in LINEAR_EXTENDED_SRGB; we read via
     * [Bitmap.getColor] which gives a linear-sRGB Color regardless of config.
     */
    private fun samplePixelsLinear(bitmap: Bitmap, rng: Random, count: Int, out: FloatArray) {
        val w = bitmap.width
        val h = bitmap.height
        for (i in 0 until count) {
            val x = rng.nextInt(w)
            val y = rng.nextInt(h)
            val c: Color = bitmap.getColor(x, y).convert(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_SRGB))
            out[i * 3]     = c.red()
            out[i * 3 + 1] = c.green()
            out[i * 3 + 2] = c.blue()
        }
    }

    /**
     * Sample [count] pixels, apply [gains] in linear sRGB, convert to OKLab,
     * write into [out].
     */
    private fun sampleAndConvert(
        bitmap: Bitmap, rng: Random, count: Int,
        gains: IlluminantEstimator.Gains, out: FloatArray,
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val linSrgb = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_SRGB)
        for (i in 0 until count) {
            val x = rng.nextInt(w)
            val y = rng.nextInt(h)
            val c: Color = bitmap.getColor(x, y).convert(linSrgb)
            val r = c.red()   * gains.r
            val g = c.green() * gains.g
            val b = c.blue()  * gains.b
            val lab = OkLab.fromLinearSrgb(r, g, b)
            out[i * 3]     = lab[0]
            out[i * 3 + 1] = lab[1]
            out[i * 3 + 2] = lab[2]
        }
    }

    // ─────────────── Legacy linear-sRGB stats (deprecated) ───────────────

    /**
     * Mean and covariance in linear-sRGB across all URIs, computed by
     * concatenating per-pixel samples (the v1.0–v1.2 method).
     *
     * **Deprecated**: use [computePerRef] + RobustAggregator (Phase C) for
     * the v1.3 pipeline. This shim remains until Phase F rewires
     * MainActivity to consume the new types.
     */
    @Deprecated("Use computePerRef + RobustAggregator", ReplaceWith("computePerRef(context, uris, stripShootingWb)"))
    data class Stats(val mu: FloatArray, val sigma: Array<FloatArray>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Stats) return false
            return mu.contentEquals(other.mu) && sigma.contentDeepEquals(other.sigma)
        }
        override fun hashCode(): Int = 31 * mu.contentHashCode() + sigma.contentDeepHashCode()
    }

    @Deprecated("Use computePerRef + RobustAggregator", ReplaceWith("computePerRef(context, uris, false)"))
    @Suppress("DEPRECATION")
    suspend fun compute(context: Context, uris: List<Uri>): Stats? = withContext(Dispatchers.Default) {
        if (uris.isEmpty()) return@withContext null
        val samples = ArrayList<FloatArray>(uris.size * SAMPLES_PER_IMAGE_LEGACY)
        for (uri in uris) {
            val bitmap = decodeForStats(context, uri) ?: continue
            sampleBitmap(bitmap, samples, SAMPLES_PER_IMAGE_LEGACY)
            bitmap.recycle()
        }
        if (samples.isEmpty()) return@withContext null

        val mu = FloatArray(3)
        for (s in samples) { mu[0] += s[0]; mu[1] += s[1]; mu[2] += s[2] }
        val n = samples.size.toFloat()
        mu[0] /= n; mu[1] /= n; mu[2] /= n

        val sigma = Array(3) { FloatArray(3) }
        for (s in samples) {
            val d0 = s[0] - mu[0]; val d1 = s[1] - mu[1]; val d2 = s[2] - mu[2]
            sigma[0][0] += d0 * d0; sigma[0][1] += d0 * d1; sigma[0][2] += d0 * d2
            sigma[1][0] += d1 * d0; sigma[1][1] += d1 * d1; sigma[1][2] += d1 * d2
            sigma[2][0] += d2 * d0; sigma[2][1] += d2 * d1; sigma[2][2] += d2 * d2
        }
        for (i in 0..2) for (j in 0..2) sigma[i][j] /= n

        Stats(mu, sigma)
    }

    private suspend fun decodeForStats(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / (sample * 2) >= MAX_LONGEST) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun sampleBitmap(bitmap: Bitmap, samples: MutableList<FloatArray>, count: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val rng = Random(0xC0FFEEL)
        val px = IntArray(1)
        repeat(count) {
            val x = rng.nextInt(w)
            val y = rng.nextInt(h)
            bitmap.getPixels(px, 0, 1, x, y, 1, 1)
            val argb = px[0]
            val rSrgb = ((argb shr 16) and 0xFF) / 255f
            val gSrgb = ((argb shr 8) and 0xFF) / 255f
            val bSrgb = (argb and 0xFF) / 255f
            samples.add(floatArrayOf(
                ColorPipeline.srgbToLinear(rSrgb),
                ColorPipeline.srgbToLinear(gSrgb),
                ColorPipeline.srgbToLinear(bSrgb),
            ))
        }
    }
}
