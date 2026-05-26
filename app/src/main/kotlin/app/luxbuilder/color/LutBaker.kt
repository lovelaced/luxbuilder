package app.luxbuilder.color

import app.luxbuilder.state.LuxState

/**
 * Bake the current [LuxState] grading pipeline into a 3D LUT by sampling at
 * uniformly-spaced neutral grid points.
 *
 * Index order: R fastest, then G, then B (matches both `.cube` and `.vlt`
 * conventions and `glTexImage3D` ordering). Output is a flat float array of
 * length `size * size * size * 3` with values in sRGB-encoded [0,1].
 *
 * IMPORTANT: divisor is (size - 1), not size. A 17-grid LUT samples at
 * r/16 for r in 0..16. Getting this wrong introduces a slight cast at
 * identity.
 */
object LutBaker {

    /** A baked 3D LUT. [size] is the grid resolution (17 for .vlt, 33 for .cube). */
    class Lut(val size: Int, val data: FloatArray) {
        init { require(data.size == size * size * size * 3) }

        fun at(r: Int, g: Int, b: Int): FloatArray {
            val i = ((b * size + g) * size + r) * 3
            return floatArrayOf(data[i], data[i + 1], data[i + 2])
        }

        companion object {
            /** Build an identity LUT at the given size — useful for sanity tests. */
            fun identity(size: Int): Lut {
                val arr = FloatArray(size * size * size * 3)
                val denom = (size - 1).toFloat()
                for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                    val i = ((b * size + g) * size + r) * 3
                    arr[i] = r / denom
                    arr[i + 1] = g / denom
                    arr[i + 2] = b / denom
                }
                return Lut(size, arr)
            }
        }
    }

    /** Bake the full pipeline at the requested grid size. */
    fun bake(state: LuxState, size: Int): Lut {
        val tables = ColorPipeline.buildTables(state)
        val denom = (size - 1).toFloat()
        val data = FloatArray(size * size * size * 3)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val rgb = ColorPipeline.apply(tables, r / denom, g / denom, b / denom)
            val i = ((b * size + g) * size + r) * 3
            data[i] = rgb[0]; data[i + 1] = rgb[1]; data[i + 2] = rgb[2]
        }
        return Lut(size, data)
    }

    /**
     * Supersample bake at 65³ → ACES 1.3 RGC → integer decimate to [finalSize].
     *
     * The supersample step eliminates the trilinear-interpolation banding that
     * a direct 33³ or 17³ bake produces on smooth gradients (sky, skin). 65
     * is chosen because `(33-1)·2+1 = (17-1)·4+1 = 65`, giving exact-stride
     * decimation to both target sizes without resampling artifacts.
     *
     * The bake walks `r / 64, g / 64, b / 64` cell centers, applies the
     * pipeline (returns sRGB-encoded), linearizes for [GamutCompress.apply],
     * then re-encodes sRGB. Finally takes every `64 / (finalSize - 1)`-th
     * cell for the target grid.
     */
    fun bakeSupersampled(state: LuxState, finalSize: Int): Lut {
        require(finalSize == 17 || finalSize == 33) {
            "bakeSupersampled supports finalSize 17 or 33 only (got $finalSize)"
        }
        val superSize = 65
        val tables = ColorPipeline.buildTables(state)
        val denom = (superSize - 1).toFloat()
        val sup = FloatArray(superSize * superSize * superSize * 3)
        val tmp = FloatArray(3)
        for (b in 0 until superSize) for (g in 0 until superSize) for (r in 0 until superSize) {
            val rgb = ColorPipeline.apply(tables, r / denom, g / denom, b / denom)
            // sRGB → linear → RGC → sRGB
            tmp[0] = ColorPipeline.srgbToLinear(rgb[0])
            tmp[1] = ColorPipeline.srgbToLinear(rgb[1])
            tmp[2] = ColorPipeline.srgbToLinear(rgb[2])
            GamutCompress.applyInPlace(tmp)
            val i = ((b * superSize + g) * superSize + r) * 3
            sup[i]     = ColorPipeline.linearToSrgb(tmp[0].coerceIn(0f, 1f))
            sup[i + 1] = ColorPipeline.linearToSrgb(tmp[1].coerceIn(0f, 1f))
            sup[i + 2] = ColorPipeline.linearToSrgb(tmp[2].coerceIn(0f, 1f))
        }
        return decimate(sup, superSize, finalSize)
    }

    /** Pull every stride-th sample from a flat super-grid into a smaller LUT. */
    private fun decimate(superData: FloatArray, superSize: Int, finalSize: Int): Lut {
        val stride = (superSize - 1) / (finalSize - 1)
        check((finalSize - 1) * stride + 1 == superSize) {
            "decimation requires exact integer stride; superSize=$superSize finalSize=$finalSize"
        }
        val out = FloatArray(finalSize * finalSize * finalSize * 3)
        for (b in 0 until finalSize) for (g in 0 until finalSize) for (r in 0 until finalSize) {
            val srcR = r * stride
            val srcG = g * stride
            val srcB = b * stride
            val srcI = ((srcB * superSize + srcG) * superSize + srcR) * 3
            val dstI = ((b * finalSize + g) * finalSize + r) * 3
            out[dstI]     = superData[srcI]
            out[dstI + 1] = superData[srcI + 1]
            out[dstI + 2] = superData[srcI + 2]
        }
        return Lut(finalSize, out)
    }
}
