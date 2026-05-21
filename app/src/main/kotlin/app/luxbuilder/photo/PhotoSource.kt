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

    suspend fun decodePreview(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        // First decode bounds-only to compute sample size
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

    private fun computeSampleSize(srcLongest: Int, targetLongest: Int): Int {
        if (srcLongest <= targetLongest) return 1
        var s = 1
        while ((srcLongest / (s * 2)) >= targetLongest) s *= 2
        return s
    }
}
