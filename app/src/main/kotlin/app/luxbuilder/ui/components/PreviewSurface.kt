package app.luxbuilder.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import app.luxbuilder.ui.theme.LuxMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live preview surface.
 *
 * The *resting* state — what the user sees when no gesture is active —
 * depends on [showGraded]. References default to `false` (original photo
 * displayed untouched; LUT was derived from this so applying it would
 * exaggerate the look). User-picked photos default to `true` (LUT is the
 * point of viewing them).
 *
 * Gestures, regardless of [showGraded]:
 *  - **Horizontal pan** on the photo: A/B split. The left half always
 *    shows original, the right half always shows LUT-applied — this is
 *    the universal before/after convention. On release the split line
 *    lingers 1.5 s then fades over 600 ms.
 *  - **Triple-tap** anywhere on the surface: toggle the histogram overlay.
 */
@Composable
fun PreviewSurface(
    bitmap: android.graphics.Bitmap?,
    state: LuxState,
    showGraded: Boolean,
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

    // A/B split state
    var splitX by remember { mutableStateOf<Float?>(null) }
    val splitAlpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var lingerJob by remember { mutableStateOf<Job?>(null) }

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
                                lingerJob?.cancel()
                                scope.launch { splitAlpha.snapTo(1f) }
                                splitX = (start.x / size.width).coerceIn(0f, 1f)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                splitX = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                lingerJob = scope.launch {
                                    delay(LuxMotion.DurAbLinger.toLong())
                                    splitAlpha.animateTo(
                                        0f,
                                        animationSpec = tween(
                                            durationMillis = 600,
                                            easing = LuxMotion.EaseAmberFade,
                                        ),
                                    )
                                    splitX = null
                                    splitAlpha.snapTo(1f)
                                }
                            },
                            onDragCancel = { splitX = null },
                        )
                    },
            ) {
                val currentSplit = splitX

                if (currentSplit == null) {
                    // Resting state — render exactly one layer
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .let { m ->
                                if (showGraded) m.graphicsLayer { this.renderEffect = renderEffect }
                                else m
                            },
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    // Split state — original on left, graded on right (universal A/B convention)
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
                                val sx = currentSplit * size.width
                                clipRect(left = sx, top = 0f, right = size.width, bottom = size.height) {
                                    this@drawWithContent.drawContent()
                                }
                            },
                        contentScale = ContentScale.Fit,
                    )
                    // The split line — 1.5px amber
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(splitAlpha.value)
                            .drawWithContent {
                                val sx = currentSplit * size.width
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
