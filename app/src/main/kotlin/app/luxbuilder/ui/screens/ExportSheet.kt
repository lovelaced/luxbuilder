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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.luxbuilder.io.LutExporter
import app.luxbuilder.io.VltWriter
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing

@Composable
fun ExportSheet(
    onDismiss: () -> Unit,
    onConfirm: (LutExporter.Format, String, LutExporter.Destination) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = Lux.colors

    var format by remember { mutableStateOf(LutExporter.Format.CUBE) }
    var filename by remember { mutableStateOf("luxlook") }
    var destination by remember { mutableStateOf(LutExporter.Destination.SHARE_SHEET) }

    LaunchedEffect(format) {
        if (format == LutExporter.Format.VLT) {
            destination = LutExporter.Destination.SAF_FOLDER
            filename = VltWriter.sanitizeFilename(filename)
        }
    }

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
            Text("EXPORT LUT", style = Lux.type.title, color = colors.textPrimary)
            Spacer(modifier = Modifier.height(LuxSpacing.xl))

            Text("FORMAT", style = Lux.type.labelMono, color = colors.textTertiary)
            Spacer(modifier = Modifier.height(LuxSpacing.sm))
            FormatRow(
                label = ".cube",
                description = "Universal · 33×33×33 · Lumix Lab, Lightroom, Davinci",
                selected = format == LutExporter.Format.CUBE,
                onSelect = { format = LutExporter.Format.CUBE },
            )
            Spacer(modifier = Modifier.height(LuxSpacing.xs))
            FormatRow(
                label = ".vlt",
                description = "Lumix camera · 17×17×17 · SD card → Real Time LUT",
                selected = format == LutExporter.Format.VLT,
                onSelect = { format = LutExporter.Format.VLT },
            )
            Spacer(modifier = Modifier.height(LuxSpacing.xs))
            FormatRow(
                label = ".cdl",
                description = "Editable intent · slope/offset/power · pairs with .cube",
                selected = format == LutExporter.Format.CDL,
                onSelect = { format = LutExporter.Format.CDL },
            )

            Spacer(modifier = Modifier.height(LuxSpacing.xl))

            Text("FILENAME", style = Lux.type.labelMono, color = colors.textTertiary)
            Spacer(modifier = Modifier.height(LuxSpacing.sm))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.surface1)
                    .border(1.dp, colors.stroke, RoundedCornerShape(2.dp))
                    .padding(horizontal = LuxSpacing.md),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = filename,
                    onValueChange = {
                        filename = if (format == LutExporter.Format.VLT) VltWriter.sanitizeFilename(it) else it
                    },
                    textStyle = TextStyle(
                        fontSize = Lux.type.readoutLg.fontSize,
                        fontFamily = Lux.type.readoutLg.fontFamily,
                        color = colors.textPrimary,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
            Spacer(modifier = Modifier.height(LuxSpacing.xs))
            Text(
                text = "→ $filename.${format.ext}",
                style = Lux.type.readoutSm,
                color = colors.textTertiary,
            )

            Spacer(modifier = Modifier.height(LuxSpacing.xl))

            Text("DESTINATION", style = Lux.type.labelMono, color = colors.textTertiary)
            Spacer(modifier = Modifier.height(LuxSpacing.sm))
            if (format == LutExporter.Format.CUBE) {
                FormatRow(
                    label = "Share sheet",
                    description = "Open in Lumix Lab · Lightroom · Files",
                    selected = destination == LutExporter.Destination.SHARE_SHEET,
                    onSelect = { destination = LutExporter.Destination.SHARE_SHEET },
                )
                Spacer(modifier = Modifier.height(LuxSpacing.xs))
                FormatRow(
                    label = "Saved folder",
                    description = "Write to your luxbuilder SAF folder",
                    selected = destination == LutExporter.Destination.SAF_FOLDER,
                    onSelect = { destination = LutExporter.Destination.SAF_FOLDER },
                )
            } else {
                FormatRow(
                    label = "Saved folder",
                    description = "Write .vlt to SAF folder → copy to SD card → camera",
                    selected = true,
                    onSelect = {},
                )
            }

            Spacer(modifier = Modifier.height(LuxSpacing.xl))

            // Confirm button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LuxSpacing.touch)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.accent)
                    .clickable { onConfirm(format, filename, destination) },
                contentAlignment = Alignment.Center,
            ) {
                Text("EXPORT", style = Lux.type.label, color = colors.accentOn)
            }
        }
    }
}

@Composable
private fun FormatRow(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = Lux.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(if (selected) colors.surface3 else colors.surface1)
            .border(
                1.dp,
                if (selected) colors.accent else colors.stroke,
                RoundedCornerShape(2.dp),
            )
            .clickable(onClick = onSelect)
            .padding(LuxSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, colors.stroke, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.accent),
                )
            }
        }
        Spacer(modifier = Modifier.width(LuxSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = Lux.type.label, color = colors.textPrimary)
            Text(description, style = Lux.type.numMicro, color = colors.textTertiary)
        }
    }
}

