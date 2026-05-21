package app.luxbuilder.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Halide/Kino color tokens, extended for luxbuilder's denser editor surface.
 * S0..S4 surface ramp; signal-* colors for per-channel curves; warm/cool tracks
 * for white-balance sliders.
 */
@Immutable
data class LuxColors(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,        // luxbuilder addition: wheel face base

    val accent: Color,
    val accentDim: Color,       // recently-touched, fading state
    val accentMuted: Color,
    val accentOn: Color,

    val armed: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    val divider: Color,
    val stroke: Color,
    val strokeFocus: Color,     // 1.5px focused-control outline

    val success: Color,
    val error: Color,
    val warning: Color,

    // Per-channel signal colors for the tone curve / waveform
    val signalRed: Color,
    val signalGreen: Color,
    val signalBlue: Color,
    val signalLuma: Color,

    // Gradient anchors for slider tracks
    val warmCoolWarm: Color,
    val warmCoolCool: Color,
    val tintMagenta: Color,
    val tintGreen: Color,

    val isDark: Boolean,
)

internal val LuxDarkColors = LuxColors(
    bg            = Color(0xFF0A0A0B),
    surface1      = Color(0xFF131315),
    surface2      = Color(0xFF1C1C1F),
    surface3      = Color(0xFF26262A),
    surface4      = Color(0xFF2F2F33),

    accent        = Color(0xFFE8B23A),
    accentDim     = Color(0x66E8B23A),  // 40% opacity amber
    accentMuted   = Color(0xFF8A6A22),
    accentOn      = Color(0xFF1A1304),

    armed         = Color(0xFFE5484D),

    textPrimary   = Color(0xFFF2F2F3),
    textSecondary = Color(0xFFA1A1A6),
    textTertiary  = Color(0xFF6B6B70),

    divider       = Color(0xFF2A2A2E),
    stroke        = Color(0xFF3A3A3F),
    strokeFocus   = Color(0xFF4A4A50),

    success       = Color(0xFF7BC47F),
    error         = Color(0xFFE5484D),
    warning       = Color(0xFFE8B23A),

    signalRed     = Color(0xFFE5564B),
    signalGreen   = Color(0xFF54C77B),
    signalBlue    = Color(0xFF5C8FE6),
    signalLuma    = Color(0xFFF2F2F3),

    warmCoolWarm  = Color(0xFFFFB060),
    warmCoolCool  = Color(0xFF7AB6FF),
    tintMagenta   = Color(0xFFE68FCB),
    tintGreen     = Color(0xFF7CD09E),

    isDark        = true,
)

internal val LuxLightColors = LuxColors(
    bg            = Color(0xFFF5F4F0),
    surface1      = Color(0xFFFFFFFF),
    surface2      = Color(0xFFEDEBE5),
    surface3      = Color(0xFFE2E0D9),
    surface4      = Color(0xFFD6D3CB),

    accent        = Color(0xFFB8821C),
    accentDim     = Color(0x66B8821C),
    accentMuted   = Color(0xFFD9C68C),
    accentOn      = Color(0xFFFFFFFF),

    armed         = Color(0xFFC0292E),

    textPrimary   = Color(0xFF14130F),
    textSecondary = Color(0xFF55534D),
    textTertiary  = Color(0xFF8C8A82),

    divider       = Color(0xFFD9D6CE),
    stroke        = Color(0xFFC4C0B5),
    strokeFocus   = Color(0xFFAEA89A),

    success       = Color(0xFF3E8E45),
    error         = Color(0xFFC0292E),
    warning       = Color(0xFFB8821C),

    signalRed     = Color(0xFFB0382F),
    signalGreen   = Color(0xFF3E8E45),
    signalBlue    = Color(0xFF3A6BC0),
    signalLuma    = Color(0xFF14130F),

    warmCoolWarm  = Color(0xFFCC7530),
    warmCoolCool  = Color(0xFF4684D6),
    tintMagenta   = Color(0xFFB05591),
    tintGreen     = Color(0xFF498F69),

    isDark        = false,
)
