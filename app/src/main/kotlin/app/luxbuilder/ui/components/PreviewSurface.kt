package app.luxbuilder.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import app.luxbuilder.gpu.PipelineShader
import app.luxbuilder.gpu.ShaderUniforms
import app.luxbuilder.state.LuxState
import app.luxbuilder.ui.theme.Lux

/**
 * Three resting states for the preview. SPLIT persists indefinitely so the
 * user can tune the grade against a frozen comparison position.
 */
enum class PreviewMode { ORIGINAL, GRADED, SPLIT }

/**
 * Live preview surface.
 *
 * Resting state is driven entirely by [mode]:
 *  - `ORIGINAL` — show the photo untouched.
 *  - `GRADED`   — show the photo with the live AGSL pipeline applied.
 *  - `SPLIT`    — show original on the left half and graded on the right,
 *                 with a thin amber hairline marking the boundary. The split
 *                 position persists at the last drag position (or 0.5 if the
 *                 user hasn't dragged since entering SPLIT).
 *
 * Gestures:
 *  - **Horizontal pan** anywhere on the photo: enters SPLIT mode (via
 *    [onUserDrag]) and sets the split position to the touch point. The
 *    split stays put when the finger lifts — clear it by selecting
 *    `ORIGINAL` or `GRADED` from the segmented control above.
 *  - **Triple-tap** anywhere on the surface: toggle histogram overlay.
 */
@Composable
fun PreviewSurface(
    bitmap: android.graphics.Bitmap?,
    state: LuxState,
    mode: PreviewMode,
    onUserDrag: () -> Unit,
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

    // splitX is the user's last drag position (0..1). Persists across drag end.
    // When the caller flips mode away from SPLIT, we clear it so re-entering
    // SPLIT later doesn't snap back to a stale position.
    var splitX by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(mode) {
        if (mode != PreviewMode.SPLIT) splitX = null
    }

    // The fraction actually drawn this frame. Defaults to 0.5 the first time
    // the user enters SPLIT without having dragged yet.
    val effectiveSplit: Float? = when (mode) {
        PreviewMode.SPLIT -> splitX ?: 0.5f
        else -> null
    }

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
                    .aspectRatio(aspect)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                onUserDrag()
                                splitX = (start.x / size.width).coerceIn(0f, 1f)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                splitX = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                            // No onDragEnd handling — splitX persists deliberately.
                        )
                    },
            ) {
                if (effectiveSplit == null) {
                    // Resting state — render exactly one layer
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .let { m ->
                                if (mode == PreviewMode.GRADED) {
                                    m.graphicsLayer { this.renderEffect = renderEffect }
                                } else m
                            },
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    // Split — original on left, graded on right (universal A/B convention)
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { this.renderEffect = renderEffect }
                            .drawWithContent {
                                val sx = effectiveSplit * size.width
                                clipRect(left = sx, top = 0f, right = size.width, bottom = size.height) {
                                    this@drawWithContent.drawContent()
                                }
                            },
                        contentScale = ContentScale.Fit,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                val sx = effectiveSplit * size.width
                                drawLine(
                                    color = Color(0xFFE8B23A),
                                    start = Offset(sx, 0f),
                                    end = Offset(sx, size.height),
                                    strokeWidth = 1.5f,
                                )
                            },
                    )
                }

                if (showHistogram) {
                    HistogramOverlay(bitmap = bitmap, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
