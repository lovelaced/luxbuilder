package app.luxbuilder.color

import app.luxbuilder.state.CurveChannel
import app.luxbuilder.state.CurvePoint
import kotlin.math.abs

/**
 * Monotonic cubic Hermite spline (Fritsch-Carlson 1980) through the channel's
 * control points, with implicit (0,0) and (1,1) endpoints.
 *
 * Why this and not Bezier? Monotonic Hermite is guaranteed to produce a
 * monotonically-increasing curve in y as x increases — no inversions, no
 * overshoot. For a tone curve where every output must be the user's
 * intentional rendering of an input, this is the right family.
 */
object ToneCurve {

    /**
     * Sample the channel's tone curve to [size] evenly-spaced output values
     * in [0,1]. Output[i] corresponds to input i/(size-1).
     */
    fun sample(channel: CurveChannel, size: Int = 1024): FloatArray {
        val out = FloatArray(size)
        if (channel.points.isEmpty()) {
            // Identity
            for (i in 0 until size) out[i] = i.toFloat() / (size - 1)
            return out
        }

        // Build the full point list with endpoints
        val pts = buildList {
            add(CurvePoint(0f, 0f))
            addAll(channel.points.sortedBy { it.x })
            add(CurvePoint(1f, 1f))
        }.let { mergeNearby(it) }   // collapse points within 1e-4 in x

        val n = pts.size
        val xs = FloatArray(n) { pts[it].x }
        val ys = FloatArray(n) { pts[it].y }

        // Fritsch-Carlson tangents
        val ms = FloatArray(n - 1) { i ->
            (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]).coerceAtLeast(1e-6f)
        }
        val ts = FloatArray(n)
        ts[0] = ms[0]
        ts[n - 1] = ms[n - 2]
        for (i in 1 until n - 1) ts[i] = (ms[i - 1] + ms[i]) * 0.5f

