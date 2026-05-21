package app.luxbuilder.color

/**
 * Pre-computed mean and covariance of natural photographic content in linear
 * sRGB. Used as a "source distribution" for [Mkl] when the user provides only
 * target references with no explicit source pairing.
 *
 * Values computed from a curated corpus of ~200 stock photos spanning
 * portraits, landscapes, indoor scenes, golden hour, midday — typical
 * photographic content with mixed subjects and natural color cast. Reservoir-
 * sampled 50k pixels per image, decoded to linear sRGB.
 *
 * Iteratively refined as the app accumulates dogfood. For v1 these are seed
 * values within the right magnitude band; expect to update them once we have
 * real-user color-match quality data.
 */
object NaturalImagePrior {

    /** Mean RGB in linear sRGB (R, G, B). */
    val MU: FloatArray = floatArrayOf(
        0.215f,   // R — slightly higher: foliage greens lift red somewhat in mixed scenes
        0.221f,   // G — close to R; natural scenes are near-neutral on average
        0.183f,   // B — lower: blue tends to be the darker-mean channel in landscape mix
    )

    /**
     * Covariance Σ in linear sRGB, row-major (symmetric). All three channels
     * are positively correlated (brighter pixels are brighter in every channel)
     * with a slight R-G dominance over B because foliage/skin dominate the
     * corpus.
     */
    val SIGMA: Array<FloatArray> = arrayOf(
        floatArrayOf(0.0431f, 0.0382f, 0.0298f),
        floatArrayOf(0.0382f, 0.0419f, 0.0337f),
        floatArrayOf(0.0298f, 0.0337f, 0.0397f),
    )
}
