package app.luxbuilder.ui.theme

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * Semantic haptic vocabulary for luxbuilder. Extends dsqueez's set with
 * `centerDetent` (a stronger tick for wheels returning through neutral) and
 * `edgeStop` (a duller tick for sliders hitting the rails).
 */
class LuxHaptics internal constructor(private val view: View) {

    fun tapConfirm()        = perform(HapticFeedbackConstants.CONFIRM)
    fun detent()            = perform(HapticFeedbackConstants.SEGMENT_TICK)
    fun gestureEnd()        = perform(HapticFeedbackConstants.GESTURE_END)
    fun thresholdActivate() = perform(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
    fun reject()            = perform(HapticFeedbackConstants.REJECT)

    /** Strong tick for the "I am exactly at neutral" wheel detent. */
    fun centerDetent() {
        perform(HapticFeedbackConstants.CONFIRM)
        view.postDelayed({ perform(HapticFeedbackConstants.CLOCK_TICK) }, 16L)
    }

    /** Dull tick for sliders bumping into min/max. */
    fun edgeStop() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** Two-beat thunk for save/export confirmation. */
    fun saveThunk() {
        perform(HapticFeedbackConstants.CONFIRM)
        view.postDelayed({ perform(HapticFeedbackConstants.CLOCK_TICK) }, 80L)
    }

    private fun perform(constant: Int) {
        if (!view.isHapticFeedbackEnabled) return
        view.performHapticFeedback(constant, View.HAPTIC_FEEDBACK_ENABLED)
    }
}

internal val LocalLuxHaptics = staticCompositionLocalOf<LuxHaptics> {
    error("LuxHaptics not provided — wrap with LuxTheme {}")
}

@Composable
internal fun rememberLuxHaptics(): LuxHaptics {
    val view = LocalView.current
    return LuxHaptics(view)
}
