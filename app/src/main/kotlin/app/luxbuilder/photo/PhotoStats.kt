package app.luxbuilder.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.luxbuilder.color.ColorPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Color statistics extraction for color-match.
 *
 * Decodes each reference URI to a downsampled bitmap (≤ 512px longest side),
 * converts the pixel values to linear sRGB, reservoir-samples up to 50k
 * pixels across all references, then computes the 3-vector mean and 3×3
 * covariance matrix used by [app.luxbuilder.color.Mkl].
 */
object PhotoStats {

    private const val MAX_LONGEST = 512
    private const val SAMPLES_PER_IMAGE = 8000

    data class Stats(val mu: FloatArray, val sigma: Array<FloatArray>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Stats) return false
            return mu.contentEquals(other.mu) && sigma.contentDeepEquals(other.sigma)
        }
        override fun hashCode(): Int = 31 * mu.contentHashCode() + sigma.contentDeepHashCode()
    }

    /** Compute (μ, Σ) in linear-RGB across all of [uris]. */
    suspend fun compute(context: Context, uris: List<Uri>): Stats? = withContext(Dispatchers.Default) {
        if (uris.isEmpty()) return@withContext null

        // Collect (r, g, b) samples in linear-RGB
        val samples = ArrayList<FloatArray>(uris.size * SAMPLES_PER_IMAGE)
        for (uri in uris) {
            val bitmap = decodeForStats(context, uri) ?: continue
            sampleBitmap(bitmap, samples, SAMPLES_PER_IMAGE)
            bitmap.recycle()
        }
        if (samples.isEmpty()) return@withContext null

        val mu = FloatArray(3)
        for (s in samples) { mu[0] += s[0]; mu[1] += s[1]; mu[2] += s[2] }
        val n = samples.size.toFloat()
        mu[0] /= n; mu[1] /= n; mu[2] /= n

        val sigma = Array(3) { FloatArray(3) }
        for (s in samples) {
            val d0 = s[0] - mu[0]
            val d1 = s[1] - mu[1]
            val d2 = s[2] - mu[2]
            sigma[0][0] += d0 * d0; sigma[0][1] += d0 * d1; sigma[0][2] += d0 * d2
            sigma[1][0] += d1 * d0; sigma[1][1] += d1 * d1; sigma[1][2] += d1 * d2
            sigma[2][0] += d2 * d0; sigma[2][1] += d2 * d1; sigma[2][2] += d2 * d2
        }
        for (i in 0..2) for (j in 0..2) sigma[i][j] /= n

        Stats(mu, sigma)
    }

    private suspend fun decodeForStats(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / (sample * 2) >= MAX_LONGEST) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun sampleBitmap(bitmap: Bitmap, samples: MutableList<FloatArray>, count: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val rng = Random(0xC0FFEEL)
        val px = IntArray(1)
        repeat(count) {
            val x = rng.nextInt(w)
            val y = rng.nextInt(h)
            bitmap.getPixels(px, 0, 1, x, y, 1, 1)
            val argb = px[0]
            val rSrgb = ((argb shr 16) and 0xFF) / 255f
            val gSrgb = ((argb shr 8) and 0xFF) / 255f
            val bSrgb = (argb and 0xFF) / 255f
            samples.add(floatArrayOf(
                ColorPipeline.srgbToLinear(rSrgb),
                ColorPipeline.srgbToLinear(gSrgb),
                ColorPipeline.srgbToLinear(bSrgb),
            ))
        }
    }
}
