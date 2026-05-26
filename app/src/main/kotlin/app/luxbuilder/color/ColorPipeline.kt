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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The full grading pipeline as pure Kotlin functions.
 *
 * v1.3 pipeline order (sRGB-encoded in → sRGB-encoded out):
 *   1. White balance      (linear sRGB von-Kries gains)
 *   2. Contrast           (around midpoint, sRGB-encoded)
 *   3. LGG                (lift → gamma → gain, ASC CDL slope/offset/power)
 *   4. Per-channel R/G/B tone curves   (sRGB-encoded; intentional casts)
 *   5. ─── OKLab cascade (auto-extracted look) ───
 *      a. sRGB → linear sRGB → OKLab
 *      b. Chroma MKL on (a, b) — 2×2 matrix + 2-vector bias
 *      c. Master luma curve on OKLab L
 *      d. Hue-band shifts in OKLCh — 6 bands × (Δh, log Δs, ΔL)
 *      e. HQ-mode residual (if present)
 *      f. OKLab → linear sRGB
 *   6. Saturation, vibrance   (sRGB-encoded)
 *
 * Must mirror [app.luxbuilder.gpu.PipelineShader] exactly — bake matches
 * preview to 1e-4 on identity-state test cases.
 */
object ColorPipeline {

    /**
     * Precomputed lookup tables, one per state. Building the 1D curve tables
     * and the LGG exponents once per state is much cheaper than per-pixel,
     * especially during the 65³ supersample bake.
     */
    class Tables(
        val tone: ToneTables,
        val lggLift: LggAxis,
        val lggGamma: LggAxis,
        val lggGain: LggAxis,
        val hsl: HslPanel,
        val wb: WhiteBalance,
        val basics: Basics,
        val mklChromaMatrix: FloatArray,   // 4 floats (2×2 row-major)
        val mklChromaBias: FloatArray,     // 2 floats
        val mklStrength: Float,
        val hqResidual: FloatArray?,       // 33³ × 3 OKLab Δ, or null
        val hqResidualSize: Int,           // 0 when residual is null
    )

    class ToneTables(
        val luma: FloatArray,
        val red: FloatArray,
        val green: FloatArray,
        val blue: FloatArray,
    )

    fun buildTables(state: LuxState): Tables {
        val residual = state.extractedHqResidual
        val residualSize = if (residual != null) {
            // residual = N³ × 3 floats → N = cbrt(size / 3)
            val n = Math.cbrt((residual.size / 3).toDouble()).toInt()
            require(n * n * n * 3 == residual.size) { "HQ residual size not a perfect cube" }
            n
        } else 0
        return Tables(
            tone = ToneTables(
                luma = ToneCurve.sample(state.tone.luma),
                red = ToneCurve.sample(state.tone.red),
                green = ToneCurve.sample(state.tone.green),
                blue = ToneCurve.sample(state.tone.blue),
            ),
            lggLift = state.lgg.lift, lggGamma = state.lgg.gamma, lggGain = state.lgg.gain,
            hsl = state.hsl, wb = state.wb, basics = state.basics,
            mklChromaMatrix = state.mklChromaMatrix,
            mklChromaBias = state.mklChromaBias,
            mklStrength = state.mklStrength,
            hqResidual = residual,
            hqResidualSize = residualSize,
        )
    }

