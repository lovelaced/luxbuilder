package app.luxbuilder.color

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic sampling from a Gaussian color distribution in OKLab — used to
 * generate "the average natural source photo's pixel statistics" for stages
 * that need pixel samples rather than just (μ, Σ): hue-band extraction,
 * IDT refinement, and any future per-pixel residual calculations.
 *
 * Sampling is via the standard 3-D multivariate normal generator:
 *   z ~ N(0, I_3) via Box-Muller
 *   x = μ + L·z   where L is the Cholesky factor of Σ
 *
 * Deterministic per call via a fixed seed so cascade runs are reproducible.
 */
object SyntheticSource {

    private const val SEED = 0xD15EA5EDL

    /**
     * Draw [n] OKLab samples from N(μ, Σ) and return them interleaved as
     * (L, a, b, L, a, b, …) — the same layout [HueBandExtractor] consumes.
     */
    fun sample(n: Int, mu: FloatArray, sigma: Array<FloatArray>): FloatArray {
        require(mu.size == 3)
        require(sigma.size == 3 && sigma[0].size == 3)
        val l = OkMat.cholesky3(sigma)
        val out = FloatArray(n * 3)
        val rng = Random(SEED)
        for (i in 0 until n) {
            val z = boxMuller3(rng)
            // x = mu + L·z   (L is lower-triangular)
            val x = floatArrayOf(
                mu[0] + l[0][0] * z[0],
                mu[1] + l[1][0] * z[0] + l[1][1] * z[1],
                mu[2] + l[2][0] * z[0] + l[2][1] * z[1] + l[2][2] * z[2],
            )
            out[i * 3]     = x[0]
            out[i * 3 + 1] = x[1]
            out[i * 3 + 2] = x[2]
        }
        return out
    }

    /** Three N(0, 1) draws via Box-Muller. */
    private fun boxMuller3(rng: Random): FloatArray {
        val u1 = rng.nextFloat().coerceAtLeast(1e-7f)
        val u2 = rng.nextFloat()
        val u3 = rng.nextFloat().coerceAtLeast(1e-7f)
        val u4 = rng.nextFloat()
        val r1 = sqrt(-2f * ln(u1))
        val r2 = sqrt(-2f * ln(u3))
        return floatArrayOf(
            (r1 * cos(2f * Math.PI.toFloat() * u2)),
            (r1 * kotlin.math.sin(2f * Math.PI.toFloat() * u2)),
            (r2 * cos(2f * Math.PI.toFloat() * u4)),
        )
    }

    /**
     * Apply a chroma-only MKL transform to interleaved (L, a, b) samples
     * **in place** would mutate the buffer — instead returns a new buffer
     * with L preserved and (a, b) mapped through `(matrix, bias)`.
     */
    fun applyChroma(
        samples: FloatArray,
        chromaMatrix: FloatArray,
        chromaBias: FloatArray,
        sourceAbMean: FloatArray,
    ): FloatArray {
        require(samples.size % 3 == 0)
        require(chromaMatrix.size == 4 && chromaBias.size == 2)
        require(sourceAbMean.size == 2)
        val out = FloatArray(samples.size)
        val n = samples.size / 3
        for (i in 0 until n) {
            out[i * 3] = samples[i * 3]  // L preserved
            val a0 = samples[i * 3 + 1] - sourceAbMean[0]
            val b0 = samples[i * 3 + 2] - sourceAbMean[1]
            out[i * 3 + 1] = chromaMatrix[0] * a0 + chromaMatrix[1] * b0 + chromaBias[0]
            out[i * 3 + 2] = chromaMatrix[2] * a0 + chromaMatrix[3] * b0 + chromaBias[1]
        }
        return out
    }
}
