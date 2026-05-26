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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import app.luxbuilder.ui.components.PreviewMode
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
        // Compute whether the current grade matches the active preset's snapshot.
        // True → the calm amber pulse plays beneath the header label.
        val activePreset = state.presets.firstOrNull { it.id == state.activePresetId }
        val atActivePreset = activePreset != null && state.gradeSnapshot() ==
            app.luxbuilder.state.GradeSnapshot(
                tone = activePreset.tone,
                lgg = activePreset.lgg,
                hsl = activePreset.hsl,
                wb = activePreset.wb,
                basics = activePreset.basics,
                mklStrength = activePreset.mklStrength,
            )

        Header(
            store = store,
            refs = state.references.size,
            matchScore = state.matchScore,
            canUndo = store.canUndo,
            canRedo = store.canRedo,
            atPresetName = if (atActivePreset) activePreset?.name else null,
            onShowPresets = onShowPresets,
            onExport = onExport,
        )

        // Reference strip — always visible, the primary input surface
        ReferenceStrip(
            refs = state.references,
            onAdd = { store.dispatch(LuxIntent.AddReferences(it)) },
            onRemove = { store.dispatch(LuxIntent.RemoveReference(it)) },
        )

        // Auto-fit toggles — STRIP WB and HQ MATCH. Both persist via UserPrefs.
        if (state.references.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LuxSpacing.lg, vertical = LuxSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("AUTO-FIT", style = Lux.type.labelMono, color = colors.textTertiary)
                Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xs))
                ChipMini(
                    label = "STRIP WB",
                    active = state.stripShootingWb,
                    onClick = { store.dispatch(LuxIntent.SetStripShootingWb(!state.stripShootingWb)) },
                )
                Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xxs))
                ChipMini(
                    label = "HQ MATCH",
                    active = state.highQualityMatch,
                    onClick = { store.dispatch(LuxIntent.SetHighQualityMatch(!state.highQualityMatch)) },
                )
            }
        }

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

        // References *define* the look — viewing them at LUT-on is a recursive
        // exaggeration, so the resting mode for a reference defaults to
        // ORIGINAL. User-picked "my photo" defaults to GRADED (the whole point
        // of seeing it is to check the look on a real test surface).
        // SPLIT mode persists indefinitely so the user can tune the grade
        // against a frozen comparison position; dragging the preview in any
        // mode auto-switches into SPLIT.
        val viewingReference = state.effectivePreviewUri != null &&
            state.references.any { it.uri == state.effectivePreviewUri }
        var previewMode by rememberSaveable(state.effectivePreviewUri) {
            mutableStateOf(if (viewingReference) PreviewMode.ORIGINAL else PreviewMode.GRADED)
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            PreviewSurface(
                bitmap = bitmap,
                state = state,
                mode = previewMode,
                onUserDrag = { previewMode = PreviewMode.SPLIT },
            )
        }

        PreviewModeRow(
            mode = previewMode,
            onSelect = { previewMode = it },
        )

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
    matchScore: Float?,
    canUndo: Boolean,
    canRedo: Boolean,
    atPresetName: String?,
    onShowPresets: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = Lux.colors

    // The "AT PRESET · name" pulse — runs only while atPresetName != null.
    val pulse = remember { androidx.compose.animation.core.Animatable(0.3f) }
    androidx.compose.runtime.LaunchedEffect(atPresetName) {
        if (atPresetName != null) {
            pulse.snapTo(0.3f)
            pulse.animateTo(
                1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 2400,
                        easing = androidx.compose.animation.core.EaseInOut,
                    ),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
            )
        } else {
            pulse.snapTo(0.3f)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LuxSpacing.lg, vertical = LuxSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (atPresetName != null) {
            Text(
                text = "AT PRESET · ${atPresetName.uppercase()}",
                style = Lux.type.labelMono,
                color = colors.accent.copy(alpha = pulse.value),
                modifier = Modifier.weight(1f),
            )
        } else {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (matchScore != null) {
                    val n = matchScore.toInt()
                    Text(
                        text = "MATCH $n",
                        style = Lux.type.labelMono,
                        color = if (n >= 80) colors.accent else colors.textSecondary,
                    )
                    Text(
                        text = " · ",
                        style = Lux.type.labelMono,
                        color = colors.textTertiary,
                    )
                }
                Text(
                    text = "luxbuilder · $refs REF${if (refs != 1) "S" else ""}",
                    style = Lux.type.labelMono,
                    color = colors.textTertiary,
                )
            }
        }
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
private fun PreviewModeRow(mode: PreviewMode, onSelect: (PreviewMode) -> Unit) {
    val colors = Lux.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LuxSpacing.lg, vertical = LuxSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "PREVIEW",
            style = Lux.type.labelMono,
            color = colors.textTertiary,
        )
        Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xs))
        ChipMini(label = "ORIG", active = mode == PreviewMode.ORIGINAL, onClick = { onSelect(PreviewMode.ORIGINAL) })
        Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xxs))
        ChipMini(label = "LUT", active = mode == PreviewMode.GRADED, onClick = { onSelect(PreviewMode.GRADED) })
        Spacer(modifier = Modifier.padding(horizontal = LuxSpacing.xxs))
        ChipMini(label = "SPLIT", active = mode == PreviewMode.SPLIT, onClick = { onSelect(PreviewMode.SPLIT) })
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (mode == PreviewMode.SPLIT) "DRAG TO ADJUST" else "DRAG ⇄ TO COMPARE",
            style = Lux.type.numMicro,
            color = colors.textTertiary,
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
    var activeChannel by rememberSaveable { mutableStateOf(ToneChannel.LUMA) }
    val activeCurve = when (activeChannel) {
        ToneChannel.LUMA  -> state.tone.luma
        ToneChannel.RED   -> state.tone.red
        ToneChannel.GREEN -> state.tone.green
        ToneChannel.BLUE  -> state.tone.blue
    }
    val ghosts = mapOf(
        ToneChannel.LUMA  to state.tone.luma,
        ToneChannel.RED   to state.tone.red,
        ToneChannel.GREEN to state.tone.green,
        ToneChannel.BLUE  to state.tone.blue,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LuxSpacing.lg),
    ) {
        ChannelPills(
            active = activeChannel,
            onSelect = { activeChannel = it },
        )
        Spacer(modifier = Modifier.height(LuxSpacing.sm))
        // Center the curve and cap its size — full-width was overwhelming and
        // left no room for the help text or scroll headroom.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            ToneCurveEditor(
                channel = activeChannel,
                curve = activeCurve,
                ghostCurves = ghosts,
                onAddPoint = { p -> store.dispatch(LuxIntent.AddCurvePoint(activeChannel, p)) },
                onMovePoint = { i, p -> store.dispatch(LuxIntent.MoveCurvePoint(activeChannel, i, p)) },
                onRemovePoint = { i -> store.dispatch(LuxIntent.RemoveCurvePoint(activeChannel, i)) },
                onResetChannel = { store.dispatch(LuxIntent.ResetCurveChannel(activeChannel)) },
                modifier = Modifier.size(280.dp),
            )
        }
        Spacer(modifier = Modifier.height(LuxSpacing.md))
        Text(
            text = "Tap to add · drag to move · long-press point to remove · long-press grid to reset channel",
            style = Lux.type.numMicro,
            color = Lux.colors.textTertiary,
        )
        Spacer(modifier = Modifier.height(LuxSpacing.xxl))
    }
}

