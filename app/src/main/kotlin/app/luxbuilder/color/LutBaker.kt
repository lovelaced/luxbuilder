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
}
