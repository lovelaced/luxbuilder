package app.luxbuilder.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import app.luxbuilder.ui.components.PreviewSurface
import app.luxbuilder.ui.components.TabStrip
import app.luxbuilder.ui.components.ToneCurveEditor
import app.luxbuilder.ui.components.WbSliders
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing
import kotlinx.coroutines.launch

private val PANEL_LABELS = listOf("TONE", "WHEELS", "HSL", "WB", "SAT/CON", "PRE")

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
        // Pinned preview surface (top ~45% of available height)
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = LuxSpacing.sm),
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
                    1 -> PlaceholderPage("LGG wheels · coming next")
                    2 -> PlaceholderPage("HSL six-color · coming next")
                    3 -> WbSliders(
                        wb = state.wb,
                        onChange = { wb -> store.dispatch(LuxIntent.SetWhiteBalance(wb)) },
                    )
                    4 -> BasicSliders(
                        basics = state.basics,
                        onChange = { b -> store.dispatch(LuxIntent.SetBasics(b)) },
                    )
                    5 -> PlaceholderPage("Presets · coming with Phase 6")
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
