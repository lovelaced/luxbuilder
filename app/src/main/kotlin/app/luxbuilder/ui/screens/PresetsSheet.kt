package app.luxbuilder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.luxbuilder.state.Preset
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing

@Composable
fun PresetsSheet(
    presets: List<Preset>,
    canSave: Boolean,
    onSave: (String) -> Unit,
    onLoad: (Preset) -> Unit,
    onDelete: (Preset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = Lux.colors
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface2,
        contentColor = colors.textPrimary,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
        scrimColor = colors.bg.copy(alpha = 0.7f),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(LuxSpacing.lg),
        ) {
            Text("PRESETS", style = Lux.type.title, color = colors.textPrimary)
            Spacer(modifier = Modifier.height(LuxSpacing.lg))

            // Save current state
            Text("SAVE CURRENT LOOK", style = Lux.type.labelMono, color = colors.textTertiary)
            Spacer(modifier = Modifier.height(LuxSpacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.surface1)
                        .border(1.dp, colors.stroke, RoundedCornerShape(2.dp))
                        .padding(horizontal = LuxSpacing.md),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (newName.isEmpty()) {
                        Text(
                            text = "preset name…",
                            style = Lux.type.readoutLg,
                            color = colors.textTertiary,
                        )
                    }
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it.take(24) },
                        textStyle = TextStyle(
                            fontSize = Lux.type.readoutLg.fontSize,
                            fontFamily = Lux.type.readoutLg.fontFamily,
                            color = colors.textPrimary,
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                }
                Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xs))
                val saveEnabled = canSave && newName.isNotBlank()
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (saveEnabled) colors.accent else colors.surface3)
                        .clickable(enabled = saveEnabled) {
                            onSave(newName.trim())
                            newName = ""
                        }
                        .padding(horizontal = LuxSpacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "SAVE",
                        style = Lux.type.label,
                        color = if (saveEnabled) colors.accentOn else colors.textTertiary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(LuxSpacing.xl))

            Text(
                "${presets.size} SAVED",
                style = Lux.type.labelMono,
                color = colors.textTertiary,
            )
            Spacer(modifier = Modifier.height(LuxSpacing.sm))

            if (presets.isEmpty()) {
                Text(
                    text = "No saved looks yet. Save your current grade above to start a library.",
                    style = Lux.type.caption,
                    color = colors.textTertiary,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    items(presets, key = { it.id }) { preset ->
                        PresetRow(preset = preset, onLoad = onLoad, onDelete = onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(preset: Preset, onLoad: (Preset) -> Unit, onDelete: (Preset) -> Unit) {
    val colors = Lux.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(colors.surface1)
            .border(1.dp, colors.divider, RoundedCornerShape(2.dp))
            .padding(LuxSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).clickable { onLoad(preset) }) {
            Text(preset.name, style = Lux.type.label, color = colors.textPrimary)
            Text(
                text = "MATCH ${(preset.mklStrength * 100).toInt()}%",
                style = Lux.type.numMicro,
                color = colors.textTertiary,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .border(1.dp, colors.stroke, RoundedCornerShape(2.dp))
                .clickable { onLoad(preset) }
                .padding(horizontal = LuxSpacing.md, vertical = LuxSpacing.xs),
        ) {
            Text("LOAD", style = Lux.type.labelMono, color = colors.textPrimary)
        }
        Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xxs))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .border(1.dp, colors.error.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                .clickable { onDelete(preset) }
                .padding(horizontal = LuxSpacing.sm, vertical = LuxSpacing.xs),
        ) {
            Text("×", style = Lux.type.label, color = colors.error)
        }
    }
    Spacer(modifier = Modifier.height(LuxSpacing.xs))
}
