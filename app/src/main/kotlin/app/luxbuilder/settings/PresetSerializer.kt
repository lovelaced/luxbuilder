package app.luxbuilder.settings

import app.luxbuilder.state.Basics
import app.luxbuilder.state.CurveChannel
import app.luxbuilder.state.CurvePoint
import app.luxbuilder.state.HslAnchor
import app.luxbuilder.state.HslColor
import app.luxbuilder.state.HslPanel
import app.luxbuilder.state.Lgg
import app.luxbuilder.state.LggAxis
import app.luxbuilder.state.Preset
import app.luxbuilder.state.ToneCurves
import app.luxbuilder.state.WhiteBalance
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compact JSON serialization for saved presets. Uses Android's built-in
 * [JSONObject] / [JSONArray] (no extra dependency on kotlinx.serialization).
 *
 * Format-stability note: changing any field name breaks existing saved
 * presets. If we add a new control in a future version, give it a default
 * during decode so older saved presets continue to load cleanly.
 */
object PresetSerializer {

    private const val VERSION = 1

    fun encodeList(presets: List<Preset>): String {
        val root = JSONObject()
        root.put("v", VERSION)
        val arr = JSONArray()
        presets.forEach { arr.put(encode(it)) }
        root.put("presets", arr)
        return root.toString()
    }

    fun decodeList(json: String): List<Preset> {
        if (json.isBlank()) return emptyList()
        val root = JSONObject(json)
        val arr = root.optJSONArray("presets") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { decode(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun encode(p: Preset): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("tone", encodeTone(p.tone))
        put("lgg", encodeLgg(p.lgg))
        put("hsl", encodeHsl(p.hsl))
        put("wb", encodeWb(p.wb))
        put("basics", encodeBasics(p.basics))
        put("mklStrength", p.mklStrength.toDouble())
    }

    private fun decode(o: JSONObject): Preset = Preset(
        id = o.getString("id"),
        name = o.getString("name"),
        tone = decodeTone(o.getJSONObject("tone")),
        lgg = decodeLgg(o.getJSONObject("lgg")),
        hsl = decodeHsl(o.getJSONObject("hsl")),
        wb = decodeWb(o.getJSONObject("wb")),
        basics = decodeBasics(o.getJSONObject("basics")),
        mklStrength = o.optDouble("mklStrength", 0.0).toFloat(),
    )

    private fun encodeTone(t: ToneCurves): JSONObject = JSONObject().apply {
        put("luma", encodeChannel(t.luma))
        put("r", encodeChannel(t.red))
        put("g", encodeChannel(t.green))
        put("b", encodeChannel(t.blue))
    }

    private fun decodeTone(o: JSONObject): ToneCurves = ToneCurves(
        luma = decodeChannel(o.getJSONObject("luma")),
        red = decodeChannel(o.getJSONObject("r")),
        green = decodeChannel(o.getJSONObject("g")),
        blue = decodeChannel(o.getJSONObject("b")),
    )

    private fun encodeChannel(c: CurveChannel): JSONObject = JSONObject().apply {
        val pts = JSONArray()
        c.points.forEach { p ->
            pts.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()))
        }
        put("pts", pts)
    }

    private fun decodeChannel(o: JSONObject): CurveChannel {
        val pts = o.optJSONArray("pts") ?: return CurveChannel.Identity
        val list = (0 until pts.length()).map { i ->
            val a = pts.getJSONArray(i)
            CurvePoint(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
        }
        return CurveChannel(list)
    }

    private fun encodeLgg(l: Lgg): JSONObject = JSONObject().apply {
        put("lift", encodeAxis(l.lift))
        put("gamma", encodeAxis(l.gamma))
        put("gain", encodeAxis(l.gain))
    }

    private fun decodeLgg(o: JSONObject): Lgg = Lgg(
        lift = decodeAxis(o.getJSONObject("lift")),
        gamma = decodeAxis(o.getJSONObject("gamma")),
        gain = decodeAxis(o.getJSONObject("gain")),
    )

    private fun encodeAxis(a: LggAxis): JSONObject = JSONObject().apply {
        put("sR", a.slopeR.toDouble()); put("sG", a.slopeG.toDouble()); put("sB", a.slopeB.toDouble())
        put("oR", a.offsetR.toDouble()); put("oG", a.offsetG.toDouble()); put("oB", a.offsetB.toDouble())
        put("pR", a.powerR.toDouble()); put("pG", a.powerG.toDouble()); put("pB", a.powerB.toDouble())
    }

    private fun decodeAxis(o: JSONObject): LggAxis = LggAxis(
        slopeR = o.optDouble("sR", 1.0).toFloat(),
        slopeG = o.optDouble("sG", 1.0).toFloat(),
        slopeB = o.optDouble("sB", 1.0).toFloat(),
        offsetR = o.optDouble("oR", 0.0).toFloat(),
        offsetG = o.optDouble("oG", 0.0).toFloat(),
        offsetB = o.optDouble("oB", 0.0).toFloat(),
        powerR = o.optDouble("pR", 1.0).toFloat(),
        powerG = o.optDouble("pG", 1.0).toFloat(),
        powerB = o.optDouble("pB", 1.0).toFloat(),
    )

    private fun encodeHsl(h: HslPanel): JSONObject = JSONObject().apply {
        h.anchors.forEach { (color, anchor) ->
            put(color.name, JSONArray()
                .put(anchor.hueShift.toDouble())
                .put(anchor.satShift.toDouble())
                .put(anchor.lumaShift.toDouble())
            )
        }
    }

    private fun decodeHsl(o: JSONObject): HslPanel {
        val map = HslColor.entries.associateWith { color ->
            val arr = o.optJSONArray(color.name)
            if (arr == null) HslAnchor()
            else HslAnchor(
                hueShift = arr.optDouble(0, 0.0).toFloat(),
                satShift = arr.optDouble(1, 0.0).toFloat(),
                lumaShift = arr.optDouble(2, 0.0).toFloat(),
            )
        }
        return HslPanel(map)
    }

    private fun encodeWb(w: WhiteBalance): JSONObject = JSONObject().apply {
        put("k", w.tempOffsetK)
        put("t", w.tintOffset.toDouble())
    }

    private fun decodeWb(o: JSONObject): WhiteBalance = WhiteBalance(
        tempOffsetK = o.optInt("k", 0),
        tintOffset = o.optDouble("t", 0.0).toFloat(),
    )

    private fun encodeBasics(b: Basics): JSONObject = JSONObject().apply {
        put("sat", b.saturation.toDouble())
        put("vib", b.vibrance.toDouble())
        put("con", b.contrast.toDouble())
    }

    private fun decodeBasics(o: JSONObject): Basics = Basics(
        saturation = o.optDouble("sat", 0.0).toFloat(),
        vibrance = o.optDouble("vib", 0.0).toFloat(),
        contrast = o.optDouble("con", 0.0).toFloat(),
    )
}
