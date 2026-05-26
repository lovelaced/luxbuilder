package app.luxbuilder.color

/**
 * Pre-computed mean and covariance of natural photographic content in
 * **OKLab**. Used as a "source distribution" for [Mkl] and [ToneExtractor]
 * when the user provides only target references with no explicit source
 * pairing.
 *
 * Values derived from a curated corpus of ~200 stock photos spanning
 * portraits, landscapes, indoor scenes, golden hour, midday — typical
 * photographic content with mixed subjects and natural color cast.
 * Reservoir-sampled 50k pixels per image, decoded to linear sRGB, then
 * converted through [OkLab.fromLinearSrgb] before pooling.
 *
 * v1.3 ships these as analytically-derived starting values from the
 * previous linear-sRGB prior. The plan calls for an offline recomputation
 * pass on the actual corpus before v1.3 final release; see TODO below.
 *
 * The legacy linear-sRGB prior is kept for one release as deprecated
 * fall-back for any persisted-preset migration paths that still need it.
 */
object NaturalImagePrior {

    // ───────────────────── OKLab prior (current) ─────────────────────

    /**
     * Mean in OKLab (L, a, b). Computed by transforming the previous
     * linear-sRGB mean (0.215, 0.221, 0.183) through [OkLab.fromLinearSrgb].
     *
     * Interpretation: natural images average to mid-bright neutral
     * (L ≈ 0.60, perceptually ~60% of pure white) with a tiny green-yellow
     * cast (mild positive b, slightly negative a).
     */
    val MU_OKLAB: FloatArray = floatArrayOf(
        0.5996f,    // L — mid-bright
        -0.0065f,   // a — slightly green
        0.0147f,    // b — slightly yellow
    )

    /**
     * Covariance Σ in OKLab, row-major (symmetric).
     *
     * Natural-image variance is concentrated on the lightness axis (most of
     * the photographic dynamic range lives there); chroma spread is small.
     * Mild positive L-b correlation reflects that brighter pixels often sit
     * in warm-cast highlights in landscape/portrait content.
     *
     * TODO(v1.3 final): recompute from the actual 200-image corpus by
     * iterating through OkLab.fromLinearSrgb and pooling per-image-weighted
     * 50k samples. The values below are conservative starting estimates
     * based on the L's typical 0.3–0.85 spread and small chroma variance.
     */
    val SIGMA_OKLAB: Array<FloatArray> = arrayOf(
        floatArrayOf(0.0300f,  0.0010f,  0.0020f),
        floatArrayOf(0.0010f,  0.0008f,  0.0002f),
        floatArrayOf(0.0020f,  0.0002f,  0.0015f),
    )

    // ───────────────────── Legacy linear-sRGB prior (deprecated) ─────────────────────

    /**
     * Mean RGB in linear sRGB. Kept for one release as a fallback during
     * persisted-preset migration; new code paths should use [MU_OKLAB].
     */
    @Deprecated("Use MU_OKLAB; this exists only for preset back-compat.")
    val MU: FloatArray = floatArrayOf(0.215f, 0.221f, 0.183f)

    /**
     * Covariance Σ in linear sRGB. Kept for preset back-compat only.
     */
    @Deprecated("Use SIGMA_OKLAB; this exists only for preset back-compat.")
    val SIGMA: Array<FloatArray> = arrayOf(
        floatArrayOf(0.0431f, 0.0382f, 0.0298f),
        floatArrayOf(0.0382f, 0.0419f, 0.0337f),
        floatArrayOf(0.0298f, 0.0337f, 0.0397f),
    )
}
