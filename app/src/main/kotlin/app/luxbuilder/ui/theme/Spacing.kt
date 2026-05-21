package app.luxbuilder.ui.theme

import androidx.compose.ui.unit.dp

object LuxSpacing {
    val xxs  = 2.dp   // hairline gap between adjacent monospace chips
    val xs   = 4.dp
    val sm   = 8.dp
    val md   = 12.dp
    val lg   = 16.dp
    val xl   = 24.dp
    val xxl  = 40.dp
    val xxxl = 64.dp

    /** Default screen horizontal padding — wider than Material's 16dp to read "editorial". */
    val screenH = 20.dp

    /** Bottom action strip height. */
    val actionStrip = 80.dp

    /** Touch target floor — tighter than Material's 48dp; consistent with iOS pro apps. */
    val touch = 44.dp

    /** Hairline border / divider weight. */
    val hairline = 1.dp
}
