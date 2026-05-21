package app.luxbuilder.color

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Monge-Kantorovich Linearization (Pitié & Kokaram 2007).
 *
 * Closed-form 3×3 color transfer: given source distribution (μ_s, Σ_s) and
 * target distribution (μ_t, Σ_t), find the unique linear map T such that
 * T(X_s) has the same mean and covariance as X_t. Operates in linear-RGB.
 *
 *     T = Σ_s^(-1/2) · (Σ_s^(1/2) · Σ_t · Σ_s^(1/2))^(1/2) · Σ_s^(-1/2)
 *     f(x) = T · (x - μ_s) + μ_t
 *
 * Output as (matrix: 9 floats row-major, bias: 3 floats) for direct upload to
 * the AGSL shader's `mat3 + vec3` uniforms.
 */
object Mkl {

    data class Transform(val matrix: FloatArray, val bias: FloatArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Transform) return false
            return matrix.contentEquals(other.matrix) && bias.contentEquals(other.bias)
        }
        override fun hashCode(): Int = 31 * matrix.contentHashCode() + bias.contentHashCode()
    }

    /** Identity transform — no shift, no scale. */
    val Identity = Transform(
        matrix = floatArrayOf(1f, 0f, 0f,  0f, 1f, 0f,  0f, 0f, 1f),
        bias = floatArrayOf(0f, 0f, 0f),
    )

    /**
     * Compute T and bias from source mean/covariance and target mean/covariance.
     * All inputs in linear-RGB. Returns matrix in row-major order.
     */
    fun solve(
        muSrc: FloatArray, sigmaSrc: Array<FloatArray>,
        muTgt: FloatArray, sigmaTgt: Array<FloatArray>,
    ): Transform {
        require(muSrc.size == 3 && muTgt.size == 3)
        require(sigmaSrc.size == 3 && sigmaTgt.size == 3)

        val ssHalf    = matrixSqrt(sigmaSrc)
        val ssInvHalf = matrixInverse(ssHalf)
        val inner     = matrixSqrt(matmul(matmul(ssHalf, sigmaTgt), ssHalf))
        val t         = matmul(matmul(ssInvHalf, inner), ssInvHalf)

        // bias = mu_t - T · mu_s
        val bias = FloatArray(3) { i ->
            muTgt[i] - (t[i][0] * muSrc[0] + t[i][1] * muSrc[1] + t[i][2] * muSrc[2])
        }

        val flat = FloatArray(9)
        for (i in 0..2) for (j in 0..2) flat[i * 3 + j] = t[i][j]
        return Transform(matrix = flat, bias = bias)
    }

    // ───────── small symmetric-3×3 linear algebra ─────────
    //
    // Σ matrices in this app are always symmetric positive semi-definite
    // (they're covariances). We use Jacobi eigendecomposition — for 3×3 it's
    // simple, stable, and produces orthonormal eigenvectors directly. Then
    // matrix sqrt and inverse are trivial in the eigenbasis.

    private fun matmul(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val r = Array(3) { FloatArray(3) }
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += a[i][k] * b[k][j]
            r[i][j] = s
        }
        return r
    }

    private fun transpose(a: Array<FloatArray>): Array<FloatArray> {
        val r = Array(3) { FloatArray(3) }
        for (i in 0..2) for (j in 0..2) r[i][j] = a[j][i]
        return r
    }

    /**
     * Jacobi diagonalization for a 3×3 symmetric matrix. Returns (V, D) where
     * V is the orthogonal eigenvector matrix (columns are eigenvectors) and
     * D is the diagonal eigenvalue array.
     */
    private fun jacobi(aIn: Array<FloatArray>): Pair<Array<FloatArray>, FloatArray> {
        val a = Array(3) { aIn[it].copyOf() }
        val v = Array(3) { i -> FloatArray(3) { j -> if (i == j) 1f else 0f } }
        val maxSweeps = 50
        for (sweep in 0 until maxSweeps) {
            var off = 0f
            for (p in 0..1) for (q in p + 1..2) off += a[p][q] * a[p][q]
            if (off < 1e-12f) break
            for (p in 0..1) for (q in p + 1..2) {
                val apq = a[p][q]
                if (abs(apq) < 1e-12f) continue
                val theta = (a[q][q] - a[p][p]) / (2f * apq)
                val t = if (abs(theta) > 1e6f) 0.5f / theta
                else (if (theta >= 0f) 1f else -1f) / (abs(theta) + sqrt(1f + theta * theta))
                val c = 1f / sqrt(1f + t * t)
                val s = t * c
                val tau = s / (1f + c)
                val app = a[p][p]; val aqq = a[q][q]
                a[p][p] = app - t * apq
                a[q][q] = aqq + t * apq
                a[p][q] = 0f; a[q][p] = 0f
                for (i in 0..2) if (i != p && i != q) {
                    val aip = a[i][p]; val aiq = a[i][q]
                    a[i][p] = aip - s * (aiq + tau * aip)
                    a[p][i] = a[i][p]
                    a[i][q] = aiq + s * (aip - tau * aiq)
                    a[q][i] = a[i][q]
                }
                for (i in 0..2) {
                    val vip = v[i][p]; val viq = v[i][q]
                    v[i][p] = c * vip - s * viq
                    v[i][q] = s * vip + c * viq
                }
            }
        }
        val d = FloatArray(3) { a[it][it] }
        return v to d
    }

    /** Matrix square root: V · diag(√d) · Vᵀ. */
    private fun matrixSqrt(m: Array<FloatArray>): Array<FloatArray> {
        val (v, d) = jacobi(m)
        val dSqrt = FloatArray(3) { sqrt(max(d[it], 0f)) }
        val vd = Array(3) { i -> FloatArray(3) { j -> v[i][j] * dSqrt[j] } }
        return matmul(vd, transpose(v))
    }

    /** Inverse via SVD-style: V · diag(1/d) · Vᵀ for symmetric matrices. */
    private fun matrixInverse(m: Array<FloatArray>): Array<FloatArray> {
        val (v, d) = jacobi(m)
        val dInv = FloatArray(3) { if (abs(d[it]) > 1e-8f) 1f / d[it] else 0f }
        val vd = Array(3) { i -> FloatArray(3) { j -> v[i][j] * dInv[j] } }
        return matmul(vd, transpose(v))
    }

    @Suppress("unused") private val SinUnused = sin(0.0)
    @Suppress("unused") private val CosUnused = cos(0.0)
}
