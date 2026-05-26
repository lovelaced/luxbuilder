package app.luxbuilder.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OKLab — Björn Ottosson's perceptually-uniform color space (2020).
 *
 * Source: https://bottosson.github.io/posts/oklab/
 *
 * luxbuilder uses OKLab as the working space for distribution-matching, tone
 * curve extraction, hue-band manipulation, and the GPU preview shader. Wins
 * over CIELAB measured by Ottosson against the Ebner-Fairchild and CAM16
 * datasets:
 *
 *  - Hue uniformity     0.49 vs 0.69 RMS  (1.4× better)
 *  - Chroma uniformity  0.81 vs 1.84 RMS  (2.3× better)
 *  - Lightness          0.20 vs 1.70 RMS  (3.6× better)
 *
 * The key practical consequence: a Gaussian stretched along OKLab's b-axis
 * does not bend blues toward purple the way CIELAB does. For reference-driven
 * grading this is the most visible artifact CIELAB would have inherited on
 * skies and shadows.
 *
 * Pipeline (luxbuilder applies these in [ColorPipeline] / GPU shader):
 *   sRGB-encoded ↔ linear sRGB         (sRGB EOTF/OETF)
 *   linear sRGB → LMS                  (matrix M1)
 *   LMS → LMS' via sign-preserving cube root
 *   LMS' → Lab                         (matrix M2)
 *
 * The sign-preserving cube root is critical for round-trip stability when
 * intermediate values land outside the sRGB gamut — which happens during
 * MKL/IDT stretches that pull pixels temporarily into negative linear-RGB.
 */
object OkLab {

    // ─── M1: linear sRGB → LMS ───
    private const val M1_00 = 0.4122214708f; private const val M1_01 = 0.5363325363f; private const val M1_02 = 0.0514459929f
    private const val M1_10 = 0.2119034982f; private const val M1_11 = 0.6806995451f; private const val M1_12 = 0.1073969566f
    private const val M1_20 = 0.0883024619f; private const val M1_21 = 0.2817188376f; private const val M1_22 = 0.6299787005f

    // ─── M2: LMS' → Lab ───
    private const val M2_00 =  0.2104542553f; private const val M2_01 =  0.7936177850f; private const val M2_02 = -0.0040720468f
    private const val M2_10 =  1.9779984951f; private const val M2_11 = -2.4285922050f; private const val M2_12 =  0.4505937099f
    private const val M2_20 =  0.0259040371f; private const val M2_21 =  0.7827717662f; private const val M2_22 = -0.8086757660f

    // ─── M2⁻¹: Lab → LMS' (Ottosson) ───
    // l' = L + 0.3963377774·a + 0.2158037573·b
    // m' = L − 0.1055613458·a − 0.0638541728·b
    // s' = L − 0.0894841775·a − 1.2914855480·b
    private const val INV_M2_LA =  0.3963377774f; private const val INV_M2_LB =  0.2158037573f
    private const val INV_M2_MA = -0.1055613458f; private const val INV_M2_MB = -0.0638541728f
    private const val INV_M2_SA = -0.0894841775f; private const val INV_M2_SB = -1.2914855480f

    // ─── M1⁻¹: LMS → linear sRGB ───
    private const val INV_M1_00 =  4.0767416621f; private const val INV_M1_01 = -3.3077115913f; private const val INV_M1_02 =  0.2309699292f
    private const val INV_M1_10 = -1.2684380046f; private const val INV_M1_11 =  2.6097574011f; private const val INV_M1_12 = -0.3413193965f
    private const val INV_M1_20 = -0.0041960863f; private const val INV_M1_21 = -0.7034186147f; private const val INV_M1_22 =  1.7076147010f

    /** Sign-preserving cube root. Required for negative LMS values that occur OOG. */
    private fun cbrt(x: Float): Float {
        if (x == 0f) return 0f
        val r = Math.cbrt(abs(x).toDouble()).toFloat()
        return if (x < 0f) -r else r
    }

    /** Linear-sRGB triple → OKLab triple (L, a, b). */
    fun fromLinearSrgb(r: Float, g: Float, b: Float): FloatArray {
        val lL = M1_00 * r + M1_01 * g + M1_02 * b
        val lM = M1_10 * r + M1_11 * g + M1_12 * b
        val lS = M1_20 * r + M1_21 * g + M1_22 * b
        val l_ = cbrt(lL); val m_ = cbrt(lM); val s_ = cbrt(lS)
        val L = M2_00 * l_ + M2_01 * m_ + M2_02 * s_
        val A = M2_10 * l_ + M2_11 * m_ + M2_12 * s_
        val B = M2_20 * l_ + M2_21 * m_ + M2_22 * s_
        return floatArrayOf(L, A, B)
    }

    /** OKLab triple → linear-sRGB triple. */
    fun toLinearSrgb(L: Float, A: Float, B: Float): FloatArray {
        val l_ = L + INV_M2_LA * A + INV_M2_LB * B
        val m_ = L + INV_M2_MA * A + INV_M2_MB * B
        val s_ = L + INV_M2_SA * A + INV_M2_SB * B
        val lL = l_ * l_ * l_; val lM = m_ * m_ * m_; val lS = s_ * s_ * s_
        val r = INV_M1_00 * lL + INV_M1_01 * lM + INV_M1_02 * lS
        val g = INV_M1_10 * lL + INV_M1_11 * lM + INV_M1_12 * lS
        val b = INV_M1_20 * lL + INV_M1_21 * lM + INV_M1_22 * lS
        return floatArrayOf(r, g, b)
    }

    /** sRGB-encoded triple → OKLab. Goes through linearization first. */
    fun fromSrgb(r: Float, g: Float, b: Float): FloatArray =
        fromLinearSrgb(
            ColorPipeline.srgbToLinear(r),
            ColorPipeline.srgbToLinear(g),
            ColorPipeline.srgbToLinear(b),
        )

    /** OKLab → sRGB-encoded triple. Goes through linear sRGB → gamma encode. */
    fun toSrgb(L: Float, A: Float, B: Float): FloatArray {
        val lin = toLinearSrgb(L, A, B)
        return floatArrayOf(
            ColorPipeline.linearToSrgb(lin[0]),
            ColorPipeline.linearToSrgb(lin[1]),
            ColorPipeline.linearToSrgb(lin[2]),
        )
    }

    /** OKLab → OKLCh. h in radians, range (-π, π]. C is unbounded. */
    fun toLCh(L: Float, A: Float, B: Float): FloatArray =
        floatArrayOf(L, sqrt(A * A + B * B), atan2(B, A))

    /** OKLCh → OKLab. h interpreted as radians. */
    fun fromLCh(L: Float, C: Float, h: Float): FloatArray =
        floatArrayOf(L, C * cos(h), C * sin(h))
}
