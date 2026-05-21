package app.luxbuilder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.luxbuilder.state.Lgg
import app.luxbuilder.state.LggAxis
import app.luxbuilder.state.LggStage
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The full LGG panel: three color wheels in a row (lift / gamma / gain).
 *
 * Each wheel emits a [LggAxis] derived from the disc thumb position and outer
 * ring offset. Wheel semantics per stage:
 *   - LIFT  → ASC offset (additive)
 *   - GAMMA → ASC power (multiplicative on 1/power); ring controls overall gamma
 *   - GAIN  → ASC slope (multiplicative)
 *
 * The disc position is HSL-style: angle = hue, radius = strength. The outer
 * ring is a luma offset (per-channel uniform). Reset gestures: double-tap
 * disc center clears tint, double-tap ring clears luma, triple-tap clears
 * the whole axis.
 */
@Composable
fun LggPanel(
    lgg: Lgg,
    onChange: (LggStage, LggAxis) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LuxSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(LuxSpacing.md),
    ) {
        LggWheel(
            stage = LggStage.LIFT,
            label = "LIFT",
            axis = lgg.lift,
            onChange = { onChange(LggStage.LIFT, it) },
            modifier = Modifier.weight(1f),
        )
        LggWheel(
            stage = LggStage.GAMMA,
            label = "GAMMA",
            axis = lgg.gamma,
            onChange = { onChange(LggStage.GAMMA, it) },
            modifier = Modifier.weight(1f),
        )
        LggWheel(
            stage = LggStage.GAIN,
            label = "GAIN",
            axis = lgg.gain,
            onChange = { onChange(LggStage.GAIN, it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LggWheel(
    stage: LggStage,
    label: String,
    axis: LggAxis,
    onChange: (LggAxis) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Lux.colors
    val haptics = Lux.haptics

    // Decompose the current axis into (tintX, tintY ∈ [-1,1], lumaOffset ∈ [-1,1])
    val (tintX, tintY, luma) = remember(axis, stage) { decomposeAxis(stage, axis) }
    var dragMode by remember { mutableStateOf<DragMode?>(null) }
    var size by remember { mutableStateOf(Size.Zero) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = Lux.type.labelMono,
            color = if (!axis.isNeutral) colors.textPrimary else colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(LuxSpacing.sm))
        Box(
            modifier = Modifier
                .size(116.dp)
                .pointerInput(stage) {
                    detectTapGestures(
                        onDoubleTap = { p ->
                            val mode = hitTest(p, size)
                            haptics.tapConfirm()
                            when (mode) {
                                DragMode.RING -> onChange(setLuma(stage, axis, 0f))
                                DragMode.DISC -> onChange(setTint(stage, axis, 0f, 0f))
                                null -> Unit
                            }
                        },
                    )
                }
                .pointerInput(stage) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragMode = hitTest(offset, size)
                            applyAt(offset, size, dragMode, stage, axis, onChange, haptics, true)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            applyAt(change.position, size, dragMode, stage, axis, onChange, haptics, false)
                        },
                        onDragEnd = {
                            if (dragMode != null) haptics.gestureEnd()
                            dragMode = null
                        },
                        onDragCancel = { dragMode = null },
                    )
                },
        ) {
            Canvas(modifier = Modifier.size(116.dp)) {
                size = this.size
                drawWheel(size, tintX, tintY, luma, colors)
            }
        }
        Spacer(modifier = Modifier.height(LuxSpacing.xs))
        // Readout: per-channel tint (in deltas) and luma offset
        Text(
            text = formatReadout(stage, axis),
            style = Lux.type.readoutSm,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ──────────── Wheel rendering ────────────

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWheel(
    size: Size,
    tintX: Float,
    tintY: Float,
    luma: Float,
    colors: app.luxbuilder.ui.theme.LuxColors,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val outerR = size.minDimension / 2f
    val ringInner = outerR - 16f      // ring is 16px thick

    // Outer ring background (hairline)
    drawCircle(
        color = colors.divider,
        radius = outerR,
        center = center,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
    )
    drawCircle(
        color = colors.divider,
        radius = ringInner,
        center = center,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
    )

    // Outer ring fill — arc from top, sweep proportional to luma offset
    if (luma != 0f) {
        val sweep = luma * 180f   // ±100% maps to ±half circle
        val startAngle = -90f - if (sweep < 0) -sweep else 0f
        drawArc(
            color = colors.accent,
            startAngle = startAngle,
            sweepAngle = if (sweep >= 0) sweep else -sweep,
            useCenter = false,
            topLeft = Offset(center.x - (outerR + ringInner) / 2f - 8f, center.y - (outerR + ringInner) / 2f - 8f),
            size = Size((outerR + ringInner) + 16f, (outerR + ringInner) + 16f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx()),
        )
    }
    // Tick at top of ring (neutral position)
    val tickStart = center + Offset(0f, -outerR)
    val tickEnd = center + Offset(0f, -ringInner)
    drawLine(
        color = colors.accent,
        start = tickStart,
        end = tickEnd,
        strokeWidth = 1.dp.toPx(),
    )

    // Disc face — radial gradient: amber-tinged outer fading to surface4 center
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colors.surface4, Color(0x33FFAA00)),
            center = center,
            radius = ringInner - 4f,
        ),
        radius = ringInner - 4f,
        center = center,
    )
    // Disc hairline border
    drawCircle(
        color = colors.stroke,
        radius = ringInner - 4f,
        center = center,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
    )

    // Center crosshair (faint)
    drawLine(
        color = colors.divider,
        start = center + Offset(-4.dp.toPx(), 0f),
        end = center + Offset(4.dp.toPx(), 0f),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = colors.divider,
        start = center + Offset(0f, -4.dp.toPx()),
        end = center + Offset(0f, 4.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )

    // Thumb position from tint
    val maxR = ringInner - 12f
    val thumbX = center.x + tintX * maxR
    val thumbY = center.y + tintY * maxR
    drawCircle(
        color = Color(0xFFF2F2F3),
        radius = 6.dp.toPx(),
        center = Offset(thumbX, thumbY),
    )
    drawCircle(
        color = Color(0xFF0A0A0B),
        radius = 6.dp.toPx(),
        center = Offset(thumbX, thumbY),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
    )
}

// ──────────── Gesture state machine ────────────

private enum class DragMode { DISC, RING }

private fun hitTest(p: Offset, s: Size): DragMode? {
    if (s.width == 0f) return null
    val center = Offset(s.width / 2f, s.height / 2f)
    val r = hypot((p.x - center.x).toDouble(), (p.y - center.y).toDouble()).toFloat()
    val outerR = s.minDimension / 2f
    val ringInner = outerR - 16f
    return when {
        r > outerR -> null
        r < ringInner - 4f -> DragMode.DISC
        else -> DragMode.RING
    }
}

private fun applyAt(
    p: Offset,
    s: Size,
    mode: DragMode?,
    stage: LggStage,
    axis: LggAxis,
    onChange: (LggAxis) -> Unit,
    haptics: app.luxbuilder.ui.theme.LuxHaptics,
    isStart: Boolean,
) {
    if (s.width == 0f || mode == null) return
    val center = Offset(s.width / 2f, s.height / 2f)
    val outerR = s.minDimension / 2f
    val ringInner = outerR - 16f

    when (mode) {
        DragMode.DISC -> {
            val maxR = ringInner - 12f
            val dx = (p.x - center.x).coerceIn(-maxR, maxR)
            val dy = (p.y - center.y).coerceIn(-maxR, maxR)
            // Clamp to disc
            val rr = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val (nx, ny) = if (rr > maxR) {
                val k = maxR / rr.coerceAtLeast(1e-3f)
                dx * k to dy * k
            } else dx to dy
            val tintX = (nx / maxR).coerceIn(-1f, 1f)
            val tintY = (ny / maxR).coerceIn(-1f, 1f)
            // Snap-to-center detent within 4% radius
            val snapTo = 0.04f
            val (sx, sy) = if (hypot(tintX, tintY) < snapTo) 0f to 0f else tintX to tintY
            if (sx == 0f && sy == 0f && (tintX != 0f || tintY != 0f)) haptics.centerDetent()
            onChange(setTint(stage, axis, sx, sy))
        }
        DragMode.RING -> {
            // Angle from top (-90°), positive = CW
            val theta = (atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble()) * 180.0 / PI).toFloat() + 90f
            val norm = theta / 180f       // -1..+1 around the half-rotation per side
            val luma = norm.coerceIn(-1f, 1f)
            if (kotlin.math.abs(luma) < 0.02f) {
                if (extractLuma(stage, axis) != 0f) haptics.centerDetent()
                onChange(setLuma(stage, axis, 0f))
            } else {
                onChange(setLuma(stage, axis, luma))
            }
        }
    }
    if (isStart) haptics.detent()
}

