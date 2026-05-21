package app.luxbuilder

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.luxbuilder.photo.PhotoSource
import app.luxbuilder.state.LuxIntent
import app.luxbuilder.state.LuxStore
import app.luxbuilder.ui.components.PreviewSurface
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing
import app.luxbuilder.ui.theme.LuxTheme
import kotlinx.coroutines.launch

/**
 * Single activity. v1 wiring is a temporary smoke-test surface — Phase 3 will
 * replace this with the proper EditScreen + tabbed editor panels.
 */
class MainActivity : ComponentActivity() {

    private val store = LuxStore()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LuxTheme {
                SmokeTestScreen(store)
            }
        }
    }
}

@Composable
private fun SmokeTestScreen(store: LuxStore) {
    val context = LocalContext.current
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) store.dispatch(LuxIntent.SetSource(uri))
    }

    LaunchedEffect(state.sourceUri) {
        val uri = state.sourceUri
        bitmap = if (uri != null) PhotoSource.decodePreview(context, uri) else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Lux.colors.bg)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(LuxSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(LuxSpacing.lg),
        ) {
            Text(
                text = "luxbuilder · SMOKE TEST",
                style = Lux.type.labelMono,
                color = Lux.colors.textTertiary,
            )

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                PreviewSurface(bitmap = bitmap, state = state, modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.sourceUri == null) "PICK A PHOTO →" else "READY",
                    style = Lux.type.labelMono,
                    color = Lux.colors.textSecondary,
                )
                Button(onClick = {
                    pickMedia.launch(PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    ))
                }) { Text("Pick photo", style = Lux.type.label) }
            }

            SliderRow("CONTRAST", state.basics.contrast, -100f..100f) { v ->
                scope.launch { store.dispatch(LuxIntent.SetBasics(state.basics.copy(contrast = v))) }
            }
            SliderRow("SATURATION", state.basics.saturation, -100f..100f) { v ->
                scope.launch { store.dispatch(LuxIntent.SetBasics(state.basics.copy(saturation = v))) }
            }
            SliderRow("WB TEMP (K offset)", state.wb.tempOffsetK.toFloat(), -2000f..2000f) { v ->
                scope.launch { store.dispatch(LuxIntent.SetWhiteBalance(state.wb.copy(tempOffsetK = v.toInt()))) }
            }

            Spacer(modifier = Modifier.height(LuxSpacing.md))
            Text(
                text = "Phase 2 verification surface — proper editor lands in Phase 3.",
                style = Lux.type.caption,
                color = Lux.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = Lux.type.labelMono, color = Lux.colors.textSecondary)
            Text("%.0f".format(value), style = Lux.type.readoutSm, color = Lux.colors.textPrimary)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}