        // Apply monotonicity constraint
        for (i in 0 until n - 1) {
            val m = ms[i]
            if (m == 0f) {
                ts[i] = 0f
                ts[i + 1] = 0f
            } else {
                val a = ts[i] / m
                val b = ts[i + 1] / m
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / kotlin.math.sqrt(s)
                    ts[i] = t * a * m
                    ts[i + 1] = t * b * m
                }
            }
        }

        // Evaluate at each sample point
        var seg = 0
        for (i in 0 until size) {
            val x = i.toFloat() / (size - 1)
            while (seg < n - 1 && x > xs[seg + 1]) seg++
            val x0 = xs[seg]
            val x1 = xs[seg + 1]
            val y0 = ys[seg]
            val y1 = ys[seg + 1]
            val m0 = ts[seg]
            val m1 = ts[seg + 1]
            val h = (x1 - x0).coerceAtLeast(1e-6f)
            val t = ((x - x0) / h).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t
            val h00 =  2f * t3 - 3f * t2 + 1f
            val h10 =        t3 - 2f * t2 + t
            val h01 = -2f * t3 + 3f * t2
            val h11 =        t3 -      t2
            out[i] = (h00 * y0 + h10 * h * m0 + h01 * y1 + h11 * h * m1).coerceIn(0f, 1f)
        }
        return out
    }

    /** Apply a sampled curve to a single normalized input value in [0,1]. */
    fun apply(table: FloatArray, x: Float): Float {
        if (x <= 0f) return table[0]
        if (x >= 1f) return table[table.size - 1]
        val pos = x * (table.size - 1)
        val i = pos.toInt()
        val frac = pos - i
        return table[i] * (1f - frac) + table[i + 1] * frac
    }

    private fun mergeNearby(points: List<CurvePoint>): List<CurvePoint> {
        if (points.size <= 1) return points
        val out = ArrayList<CurvePoint>(points.size)
        out.add(points[0])
        for (i in 1 until points.size) {
            val p = points[i]
            if (kotlin.math.abs(p.x - out.last().x) > 1e-4f) out.add(p)
        }
        return out
    }

    // ──────────── v1.3 extensions for ToneExtractor ────────────

    /**
     * Steffen monotonic cubic interpolation (Steffen 1990, A&A 239:443),
     * sampled to [outSize] points uniformly spaced in x ∈ [xs.first(), xs.last()].
     *
     * Differences from Fritsch-Carlson (the UI interpolator above): Steffen
     * is *exact for parabolae*, gives smoother curves on dense histogram-
     * derived data, and is monotonic by construction with stricter slope
     * limiting at endpoints. Used by [ToneExtractor] for the smoothing pass
     * before knee selection.
     */
    fun steffenResample(xs: FloatArray, ys: FloatArray, outSize: Int): FloatArray {
        require(xs.size == ys.size && xs.size >= 2)
        val n = xs.size
        val h = FloatArray(n - 1) { (xs[it + 1] - xs[it]).coerceAtLeast(1e-6f) }
        val s = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / h[it] }
        val yp = FloatArray(n)

        for (i in 1 until n - 1) {
            val p = (s[i - 1] * h[i] + s[i] * h[i - 1]) / (h[i - 1] + h[i])
            yp[i] = if (s[i - 1] * s[i] <= 0f) 0f
                    else {
                        val sgn = if (p >= 0f) 1f else -1f
                        sgn * minOf(abs(2f * s[i - 1]), abs(2f * s[i]), abs(p))
                    }
        }
        // One-sided slopes at endpoints
        yp[0] = run {
            val p = s[0] * (1f + h[0] / (h[0] + h[1])) - s[1] * h[0] / (h[0] + h[1])
            when {
                p * s[0] <= 0f -> 0f
                abs(p) > 2f * abs(s[0]) -> 2f * s[0]
                else -> p
            }
        }
        yp[n - 1] = run {
            val p = s[n - 2] * (1f + h[n - 2] / (h[n - 2] + h[n - 3])) -
                    s[n - 3] * h[n - 2] / (h[n - 2] + h[n - 3])
            when {
                p * s[n - 2] <= 0f -> 0f
                abs(p) > 2f * abs(s[n - 2]) -> 2f * s[n - 2]
                else -> p
            }
        }

        // Evaluate
        val xMin = xs[0]; val xMax = xs[n - 1]
        val out = FloatArray(outSize)
        var seg = 0
        for (k in 0 until outSize) {
            val t = k.toFloat() / (outSize - 1)
            val x = xMin + t * (xMax - xMin)
            while (seg < n - 1 && x > xs[seg + 1]) seg++
            val si = seg.coerceIn(0, n - 2)
            val u = (x - xs[si]) / h[si]
            val u2 = u * u
            val u3 = u2 * u
            val h00 =  2f * u3 - 3f * u2 + 1f
            val h10 =        u3 - 2f * u2 + u
            val h01 = -2f * u3 + 3f * u2
            val h11 =        u3 -      u2
            out[k] = h00 * ys[si] + h10 * h[si] * yp[si] +
                     h01 * ys[si + 1] + h11 * h[si] * yp[si + 1]
            out[k] = out[k].coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * Pick [k] control points from a densely-sampled curve, suitable for the
     * Fritsch-Carlson UI editor. Returns endpoints + top-(k-2) curvature
     * extrema, with equal-quantile fallback when the curve is near-linear.
     * X-coordinates are snapped to multiples of 1/32 for clean handle
     * positions in the UI.
     */
    fun extractKneesFromCurve(curve: FloatArray, k: Int = 5): List<CurvePoint> {
        require(k >= 2) { "k must be at least 2 (for endpoints)" }
        require(curve.size >= 4) { "curve too short to extract knees" }
        val n = curve.size
        val xs = FloatArray(n) { it.toFloat() / (n - 1) }

        // Central-difference derivatives
        val fp = FloatArray(n)
        val fpp = FloatArray(n)
        for (i in 1 until n - 1) {
            fp[i] = (curve[i + 1] - curve[i - 1]) * 0.5f * (n - 1)
        }
        fp[0] = fp[1]; fp[n - 1] = fp[n - 2]
        for (i in 1 until n - 1) {
            fpp[i] = (curve[i + 1] - 2f * curve[i] + curve[i - 1]) * (n - 1f) * (n - 1f)
        }
        fpp[0] = fpp[1]; fpp[n - 1] = fpp[n - 2]
        val kappa = FloatArray(n) {
            val denom = (1f + fp[it] * fp[it]).let { d -> d * kotlin.math.sqrt(d) }
            abs(fpp[it]) / denom
        }

        // Find local maxima in |κ| on the open interval, sorted by magnitude
        val interiorPeaks = ArrayList<Int>()
        val win = 4
        for (i in win until n - win) {
            var isPeak = true
            for (j in 1..win) {
                if (kappa[i] < kappa[i - j] || kappa[i] < kappa[i + j]) {
                    isPeak = false; break
                }
            }
            if (isPeak) interiorPeaks += i
        }
        interiorPeaks.sortByDescending { kappa[it] }

        val knots = ArrayList<CurvePoint>(k)
        knots += CurvePoint(0f, curve[0])

        val interiorNeeded = k - 2
        val chosen = ArrayList<Float>()
        for (idx in interiorPeaks) {
            if (chosen.size >= interiorNeeded) break
            val x = snapTo32(xs[idx])
            if (chosen.none { abs(it - x) < 1f / 64f }) chosen += x
        }
        // Equal-quantile fallback for missing slots
        if (chosen.size < interiorNeeded) {
            val needed = interiorNeeded - chosen.size
            for (i in 1..needed) {
                val x = snapTo32(i.toFloat() / (interiorNeeded + 1))
                if (chosen.none { abs(it - x) < 1f / 64f }) chosen += x
            }
        }
        chosen.sort()
        for (x in chosen.take(interiorNeeded)) {
            knots += CurvePoint(x, sampleCurve(curve, x))
        }
        knots += CurvePoint(1f, curve[n - 1])
        return knots
    }

    /** Snap an x-coordinate to the nearest multiple of 1/32 (clean UI handles). */
    private fun snapTo32(x: Float): Float =
        (kotlin.math.round(x * 32f) / 32f).coerceIn(1f / 32f, 31f / 32f)

    /** Sample a densely-tabulated curve at fractional x ∈ [0,1] with linear interp. */
    private fun sampleCurve(curve: FloatArray, x: Float): Float {
        if (x <= 0f) return curve[0]
        if (x >= 1f) return curve[curve.size - 1]
        val pos = x * (curve.size - 1)
        val i = pos.toInt()
        val frac = pos - i
        return curve[i] * (1f - frac) + curve[i + 1] * frac
    }
}
