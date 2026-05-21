package app.luxbuilder.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.luxbuilder.state.LuxIntent
import app.luxbuilder.state.LuxState
import app.luxbuilder.state.LuxStore
import app.luxbuilder.state.ToneChannel
import app.luxbuilder.ui.components.BasicSliders
import app.luxbuilder.ui.components.HslPanelView
import app.luxbuilder.ui.components.LggPanel
import app.luxbuilder.ui.components.LuxSlider
import app.luxbuilder.ui.components.PreviewSurface
import app.luxbuilder.ui.components.ReferenceStrip
import app.luxbuilder.ui.components.TabStrip
import app.luxbuilder.ui.components.ToneCurveEditor
import app.luxbuilder.ui.components.WbSliders
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing
import kotlinx.coroutines.launch

private val PANEL_LABELS = listOf("TONE", "WHEELS", "HSL", "WB", "SAT/CON")

@Composable
fun EditScreen(
    store: LuxStore,
    state: LuxState,
    bitmap: Bitmap?,
    onAddReferences: () -> Unit,
    onPickPreviewSource: () -> Unit,
    onExport: () -> Unit,
    onShowPresets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { PANEL_LABELS.size })
    val scope = rememberCoroutineScope()
    val colors = Lux.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Header(
            store = store,
            refs = state.references.size,
            canUndo = store.canUndo,
            canRedo = store.canRedo,
            onShowPresets = onShowPresets,
            onExport = onExport,
        )

        // Reference strip — always visible, the primary input surface
        ReferenceStrip(
            refs = state.references,
            onAdd = { store.dispatch(LuxIntent.AddReferences(it)) },
            onRemove = { store.dispatch(LuxIntent.RemoveReference(it)) },
        )

        // Match strength slider — directly above the preview, inline
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LuxSpacing.lg, vertical = LuxSpacing.xs),
        ) {
            LuxSlider(
                label = "MATCH",
                value = state.mklStrength * 100f,
                range = 0f..100f,
                onValueChange = { store.dispatch(LuxIntent.SetMklStrength(it / 100f)) },
                valueFormatter = { "%d%%".format(it.toInt()) },
                tickEvery = 2f,
            )
        }

        // Preview
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            PreviewSurface(bitmap = bitmap, state = state)
        }

        // Preview-source switcher
        PreviewSourceRow(state = state, store = store, onPickOwn = onPickPreviewSource)

        Spacer(modifier = Modifier.height(LuxSpacing.sm))

        // Refinement tabs
        Text(
            text = "REFINE",
            style = Lux.type.labelMono,
            color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = LuxSpacing.lg, vertical = LuxSpacing.xs),
        )
        TabStrip(
            labels = PANEL_LABELS,
            selected = pagerState.currentPage,
            onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            Box(
                modifier = Modifier.fillMaxSize().padding(LuxSpacing.lg),
                contentAlignment = Alignment.TopCenter,
            ) {
                when (page) {
                    0 -> TonePage(store = store, state = state)
                    1 -> LggPanel(
                        lgg = state.lgg,
                        onChange = { stage, axis -> store.dispatch(LuxIntent.SetLgg(stage, axis)) },
                    )
                    2 -> HslPanelView(
                        panel = state.hsl,
                        onChange = { color, anchor -> store.dispatch(LuxIntent.SetHsl(color, anchor)) },
                    )
                    3 -> WbSliders(
                        wb = state.wb,
                        onChange = { wb -> store.dispatch(LuxIntent.SetWhiteBalance(wb)) },
                    )
                    4 -> BasicSliders(
                        basics = state.basics,
                        onChange = { b -> store.dispatch(LuxIntent.SetBasics(b)) },
                    )
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun Header(
    store: LuxStore,
    refs: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    onShowPresets: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = Lux.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LuxSpacing.lg, vertical = LuxSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "luxbuilder · $refs REF${if (refs != 1) "S" else ""}",
            style = Lux.type.labelMono,
            color = colors.textTertiary,
            modifier = Modifier.weight(1f),
        )
        HeaderChip(label = "↶", enabled = canUndo) { store.dispatch(LuxIntent.Undo) }
        Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xxs))
        HeaderChip(label = "↷", enabled = canRedo) { store.dispatch(LuxIntent.Redo) }
        Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xs))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .border(1.dp, colors.stroke, RoundedCornerShape(2.dp))
                .clickable { onShowPresets() }
                .padding(horizontal = LuxSpacing.md, vertical = LuxSpacing.xs),
        ) {
            Text("PRESETS", style = Lux.type.labelMono, color = colors.textPrimary)
        }
        Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xxs))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .border(1.5.dp, colors.accent, RoundedCornerShape(2.dp))
                .clickable { onExport() }
                .padding(horizontal = LuxSpacing.md, vertical = LuxSpacing.xs),
        ) {
            Text("EXPORT", style = Lux.type.label, color = colors.accent)
        }
    }
}

