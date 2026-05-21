package app.luxbuilder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.luxbuilder.state.Basics
import app.luxbuilder.ui.theme.LuxSpacing

/** Saturation / vibrance / contrast — three plain sliders. */
@Composable
fun BasicSliders(
    basics: Basics,
    onChange: (Basics) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = LuxSpacing.screenH)) {
        LuxSlider(
            label = "SATURATION",
            value = basics.saturation,
            range = -100f..100f,
            onValueChange = { onChange(basics.copy(saturation = it)) },
            valueFormatter = { "%+d".format(it.toInt()) },
            tickEvery = 2f,
        )
        Spacer(modifier = Modifier.height(LuxSpacing.lg))
        LuxSlider(
            label = "VIBRANCE",
            value = basics.vibrance,
            range = -100f..100f,
            onValueChange = { onChange(basics.copy(vibrance = it)) },
            valueFormatter = { "%+d".format(it.toInt()) },
            tickEvery = 2f,
        )
        Spacer(modifier = Modifier.height(LuxSpacing.lg))
        LuxSlider(
            label = "CONTRAST",
            value = basics.contrast,
            range = -100f..100f,
            onValueChange = { onChange(basics.copy(contrast = it)) },
            valueFormatter = { "%+d".format(it.toInt()) },
            tickEvery = 2f,
        )
    }
}
