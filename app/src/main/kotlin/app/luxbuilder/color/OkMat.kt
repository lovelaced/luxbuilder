package app.luxbuilder.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Small symmetric-matrix linear algebra used across luxbuilder's color
 * statistics — covariances are always SPD, so Jacobi eigendecomposition
 * is the right tool: simple, stable on tiny matrices, produces orthonormal
 * eigenvectors directly. Matrix sqrt and inverse fall out trivially in
 * the eigenbasis.
 *
 * Extracted from [Mkl]'s private helpers so [RobustAggregator],
 * [Idt], and [HueBandExtractor] can share them without duplication.
 *
 * Conventions:
 *   - 3×3 matrices stored as Array<FloatArray> with [row][col] indexing
 *   - 2×2 matrices stored as flat FloatArray(4) in row-major [r00, r01, r10, r11]
 *   - Vectors stored as FloatArray
 */
object OkMat {

    // ─── 3×3 ───

    fun matmul3(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val r = Array(3) { FloatArray(3) }
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += a[i][k] * b[k][j]
            r[i][j] = s
        }
        return r
    }

    fun matvec3(a: Array<FloatArray>, v: FloatArray): FloatArray = floatArrayOf(
        a[0][0] * v[0] + a[0][1] * v[1] + a[0][2] * v[2],
        a[1][0] * v[0] + a[1][1] * v[1] + a[1][2] * v[2],
        a[2][0] * v[0] + a[2][1] * v[1] + a[2][2] * v[2],
    )

    fun transpose3(a: Array<FloatArray>): Array<FloatArray> {
        val r = Array(3) { FloatArray(3) }
        for (i in 0..2) for (j in 0..2) r[i][j] = a[j][i]
        return r
    }

    fun identity3(): Array<FloatArray> = arrayOf(
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f),
    )

    fun copy3(a: Array<FloatArray>): Array<FloatArray> =
        Array(3) { a[it].copyOf() }

    fun trace3(a: Array<FloatArray>): Float = a[0][0] + a[1][1] + a[2][2]

    /** Add b to a (in place). */
    fun addInPlace3(a: Array<FloatArray>, b: Array<FloatArray>) {
        for (i in 0..2) for (j in 0..2) a[i][j] += b[i][j]
    }

    /** Scale matrix by scalar (in place). */
    fun scaleInPlace3(a: Array<FloatArray>, s: Float) {
        for (i in 0..2) for (j in 0..2) a[i][j] *= s
    }

    /**
     * Jacobi diagonalization for a 3×3 symmetric matrix. Returns (V, D) where
     * V is the orthogonal eigenvector matrix (columns are eigenvectors) and
     * D is the diagonal eigenvalue array.
     *
     * Max 50 sweeps; converges quadratically once the off-diagonal mass is
     * small. For 3×3 SPD covariances this is essentially always 3-6 sweeps.
     */
    fun jacobi3(aIn: Array<FloatArray>): Pair<Array<FloatArray>, FloatArray> {
        val a = copy3(aIn)
        val v = identity3()
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

    /** 3×3 symmetric matrix square root via Jacobi: V · diag(√d) · Vᵀ. */
    fun matrixSqrt3(m: Array<FloatArray>): Array<FloatArray> {
        val (v, d) = jacobi3(m)
        val dSqrt = FloatArray(3) { sqrt(max(d[it], 0f)) }
        val vd = Array(3) { i -> FloatArray(3) { j -> v[i][j] * dSqrt[j] } }
        return matmul3(vd, transpose3(v))
    }

    /** 3×3 symmetric matrix inverse via Jacobi: V · diag(1/d) · Vᵀ. */
    fun matrixInverse3(m: Array<FloatArray>): Array<FloatArray> {
        val (v, d) = jacobi3(m)
        val dInv = FloatArray(3) { if (abs(d[it]) > 1e-8f) 1f / d[it] else 0f }
        val vd = Array(3) { i -> FloatArray(3) { j -> v[i][j] * dInv[j] } }
        return matmul3(vd, transpose3(v))
    }

    /**
     * 3×3 Cholesky (lower triangular L such that L·Lᵀ = m), for Mahalanobis
     * whitening of 3-D color vectors. Assumes SPD input; clamps near-zero
     * diagonal entries to avoid sqrt(-eps).
     */
    fun cholesky3(m: Array<FloatArray>): Array<FloatArray> {
        val l = Array(3) { FloatArray(3) }
        l[0][0] = sqrt(max(m[0][0], 0f))
        l[1][0] = if (l[0][0] > 1e-12f) m[1][0] / l[0][0] else 0f
        l[1][1] = sqrt(max(m[1][1] - l[1][0] * l[1][0], 0f))
        l[2][0] = if (l[0][0] > 1e-12f) m[2][0] / l[0][0] else 0f
        l[2][1] = if (l[1][1] > 1e-12f) (m[2][1] - l[2][0] * l[1][0]) / l[1][1] else 0f
        l[2][2] = sqrt(max(m[2][2] - l[2][0] * l[2][0] - l[2][1] * l[2][1], 0f))
        return l
    }

    /** Solve lower-triangular L·x = b. */
    fun solveLower3(l: Array<FloatArray>, b: FloatArray): FloatArray {
        val x = FloatArray(3)
        x[0] = if (l[0][0] > 1e-12f) b[0] / l[0][0] else 0f
        x[1] = if (l[1][1] > 1e-12f) (b[1] - l[1][0] * x[0]) / l[1][1] else 0f
        x[2] = if (l[2][2] > 1e-12f) (b[2] - l[2][0] * x[0] - l[2][1] * x[1]) / l[2][2] else 0f
        return x
    }

    /** Frobenius norm of a 3×3 matrix. */
    fun frobenius3(a: Array<FloatArray>): Float {
        var s = 0f
        for (i in 0..2) for (j in 0..2) s += a[i][j] * a[i][j]
        return sqrt(s)
    }

    // ─── 2×2 (flat [r00, r01, r10, r11]) ───

    /** 2×2 matrix multiply: a · b. */
    fun matmul2(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[0] * b[0] + a[1] * b[2],   a[0] * b[1] + a[1] * b[3],
        a[2] * b[0] + a[3] * b[2],   a[2] * b[1] + a[3] * b[3],
    )

    /** 2×2 matrix × vector: a · v. */
    fun matvec2(a: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        a[0] * v[0] + a[1] * v[1],
        a[2] * v[0] + a[3] * v[1],
    )

    /**
     * Closed-form 2×2 symmetric matrix square root.
     *
     *   A^½ = (A + √det A · I) / √(tr A + 2√det A)
     *
     * Valid when det A ≥ 0 and the denominator is positive (always true for
     * SPD A with non-degenerate trace). No eigendecomposition needed.
     */
    fun matrixSqrt2(m: FloatArray): FloatArray {
        val det = m[0] * m[3] - m[1] * m[2]
        val s = sqrt(max(det, 0f))
        val tr = m[0] + m[3]
        val t = sqrt(max(tr + 2f * s, 1e-12f))
        return floatArrayOf(
            (m[0] + s) / t, m[1] / t,
            m[2] / t,       (m[3] + s) / t,
        )
    }

    /** 2×2 inverse: 1/det · [d, -b, -c, a]. Returns identity if det≈0. */
    fun matrixInverse2(m: FloatArray): FloatArray {
        val det = m[0] * m[3] - m[1] * m[2]
        if (abs(det) < 1e-12f) return floatArrayOf(1f, 0f, 0f, 1f)
        val inv = 1f / det
        return floatArrayOf( m[3] * inv, -m[1] * inv, -m[2] * inv,  m[0] * inv)
    }

    /** Trace of a 2×2 flat matrix. */
    fun trace2(m: FloatArray): Float = m[0] + m[3]

    /** Identity 2×2. */
    fun identity2(): FloatArray = floatArrayOf(1f, 0f, 0f, 1f)
}