@Composable
private fun HeaderChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Lux.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .border(
                1.dp,
                if (enabled) colors.stroke else colors.divider,
                RoundedCornerShape(2.dp),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = LuxSpacing.sm, vertical = LuxSpacing.xs),
    ) {
        Text(
            text = label,
            style = Lux.type.readoutLg,
            color = if (enabled) colors.textPrimary else colors.textTertiary,
        )
    }
}

@Composable
private fun PreviewSourceRow(state: LuxState, store: LuxStore, onPickOwn: () -> Unit) {
    val colors = Lux.colors
    val current = state.effectivePreviewUri
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LuxSpacing.lg, vertical = LuxSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "VIEWING",
            style = Lux.type.labelMono,
            color = colors.textTertiary,
        )
        Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xs))
        state.references.forEachIndexed { idx, ref ->
            val active = current == ref.uri
            ChipMini(
                label = "R${idx + 1}",
                active = active,
                onClick = { store.dispatch(LuxIntent.SetPreview(ref.uri)) },
            )
            Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xxs))
        }
        Spacer(modifier = Modifier.weight(1f))
        ChipMini(
            label = "MY PHOTO…",
            active = state.previewUri != null && state.references.none { it.uri == state.previewUri },
            onClick = onPickOwn,
        )
    }
}

@Composable
private fun ChipMini(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = Lux.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .border(
                1.dp,
                if (active) colors.accent else colors.stroke,
                RoundedCornerShape(2.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = LuxSpacing.sm, vertical = LuxSpacing.xxs),
    ) {
        Text(
            text = label,
            style = Lux.type.labelMono,
            color = if (active) colors.accent else colors.textSecondary,
        )
    }
}

@Composable
private fun TonePage(store: LuxStore, state: LuxState) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = LuxSpacing.lg)) {
        Text(
            text = "MASTER · LUMA",
            style = Lux.type.labelMono,
            color = Lux.colors.textTertiary,
            modifier = Modifier.padding(bottom = LuxSpacing.sm),
        )
        ToneCurveEditor(
            channel = ToneChannel.LUMA,
            curve = state.tone.luma,
            onAddPoint = { p -> store.dispatch(LuxIntent.AddCurvePoint(ToneChannel.LUMA, p)) },
            onMovePoint = { i, p -> store.dispatch(LuxIntent.MoveCurvePoint(ToneChannel.LUMA, i, p)) },
            onRemovePoint = { i -> store.dispatch(LuxIntent.RemoveCurvePoint(ToneChannel.LUMA, i)) },
            onResetChannel = { store.dispatch(LuxIntent.ResetCurveChannel(ToneChannel.LUMA)) },
        )
        Spacer(modifier = Modifier.height(LuxSpacing.md))
        Text(
            text = "Tap to add a point · drag to move · long-press to remove · two-finger to reset",
            style = Lux.type.numMicro,
            color = Lux.colors.textTertiary,
        )
    }
}
