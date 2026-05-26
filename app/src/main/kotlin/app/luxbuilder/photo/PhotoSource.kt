package app.luxbuilder.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PhotoSource {

    private const val PREVIEW_MAX_LONGEST_SIDE = 2048
    private const val STATS_MAX_LONGEST_SIDE = 512

    /**
     * Display preview decode — Display P3 ARGB_8888 at 2K, used by the
     * RuntimeShader preview surface. Wide-gamut for richer rendering on
     * Pixel 6 Pro's display.
     */
    suspend fun decodePreview(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val sample = computeSampleSize(longest, PREVIEW_MAX_LONGEST_SIDE)

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            }
        }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /**
     * Reference-stats decode — **linear** sRGB, RGBA_F16, 512px longest side.
     *
     * The decoder reads any source color space (ICC profile / HEIF nclx / EXIF
     * fallback) and chromatic-adapts to linear sRGB primaries at D65. The
     * RGBA_F16 buffer preserves wide-gamut excursions (negative components are
     * legal in `LINEAR_EXTENDED_SRGB`) so we don't lose data from Display P3
     * or Adobe RGB references that fall outside the sRGB cube.
     *
     * Used by [PhotoStats] for color-statistics computation; not the preview.
     */
    suspend fun decodeReferenceLinear(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val sample = computeSampleSize(longest, STATS_MAX_LONGEST_SIDE)

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGBA_F16
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
        }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun computeSampleSize(srcLongest: Int, targetLongest: Int): Int {
        if (srcLongest <= targetLongest) return 1
        var s = 1
        while ((srcLongest / (s * 2)) >= targetLongest) s *= 2
        return s
    }
}
