package app.luxbuilder.io

import app.luxbuilder.state.LggAxis
import app.luxbuilder.state.LuxState
import java.util.Locale

/**
 * ASC ColorDecisionList (CDL) v1.2 writer.
 *
 * Pro grading workflows ship **CDL + LUT**: CDL captures editable "intent"
 * (primary correction), the LUT carries the rendered look. Colorists tweak
 * CDL downstream without re-rendering the LUT.
 *
 * luxbuilder's CDL emits the user's manual primaries — three `ColorCorrection`
 * nodes for the lift/gamma/gain stages plus a `SatNode` on the final
 * correction. The auto-extracted chroma MKL, OKLab tone curve, hue bands,
 * and optional HQ residual cannot be expressed in CDL's 9-DOF SOP form and
 * are deliberately omitted — they live in the .cube companion.
 *
 * Format: ASC-CDL-Specification-1.2.pdf (American Society of Cinematographers).
 * Schema: http://www.asc.cool/wp-content/uploads/2017/05/ASC-CDL-Schema-v1.2.xsd
 */
object CdlWriter {

    fun write(state: LuxState): ByteArray {
        val sat = 1f + state.basics.saturation / 100f
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<ColorDecisionList xmlns="urn:ASC:CDL:v1.2">""").append('\n')
        sb.append("  <Description>luxbuilder export -- manual primaries only; pair with companion .cube for full look</Description>\n")

        emitColorDecision(sb, "lift",  state.lgg.lift,  appendSat = null)
        emitColorDecision(sb, "gamma", state.lgg.gamma, appendSat = null)
        emitColorDecision(sb, "gain",  state.lgg.gain,  appendSat = sat)

        sb.append("</ColorDecisionList>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun emitColorDecision(
        sb: StringBuilder,
        id: String,
        axis: LggAxis,
        appendSat: Float?,
    ) {
        sb.append("  <ColorDecision>\n")
        sb.append("""    <ColorCorrection id="luxbuilder-$id">""").append('\n')
        sb.append("      <SOPNode>\n")
        sb.append("        <Slope>")
            .append(fmt(axis.slopeR)).append(' ')
            .append(fmt(axis.slopeG)).append(' ')
            .append(fmt(axis.slopeB)).append("</Slope>\n")
        sb.append("        <Offset>")
            .append(fmt(axis.offsetR)).append(' ')
            .append(fmt(axis.offsetG)).append(' ')
            .append(fmt(axis.offsetB)).append("</Offset>\n")
        sb.append("        <Power>")
            .append(fmt(axis.powerR)).append(' ')
            .append(fmt(axis.powerG)).append(' ')
            .append(fmt(axis.powerB)).append("</Power>\n")
        sb.append("      </SOPNode>\n")
        if (appendSat != null) {
            sb.append("      <SatNode>\n")
            sb.append("        <Saturation>").append(fmt(appendSat)).append("</Saturation>\n")
            sb.append("      </SatNode>\n")
        }
        sb.append("    </ColorCorrection>\n")
        sb.append("  </ColorDecision>\n")
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.6f", v)
}
