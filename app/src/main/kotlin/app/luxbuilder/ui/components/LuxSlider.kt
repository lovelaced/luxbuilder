package app.luxbuilder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A photographer-tool slider. Full-width, hairline-only track with optional
 * colored gradient background (for temperature / tint), centered zero detent
 * with `centerDetent` haptic, edge-stop ticks at min/max.
 *
 * The thumb is a 16dp pill in F2F2F3 with a 1px shadow ring — the same look
 * as dsqueez's segmented control selection. No fill bar; the value-from-zero
 * is implied by an amber 1px line connecting thumb to centerline.
 */
@Composable
fun LuxSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueFormatter: (Float) -> String = { "%+d".format(it.roundToInt()) },
    tickEvery: Float = 5f,     // haptic detent every N units
    trackGradient: Brush? = null,
) {
    val colors = Lux.colors
    val haptics = Lux.haptics

    var lastTick by remember { mutableIntStateOf(((value - range.start) / tickEvery).toInt()) }
    var atEdge by remember { mutableIntStateOf(0) }
    var trackWidth by remember { mutableFloatStateOf(0f) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LuxSpacing.xs),
        ) {
            Text(label, style = Lux.type.labelMono, color = colors.textSecondary)
            Spacer(modifier = Modifier.weight(1f))
            Text(valueFormatter(value), style = Lux.type.readoutSm, color = colors.textPrimary)
        }
        Spacer(modifier = Modifier.height(LuxSpacing.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(LuxSpacing.touch)
                .pointerInput(range) {
                    detectHorizontalDragGestures(
                        onDragStart = { /* nothing */ },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val w = trackWidth.coerceAtLeast(1f)
                            val frac = (change.position.x / w).coerceIn(0f, 1f)
                            val raw = range.start + frac * (range.endInclusive - range.start)
                            val span = range.endInclusive - range.start
                            val mid = (range.start + range.endInclusive) / 2f

                            // Center-detent: gentle snap near 0 (or midpoint)
                            val snapped = if (abs(raw - mid) < span * 0.01f) {
                                if (abs(value - mid) > 0.001f) haptics.centerDetent()
                                mid
                            } else raw

                            // Gridline detents
                            val tick = ((snapped - range.start) / tickEvery).toInt()
                            if (tick != lastTick) {
                                haptics.detent()
                                lastTick = tick
                            }

                            // Edge-stop
                            val newAtEdge = when {
                                snapped <= range.start + 0.001f -> -1
                                snapped >= range.endInclusive - 0.001f -> 1
                                else -> 0
                            }
                            if (newAtEdge != 0 && newAtEdge != atEdge) haptics.edgeStop()
                            atEdge = newAtEdge

                            onValueChange(snapped)
                        },
                        onDragEnd = { haptics.gestureEnd() },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(LuxSpacing.touch)) {
                trackWidth = size.width
                val cy = size.height / 2f
                val mid = (range.start + range.endInclusive) / 2f
                val span = range.endInclusive - range.start
                val frac = ((value - range.start) / span).coerceIn(0f, 1f)
                val thumbX = frac * size.width

                // Track (with optional gradient)
                val trackTop = cy - 2.dp.toPx()
                val trackBot = cy + 2.dp.toPx()
                if (trackGradient != null) {
                    drawRect(
                        brush = trackGradient,
                        topLeft = Offset(0f, trackTop),
                        size = Size(size.width, trackBot - trackTop),
                    )
                } else {
                    drawRect(
                        color = colors.divider,
                        topLeft = Offset(0f, cy - 0.5.dp.toPx()),
                        size = Size(size.width, 1.dp.toPx()),
                    )
                }

                // Center tick (1dp amber notch at midpoint)
                val midX = ((mid - range.start) / span) * size.width
                drawLine(
                    color = colors.accent,
                    start = Offset(midX, cy - 6.dp.toPx()),
                    end = Offset(midX, cy + 6.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )

                // Amber fill from midpoint to thumb (sign indicator)
                if (abs(value - mid) > 0.001f) {
                    drawLine(
                        color = colors.accent,
                        start = Offset(midX, cy),
                        end = Offset(thumbX, cy),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }

                // Thumb pill — 16dp tall, 5dp wide
                val thumbW = 5.dp.toPx()
                val thumbH = 16.dp.toPx()
                drawRoundRect(
                    color = colors.textPrimary,
                    topLeft = Offset(thumbX - thumbW / 2f, cy - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                )
                // 1px dark contrast ring
                drawRoundRect(
                    color = Color(0xFF0A0A0B),
                    topLeft = Offset(thumbX - thumbW / 2f, cy - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}
