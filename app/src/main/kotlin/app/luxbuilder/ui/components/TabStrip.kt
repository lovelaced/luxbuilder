package app.luxbuilder.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxMotion
import app.luxbuilder.ui.theme.LuxSpacing

/**
 * Monospace-label tab strip used at the top of the editor pager. Active label
 * brightens to amber; others are textTertiary. Hairline divider below.
 */
@Composable
fun TabStrip(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Lux.colors
    val haptics = Lux.haptics
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(LuxSpacing.touch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { idx, label ->
                val isActive = idx == selected
                val color by animateColorAsState(
                    if (isActive) colors.accent else colors.textTertiary,
                    animationSpec = LuxMotion.springChrome(),
                    label = "tabColor",
                )
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) {
                            if (!isActive) {
                                haptics.detent()
                                onSelect(idx)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = Lux.type.labelMono,
                        color = color,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(LuxSpacing.hairline)
                .background(colors.divider),
        )
    }
}
