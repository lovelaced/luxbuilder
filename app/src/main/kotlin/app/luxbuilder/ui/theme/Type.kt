package app.luxbuilder.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import app.luxbuilder.R

private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val Inter = FontFamily(
    Font(googleFont = GoogleFont("Inter"), fontProvider = googleFontProvider, weight = FontWeight.W400),
    Font(googleFont = GoogleFont("Inter"), fontProvider = googleFontProvider, weight = FontWeight.W500),
    Font(googleFont = GoogleFont("Inter"), fontProvider = googleFontProvider, weight = FontWeight.W600),
)

private val JetBrainsMono = FontFamily(
    Font(googleFont = GoogleFont("JetBrains Mono"), fontProvider = googleFontProvider, weight = FontWeight.W400),
    Font(googleFont = GoogleFont("JetBrains Mono"), fontProvider = googleFontProvider, weight = FontWeight.W500),
)

private const val MonoFeatures = "tnum, ss01"

@Immutable
data class LuxTypography(
    val display:        TextStyle,
    val displayNumeric: TextStyle,   // post-export filename reveal
    val title:          TextStyle,
    val body:           TextStyle,
    val label:          TextStyle,
    val labelMono:      TextStyle,   // uppercase panel headers (TONE WHEELS HSL …)
    val caption:        TextStyle,
    val micro:          TextStyle,
    val numDisplay:     TextStyle,
    val numBody:        TextStyle,
    val readoutLg:      TextStyle,   // wheel R/G/B/Y readouts
    val readoutSm:      TextStyle,   // inline numeric chips, curve coords
    val numMicro:       TextStyle,
)

internal val LuxTypographyDefault = LuxTypography(
    display = TextStyle(
        fontFamily = Inter, fontSize = 32.sp, lineHeight = 36.sp,
        fontWeight = FontWeight.W500, letterSpacing = (-0.4).sp,
    ),
    displayNumeric = TextStyle(
        fontFamily = JetBrainsMono, fontSize = 22.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.W500, letterSpacing = (-0.5).sp,
        fontFeatureSettings = MonoFeatures,
    ),
    title = TextStyle(
        fontFamily = Inter, fontSize = 20.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.W500, letterSpacing = (-0.2).sp,
    ),
    body = TextStyle(
        fontFamily = Inter, fontSize = 15.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.W400,
    ),
    label = TextStyle(
        fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.W500, letterSpacing = 0.2.sp,
    ),
    labelMono = TextStyle(
        fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 12.sp,
        fontWeight = FontWeight.W600, letterSpacing = 1.2.sp,
        fontFeatureSettings = MonoFeatures,
    ),
    caption = TextStyle(
        fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.W400,
    ),
    micro = TextStyle(
        fontFamily = Inter, fontSize = 10.sp, lineHeight = 12.sp,
        fontWeight = FontWeight.W600, letterSpacing = 1.2.sp,
    ),
    numDisplay = TextStyle(
        fontFamily = JetBrainsMono, fontSize = 28.sp, lineHeight = 32.sp,
        fontWeight = FontWeight.W500, fontFeatureSettings = MonoFeatures,
    ),
    numBody = TextStyle(
        fontFamily = JetBrainsMono, fontSize = 14.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.W400, fontFeatureSettings = MonoFeatures,
    ),
    readoutLg = TextStyle(
        fontFamily = JetBrainsMono, fontSize = 14.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.W500, letterSpacing = (-0.2).sp,
        fontFeatureSettings = MonoFeatures,
    ),
    readoutSm = TextStyle(
        fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.W500, fontFeatureSettings = MonoFeatures,
    ),
    numMicro = TextStyle(
        fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.W400, fontFeatureSettings = MonoFeatures,
    ),
)
