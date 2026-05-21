package app.luxbuilder.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

object LuxMotion {
    // Durations (ms)
    const val DurInstant      = 80
    const val DurQuick        = 180
    const val DurNormal       = 280
    const val DurSlow         = 520
    const val DurReveal       = 720
    const val DurExportSweep  = 900    // signature: full-screen amber sweep
    const val DurAmberFade    = 600    // accent → accentDim decay
    const val DurAbLinger     = 1500   // A/B split bar lingers after release

    // Easings — custom, not Material defaults
    val EaseStandard    = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EaseExit        = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
    val EaseStretch     = CubicBezierEasing(0.65f, 0.0f, 0.35f, 1.0f)
    val EaseCurveDrag   = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val EaseAmberFade   = CubicBezierEasing(0.4f, 0.0f, 0.6f, 1.0f)
    val EaseExportSweep = CubicBezierEasing(0.65f, 0.0f, 0.35f, 1.0f)

    fun <T> springChrome() = spring<T>(dampingRatio = 1f,    stiffness = 800f)
    fun <T> springSheet()  = spring<T>(dampingRatio = 0.9f,  stiffness = 500f)
    fun <T> springDial()   = spring<T>(dampingRatio = 0.75f, stiffness = 400f)
    fun <T> springSettle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
}
