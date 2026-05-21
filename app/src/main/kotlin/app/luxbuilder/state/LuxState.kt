package app.luxbuilder.state

import android.net.Uri

/** A single tone-curve control point in normalized [0,1] image space. */
data class CurvePoint(val x: Float, val y: Float)

/** One channel of the tone curve. End points (0,0) and (1,1) are implicit. */
data class CurveChannel(val points: List<CurvePoint>) {
    companion object {
        /** Identity: just the two endpoints. */
        val Identity = CurveChannel(emptyList())
    }
}

/** Per-channel tone curves (master luma + individual R/G/B). */
data class ToneCurves(
    val luma:  CurveChannel = CurveChannel.Identity,
    val red:   CurveChannel = CurveChannel.Identity,
    val green: CurveChannel = CurveChannel.Identity,
    val blue:  CurveChannel = CurveChannel.Identity,
) {
    val isIdentity: Boolean
        get() = luma.points.isEmpty() && red.points.isEmpty() &&
                green.points.isEmpty() && blue.points.isEmpty()
}

/** ASC CDL primary controls per wheel (lift/gamma/gain) in slope/offset/power form. */
data class LggAxis(
    val slopeR:  Float = 1f,   // gain (multiplicative)
    val slopeG:  Float = 1f,
    val slopeB:  Float = 1f,
    val offsetR: Float = 0f,   // lift (additive)
    val offsetG: Float = 0f,
    val offsetB: Float = 0f,
    val powerR:  Float = 1f,   // gamma (exponential)
    val powerG:  Float = 1f,
    val powerB:  Float = 1f,
) {
    val isNeutral: Boolean
        get() = slopeR == 1f && slopeG == 1f && slopeB == 1f &&
                offsetR == 0f && offsetG == 0f && offsetB == 0f &&
                powerR == 1f && powerG == 1f && powerB == 1f
    companion object { val Identity = LggAxis() }
}

/** Lift / Gamma / Gain triplet — three stages of primary correction. */
data class Lgg(
    val lift: LggAxis = LggAxis.Identity,
    val gamma: LggAxis = LggAxis.Identity,
    val gain: LggAxis = LggAxis.Identity,
)

/** One of six HSL color anchors. Adjustments are in normalized units (-1..+1). */
data class HslAnchor(
    val hueShift:  Float = 0f,    // -1..+1 maps to roughly ±30° per anchor
    val satShift:  Float = 0f,    // -1..+1
    val lumaShift: Float = 0f,    // -1..+1
)

/** Index into the 6-color HSL panel. */
enum class HslColor { RED, ORANGE, YELLOW, GREEN, AQUA, BLUE }

data class HslPanel(
    val anchors: Map<HslColor, HslAnchor> = HslColor.entries.associateWith { HslAnchor() },
) {
    val isNeutral: Boolean get() = anchors.values.all {
        it.hueShift == 0f && it.satShift == 0f && it.lumaShift == 0f
    }
}

/** White balance offset relative to whatever the source's encoded WB is. */
data class WhiteBalance(
    val tempOffsetK: Int = 0,      // -2000..+2000 Kelvin
    val tintOffset:  Float = 0f,   // -100..+100 (negative = green, positive = magenta)
) {
    val isNeutral: Boolean get() = tempOffsetK == 0 && tintOffset == 0f
}

/** Saturation, vibrance, contrast as three scalar adjustments. */
data class Basics(
    val saturation: Float = 0f,    // -100..+100
    val vibrance:   Float = 0f,
    val contrast:   Float = 0f,
) {
    val isNeutral: Boolean get() = saturation == 0f && vibrance == 0f && contrast == 0f
}

/** A reference photo provided by the user for color-match. */
data class RefPhoto(val uri: Uri, val displayName: String)

/** A saved preset is a snapshot of every grading parameter (no references). */
data class Preset(
    val id: String,
    val name: String,
    val tone: ToneCurves,
    val lgg: Lgg,
    val hsl: HslPanel,
    val wb: WhiteBalance,
    val basics: Basics,
    val mklStrength: Float,
)

/**
 * The complete editing state. Owned by the [Store]. All UI state lives here so
 * undo/redo and preset save/load can snapshot atomically.
 */
data class LuxState(
    val sourceUri: Uri? = null,
    val tone: ToneCurves = ToneCurves(),
    val lgg: Lgg = Lgg(),
    val hsl: HslPanel = HslPanel(),
    val wb: WhiteBalance = WhiteBalance(),
    val basics: Basics = Basics(),

    // Color-match stage — sits between WB and LGG when active. The 3×3 matrix
    // and bias are computed from references; mklStrength fades the effect in.
    val mklMatrix: FloatArray = floatArrayOf(1f, 0f, 0f,  0f, 1f, 0f,  0f, 0f, 1f),
    val mklBias:   FloatArray = floatArrayOf(0f, 0f, 0f),
    val mklStrength: Float = 0f,   // 0 = bypass, 1 = full

    val references: List<RefPhoto> = emptyList(),
    val presets: List<Preset> = emptyList(),
    val activePresetId: String? = null,
) {
    val isIdentity: Boolean
        get() = tone.isIdentity && lgg.lift.isNeutral && lgg.gamma.isNeutral && lgg.gain.isNeutral &&
                hsl.isNeutral && wb.isNeutral && basics.isNeutral && mklStrength == 0f

    /**
     * Snapshot of grading parameters only (no source or references). Used for
     * preset save/load and equality checks against the active preset for the
     * "recently-saved pulse" signature design moment.
     */
    fun gradeSnapshot(): GradeSnapshot = GradeSnapshot(
        tone = tone, lgg = lgg, hsl = hsl, wb = wb, basics = basics,
        mklStrength = mklStrength,
    )

    // FloatArray equals semantics force these manual overrides.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LuxState) return false
        return sourceUri == other.sourceUri &&
            tone == other.tone && lgg == other.lgg && hsl == other.hsl &&
            wb == other.wb && basics == other.basics &&
            mklMatrix.contentEquals(other.mklMatrix) &&
            mklBias.contentEquals(other.mklBias) &&
            mklStrength == other.mklStrength &&
            references == other.references && presets == other.presets &&
            activePresetId == other.activePresetId
    }
    override fun hashCode(): Int {
        var h = sourceUri?.hashCode() ?: 0
        h = 31 * h + tone.hashCode()
        h = 31 * h + lgg.hashCode()
        h = 31 * h + hsl.hashCode()
        h = 31 * h + wb.hashCode()
        h = 31 * h + basics.hashCode()
        h = 31 * h + mklMatrix.contentHashCode()
        h = 31 * h + mklBias.contentHashCode()
        h = 31 * h + mklStrength.hashCode()
        h = 31 * h + references.hashCode()
        h = 31 * h + presets.hashCode()
        h = 31 * h + (activePresetId?.hashCode() ?: 0)
        return h
    }
}

data class GradeSnapshot(
    val tone: ToneCurves,
    val lgg: Lgg,
    val hsl: HslPanel,
    val wb: WhiteBalance,
    val basics: Basics,
    val mklStrength: Float,
)