// ──────────── Axis ↔ wheel-space conversion ────────────

/**
 * Decompose an [LggAxis] into the wheel's three control values:
 *   tintX, tintY ∈ [-1, +1]  (disc position)
 *   luma          ∈ [-1, +1]  (outer ring)
 *
 * For LIFT: offsets ↔ tint+luma. For GAMMA: power ↔ tint+luma (mapped log). For
 * GAIN: slope ↔ tint+luma (mapped log).
 */
private fun decomposeAxis(stage: LggStage, axis: LggAxis): Triple<Float, Float, Float> = when (stage) {
    LggStage.LIFT -> {
        val y = (axis.offsetR + axis.offsetG + axis.offsetB) / 3f
        // tint = channels minus their mean, normalized
        val cr = axis.offsetR - y
        val cb = axis.offsetB - y
        Triple(cr / 0.1f, -cb / 0.1f, y / 0.1f)   // ±0.1 maps to ±1
    }
    LggStage.GAMMA -> {
        val y = ((kotlin.math.ln(axis.powerR) + kotlin.math.ln(axis.powerG) + kotlin.math.ln(axis.powerB)) / 3f).toFloat()
        val cr = kotlin.math.ln(axis.powerR).toFloat() - y
        val cb = kotlin.math.ln(axis.powerB).toFloat() - y
        Triple(cr / 0.3f, -cb / 0.3f, y / 0.6f)
    }
    LggStage.GAIN -> {
        val y = ((kotlin.math.ln(axis.slopeR) + kotlin.math.ln(axis.slopeG) + kotlin.math.ln(axis.slopeB)) / 3f).toFloat()
        val cr = kotlin.math.ln(axis.slopeR).toFloat() - y
        val cb = kotlin.math.ln(axis.slopeB).toFloat() - y
        Triple(cr / 0.3f, -cb / 0.3f, y / 0.6f)
    }
}

