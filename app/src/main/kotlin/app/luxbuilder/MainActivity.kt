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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.luxbuilder.photo.PhotoSource
import app.luxbuilder.state.LuxIntent
import app.luxbuilder.state.LuxStore
import app.luxbuilder.ui.screens.EditScreen
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing
import app.luxbuilder.ui.theme.LuxTheme

class MainActivity : ComponentActivity() {

    private val store = LuxStore()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LuxTheme {
                AppRoot(store)
            }
        }
    }
}

@Composable
private fun AppRoot(store: LuxStore) {
    val context = LocalContext.current
    val state by store.state.collectAsState()
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

    if (state.sourceUri == null) {
        EmptyScreen(onPickPhoto = {
            pickMedia.launch(PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            ))
        })
    } else {
        EditScreen(
            store = store,
            state = state,
            bitmap = bitmap,
            onPickPhoto = {
                pickMedia.launch(PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                ))
            },
            onExport = { /* Phase 5 */ },
        )
    }
}

@Composable
private fun EmptyScreen(onPickPhoto: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Lux.colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(LuxSpacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "luxbuilder",
                style = Lux.type.display,
                color = Lux.colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(LuxSpacing.md))
            Text(
                text = "BUILD A LOOK · PICK A PHOTO TO BEGIN",
                style = Lux.type.labelMono,
                color = Lux.colors.textTertiary,
            )
            Spacer(modifier = Modifier.height(LuxSpacing.xxl))
            Button(onClick = onPickPhoto) {
                Text("Choose Photo", style = Lux.type.label)
            }
        }
    }
}
