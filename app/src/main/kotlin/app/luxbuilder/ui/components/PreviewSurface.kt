package app.luxbuilder.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import app.luxbuilder.gpu.PipelineShader
import app.luxbuilder.gpu.ShaderUniforms
import app.luxbuilder.state.LuxState
import app.luxbuilder.ui.theme.Lux

/**
 * Live preview of the source photo with the full grading pipeline applied
 * via an AGSL RuntimeShader bound through Modifier.graphicsLayer.
 *
 * Triple-tap toggles a hairline RGB histogram overlay.
 */
@Composable
fun PreviewSurface(
    bitmap: android.graphics.Bitmap?,
    state: LuxState,
    modifier: Modifier = Modifier,
) {
    val colors = Lux.colors

    val shader = remember { RuntimeShader(PipelineShader.SOURCE) }
    val uniforms = remember(shader) { ShaderUniforms(shader) }

    var renderEffect by remember { mutableStateOf<androidx.compose.ui.graphics.RenderEffect?>(null) }
    LaunchedEffect(state) {
        uniforms.bind(state)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "composable")
            .asComposeRenderEffect()
    }

    var showHistogram by remember { mutableStateOf(false) }
    val imageBitmap: ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg)
            .pointerInput(Unit) {
                var tapCount = 0
                var lastTap = 0L
                detectTapGestures(
                    onTap = {
                        val now = System.currentTimeMillis()
                        tapCount = if (now - lastTap < 350) tapCount + 1 else 1
                        lastTap = now
                        if (tapCount >= 3) {
                            showHistogram = !showHistogram
                            tapCount = 0
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f)
                    .background(colors.surface1),
            )
        } else {
            val aspect = imageBitmap.width.toFloat() / imageBitmap.height.toFloat()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect),
            ) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { this.renderEffect = renderEffect },
                    contentScale = ContentScale.Fit,
                )
                if (showHistogram) {
                    HistogramOverlay(bitmap = bitmap, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
