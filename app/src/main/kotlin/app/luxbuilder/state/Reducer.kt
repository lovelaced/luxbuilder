package app.luxbuilder.state

import java.util.UUID

/**
 * Pure reducer. Takes the current state and an intent, returns the next state.
 * No IO, no coroutines, no side effects — those happen in the caller around the
 * dispatch site.
 */
fun reduce(state: LuxState, intent: LuxIntent): LuxState = when (intent) {
    is LuxIntent.SetPreview         -> state.copy(previewUri = intent.uri)

    is LuxIntent.AddCurvePoint      -> state.copy(tone = state.tone.withChannel(intent.channel) { ch ->
        ch.copy(points = (ch.points + intent.point).sortedBy { it.x })
    })
    is LuxIntent.MoveCurvePoint     -> state.copy(tone = state.tone.withChannel(intent.channel) { ch ->
        ch.copy(points = ch.points.toMutableList().apply {
            if (intent.index in indices) this[intent.index] = intent.point
        }.sortedBy { it.x })
    })
    is LuxIntent.RemoveCurvePoint   -> state.copy(tone = state.tone.withChannel(intent.channel) { ch ->
        ch.copy(points = ch.points.filterIndexed { i, _ -> i != intent.index })
    })
    is LuxIntent.ResetCurveChannel  -> state.copy(tone = state.tone.withChannel(intent.channel) { CurveChannel.Identity })

    is LuxIntent.SetLgg             -> state.copy(lgg = state.lgg.withStage(intent.stage, intent.axis))
    is LuxIntent.ResetLgg           -> state.copy(lgg = state.lgg.withStage(intent.stage, LggAxis.Identity))

    is LuxIntent.SetHsl             -> state.copy(hsl = HslPanel(state.hsl.anchors + (intent.color to intent.anchor)))
    is LuxIntent.ResetHslAnchor     -> state.copy(hsl = HslPanel(state.hsl.anchors + (intent.color to HslAnchor())))

    is LuxIntent.SetWhiteBalance    -> state.copy(wb = intent.wb)
    is LuxIntent.SetBasics          -> state.copy(basics = intent.basics)

    is LuxIntent.AddReferences      -> state.copy(
        references = state.references + intent.uris
            .filter { newUri -> state.references.none { it.uri == newUri } }
            .map { RefPhoto(it, displayName = it.lastPathSegment ?: "photo") },
    )
    is LuxIntent.RemoveReference    -> state.copy(references = state.references.filterNot { it.uri == intent.uri })
    is LuxIntent.ClearReferences    -> state.copy(references = emptyList())

    is LuxIntent.SetMklTransform    -> state.copy(mklMatrix = intent.matrix, mklBias = intent.bias)
    is LuxIntent.SetMklStrength     -> state.copy(mklStrength = intent.strength.coerceIn(0f, 1f))
    is LuxIntent.ResetMkl           -> state.copy(
        mklMatrix = floatArrayOf(1f, 0f, 0f,  0f, 1f, 0f,  0f, 0f, 1f),
        mklBias = floatArrayOf(0f, 0f, 0f),
        mklStrength = 0f,
    )

    is LuxIntent.SavePreset         -> state.copy(
        presets = state.presets + Preset(
            id = UUID.randomUUID().toString(),
            name = intent.name,
            tone = state.tone, lgg = state.lgg, hsl = state.hsl,
            wb = state.wb, basics = state.basics, mklStrength = state.mklStrength,
        ),
    )
    is LuxIntent.LoadPreset         -> state.presets.firstOrNull { it.id == intent.id }?.let { p ->
        state.copy(
            tone = p.tone, lgg = p.lgg, hsl = p.hsl,
            wb = p.wb, basics = p.basics, mklStrength = p.mklStrength,
            activePresetId = p.id,
        )
    } ?: state
    is LuxIntent.DeletePreset       -> state.copy(
        presets = state.presets.filterNot { it.id == intent.id },
        activePresetId = if (state.activePresetId == intent.id) null else state.activePresetId,
    )
    is LuxIntent.SetPresetList      -> state.copy(presets = intent.presets)

    is LuxIntent.ResetAll           -> LuxState(
        previewUri = state.previewUri,
        references = state.references,
        presets = state.presets,
    )

    // Undo/Redo are handled by the Store wrapping the reducer.
    LuxIntent.Undo, LuxIntent.Redo  -> state
}

private fun ToneCurves.withChannel(c: ToneChannel, f: (CurveChannel) -> CurveChannel): ToneCurves = when (c) {
    ToneChannel.LUMA  -> copy(luma = f(luma))
    ToneChannel.RED   -> copy(red = f(red))
    ToneChannel.GREEN -> copy(green = f(green))
    ToneChannel.BLUE  -> copy(blue = f(blue))
}

private fun Lgg.withStage(s: LggStage, a: LggAxis): Lgg = when (s) {
    LggStage.LIFT  -> copy(lift = a)
    LggStage.GAMMA -> copy(gamma = a)
    LggStage.GAIN  -> copy(gain = a)
}
