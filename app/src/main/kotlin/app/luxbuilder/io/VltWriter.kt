package app.luxbuilder.io

import app.luxbuilder.color.LutBaker
import java.util.Locale

/**
 * Panasonic `.vlt` LUT writer (3DLUT version 1.0).
 *
 * Specification (from LUTCalc lut-vlt.js, validated against Panasonic-produced
 * .vlt files):
 *
 * - ASCII text
 * - LF line endings only (CRLF is rejected by some Lumix firmware)
 * - Header:
 *   `# panasonic vlt file version 1.0`
 *   `# source vlt file ""`
 *   `LUT_3D_SIZE 17`
 * - 4913 data lines: three space-separated 12-bit integers (0..4095)
 * - Axis order: R fastest, G middle, B slowest (same as `.cube`)
 * - sRGB-encoded in / sRGB-encoded out for creative LUTs that operate on the
 *   camera's standard photo-style output (the conventional luxbuilder export
 *   target for in-camera Real Time LUT use)
 *
 * The Lumix S9 expects exactly 17³. We require [lut].size == 17.
 */
object VltWriter {

    private const val MAX_CODE = 4095   // 12-bit

    fun write(lut: LutBaker.Lut): ByteArray {
        require(lut.size == 17) { "VLT requires a 17³ LUT (got ${lut.size}³)" }
        val sb = StringBuilder()
        sb.append("# panasonic vlt file version 1.0\n")
        sb.append("# source vlt file \"\"\n")
        sb.append("LUT_3D_SIZE 17\n")
        val s = 17
        for (b in 0 until s) for (g in 0 until s) for (r in 0 until s) {
            val i = ((b * s + g) * s + r) * 3
            val ri = (lut.data[i].coerceIn(0f, 1f) * MAX_CODE + 0.5f).toInt().coerceIn(0, MAX_CODE)
            val gi = (lut.data[i + 1].coerceIn(0f, 1f) * MAX_CODE + 0.5f).toInt().coerceIn(0, MAX_CODE)
            val bi = (lut.data[i + 2].coerceIn(0f, 1f) * MAX_CODE + 0.5f).toInt().coerceIn(0, MAX_CODE)
            sb.append(String.format(Locale.US, "%d %d %d\n", ri, gi, bi))
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** Lumix camera filename rules: ≤ 8 ASCII alphanumerics, no spaces or special chars. */
    fun sanitizeFilename(input: String): String =
        input.filter { it.isLetterOrDigit() }.take(8).ifBlank { "lut" }
}