    /** Apply the full pipeline to a single normalized sRGB-encoded RGB triple. */
    fun apply(t: Tables, r: Float, g: Float, b: Float): FloatArray {
        var rr = r; var gg = g; var bb = b

        // 1. White balance — multiplicative gains in linear sRGB
        if (!t.wb.isNeutral) {
            val rgb = applyWhiteBalance(rr, gg, bb, t.wb)
            rr = rgb[0]; gg = rgb[1]; bb = rgb[2]
        }

        // 2. Contrast around midpoint, sRGB-encoded
        if (t.basics.contrast != 0f) {
            val c = 1f + t.basics.contrast / 100f
            rr = (rr - 0.5f) * c + 0.5f
            gg = (gg - 0.5f) * c + 0.5f
            bb = (bb - 0.5f) * c + 0.5f
        }

        // 3. LGG
        val o1 = applyLgg(rr, gg, bb, t.lggLift)
        val o2 = applyLgg(o1[0], o1[1], o1[2], t.lggGamma)
        val o3 = applyLgg(o2[0], o2[1], o2[2], t.lggGain)
        rr = o3[0]; gg = o3[1]; bb = o3[2]

        // 4. Per-channel R/G/B tone curves (sRGB; intentional cast)
        rr = ToneCurve.apply(t.tone.red,   rr.coerceIn(0f, 1f))
        gg = ToneCurve.apply(t.tone.green, gg.coerceIn(0f, 1f))
        bb = ToneCurve.apply(t.tone.blue,  bb.coerceIn(0f, 1f))

        // 5. ─── OKLab cascade ───
        val lin = floatArrayOf(srgbToLinear(rr), srgbToLinear(gg), srgbToLinear(bb))
        val lab = OkLab.fromLinearSrgb(lin[0], lin[1], lin[2])
        var L = lab[0]; var a = lab[1]; var bChr = lab[2]

        // 5b. Chroma MKL on (a, b), mixed by mklStrength
        if (t.mklStrength > 0f) {
            val mat = t.mklChromaMatrix
            val ma = mat[0] * a + mat[1] * bChr + t.mklChromaBias[0]
            val mb = mat[2] * a + mat[3] * bChr + t.mklChromaBias[1]
            val w = t.mklStrength
            a    = a    * (1f - w) + ma * w
            bChr = bChr * (1f - w) + mb * w
        }

        // 5b'. HQ residual — applied right after chroma MKL since IDT was
        //      fit on post-chroma-MKL source samples. Indexed by raw linear
        //      sRGB cell coords (matches what bakeResidual walked).
        if (t.hqResidual != null && t.hqResidualSize > 0) {
            val linR = srgbToLinear(r.coerceIn(0f, 1f))
            val linG = srgbToLinear(g.coerceIn(0f, 1f))
            val linB = srgbToLinear(b.coerceIn(0f, 1f))
            val res = sampleResidual(t.hqResidual, t.hqResidualSize, linR, linG, linB)
            L    += res[0]
            a    += res[1]
            bChr += res[2]
        }

        // 5c. Master luma curve on OKLab L
        if (!isIdentity(t.tone.luma)) {
            L = ToneCurve.apply(t.tone.luma, L.coerceIn(0f, 1f))
        }

        // 5d. OKLCh hue-band shifts
        val C = sqrt(a * a + bChr * bChr)
        val h = atan2(bChr, a)
        if (C > 1e-6f) {
            var dh = 0f; var ds = 0f; var dl = 0f
            for ((anchorColor, anchorH) in OKLAB_ANCHORS) {
                val anchor = t.hsl.anchors[anchorColor] ?: continue
                if (anchor.hueShift == 0f && anchor.satShift == 0f && anchor.lumaShift == 0f) continue
                val rawD = h - anchorH
                val wrapped = wrapPi(rawD)
                val w0 = max(0f, 1f - abs(wrapped) / (PI.toFloat() / 3f))
                val ww = w0 * w0
                dh += anchor.hueShift  * (PI.toFloat() / 6f) * ww
                ds += anchor.satShift  * SAT_LOG2 * ww
                dl += anchor.lumaShift * LUMA_OKLAB_PER_SLIDER * ww
            }
            val chromaGate = (C / 0.02f).coerceIn(0f, 1f)
            dh *= chromaGate; ds *= chromaGate; dl *= chromaGate
            L += dl
            val cNew = C * exp(ds)
            val hNew = h + dh
            a    = cNew * cos(hNew)
            bChr = cNew * sin(hNew)
        }

        // 5e. OKLab → linear sRGB → sRGB
        val outLin = OkLab.toLinearSrgb(L, a, bChr)
        rr = linearToSrgb(outLin[0].coerceIn(0f, 1f))
        gg = linearToSrgb(outLin[1].coerceIn(0f, 1f))
        bb = linearToSrgb(outLin[2].coerceIn(0f, 1f))

        // 6. Sat/vib
        if (t.basics.saturation != 0f || t.basics.vibrance != 0f) {
            val rgb = applySatVib(rr, gg, bb, t.basics)
            rr = rgb[0]; gg = rgb[1]; bb = rgb[2]
        }

        return floatArrayOf(rr.coerceIn(0f, 1f), gg.coerceIn(0f, 1f), bb.coerceIn(0f, 1f))
    }

