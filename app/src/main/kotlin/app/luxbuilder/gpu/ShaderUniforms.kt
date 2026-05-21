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
import app.luxbuilder.state.HslPanel
import app.luxbuilder.state.LggAxis
import app.luxbuilder.state.LuxState
import app.luxbuilder.state.WhiteBalance

/**
 * Builds and binds uniforms on a [RuntimeShader] from the current [LuxState].
 *
 * The tone curve and Kelvin→RGB gains are precomputed on the host so the
 * shader stays pure-arithmetic; uniforms ship to the GPU via setFloatUniform
 * (and a single setInputShader for the curve bitmap).
 */
@RequiresApi(33)
class ShaderUniforms(val shader: RuntimeShader) {

    private var curveBitmap: Bitmap? = null

    fun bind(state: LuxState) {
        // 1. WB — precompute Kelvin gains relative to 6500K daylight neutral
        val gains = computeWbGains(state.wb)
        shader.setFloatUniform("uWbGains", gains[0], gains[1], gains[2])

        // 2. MKL
        shader.setFloatUniform("uMklStrength", state.mklStrength)
        val m = state.mklMatrix
        shader.setFloatUniform("uMklRow0", m[0], m[1], m[2])
        shader.setFloatUniform("uMklRow1", m[3], m[4], m[5])
        shader.setFloatUniform("uMklRow2", m[6], m[7], m[8])
        shader.setFloatUniform("uMklBias", state.mklBias[0], state.mklBias[1], state.mklBias[2])

        // 3. Contrast
        shader.setFloatUniform("uContrast", 1f + state.basics.contrast / 100f)

        // 4. LGG — pack each axis into three vec3s
        bindLgg("uLift",  state.lgg.lift)
        bindLgg("uGamma", state.lgg.gamma)
        bindLgg("uGain",  state.lgg.gain)

        // 5. Tone curve — luma channel only in v1
        val curve = ColorPipeline.buildTables(state).tone.luma
        val (bitmap, isIdentity) = buildCurveBitmap(curve)
        curveBitmap = bitmap
        shader.setInputShader("toneCurve", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("uHasToneCurve", if (isIdentity) 0f else 1f)

        // 6. HSL anchors — pack as 6 vec3s (hueShiftDeg, satScale, valScale)
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

    private fun bindHslAnchor(name: String, a: HslAnchor) {
        // hueShiftDeg = ±30°, satScale = ±1 normalized, valScale = ±1 normalized.
        // The shader treats y/z as additive scale-offsets (e.g. 0 = neutral, +1 = 2× sat).
        shader.setFloatUniform(name, a.hueShift * 30f, a.satShift, a.lumaShift)
    }

    private fun computeWbGains(wb: WhiteBalance): FloatArray {
        if (wb.isNeutral) return floatArrayOf(1f, 1f, 1f)
        // Reuse the public Kelvin→RGB approximation by sampling at midgray.
        // ColorPipeline.applyWhiteBalance does this in one shot; we extract the
        // gains by applying it to a linearized 1.0 grey and reading back.
        val mid = 0.5f
        val out = floatArrayOf(mid, mid, mid)
        // Use public sRGB helpers + the Kelvin gains routine indirectly:
        // apply to a known linear-1 sample, then undo.
        val r = ColorPipeline.srgbToLinear(0.5f)
        // We need the raw gains; reverse-engineer via a sample probe:
        val sampled = sampleProbe(wb)
        out[0] = sampled[0] / r
        out[1] = sampled[1] / r
        out[2] = sampled[2] / r
        return out
    }

    private fun sampleProbe(wb: WhiteBalance): FloatArray {
        // Apply the host pipeline's WB to a known linear sample and read the
        // result back. This guarantees the shader uses bit-identical gains.
        // Use a single midgray pixel; the gains are independent of value.
        val pipe = ColorPipeline.buildTables(
            app.luxbuilder.state.LuxState(wb = wb)
        )
        val out = ColorPipeline.apply(pipe, 0.5f, 0.5f, 0.5f)
        // Convert back to linear for use as gains
        return floatArrayOf(
            ColorPipeline.srgbToLinear(out[0]),
            ColorPipeline.srgbToLinear(out[1]),
            ColorPipeline.srgbToLinear(out[2]),
        )
    }

    /**
     * Pack the 1024-sample luma curve into a 1024×1 ALPHA_8 bitmap. The shader
     * samples this as a BitmapShader and reads the red channel (which equals
     * alpha for ALPHA_8). Returns (bitmap, isIdentity).
     */
    private fun buildCurveBitmap(table: FloatArray): Pair<Bitmap, Boolean> {
        val bmp = Bitmap.createBitmap(table.size, 1, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(table.size)
        var identity = true
        for (i in table.indices) {
            val expected = i.toFloat() / (table.size - 1)
            if (kotlin.math.abs(table[i] - expected) > 1e-4f) identity = false
            val v = (table[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            // pack v into all three RGB channels + opaque alpha
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(pixels, 0, table.size, 0, 0, table.size, 1)
        return bmp to identity
    }

    @Suppress("unused") private val ToneCurveUnused = ToneCurve  // import keep
}
