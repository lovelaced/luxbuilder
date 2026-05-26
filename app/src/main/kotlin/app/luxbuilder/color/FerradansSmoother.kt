package app.luxbuilder.color

import kotlin.math.exp

/**
 * Spatial smoothing on an IDT residual grid in OKLab.
 *
 * Ferradans, Papadakis, Peyré, Aujol (SIIMS 2014, "Regularized Discrete
 * Optimal Transport") add a smoothness term to OT-based color transfer
 * so nearby source colors map to nearby target colors. Without it, the
 * baked LUT inherits IDT's pixel-irregular transport plan and shows
 * chroma noise on smooth gradients (skies, skin).
 *
 * For a dense 33³ residual grid (the output of [Idt] evaluated at grid
 * points), the spatial-smoothness term reduces to a 3D Gaussian on the
 * grid — equivalent to Ferradans's λ-weighted Laplacian smoothing but
 * O(N) per pass via separable convolution.
 *
 * Default σ = 0.7 cells (≈ 2 grid spacings full-width-half-max), λ=0.05
 * mapping. Conservative — preserves the IDT signal while killing
 * single-cell discontinuities.
 */
object FerradansSmoother {

    private const val DEFAULT_SIGMA_CELLS = 0.7f

    /**
     * Smooth a 33³ × 3 OKLab residual in place via separable 3D Gaussian.
     *
     * @param residual  flat [gridSize³ × 3] array, indexed by
     *                  ((b·N + g)·N + r)·3 + channel
     * @param gridSize  cubic grid edge length (e.g. 33 for cube, 17 for vlt)
     * @param sigmaCells  Gaussian σ in grid-cell units
     */
    fun smoothGrid(
        residual: FloatArray,
        gridSize: Int,
        sigmaCells: Float = DEFAULT_SIGMA_CELLS,
    ) {
        require(residual.size == gridSize * gridSize * gridSize * 3) {
            "residual size mismatch for gridSize=$gridSize"
        }
        if (sigmaCells <= 0f) return

        val radius = (3f * sigmaCells).toInt().coerceAtLeast(1)
        val ker = FloatArray(2 * radius + 1)
        val invTwoSigSq = 1f / (2f * sigmaCells * sigmaCells)
        var ks = 0f
        for (i in -radius..radius) {
            val v = exp(-(i * i) * invTwoSigSq)
            ker[i + radius] = v; ks += v
        }
        for (i in ker.indices) ker[i] /= ks

        // Three separable passes (X, Y, Z), three channels each. Work via
        // a single scratch buffer.
        val tmp = FloatArray(residual.size)
        for (ch in 0..2) {
            blurAlongX(residual, tmp, gridSize, ch, ker, radius)
            System.arraycopy(tmp, 0, residual, 0, residual.size)
            blurAlongY(residual, tmp, gridSize, ch, ker, radius)
            System.arraycopy(tmp, 0, residual, 0, residual.size)
            blurAlongZ(residual, tmp, gridSize, ch, ker, radius)
            System.arraycopy(tmp, 0, residual, 0, residual.size)
        }
    }

    private fun blurAlongX(
        src: FloatArray, dst: FloatArray, N: Int, ch: Int,
        ker: FloatArray, radius: Int,
    ) {
        // Copy src → dst then overwrite the target channel
        System.arraycopy(src, 0, dst, 0, src.size)
        for (b in 0 until N) for (g in 0 until N) {
            for (r in 0 until N) {
                var v = 0f
                for (k in -radius..radius) {
                    val rr = (r + k).coerceIn(0, N - 1)
                    val idx = ((b * N + g) * N + rr) * 3 + ch
                    v += src[idx] * ker[k + radius]
                }
                dst[((b * N + g) * N + r) * 3 + ch] = v
            }
        }
    }

    private fun blurAlongY(
        src: FloatArray, dst: FloatArray, N: Int, ch: Int,
        ker: FloatArray, radius: Int,
    ) {
        System.arraycopy(src, 0, dst, 0, src.size)
        for (b in 0 until N) for (r in 0 until N) {
            for (g in 0 until N) {
                var v = 0f
                for (k in -radius..radius) {
                    val gg = (g + k).coerceIn(0, N - 1)
                    val idx = ((b * N + gg) * N + r) * 3 + ch
                    v += src[idx] * ker[k + radius]
                }
                dst[((b * N + g) * N + r) * 3 + ch] = v
            }
        }
    }

    private fun blurAlongZ(
        src: FloatArray, dst: FloatArray, N: Int, ch: Int,
        ker: FloatArray, radius: Int,
    ) {
        System.arraycopy(src, 0, dst, 0, src.size)
        for (g in 0 until N) for (r in 0 until N) {
            for (b in 0 until N) {
                var v = 0f
                for (k in -radius..radius) {
                    val bb = (b + k).coerceIn(0, N - 1)
                    val idx = ((bb * N + g) * N + r) * 3 + ch
                    v += src[idx] * ker[k + radius]
                }
                dst[((b * N + g) * N + r) * 3 + ch] = v
            }
        }
    }
}
