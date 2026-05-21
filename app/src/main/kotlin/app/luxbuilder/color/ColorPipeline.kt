package app.luxbuilder.color

import app.luxbuilder.state.Basics
import app.luxbuilder.state.HslColor
import app.luxbuilder.state.HslPanel
import app.luxbuilder.state.Lgg
import app.luxbuilder.state.LggAxis
import app.luxbuilder.state.LuxState
import app.luxbuilder.state.ToneCurves
import app.luxbuilder.state.WhiteBalance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The full grading pipeline as pure Kotlin functions.
 *
 * Pipeline order (sRGB-encoded input → sRGB-encoded output):
 *   1. White balance      (linearize → von-Kries gain → re-encode)
 *   2. MKL color-match    (3×3 matrix + bias on linear-RGB, weighted by strength)
 *   3. Exposure / contrast (sRGB-encoded around midpoint)
 *   4. LGG: lift → gamma → gain  (ASC CDL slope/offset/power, per-channel)
 *   5. Tone curve         (master luma + per-channel)
 *   6. HSL six-color      (HSV decomposition, hue-windowed)
 *   7. Saturation, vibrance
 *
 * Designed to mirror the AGSL fragment shader in [app.luxbuilder.gpu.PipelineShader].
 * Used directly by [LutBaker] to sample the pipeline at neutral grid points for
 * .cube / .vlt export, and by host-side validation runners.
 */
object ColorPipeline {

    /**
     * Precomputed lookup tables, one per state. Use this when applying the
     * pipeline to many pixels — building the 1D curve tables and the LGG
     * exponents once per state is much cheaper than per-pixel.
     */
    class Tables(
        val tone: ToneTables,
        val lggLift: LggAxis,
        val lggGamma: LggAxis,
        val lggGain: LggAxis,
        val hsl: HslPanel,
        val wb: WhiteBalance,
        val basics: Basics,
        val mklMatrix: FloatArray,
        val mklBias: FloatArray,
        val mklStrength: Float,
    )

    class ToneTables(
        val luma: FloatArray,
        val red: FloatArray,
        val green: FloatArray,
        val blue: FloatArray,
    )

    fun buildTables(state: LuxState): Tables = Tables(
        tone = ToneTables(
            luma = ToneCurve.sample(state.tone.luma),
            red = ToneCurve.sample(state.tone.red),
            green = ToneCurve.sample(state.tone.green),
            blue = ToneCurve.sample(state.tone.blue),
        ),
        lggLift = state.lgg.lift, lggGamma = state.lgg.gamma, lggGain = state.lgg.gain,
        hsl = state.hsl, wb = state.wb, basics = state.basics,
        mklMatrix = state.mklMatrix, mklBias = state.mklBias, mklStrength = state.mklStrength,
    )

