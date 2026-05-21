package app.luxbuilder.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.luxbuilder.state.RefPhoto
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing

/**
 * Horizontal strip of reference photos for the color-match flow.
 *
 * Empty state: a single dashed-border tile inviting the user to drop or pick
 * references. Populated state: horizontally-scrollable tiles with × to remove
 * and a trailing "+" tile to add more.
 */
@Composable
fun ReferenceStrip(
    refs: List<RefPhoto>,
    onAdd: (List<Uri>) -> Unit,
    onRemove: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Lux.colors

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8)
    ) { uris ->
        if (uris.isNotEmpty()) onAdd(uris)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = LuxSpacing.lg),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (refs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, colors.stroke.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .clickable {
                        pick.launch(PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        ))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "DROP REFERENCE PHOTOS · TAP TO PICK",
                    style = Lux.type.labelMono,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(LuxSpacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(refs) { ref ->
                    ReferenceTile(ref = ref, onRemove = { onRemove(ref.uri) })
                }
                item {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, colors.stroke, RoundedCornerShape(4.dp))
                            .clickable {
                                pick.launch(PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                ))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+", style = Lux.type.display, color = colors.textSecondary)
                    }
                }
                item { Spacer(modifier = Modifier.width(LuxSpacing.xxl)) }
            }
        }
    }
}

@Composable
private fun ReferenceTile(ref: RefPhoto, onRemove: () -> Unit) {
    val colors = Lux.colors
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surface2)
            .border(1.dp, colors.stroke, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Lightweight: just shows the filename. Thumbnail decode could replace
        // this in a v1.1 polish pass.
        Text(
            text = ref.displayName.take(8),
            style = Lux.type.numMicro,
            color = colors.textTertiary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.bg.copy(alpha = 0.7f))
                .clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Text("×", style = Lux.type.label, color = colors.textPrimary)
        }
    }
}
