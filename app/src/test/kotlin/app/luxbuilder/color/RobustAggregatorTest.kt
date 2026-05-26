package app.luxbuilder.color

import app.luxbuilder.photo.IlluminantEstimator
import app.luxbuilder.photo.PhotoStats
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

/**
 * Unit tests for [RobustAggregator].
 *
 * Invariants:
 *   - N=1 returns the input ref's stats unchanged
 *   - For multiple identical refs, μ* equals the shared μ and Σ* equals the
 *     shared Σ (within Ledoit-Wolf shrinkage)
 *   - Outliers (refs distant from the cluster) are flagged via Mahalanobis
 */
class RobustAggregatorTest {

    @Test
    fun singleRefReturnsItsStatsUnchanged() {
        val mu = floatArrayOf(0.6f, -0.01f, 0.02f)
        val sigma = arrayOf(
            floatArrayOf(0.030f, 0.001f, 0.002f),
            floatArrayOf(0.001f, 0.0008f, 0.0002f),
            floatArrayOf(0.002f, 0.0002f, 0.0015f),
        )
        val ref = PhotoStats.RefStat(
            mu = mu, sigma = sigma, count = 50_000,
            gains = IlluminantEstimator.Gains.Neutral,
            lHistogram = FloatArray(PhotoStats.L_HISTOGRAM_BINS) { 1f / PhotoStats.L_HISTOGRAM_BINS },
            labSamples = FloatArray(0),
        )
        val agg = RobustAggregator.aggregate(listOf(ref))!!
        assertCloseEnough(mu[0], agg.mu[0], 1e-5f, "μ_L")
        assertCloseEnough(mu[1], agg.mu[1], 1e-5f, "μ_a")
        assertCloseEnough(mu[2], agg.mu[2], 1e-5f, "μ_b")
        assertTrue("no outliers expected for single ref", agg.outlierIndices.isEmpty())
    }

    @Test
    fun multipleIdenticalRefsConverge() {
        val mu = floatArrayOf(0.6f, 0.05f, -0.04f)
        val sigma = arrayOf(
            floatArrayOf(0.03f, 0.001f, 0.0f),
            floatArrayOf(0.001f, 0.005f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 0.004f),
        )
        val ref = PhotoStats.RefStat(
            mu = mu, sigma = sigma, count = 50_000,
            gains = IlluminantEstimator.Gains.Neutral,
            lHistogram = FloatArray(PhotoStats.L_HISTOGRAM_BINS) { 1f / PhotoStats.L_HISTOGRAM_BINS },
            labSamples = FloatArray(0),
        )
        val agg = RobustAggregator.aggregate(listOf(ref, ref, ref))!!
        assertCloseEnough(mu[0], agg.mu[0], 1e-4f, "μ_L (3 identical refs)")
        assertCloseEnough(mu[1], agg.mu[1], 1e-4f, "μ_a")
        assertCloseEnough(mu[2], agg.mu[2], 1e-4f, "μ_b")
        // Covariance roughly matches (Ledoit-Wolf adds slight shrinkage at λ=0.10)
        assertCloseEnough(sigma[0][0], agg.sigma[0][0], 0.01f, "Σ_LL")
    }

    @Test
    fun outlierRefIsFlagged() {
        val cluster = floatArrayOf(0.55f, 0.0f, 0.0f)
        val outlier = floatArrayOf(0.95f, 0.30f, 0.30f)   // very far from cluster
        val sigma = arrayOf(
            floatArrayOf(0.001f, 0f, 0f),
            floatArrayOf(0f, 0.001f, 0f),
            floatArrayOf(0f, 0f, 0.001f),
        )
        val empty = FloatArray(PhotoStats.L_HISTOGRAM_BINS) { 1f / PhotoStats.L_HISTOGRAM_BINS }
        val noSamples = FloatArray(0)
        val refs = listOf(
            PhotoStats.RefStat(cluster, sigma, 50000, IlluminantEstimator.Gains.Neutral, empty, noSamples),
            PhotoStats.RefStat(cluster, sigma, 50000, IlluminantEstimator.Gains.Neutral, empty, noSamples),
            PhotoStats.RefStat(cluster, sigma, 50000, IlluminantEstimator.Gains.Neutral, empty, noSamples),
            PhotoStats.RefStat(outlier, sigma, 50000, IlluminantEstimator.Gains.Neutral, empty, noSamples),
        )
        val agg = RobustAggregator.aggregate(refs)!!
        assertTrue(
            "outlier (index 3) should be flagged, got ${agg.outlierIndices}",
            3 in agg.outlierIndices,
        )
        // Geometric median should NOT be pulled significantly toward the outlier
        // (Weiszfeld breakdown is 0.5; 1-of-4 ratio = 0.25 < 0.5).
        assertCloseEnough(cluster[0], agg.mu[0], 0.05f, "μ_L pulled to outlier")
    }

    @Test
    fun emptyInputReturnsNull() {
        val agg = RobustAggregator.aggregate(emptyList())
        assertTrue("expected null for empty input", agg == null)
    }

    private fun assertCloseEnough(expected: Float, actual: Float, eps: Float, label: String) {
        assertTrue(
            "$label: expected $expected got $actual (|Δ|=${abs(expected - actual)} > $eps)",
            abs(expected - actual) <= eps,
        )
    }
}