    /** Apply the full pipeline to a single normalized sRGB-encoded RGB triple. */
    fun apply(t: Tables, r: Float, g: Float, b: Float): FloatArray {
        var rr = r; var gg = g; var bb = b

        // 1. White balance
        if (!t.wb.isNeutral) {
            val rgb = applyWhiteBalance(rr, gg, bb, t.wb)
            rr = rgb[0]; gg = rgb[1]; bb = rgb[2]
        }

        // 2. MKL color-match (linear-space matrix + bias, with strength fade)
        if (t.mklStrength > 0f) {
            val lr = srgbToLinear(rr); val lg = srgbToLinear(gg); val lb = srgbToLinear(bb)
            val m = t.mklMatrix
            val nr = m[0] * lr + m[1] * lg + m[2] * lb + t.mklBias[0]
            val ng = m[3] * lr + m[4] * lg + m[5] * lb + t.mklBias[1]
            val nb = m[6] * lr + m[7] * lg + m[8] * lb + t.mklBias[2]
            val w = t.mklStrength
            val xr = linearToSrgb(softClamp(lr * (1f - w) + nr * w))
            val xg = linearToSrgb(softClamp(lg * (1f - w) + ng * w))
            val xb = linearToSrgb(softClamp(lb * (1f - w) + nb * w))
            rr = xr; gg = xg; bb = xb
        }

        // 3. Contrast (around midpoint, in sRGB-encoded)
        if (t.basics.contrast != 0f) {
            val c = 1f + t.basics.contrast / 100f
            rr = (rr - 0.5f) * c + 0.5f
            gg = (gg - 0.5f) * c + 0.5f
            bb = (bb - 0.5f) * c + 0.5f
        }

        // 4. LGG — lift, gamma, gain (each applies per-channel ASC CDL)
        val out1 = applyLgg(rr, gg, bb, t.lggLift)
        val out2 = applyLgg(out1[0], out1[1], out1[2], t.lggGamma)
        val out3 = applyLgg(out2[0], out2[1], out2[2], t.lggGain)
        rr = out3[0]; gg = out3[1]; bb = out3[2]

        // 5. Tone curves
        if (!isIdentity(t.tone.luma)) {
            val y = luma(rr, gg, bb)
            val yNew = ToneCurve.apply(t.tone.luma, y.coerceIn(0f, 1f))
            val dy = yNew - y
            rr += dy; gg += dy; bb += dy
        }
        rr = ToneCurve.apply(t.tone.red, rr.coerceIn(0f, 1f))
        gg = ToneCurve.apply(t.tone.green, gg.coerceIn(0f, 1f))
        bb = ToneCurve.apply(t.tone.blue, bb.coerceIn(0f, 1f))

        // 6. HSL six-color
        if (!t.hsl.isNeutral) {
            val rgb = applyHsl(rr, gg, bb, t.hsl)
            rr = rgb[0]; gg = rgb[1]; bb = rgb[2]
        }

        // 7. Saturation + vibrance
        if (t.basics.saturation != 0f || t.basics.vibrance != 0f) {
            val rgb = applySatVib(rr, gg, bb, t.basics)
            rr = rgb[0]; gg = rgb[1]; bb = rgb[2]
        }

        return floatArrayOf(rr.coerceIn(0f, 1f), gg.coerceIn(0f, 1f), bb.coerceIn(0f, 1f))
    }

    // ───────── stage implementations ─────────

    /** ASC CDL: out = (in * slope + offset)^(1/power), per channel, sRGB-encoded space. */
    private fun applyLgg(r: Float, g: Float, b: Float, a: LggAxis): FloatArray {
        if (a.isNeutral) return floatArrayOf(r, g, b)
        fun stage(x: Float, slope: Float, offset: Float, power: Float): Float {
            val v = x * slope + offset
            return if (v <= 0f) 0f else v.pow(1f / power)
        }
        return floatArrayOf(
            stage(r, a.slopeR, a.offsetR, a.powerR),
            stage(g, a.slopeG, a.offsetG, a.powerG),
            stage(b, a.slopeB, a.offsetB, a.powerB),
        )
    }

    /** Tanner Helland Kelvin→RGB approximation + tint, applied as von-Kries gains. */
    private fun applyWhiteBalance(r: Float, g: Float, b: Float, wb: WhiteBalance): FloatArray {
        val k = (6500 + wb.tempOffsetK).coerceIn(1000, 40000)
        val (kr, kg, kb) = kelvinToRgbGains(k)
        // Linearize → apply gains → re-encode. Tint is a green/magenta shift on G.
        val lr = srgbToLinear(r) * kr
        var lg = srgbToLinear(g) * kg
        val lb = srgbToLinear(b) * kb
        lg *= (1f - wb.tintOffset / 200f) // tintOffset -100..+100 maps to ±0.5
        return floatArrayOf(linearToSrgb(lr), linearToSrgb(lg), linearToSrgb(lb))
    }

    /**
     * Tanner Helland's Kelvin → RGB polynomial fit (clamped to typical phot range).
     * Returns the gains relative to 6500K daylight (so 6500 gives (1,1,1)).
     */
    private fun kelvinToRgbGains(k: Int): Triple<Float, Float, Float> {
        val target = kelvinToRgb(k.toDouble())
        val baseline = kelvinToRgb(6500.0)
        return Triple(
            (target.first / baseline.first).toFloat(),
            (target.second / baseline.second).toFloat(),
            (target.third / baseline.third).toFloat(),
        )
    }

