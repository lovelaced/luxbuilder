package app.luxbuilder.io

import app.luxbuilder.color.LutBaker
import java.util.Locale

/**
 * Adobe `.cube` LUT writer (IRIDAS / Adobe Cube LUT Specification 1.0).
 *
 * - Text format, ASCII only
 * - LUT_3D_SIZE between 2 and 256
 * - R fastest, G middle, B slowest (matches our [LutBaker] layout)
 * - LF line endings
 * - Locale-safe number formatting via [Locale.US]
 */
object CubeWriter {

    fun write(lut: LutBaker.Lut, title: String? = null): ByteArray {
        val sb = StringBuilder()
        if (!title.isNullOrBlank()) sb.append("TITLE \"").append(title).append("\"\n")
        sb.append("LUT_3D_SIZE ").append(lut.size).append('\n')
        sb.append("DOMAIN_MIN 0.0 0.0 0.0\n")
        sb.append("DOMAIN_MAX 1.0 1.0 1.0\n")
        // Iterate B slowest, G middle, R fastest (the .cube convention)
        val s = lut.size
        for (b in 0 until s) for (g in 0 until s) for (r in 0 until s) {
            val i = ((b * s + g) * s + r) * 3
            sb.append(String.format(Locale.US, "%.6f %.6f %.6f\n",
                lut.data[i].coerceIn(0f, 1f),
                lut.data[i + 1].coerceIn(0f, 1f),
                lut.data[i + 2].coerceIn(0f, 1f),
            ))
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }
}
