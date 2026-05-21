package app.luxbuilder.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalLuxColors = staticCompositionLocalOf { LuxDarkColors }
val LocalLuxTypography = staticCompositionLocalOf { LuxTypographyDefault }

@Composable
fun LuxTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val lux = if (dark) LuxDarkColors else LuxLightColors

    // Feed Material 3 with our values where they overlap — ripples, sheet scrim,
    // pull from MaterialTheme.colorScheme even when we mostly bypass it.
    val m3 = if (dark) {
        darkColorScheme(
            primary = lux.accent,
            onPrimary = lux.accentOn,
            secondary = lux.accent,
            background = lux.bg,
            onBackground = lux.textPrimary,
            surface = lux.surface1,
            onSurface = lux.textPrimary,
            surfaceVariant = lux.surface2,
            onSurfaceVariant = lux.textSecondary,
            outline = lux.stroke,
            outlineVariant = lux.divider,
            error = lux.error,
        )
    } else {
        lightColorScheme(
            primary = lux.accent,
            onPrimary = lux.accentOn,
            secondary = lux.accent,
            background = lux.bg,
            onBackground = lux.textPrimary,
            surface = lux.surface1,
            onSurface = lux.textPrimary,
            surfaceVariant = lux.surface2,
            onSurfaceVariant = lux.textSecondary,
            outline = lux.stroke,
            outlineVariant = lux.divider,
            error = lux.error,
        )
    }

    // System bar appearance follows the theme. enableEdgeToEdge() makes the bars
    // transparent on API 35+; we only need to set the icon-tint appearance.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    val haptics = rememberLuxHaptics()

    CompositionLocalProvider(
        LocalLuxColors provides lux,
        LocalLuxTypography provides LuxTypographyDefault,
        LocalLuxHaptics provides haptics,
    ) {
        MaterialTheme(
            colorScheme = m3,
            content = content,
        )
    }
}

object Lux {
    val colors: LuxColors @Composable get() = LocalLuxColors.current
    val type:   LuxTypography @Composable get() = LocalLuxTypography.current
    val haptics: LuxHaptics @Composable get() = LocalLuxHaptics.current
}