private fun setTint(stage: LggStage, axis: LggAxis, tintX: Float, tintY: Float): LggAxis {
    val (_, _, luma) = decomposeAxis(stage, axis)
    return composeAxis(stage, tintX, tintY, luma)
}

private fun setLuma(stage: LggStage, axis: LggAxis, luma: Float): LggAxis {
    val (tx, ty, _) = decomposeAxis(stage, axis)
    return composeAxis(stage, tx, ty, luma)
}

private fun extractLuma(stage: LggStage, axis: LggAxis): Float =
    decomposeAxis(stage, axis).third

private fun composeAxis(stage: LggStage, tintX: Float, tintY: Float, luma: Float): LggAxis = when (stage) {
    LggStage.LIFT -> {
        val y = luma * 0.1f
        val cr = tintX * 0.1f
        val cb = -tintY * 0.1f
        LggAxis(
            slopeR = 1f, slopeG = 1f, slopeB = 1f,
            offsetR = y + cr,
            offsetG = y - (cr + cb) / 2f,
            offsetB = y + cb,
            powerR = 1f, powerG = 1f, powerB = 1f,
        )
    }
    LggStage.GAMMA -> {
        val y = luma * 0.6f
        val cr = tintX * 0.3f
        val cb = -tintY * 0.3f
        LggAxis(
            slopeR = 1f, slopeG = 1f, slopeB = 1f,
            offsetR = 0f, offsetG = 0f, offsetB = 0f,
            powerR = kotlin.math.exp((y + cr).toDouble()).toFloat(),
            powerG = kotlin.math.exp((y - (cr + cb) / 2f).toDouble()).toFloat(),
            powerB = kotlin.math.exp((y + cb).toDouble()).toFloat(),
        )
    }
    LggStage.GAIN -> {
        val y = luma * 0.6f
        val cr = tintX * 0.3f
        val cb = -tintY * 0.3f
        LggAxis(
            slopeR = kotlin.math.exp((y + cr).toDouble()).toFloat(),
            slopeG = kotlin.math.exp((y - (cr + cb) / 2f).toDouble()).toFloat(),
            slopeB = kotlin.math.exp((y + cb).toDouble()).toFloat(),
            offsetR = 0f, offsetG = 0f, offsetB = 0f,
            powerR = 1f, powerG = 1f, powerB = 1f,
        )
    }
}

private fun formatReadout(stage: LggStage, axis: LggAxis): String {
    val (rDelta, gDelta, bDelta) = when (stage) {
        LggStage.LIFT  -> Triple(axis.offsetR, axis.offsetG, axis.offsetB)
        LggStage.GAMMA -> Triple(axis.powerR - 1f, axis.powerG - 1f, axis.powerB - 1f)
        LggStage.GAIN  -> Triple(axis.slopeR - 1f, axis.slopeG - 1f, axis.slopeB - 1f)
    }
    return "R%+.2f G%+.2f B%+.2f".format(rDelta, gDelta, bDelta)
        .replace('-', '−')   // proper U+2212 minus
}

@Suppress("unused") private val SinUnused = sin(0.0)
@Suppress("unused") private val CosUnused = cos(0.0)
@Suppress("unused") private val SqrtUnused = sqrt(0.0)
