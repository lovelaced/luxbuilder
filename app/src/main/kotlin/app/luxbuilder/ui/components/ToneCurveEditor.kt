package app.luxbuilder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.luxbuilder.color.ToneCurve
import app.luxbuilder.state.CurveChannel
import app.luxbuilder.state.CurvePoint
import app.luxbuilder.state.ToneChannel
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing

/**
 * Interactive tone curve editor.
 *
 * Visual:
 *   - Square Canvas inside surface1, hairline border
 *   - 4×4 grid in strokeHairline, identity diagonal hint
 *   - Curve drawn in the channel's signal color (luma/R/G/B)
 *   - Control points as small filled circles with surface1 donut stroke
 *   - End points (0,0) and (1,1) are implicit and not user-movable
 *
 * Interactions:
 *   - Tap empty area  → add point at touch position
 *   - Drag a point    → move with SegmentTick haptic at gridline crossings
 *   - Long-press      → delete that point (Reject haptic)
 *   - Two-finger long-press anywhere → reset whole channel to identity
 */
@Composable
fun ToneCurveEditor(
    channel: ToneChannel,
    curve: CurveChannel,
    onAddPoint: (CurvePoint) -> Unit,
    onMovePoint: (Int, CurvePoint) -> Unit,
    onRemovePoint: (Int) -> Unit,
    onResetChannel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Lux.colors
    val haptics = Lux.haptics

    val curveColor = when (channel) {
        ToneChannel.LUMA  -> colors.signalLuma
        ToneChannel.RED   -> colors.signalRed
        ToneChannel.GREEN -> colors.signalGreen
        ToneChannel.BLUE  -> colors.signalBlue
    }

    // Pointer state for active drag
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var lastCrossedRow by remember { mutableStateOf(-1) }
    var lastCrossedCol by remember { mutableStateOf(-1) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    fun toNorm(p: Offset): CurvePoint {
        val x = (p.x / canvasSize.width).coerceIn(0f, 1f)
        // y axis is flipped — top is 1.0, bottom is 0.0
        val y = (1f - p.y / canvasSize.height).coerceIn(0f, 1f)
        return CurvePoint(x, y)
    }

    fun toScreen(c: CurvePoint): Offset =
        Offset(c.x * canvasSize.width, (1f - c.y) * canvasSize.height)

    fun hitTest(p: Offset): Int {
        val touchR = 22f  // dp-ish; Canvas works in pixels but at typical density this is the right ballpark
        return curve.points.indexOfFirst { (toScreen(it) - p).getDistance() < touchR }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.surface1)
            .border(LuxSpacing.hairline, colors.stroke.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
            .pointerInput(channel, curve) {
                detectTapGestures(
                    onTap = { offset ->
                        // Tap on a point would be a no-op; tap on empty area adds
                        if (hitTest(offset) < 0) {
                            haptics.detent()
                            onAddPoint(toNorm(offset))
                        }
                    },
                    onLongPress = { offset ->
                        val idx = hitTest(offset)
                        if (idx >= 0) {
                            haptics.reject()
                            onRemovePoint(idx)
                        } else {
                            // Reset whole channel
                            haptics.tapConfirm()
                            onResetChannel()
                        }
                    },
                )
            }
            .pointerInput(channel, curve) {
                detectDragGestures(
                    onDragStart = { offset ->
                        draggingIndex = hitTest(offset)
                        lastCrossedRow = -1
                        lastCrossedCol = -1
                    },
                    onDrag = { change, _ ->
                        val idx = draggingIndex ?: return@detectDragGestures
                        change.consume()
                        val n = toNorm(change.position)
                        val row = (n.y * 4f).toInt()
                        val col = (n.x * 4f).toInt()
                        if (row != lastCrossedRow || col != lastCrossedCol) {
                            if (lastCrossedRow >= 0) haptics.detent()
                            lastCrossedRow = row; lastCrossedCol = col
                        }
                        onMovePoint(idx, n)
                    },
                    onDragEnd = {
                        if (draggingIndex != null) haptics.gestureEnd()
                        draggingIndex = null
                    },
                    onDragCancel = { draggingIndex = null },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            canvasSize = size
            val w = size.width
            val h = size.height

            // Grid: 4 quadrants (3 internal gridlines per axis)
            val gridColor = colors.divider
            for (i in 1..3) {
                val x = w * i / 4f
                val y = h * i / 4f
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            // Identity diagonal hint
            drawLine(
                colors.divider.copy(alpha = 0.4f),
                Offset(0f, h), Offset(w, 0f),
                strokeWidth = 1f,
            )

            // Sample the curve and draw it as a stroked polyline
            val table = ToneCurve.sample(curve, 128)
            val path = Path().apply {
                for (i in table.indices) {
                    val x = i * (w / (table.size - 1))
                    val y = (1f - table[i]) * h
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(path, curveColor, style = Stroke(width = 2.5f))

            // Implicit endpoints (locked, drawn in mid-strength)
            drawCircle(colors.stroke, radius = 5f, center = Offset(0f, h))
            drawCircle(colors.stroke, radius = 5f, center = Offset(w, 0f))

            // User control points
            curve.points.forEach { p ->
                val center = Offset(p.x * w, (1f - p.y) * h)
                // donut: outer matching surface1, inner amber
                drawCircle(colors.surface1, radius = 8f, center = center)
                drawCircle(curveColor, radius = 6f, center = center)
            }
        }
    }
}
