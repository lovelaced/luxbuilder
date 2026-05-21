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
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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

private val PANEL_LABELS = listOf("TONE", "WHEELS", "HSL", "WB", "SAT/CON", "MATCH")

@Composable
fun EditScreen(
    store: LuxStore,
    state: LuxState,
    bitmap: Bitmap?,
    onPickPhoto: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { PANEL_LABELS.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Lux.colors.bg)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LuxSpacing.lg, vertical = LuxSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "luxbuilder",
                style = Lux.type.labelMono,
                color = Lux.colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .background(Lux.colors.bg)
                    .border(1.5.dp, Lux.colors.accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .clickable { onExport() }
                    .padding(horizontal = LuxSpacing.md, vertical = LuxSpacing.xs),
            ) {
                Text("EXPORT", style = Lux.type.label, color = Lux.colors.accent)
            }
        }

        // Pinned preview surface
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            PreviewSurface(bitmap = bitmap, state = state)
        }

        Spacer(modifier = Modifier.height(LuxSpacing.sm))

        // Tab strip
        TabStrip(
            labels = PANEL_LABELS,
            selected = pagerState.currentPage,
            onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
        )

        // Panels via swipeable HorizontalPager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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
                    5 -> MatchPage(store = store, state = state)
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun TonePage(store: LuxStore, state: LuxState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = LuxSpacing.lg),
    ) {
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

@Composable
private fun MatchPage(store: LuxStore, state: LuxState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "REFERENCES",
            style = Lux.type.labelMono,
            color = Lux.colors.textTertiary,
            modifier = Modifier.padding(horizontal = LuxSpacing.lg, vertical = LuxSpacing.sm),
        )
        ReferenceStrip(
            refs = state.references,
            onAdd = { store.dispatch(LuxIntent.AddReferences(it)) },
            onRemove = { store.dispatch(LuxIntent.RemoveReference(it)) },
        )
        Spacer(modifier = Modifier.height(LuxSpacing.md))
        Column(modifier = Modifier.padding(horizontal = LuxSpacing.lg)) {
            LuxSlider(
                label = "MATCH STRENGTH",
                value = state.mklStrength * 100f,
                range = 0f..100f,
                onValueChange = { store.dispatch(LuxIntent.SetMklStrength(it / 100f)) },
                valueFormatter = { "%d%%".format(it.toInt()) },
                tickEvery = 2f,
            )
            Spacer(modifier = Modifier.height(LuxSpacing.md))
            Text(
                text = if (state.references.isEmpty())
                    "Drop reference photos above to compute a starter LUT — Monge-Kantorovich color transfer."
                else
                    "Match fits your photos toward the average look of these references. Tweak with the wheels and curve.",
                style = Lux.type.caption,
                color = Lux.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun PlaceholderPage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message.uppercase(),
            style = Lux.type.labelMono,
            color = Lux.colors.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}
