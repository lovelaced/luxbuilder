package app.luxbuilder.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import app.luxbuilder.ui.theme.Lux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.max

/**
 * Hairline-only histogram overlay. Three RGB channel curves drawn subtractively
 * on top of the preview, no fill — pro-tool aesthetic.
 *
 * Sampled from the un-graded bitmap (a cheap, content-only signal).
 * Drawing happens at half resolution sample (every other pixel) for speed;
 * 256 bins per channel.
 */
@Composable
fun HistogramOverlay(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    val colors = Lux.colors
    var histograms by remember(bitmap) { mutableStateOf<Triple<IntArray, IntArray, IntArray>?>(null) }

    LaunchedEffect(bitmap) {
        if (bitmap == null) {
            histograms = null
            return@LaunchedEffect
        }
        histograms = withContext(Dispatchers.Default) { compute(bitmap) }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val (r, g, b) = histograms ?: return@Canvas
        val maxVal = max(max(r.max(), g.max()), b.max()).coerceAtLeast(1)
        val logMax = ln(maxVal.toFloat() + 1f)

        fun pathFor(arr: IntArray, color: Color) {
            val path = Path()
            for (i in arr.indices) {
                // log-scale for visibility — pure linear collapses the upper tail
                val v = ln(arr[i].toFloat() + 1f) / logMax
                val x = i / 255f * size.width
                val y = (1f - v) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 1.5f))
        }
        pathFor(r, colors.signalRed.copy(alpha = 0.7f))
        pathFor(g, colors.signalGreen.copy(alpha = 0.7f))
        pathFor(b, colors.signalBlue.copy(alpha = 0.7f))
    }
}

private fun compute(bitmap: Bitmap): Triple<IntArray, IntArray, IntArray> {
    val r = IntArray(256)
    val g = IntArray(256)
    val b = IntArray(256)
    val w = bitmap.width
    val h = bitmap.height
    val row = IntArray(w)
    for (y in 0 until h step 2) {
        bitmap.getPixels(row, 0, w, 0, y, w, 1)
        var x = 0
        while (x < w) {
            val px = row[x]
            r[(px ushr 16) and 0xFF]++
            g[(px ushr 8) and 0xFF]++
            b[px and 0xFF]++
            x += 2
        }
    }
    return Triple(r, g, b)
}

private fun IntArray.max(): Int {
    var m = 0
    for (v in this) if (v > m) m = v
    return m
}
