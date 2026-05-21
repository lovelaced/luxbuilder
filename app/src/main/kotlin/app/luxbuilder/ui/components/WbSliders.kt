package app.luxbuilder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import app.luxbuilder.state.WhiteBalance
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing

/**
 * White balance + tint pair. Temperature track gets a warm/cool gradient,
 * tint track gets a magenta/green gradient. Both centered on zero.
 */
@Composable
fun WbSliders(
    wb: WhiteBalance,
    onChange: (WhiteBalance) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Lux.colors
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = LuxSpacing.screenH)) {
        LuxSlider(
            label = "TEMPERATURE",
            value = wb.tempOffsetK.toFloat(),
            range = -2000f..2000f,
            onValueChange = { onChange(wb.copy(tempOffsetK = it.toInt())) },
            valueFormatter = { "%+dK".format(it.toInt()) },
            tickEvery = 100f,
            trackGradient = Brush.horizontalGradient(
                listOf(colors.warmCoolCool.copy(alpha = 0.5f), colors.warmCoolWarm.copy(alpha = 0.5f)),
            ),
        )
        Spacer(modifier = Modifier.height(LuxSpacing.lg))
        LuxSlider(
            label = "TINT",
            value = wb.tintOffset,
            range = -100f..100f,
            onValueChange = { onChange(wb.copy(tintOffset = it)) },
            valueFormatter = { "%+d".format(it.toInt()) },
            tickEvery = 2f,
            trackGradient = Brush.horizontalGradient(
                listOf(colors.tintGreen.copy(alpha = 0.5f), colors.tintMagenta.copy(alpha = 0.5f)),
            ),
        )
    }
}
