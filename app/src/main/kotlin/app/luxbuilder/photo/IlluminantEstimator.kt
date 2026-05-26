package app.luxbuilder.photo

import kotlin.math.max
import kotlin.math.pow

/**
 * Per-reference illuminant estimation for "strip shooting white balance."
 *
 * When a user moodboards three references shot under daylight + tungsten +
 * shade, the *common look* is the residual chromatic content AFTER each ref
 * is neutralized to a canonical white point. Concatenating their pixels
 * without neutralization bakes the shooting WB variance into the look,
 * which is rarely what the user wants.
 *
 * Method: **Shades-of-Gray** (Finlayson & Trezzi 2004), Minkowski-p mean
 * per channel with p=6. Survey reviewed in research: ~3.5° mean angular
 * error on Gehler-Shi vs ~1° for FFCC, but FFCC needs per-camera training
 * data luxbuilder doesn't have. Shades-of-Gray is 30 lines of code, zero
 * training, no patents, fully transparent — on-thesis.
 *
 * Pipeline:
 *   linear sRGB pixels → clip-mask (reject near-0 and near-1) →
 *   Σ pixel^p per channel → ^(1/p) → normalize so G=1 → return as gains
 *
 * Apply gains to linear sRGB to "neutralize" the reference toward D65-equiv.
 *
 * Reference: Finlayson, G. & Trezzi, E. "Shades of Gray and Colour Constancy"
 * CIC 2004. https://library.imaging.org/cic/articles/12/1/art00008
 */
object IlluminantEstimator {

    private const val P = 6
    private const val LOW_CLIP = 0.02f
    private const val HIGH_CLIP = 0.98f

    /** Estimated illuminant gains in linear sRGB. Default p=6 (Shades-of-Gray sweet spot). */
    data class Gains(val r: Float, val g: Float, val b: Float) {
        val isNeutral: Boolean get() = r == 1f && g == 1f && b == 1f
        fun apply(linR: Float, linG: Float, linB: Float): FloatArray =
            floatArrayOf(linR * r, linG * g, linB * b)
        companion object {
            val Neutral = Gains(1f, 1f, 1f)
        }
    }

    /**
     * Estimate illuminant from a linear-sRGB pixel stream. [pixels] is
     * a flat FloatArray of (r, g, b, r, g, b, …) triples in linear light.
     *
     * Per-channel Minkowski-p mean over clip-masked pixels:
     *   ê_c = (Σ_i I_c^p / N)^(1/p)
     *
     * Returns gains normalized so G=1; apply per-channel to linear sRGB.
     */
    fun shadesOfGray(linearPixels: FloatArray, p: Int = P): Gains {
        require(linearPixels.size % 3 == 0) { "pixel buffer must be RGB triples" }
        val n = linearPixels.size / 3
        if (n == 0) return Gains.Neutral

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0L
        for (i in 0 until n) {
            val r = linearPixels[i * 3]
            val g = linearPixels[i * 3 + 1]
            val b = linearPixels[i * 3 + 2]
            // Per-pixel clip mask: skip if any channel is near-black or near-clipped
            if (r < LOW_CLIP || g < LOW_CLIP || b < LOW_CLIP) continue
            if (r > HIGH_CLIP || g > HIGH_CLIP || b > HIGH_CLIP) continue
            sumR += r.toDouble().pow(p)
            sumG += g.toDouble().pow(p)
            sumB += b.toDouble().pow(p)
            count++
        }
        // Fallback: if everything was clipped, use the whole image unweighted
        if (count == 0L) {
            for (i in 0 until n) {
                sumR += linearPixels[i * 3].toDouble().pow(p)
                sumG += linearPixels[i * 3 + 1].toDouble().pow(p)
                sumB += linearPixels[i * 3 + 2].toDouble().pow(p)
            }
            count = n.toLong()
        }
        val invP = 1.0 / p
        val eR = (sumR / count).pow(invP).toFloat()
        val eG = (sumG / count).pow(invP).toFloat()
        val eB = (sumB / count).pow(invP).toFloat()
        // Normalize so G = 1 → gains = (eG/eR, 1, eG/eB)
        val safeG = max(eG, 1e-6f)
        return Gains(
            r = safeG / max(eR, 1e-6f),
            g = 1f,
            b = safeG / max(eB, 1e-6f),
        )
    }
}
