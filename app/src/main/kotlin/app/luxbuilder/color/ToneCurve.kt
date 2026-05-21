package app.luxbuilder.color

import app.luxbuilder.state.CurveChannel
import app.luxbuilder.state.CurvePoint

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
}