@Composable
private fun ChannelPills(active: ToneChannel, onSelect: (ToneChannel) -> Unit) {
    val colors = Lux.colors
    val haptics = Lux.haptics
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(LuxSpacing.xs),
    ) {
        ToneChannel.entries.forEach { ch ->
            val isActive = ch == active
            val signal = when (ch) {
                ToneChannel.LUMA  -> colors.signalLuma
                ToneChannel.RED   -> colors.signalRed
                ToneChannel.GREEN -> colors.signalGreen
                ToneChannel.BLUE  -> colors.signalBlue
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isActive) colors.surface3 else colors.surface1)
                    .border(
                        1.dp,
                        if (isActive) signal else colors.stroke,
                        RoundedCornerShape(2.dp),
                    )
                    .clickable {
                        if (!isActive) {
                            haptics.detent()
                            onSelect(ch)
                        }
                    }
                    .padding(vertical = LuxSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ch.label(),
                    style = Lux.type.labelMono,
                    color = if (isActive) signal else colors.textSecondary,
                )
            }
        }
    }
}

private fun ToneChannel.label(): String = when (this) {
    ToneChannel.LUMA  -> "LUMA"
    ToneChannel.RED   -> "R"
    ToneChannel.GREEN -> "G"
    ToneChannel.BLUE  -> "B"
}
