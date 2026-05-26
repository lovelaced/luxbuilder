package app.luxbuilder.color

import kotlin.math.max
import kotlin.math.pow

/**
 * ACES 1.3 Reference Gamut Compression (RGC).
 *
 * Hard-clipping out-of-gamut colors at LUT bake time produces visible
 * terracing on saturated reds and blues — the classic "skin tone falls off
 * a cliff" failure mode. RGC smoothly squeezes super-saturated colors into
 * the target gamut while preserving (a) hue along the per-channel
 * "distance-from-max" axis, (b) the achromatic gray axis, and (c) exposure
 * invariance.
 *
 * Math (per pixel, operates in linear-light RGB):
 *
 *   A = max(R, G, B)                          (achromatic axis)
 *   d_c = (A − c) / A                         (per-channel distance from axis)
 *   if d_c < threshold_c:  d'_c = d_c         (untouched "zone of trust")
 *   else:                  d'_c = threshold_c +
 *                                   (d_c − threshold_c)
 *                                   / (1 + ((d_c − threshold_c) / s_c)^p)^(1/p)
 *   c' = A − d'_c · A
 *
 * Scale factor s_c ensures d'_c (limit_c) = 1.0:
 *   s_c = (limit_c − threshold_c)
 *         / (((1 − threshold_c) / (limit_c − threshold_c))^(−p) − 1)^(1/p)
 *
 * luxbuilder ships **sRGB-tuned** defaults — narrower primaries than ACES
 * AP1 so we use tighter thresholds than the ACES standard:
 *
 *   limit     = (1.10, 1.20, 1.25)   (C, M, Y directions)
 *   threshold = (0.85, 0.85, 0.85)
 *   power     = 1.2
 *
 * References:
 *  - ACES Reference Gamut Compression Specification (2022) —
 *    docs.acescentral.com/rgc/specification
 *  - AMPAS gamut mapping VWG reference repo (DCTL + Python)
 */
object GamutCompress {

    private val LIMIT     = floatArrayOf(1.10f, 1.20f, 1.25f)
    private val THRESHOLD = floatArrayOf(0.85f, 0.85f, 0.85f)
    private const val POWER = 1.2f

    // Precomputed scale factors per channel: s_c
    private val SCALE: FloatArray = FloatArray(3) { i ->
        val t = THRESHOLD[i]; val l = LIMIT[i]
        ((l - t) / (((1f - t) / (l - t)).pow(-POWER) - 1f).pow(1f / POWER))
    }

    /**
     * Apply RGC to a single linear-sRGB triple in place. Returns the
     * (possibly compressed) triple. Negative inputs are clamped to 0 first
     * — RGC operates on display-referred non-negative linear light.
     */
    fun apply(rgbLinear: FloatArray): FloatArray {
        require(rgbLinear.size == 3)
        val r = max(rgbLinear[0], 0f)
        val g = max(rgbLinear[1], 0f)
        val b = max(rgbLinear[2], 0f)
        val a = max(r, max(g, b))
        if (a <= 1e-9f) return floatArrayOf(0f, 0f, 0f)
        // Per-channel distance from achromatic axis
        val dr = (a - r) / a; val dg = (a - g) / a; val db = (a - b) / a
        val drp = compress(dr, 0)
        val dgp = compress(dg, 1)
        val dbp = compress(db, 2)
        return floatArrayOf(a - drp * a, a - dgp * a, a - dbp * a)
    }

    /** Apply RGC to a flat 3-float buffer in place (faster — avoids allocation). */
    fun applyInPlace(rgb: FloatArray, offset: Int = 0) {
        val r = max(rgb[offset], 0f)
        val g = max(rgb[offset + 1], 0f)
        val b = max(rgb[offset + 2], 0f)
        val a = max(r, max(g, b))
        if (a <= 1e-9f) {
            rgb[offset] = 0f; rgb[offset + 1] = 0f; rgb[offset + 2] = 0f
            return
        }
        rgb[offset]     = a - compress((a - r) / a, 0) * a
        rgb[offset + 1] = a - compress((a - g) / a, 1) * a
        rgb[offset + 2] = a - compress((a - b) / a, 2) * a
    }

    private fun compress(d: Float, channel: Int): Float {
        val t = THRESHOLD[channel]
        if (d < t) return d
        val s = SCALE[channel]
        val excess = d - t
        return t + excess / (1f + (excess / s).pow(POWER)).pow(1f / POWER)
    }
}