    // ───────── shader-matching constants ─────────
    private val SAT_LOG2 = ln(2.0).toFloat()
    private const val LUMA_OKLAB_PER_SLIDER = 0.1f

    /** Match shader anchor angles exactly. */
    private val OKLAB_ANCHORS: List<Pair<HslColor, Float>> = listOf(
        HslColor.ORANGE to 0.5235987756f,  //  30° red-orange
        HslColor.YELLOW to 1.5707963268f,  //  90° yellow
        HslColor.GREEN  to 2.6179938780f,  // 150° green
        HslColor.AQUA   to 3.6651914292f,  // 210° cyan/aqua
        HslColor.BLUE   to 4.7123889804f,  // 270° blue
        HslColor.RED    to 5.7595865316f,  // 330° magenta/red
    )

    private fun wrapPi(x: Float): Float {
        var y = x
        while (y >  PI) y = (y - 2 * PI).toFloat()
        while (y <= -PI) y = (y + 2 * PI).toFloat()
        return y
    }

    /** Trilinear sample of an N³×3 OKLab residual grid at linear sRGB coords. */
    private fun sampleResidual(res: FloatArray, n: Int, r: Float, g: Float, b: Float): FloatArray {
        val s = (n - 1).toFloat()
        val rf = r * s; val gf = g * s; val bf = b * s
        val ri = rf.toInt().coerceIn(0, n - 2); val ru = (rf - ri)
        val gi = gf.toInt().coerceIn(0, n - 2); val gu = (gf - gi)
        val bi = bf.toInt().coerceIn(0, n - 2); val bu = (bf - bi)
        val out = FloatArray(3)
        for (ch in 0..2) {
            fun at(rr: Int, gg: Int, bbb: Int): Float =
                res[((bbb * n + gg) * n + rr) * 3 + ch]
            val c00 = at(ri, gi, bi) * (1 - ru) + at(ri + 1, gi, bi) * ru
            val c01 = at(ri, gi, bi + 1) * (1 - ru) + at(ri + 1, gi, bi + 1) * ru
            val c10 = at(ri, gi + 1, bi) * (1 - ru) + at(ri + 1, gi + 1, bi) * ru
            val c11 = at(ri, gi + 1, bi + 1) * (1 - ru) + at(ri + 1, gi + 1, bi + 1) * ru
            val c0 = c00 * (1 - gu) + c10 * gu
            val c1 = c01 * (1 - gu) + c11 * gu
            out[ch] = c0 * (1 - bu) + c1 * bu
        }
        return out
    }

    // ───────── individual stages ─────────

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
        val lr = srgbToLinear(r) * kr
        var lg = srgbToLinear(g) * kg
        val lb = srgbToLinear(b) * kb
        lg *= (1f - wb.tintOffset / 200f)
        return floatArrayOf(linearToSrgb(lr), linearToSrgb(lg), linearToSrgb(lb))
    }

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

    private fun applySatVib(r: Float, g: Float, b: Float, basics: Basics): FloatArray {
        val y = luma(r, g, b)
        val sat = 1f + basics.saturation / 100f
        var rr = y + (r - y) * sat
        var gg = y + (g - y) * sat
        var bb = y + (b - y) * sat
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

    private fun luma(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun isIdentity(table: FloatArray): Boolean {
        val n = table.size
        for (i in arrayOf(0, n / 4, n / 2, 3 * n / 4, n - 1)) {
            val expected = i.toFloat() / (n - 1)
            if (abs(table[i] - expected) > 1e-5f) return false
        }
        return true
    }

    @Suppress("unused") private val PiUnused = PI
}
