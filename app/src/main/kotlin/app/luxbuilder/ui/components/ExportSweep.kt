package app.luxbuilder.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxMotion
import app.luxbuilder.ui.theme.LuxSpacing
import kotlinx.coroutines.delay

/**
 * Signature export-success animation. A 1.5px amber hairline sweeps
 * left → right across the entire screen over 900ms, depositing the
 * destination path in monospace as it crosses the bottom. Both the
 * line and the deposited text fade out together over 800ms after the
 * sweep completes.
 *
 * Place at the root of the Compose tree (inside the theme provider)
 * so it overlays everything else.
 *
 * @param key — changes every time we want a new sweep to play. The
 *   parent should bump this on each successful export.
 */
@Composable
fun ExportSweep(
    key: Any?,
    destinationLine: String?,
    modifier: Modifier = Modifier,
) {
    if (key == null || destinationLine == null) return

    val colors = Lux.colors
    val sweep = remember(key) { Animatable(0f) }
    val fade = remember(key) { Animatable(1f) }

    LaunchedEffect(key) {
        sweep.snapTo(0f)
        fade.snapTo(1f)
        sweep.animateTo(
            1f,
            animationSpec = tween(
                durationMillis = LuxMotion.DurExportSweep,
                easing = LuxMotion.EaseExportSweep,
            ),
        )
        delay(120)
        fade.animateTo(
            0f,
            animationSpec = tween(
                durationMillis = 800,
                easing = LuxMotion.EaseAmberFade,
            ),
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        // The vertical 1.5dp amber hairline traversing the screen
        Canvas(modifier = Modifier.fillMaxSize()) {
            val x = sweep.value * size.width
            drawLine(
                color = Color(0xFFE8B23A).copy(alpha = fade.value),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }

        // Deposited destination line near the bottom. Reveals one char at a
        // time in sync with the sweep position so the text emerges as the
        // hairline passes overhead.
        val charCount = (sweep.value * destinationLine.length).toInt().coerceAtLeast(0)
        val revealed = destinationLine.take(charCount)
        Text(
            text = "EXPORTED · $revealed",
            style = Lux.type.displayNumeric,
            color = colors.accent.copy(alpha = fade.value),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    horizontal = LuxSpacing.lg,
                    vertical = LuxSpacing.xxl,
                ),
        )
    }
}
