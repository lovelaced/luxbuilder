package app.luxbuilder.gpu

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import app.luxbuilder.color.ColorPipeline
import app.luxbuilder.color.ToneCurve
import app.luxbuilder.state.HslAnchor
import app.luxbuilder.state.HslColor
import app.luxbuilder.state.LggAxis
import app.luxbuilder.state.LuxState
import app.luxbuilder.state.WhiteBalance
import kotlin.math.PI
import kotlin.math.ln

/**
 * Builds and binds uniforms on a [RuntimeShader] from the current [LuxState].
 *
 * v1.3 changes:
 *   - Replaced legacy 3×3 MKL uniforms (uMklRow0/1/2, uMklBias) with the
 *     chroma-only pair (uMklChromaMat: vec4, uMklChromaBias: vec2).
 *   - HSL anchors now pack OKLab-domain shifts (hueShiftRad, logSatScale,
 *     lumaShift) instead of HSV-domain (hueShiftDeg, satScale, valScale).
 *   - The tone-curve bitmap row 0 (master luma) is now sampled in OKLab L
 *     space inside the shader, not as a sRGB-luma delta.
 */
@RequiresApi(33)
class ShaderUniforms(val shader: RuntimeShader) {

    private var curveBitmap: Bitmap? = null

    fun bind(state: LuxState) {
        // 1. WB — precompute Kelvin gains relative to 6500K daylight neutral
        val gains = computeWbGains(state.wb)
        shader.setFloatUniform("uWbGains", gains[0], gains[1], gains[2])

        // 2. Chroma MKL (2×2 + 2-vector bias)
        shader.setFloatUniform("uMklStrength", state.mklStrength)
        val m = state.mklChromaMatrix
        shader.setFloatUniform("uMklChromaMat", m[0], m[1], m[2], m[3])
        shader.setFloatUniform("uMklChromaBias", state.mklChromaBias[0], state.mklChromaBias[1])

        // 3. Contrast
        shader.setFloatUniform("uContrast", 1f + state.basics.contrast / 100f)

        // 4. LGG — pack each axis into three vec3s
        bindLgg("uLift",  state.lgg.lift)
        bindLgg("uGamma", state.lgg.gamma)
        bindLgg("uGain",  state.lgg.gain)

        // 5. Tone curves — all four channels packed into a 1024×4 RGBA bitmap.
        //    Row 0 (master luma) is now sampled in OKLab L space; rows 1-3
        //    (per-channel R/G/B) stay in sRGB-encoded space.
        val toneTables = ColorPipeline.buildTables(state).tone
        val (bitmap, hasFlags) = buildCurveBitmap(
            toneTables.luma, toneTables.red, toneTables.green, toneTables.blue
        )
        curveBitmap = bitmap
        shader.setInputShader("toneCurve", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("uHasCurve", hasFlags[0], hasFlags[1], hasFlags[2], hasFlags[3])

        // 6. HSL anchors — pack in OKLab units: (hueShiftRad, logSatScale, lumaShift).
        //    Slider ranges (−1..+1) calibrated to feel natural:
        //      hueShift  ±1  → ±30° = ±π/6 rad
        //      satShift  ±1  → ×½..×2  = ±ln 2
        //      lumaShift ±1  → ±0.1 OKLab L
        bindHslAnchor("uHsl_red",    state.hsl.anchors[HslColor.RED]    ?: HslAnchor())
        bindHslAnchor("uHsl_orange", state.hsl.anchors[HslColor.ORANGE] ?: HslAnchor())
        bindHslAnchor("uHsl_yellow", state.hsl.anchors[HslColor.YELLOW] ?: HslAnchor())
        bindHslAnchor("uHsl_green",  state.hsl.anchors[HslColor.GREEN]  ?: HslAnchor())
        bindHslAnchor("uHsl_aqua",   state.hsl.anchors[HslColor.AQUA]   ?: HslAnchor())
        bindHslAnchor("uHsl_blue",   state.hsl.anchors[HslColor.BLUE]   ?: HslAnchor())

        // 7. Saturation / vibrance
        shader.setFloatUniform("uSaturation", 1f + state.basics.saturation / 100f)
        shader.setFloatUniform("uVibrance", state.basics.vibrance / 100f)
    }

    private fun bindLgg(prefix: String, axis: LggAxis) {
        shader.setFloatUniform("${prefix}_slope",  axis.slopeR,  axis.slopeG,  axis.slopeB)
        shader.setFloatUniform("${prefix}_offset", axis.offsetR, axis.offsetG, axis.offsetB)
        shader.setFloatUniform("${prefix}_power",  axis.powerR,  axis.powerG,  axis.powerB)
    }

    private val hueSliderToRad = (PI.toFloat() / 6f)
    private val satSliderToLog = ln(2.0).toFloat()
    private val lumaSliderToOkLabL = 0.1f

    private fun bindHslAnchor(name: String, a: HslAnchor) {
        shader.setFloatUniform(
            name,
            a.hueShift  * hueSliderToRad,
            a.satShift  * satSliderToLog,
            a.lumaShift * lumaSliderToOkLabL,
        )
    }

    private fun computeWbGains(wb: WhiteBalance): FloatArray {
        if (wb.isNeutral) return floatArrayOf(1f, 1f, 1f)
        val r = ColorPipeline.srgbToLinear(0.5f)
        val sampled = sampleProbe(wb)
        return floatArrayOf(sampled[0] / r, sampled[1] / r, sampled[2] / r)
    }

    private fun sampleProbe(wb: WhiteBalance): FloatArray {
        val pipe = ColorPipeline.buildTables(LuxState(wb = wb))
        val out = ColorPipeline.apply(pipe, 0.5f, 0.5f, 0.5f)
        return floatArrayOf(
            ColorPipeline.srgbToLinear(out[0]),
            ColorPipeline.srgbToLinear(out[1]),
            ColorPipeline.srgbToLinear(out[2]),
        )
    }

    /**
     * Pack four 1024-sample curve tables into a 1024×4 ARGB_8888 bitmap, one
     * row per channel in (luma, R, G, B) order. The shader samples the red
     * channel of each pixel; all RGB channels of a given pixel encode the
     * same value, so the shader sees scalar curve data.
     *
     * Returns the bitmap plus a 4-float identity-flag array (1.0 if the curve
     * actively transforms the channel, 0.0 if it's identity within tolerance).
     */
    private fun buildCurveBitmap(
        luma: FloatArray,
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
    ): Pair<Bitmap, FloatArray> {
        val w = luma.size
        val bmp = Bitmap.createBitmap(w, 4, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * 4)
        val rows = arrayOf(luma, red, green, blue)
        val flags = FloatArray(4)
        for (row in 0..3) {
            val table = rows[row]
            var identity = true
            for (i in 0 until w) {
                val expected = i.toFloat() / (w - 1)
                if (kotlin.math.abs(table[i] - expected) > 1e-4f) identity = false
                val v = (table[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[row * w + i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            flags[row] = if (identity) 0f else 1f
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, 4)
        return bmp to flags
    }

    @Suppress("unused") private val ToneCurveUnused = ToneCurve  // import keep
}
