package app.luxbuilder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.luxbuilder.state.HslAnchor
import app.luxbuilder.state.HslColor
import app.luxbuilder.state.HslPanel
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing
import kotlin.math.roundToInt

/**
 * Six-color HSL panel. Six columns (one per anchor); each column has three
 * vertical sliders (Hue / Sat / Luma). Anchor swatch above the sliders;
 * monospace value chip below each slider.
 */
@Composable
fun HslPanelView(
    panel: HslPanel,
    onChange: (HslColor, HslAnchor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anchors = listOf(
        HslColor.RED    to Color(0xFFE5564B),
        HslColor.ORANGE to Color(0xFFE39A4D),
        HslColor.YELLOW to Color(0xFFE2D14C),
        HslColor.GREEN  to Color(0xFF54C77B),
        HslColor.AQUA   to Color(0xFF4FC9C5),
        HslColor.BLUE   to Color(0xFF5C8FE6),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LuxSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(LuxSpacing.xs),
    ) {
        anchors.forEach { (color, swatchColor) ->
            val anchor = panel.anchors[color] ?: HslAnchor()
            HslColumn(
                color = color,
                swatchColor = swatchColor,
                anchor = anchor,
                onChange = { onChange(color, it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HslColumn(
    color: HslColor,
    swatchColor: Color,
    anchor: HslAnchor,
    onChange: (HslAnchor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Lux.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(swatchColor),
        )
        Spacer(modifier = Modifier.height(LuxSpacing.xs))
        Text(
            text = color.name.take(3),
            style = Lux.type.labelMono,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(LuxSpacing.sm))

        VerticalSlider(
            value = anchor.hueShift,
            onChange = { onChange(anchor.copy(hueShift = it)) },
        )
        Spacer(modifier = Modifier.height(LuxSpacing.xxs))
        Text(
            text = "%+d".format((anchor.hueShift * 30f).roundToInt()).replace('-', '−'),
            style = Lux.type.readoutSm, color = colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(LuxSpacing.sm))

        VerticalSlider(
            value = anchor.satShift,
            onChange = { onChange(anchor.copy(satShift = it)) },
        )
        Spacer(modifier = Modifier.height(LuxSpacing.xxs))
        Text(
            text = "%+d".format((anchor.satShift * 100f).roundToInt()).replace('-', '−'),
            style = Lux.type.readoutSm, color = colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(LuxSpacing.sm))

        VerticalSlider(
            value = anchor.lumaShift,
            onChange = { onChange(anchor.copy(lumaShift = it)) },
        )
        Spacer(modifier = Modifier.height(LuxSpacing.xxs))
        Text(
            text = "%+d".format((anchor.lumaShift * 100f).roundToInt()).replace('-', '−'),
            style = Lux.type.readoutSm, color = colors.textPrimary,
        )
    }
}

@Composable
private fun VerticalSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Lux.colors
    val haptics = Lux.haptics
    var height by remember { mutableFloatStateOf(0f) }
    var lastTick by remember { mutableFloatStateOf(value) }

    Box(
        modifier = modifier
            .width(40.dp)
            .height(90.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val h = height.coerceAtLeast(1f)
                        val frac = ((h - change.position.y) / h - 0.5f) * 2f   // -1 (bottom) .. +1 (top)
                        val snap = if (kotlin.math.abs(frac) < 0.04f) 0f else frac.coerceIn(-1f, 1f)
                        if (snap == 0f && value != 0f) haptics.centerDetent()
                        else if (kotlin.math.abs(snap - lastTick) >= 0.02f) {
                            haptics.detent()
                            lastTick = snap
                        }
                        onChange(snap)
                    },
                    onDragEnd = { haptics.gestureEnd() },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            height = size.height
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            // Track
            drawLine(
                color = colors.divider,
                start = Offset(centerX, 0f),
                end = Offset(centerX, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            // Zero mark
            drawLine(
                color = colors.accent,
                start = Offset(centerX - 6.dp.toPx(), centerY),
                end = Offset(centerX + 6.dp.toPx(), centerY),
                strokeWidth = 1.dp.toPx(),
            )
            // Fill from zero to thumb
            val thumbY = centerY - value * (size.height / 2f - 6.dp.toPx())
            if (value != 0f) {
                drawLine(
                    color = colors.accent,
                    start = Offset(centerX, centerY),
                    end = Offset(centerX, thumbY),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            // Thumb pill (12dp tall × 4dp wide)
            val thumbW = 4.dp.toPx()
            val thumbH = 12.dp.toPx()
            drawRoundRect(
                color = Color(0xFFF2F2F3),
                topLeft = Offset(centerX - thumbW / 2f, thumbY - thumbH / 2f),
                size = Size(thumbW, thumbH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
            drawRoundRect(
                color = Color(0xFF0A0A0B),
                topLeft = Offset(centerX - thumbW / 2f, thumbY - thumbH / 2f),
                size = Size(thumbW, thumbH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
