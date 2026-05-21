package app.luxbuilder.state

import android.net.Uri

/**
 * Every state-modifying action flows through the reducer as a [LuxIntent]. Pure
 * data; no behavior. Side effects (file IO, MKL computation) happen outside the
 * reducer and dispatch follow-up intents with the results.
 */
sealed interface LuxIntent {
    // Preview photo selection (optional; defaults to first reference when null)
    data class SetPreview(val uri: Uri?) : LuxIntent

    // Tone curve
    data class AddCurvePoint(val channel: ToneChannel, val point: CurvePoint) : LuxIntent
    data class MoveCurvePoint(val channel: ToneChannel, val index: Int, val point: CurvePoint) : LuxIntent
    data class RemoveCurvePoint(val channel: ToneChannel, val index: Int) : LuxIntent
    data class ResetCurveChannel(val channel: ToneChannel) : LuxIntent

    // LGG
    data class SetLgg(val stage: LggStage, val axis: LggAxis) : LuxIntent
    data class ResetLgg(val stage: LggStage) : LuxIntent

    // HSL
    data class SetHsl(val color: HslColor, val anchor: HslAnchor) : LuxIntent
    data class ResetHslAnchor(val color: HslColor) : LuxIntent

    // White balance
    data class SetWhiteBalance(val wb: WhiteBalance) : LuxIntent

    // Basics
    data class SetBasics(val basics: Basics) : LuxIntent

    // References (color-match input)
    data class AddReferences(val uris: List<Uri>) : LuxIntent
    data class RemoveReference(val uri: Uri) : LuxIntent
    data object ClearReferences : LuxIntent

    // Color-match (the MKL stage)
    data class SetMklTransform(
        val matrix: FloatArray,
        val bias: FloatArray,
    ) : LuxIntent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SetMklTransform) return false
            return matrix.contentEquals(other.matrix) && bias.contentEquals(other.bias)
        }
        override fun hashCode(): Int = 31 * matrix.contentHashCode() + bias.contentHashCode()
    }
    data class SetMklStrength(val strength: Float) : LuxIntent
    data object ResetMkl : LuxIntent

    // Presets
    data class SavePreset(val name: String) : LuxIntent
    data class LoadPreset(val id: String) : LuxIntent
    data class DeletePreset(val id: String) : LuxIntent
    /** Hydrate the preset list from persistence — used at boot, not in undo history. */
    data class SetPresetList(val presets: List<Preset>) : LuxIntent

    // Global
    data object ResetAll : LuxIntent
    data object Undo : LuxIntent
    data object Redo : LuxIntent
}

enum class ToneChannel { LUMA, RED, GREEN, BLUE }
enum class LggStage { LIFT, GAMMA, GAIN }
