package app.luxbuilder.io

import app.luxbuilder.state.Basics
import app.luxbuilder.state.Lgg
import app.luxbuilder.state.LggAxis
import app.luxbuilder.state.LuxState
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * Unit tests for [CdlWriter].
 *
 * Invariants:
 *   - Output is well-formed XML with the ASC CDL v1.2 namespace
 *   - Three ColorCorrection blocks (lift, gamma, gain) with SOPNode
 *   - Saturation lives on the gain ColorCorrection
 *   - Identity state produces neutral SOP values (1 0 1)
 */
class CdlWriterTest {

    @Test
    fun identityStateEmitsWellFormedXml() {
        val xml = String(CdlWriter.write(LuxState()), Charsets.UTF_8)
        assertTrue("XML declaration", xml.startsWith("<?xml"))
        assertTrue("namespace", xml.contains("urn:ASC:CDL:v1.2"))
        assertTrue("contains lift ColorCorrection", xml.contains("luxbuilder-lift"))
        assertTrue("contains gamma ColorCorrection", xml.contains("luxbuilder-gamma"))
        assertTrue("contains gain ColorCorrection", xml.contains("luxbuilder-gain"))
        assertTrue("contains SOPNode", xml.contains("<SOPNode>"))
        assertTrue("contains SatNode", xml.contains("<SatNode>"))
        // Identity slope = 1.0, identity power = 1.0, identity offset = 0.0
        assertTrue("identity slope", xml.contains("<Slope>1.000000 1.000000 1.000000</Slope>"))
        assertTrue("identity offset", xml.contains("<Offset>0.000000 0.000000 0.000000</Offset>"))
        assertTrue("identity power", xml.contains("<Power>1.000000 1.000000 1.000000</Power>"))
        assertTrue("identity saturation", xml.contains("<Saturation>1.000000</Saturation>"))
        assertTrue("closes list", xml.contains("</ColorDecisionList>"))
    }

    @Test
    fun nonNeutralLggValuesAppearInOutput() {
        val state = LuxState(
            lgg = Lgg(
                lift = LggAxis(offsetR = 0.05f),
                gamma = LggAxis.Identity,
                gain = LggAxis(slopeR = 1.2f, slopeG = 1.1f, slopeB = 0.95f),
            ),
            basics = Basics(saturation = 50f),
        )
        val xml = String(CdlWriter.write(state), Charsets.UTF_8)
        // Lift offset on R should show 0.05
        assertTrue(
            "lift offset 0.05 not found in: $xml",
            xml.contains("<Offset>0.050000 0.000000 0.000000</Offset>"),
        )
        // Gain slope (1.2, 1.1, 0.95)
        assertTrue(
            "gain slope not found in: $xml",
            xml.contains("<Slope>1.200000 1.100000 0.950000</Slope>"),
        )
        // Saturation 1.5 (= 1 + 50/100)
        assertTrue(
            "sat 1.5 not found in: $xml",
            xml.contains("<Saturation>1.500000</Saturation>"),
        )
    }

    @Test
    fun outputIsValidUtf8AsciiCompatible() {
        // CDL XML should be pure 7-bit ASCII for maximum tool compatibility.
        val bytes = CdlWriter.write(LuxState())
        for ((i, byte) in bytes.withIndex()) {
            val v = byte.toInt() and 0xFF
            assertFalse(
                "non-ASCII byte 0x${v.toString(16)} at offset $i",
                v >= 0x80,
            )
        }
    }
}