    private fun kelvinToRgb(kelvin: Double): Triple<Double, Double, Double> {
        val t = kelvin / 100.0
        val r = if (t <= 66) 255.0 else 329.69873 * (t - 60).pow(-0.13320476)
        val g = if (t <= 66) 99.4708 * kotlin.math.ln(t) - 161.11957
        else 288.12217 * (t - 60).pow(-0.07551485)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.51773 * kotlin.math.ln(t - 10) - 305.0448
        }
        return Triple(
            (r.coerceIn(0.0, 255.0)) / 255.0,
            (g.coerceIn(0.0, 255.0)) / 255.0,
            (b.coerceIn(0.0, 255.0)) / 255.0,
        )
    }

    /** Six-color HSL: per-anchor hue-windowed deltas in HSV space. */
    private fun applyHsl(r: Float, g: Float, b: Float, panel: HslPanel): FloatArray {
        val hsv = rgbToHsv(r, g, b)
        var h = hsv[0]; var s = hsv[1]; var v = hsv[2]

        // Each anchor's center hue (degrees) and ±60° smooth window
        val anchors = listOf(
            HslColor.RED    to 0f,
            HslColor.ORANGE to 30f,
            HslColor.YELLOW to 60f,
            HslColor.GREEN  to 120f,
            HslColor.AQUA   to 180f,
            HslColor.BLUE   to 240f,
        )
        var hShift = 0f
        var sScale = 1f
        var vScale = 1f
        for ((color, anchorH) in anchors) {
            val a = panel.anchors[color] ?: continue
            if (a.hueShift == 0f && a.satShift == 0f && a.lumaShift == 0f) continue
            val d = hueDistance(h, anchorH)
            val w = max(0f, 1f - d / 60f).let { it * it }    // smooth-squared falloff
            hShift += w * a.hueShift * 30f                   // ±1 → ±30°
            sScale *= 1f + w * a.satShift
            vScale *= 1f + w * a.lumaShift
        }
        h = (h + hShift + 360f) % 360f
        s = (s * sScale).coerceIn(0f, 1f)
        v = (v * vScale).coerceIn(0f, 1f)
        return hsvToRgb(h, s, v)
    }

    private fun applySatVib(r: Float, g: Float, b: Float, basics: Basics): FloatArray {
        val y = luma(r, g, b)
        // Saturation: simple luma-mix
        val sat = 1f + basics.saturation / 100f
        var rr = y + (r - y) * sat
        var gg = y + (g - y) * sat
        var bb = y + (b - y) * sat
        // Vibrance: adaptive — boost less-saturated more
        if (basics.vibrance != 0f) {
            val maxC = max(rr, max(gg, bb))
            val minC = min(rr, min(gg, bb))
            val curSat = if (maxC > 0f) (maxC - minC) / maxC else 0f
            val vib = 1f + (basics.vibrance / 100f) * (1f - curSat)
            rr = y + (rr - y) * vib
            gg = y + (gg - y) * vib
            bb = y + (bb - y) * vib
        }
        return floatArrayOf(rr, gg, bb)
    }

    // ───────── helpers ─────────

    fun srgbToLinear(x: Float): Float =
        if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)

    fun linearToSrgb(x: Float): Float =
        if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f

    /** Soft clamp via exponential rolloff near 1 (and mirror near 0). */
    private fun softClamp(x: Float): Float = when {
        x <= 0f -> 0f
        x >= 1f -> 1f - (1f - 1f / (1f + (x - 1f) * 4f)) * 0.0001f   // tiny; just keeps it < 1
        else -> x
    }

    private fun luma(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun isIdentity(table: FloatArray): Boolean {
        // table[i] should equal i / (size - 1) within fp tolerance
        val n = table.size
        for (i in arrayOf(0, n / 4, n / 2, 3 * n / 4, n - 1)) {
            val expected = i.toFloat() / (n - 1)
            if (abs(table[i] - expected) > 1e-5f) return false
        }
        return true
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else    -> 60f * (((r - g) / d) + 4f)
        }
        val hh = (h + 360f) % 360f
        val s = if (mx == 0f) 0f else d / mx
        return floatArrayOf(hh, s, mx)
    }

    fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
        val c = v * s
        val hp = h / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r, g, b) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        return floatArrayOf(r + m, g + m, b + m)
    }

    // Silence unused-import warning in some toolchains
    @Suppress("unused") private val PiUnused = PI
}
