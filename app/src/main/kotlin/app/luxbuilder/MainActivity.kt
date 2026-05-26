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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.luxbuilder.color.FerradansSmoother
import app.luxbuilder.color.HueBandExtractor
import app.luxbuilder.color.Idt
import app.luxbuilder.color.MatchScore
import app.luxbuilder.color.Mkl
import app.luxbuilder.color.NaturalImagePrior
import app.luxbuilder.color.RobustAggregator
import app.luxbuilder.color.SyntheticSource
import app.luxbuilder.color.ToneExtractor
import app.luxbuilder.io.LutExporter
import app.luxbuilder.io.SafFolder
import app.luxbuilder.photo.PhotoSource
import app.luxbuilder.photo.PhotoStats
import app.luxbuilder.settings.UserPrefs
import app.luxbuilder.share.parseIncomingUris
import app.luxbuilder.ui.components.ExportSweep
import app.luxbuilder.state.LuxIntent
import app.luxbuilder.state.LuxStore
import app.luxbuilder.state.Preset
import app.luxbuilder.ui.screens.EditScreen
import app.luxbuilder.ui.screens.ExportSheet
import app.luxbuilder.ui.screens.PresetsSheet
import app.luxbuilder.ui.theme.Lux
import app.luxbuilder.ui.theme.LuxSpacing
import app.luxbuilder.ui.theme.LuxTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val store = LuxStore()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val incoming = parseIncomingUris(intent)
        if (incoming.isNotEmpty()) store.dispatch(LuxIntent.AddReferences(incoming))

        setContent {
            LuxTheme { AppRoot(store) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val incoming = parseIncomingUris(intent)
        if (incoming.isNotEmpty()) store.dispatch(LuxIntent.AddReferences(incoming))
    }
}

@Composable
private fun AppRoot(store: LuxStore) {
    val context = LocalContext.current
    val state by store.state.collectAsState()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPrefs(context.applicationContext) }

    // One-shot hydration from persistence
    var hydrated by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val saved = prefs.presets.first()
        if (saved.isNotEmpty()) {
            store.dispatch(LuxIntent.SetPresetList(saved), recordInHistory = false)
        }
        val savedWb = prefs.stripShootingWb.first()
        store.dispatch(LuxIntent.SetStripShootingWb(savedWb), recordInHistory = false)
        val savedHq = prefs.highQualityMatch.first()
        store.dispatch(LuxIntent.SetHighQualityMatch(savedHq), recordInHistory = false)
        hydrated = true
    }
    // Persist on any change to the preset list (only after hydration completes)
    LaunchedEffect(state.presets, hydrated) {
        if (hydrated) prefs.savePresets(state.presets)
    }
    LaunchedEffect(state.stripShootingWb, hydrated) {
        if (hydrated) prefs.setStripShootingWb(state.stripShootingWb)
    }
    LaunchedEffect(state.highQualityMatch, hydrated) {
        if (hydrated) prefs.setHighQualityMatch(state.highQualityMatch)
    }

    // Multi-pick references — the primary input flow
    val pickReferences = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 12)
    ) { uris ->
        if (uris.isNotEmpty()) store.dispatch(LuxIntent.AddReferences(uris))
    }

    // Optional source-photo picker (preview-against-my-own-photo)
    val pickPreviewSource = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) store.dispatch(LuxIntent.SetPreview(uri))
    }

    // Decode the effective preview bitmap whenever the underlying URI changes
    LaunchedEffect(state.effectivePreviewUri) {
        val uri = state.effectivePreviewUri
        bitmap = if (uri != null) PhotoSource.decodePreview(context, uri) else null
    }

    // v1.3 auto-fit cascade. Re-runs whenever references change OR either
    // toggle flips. Heavy work happens on Dispatchers.Default inside
    // PhotoStats / extractors. Debounced 400ms to avoid thrashing while the
    // user rapidly toggles or adds/removes refs.
    LaunchedEffect(state.references, state.stripShootingWb, state.highQualityMatch) {
        val refUris = state.references.map { it.uri }
        if (refUris.isEmpty()) {
            store.dispatch(LuxIntent.ResetMkl, recordInHistory = false)
            store.dispatch(LuxIntent.SetMatchScore(null), recordInHistory = false)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(400)

        // 1) Per-ref OKLab statistics (with optional WB-strip via Shades-of-Gray)
        val perRef = PhotoStats.computePerRef(context, refUris, state.stripShootingWb)
        if (perRef.isEmpty()) return@LaunchedEffect

        // 2) Robust multi-reference aggregation
        val agg = RobustAggregator.aggregate(perRef) ?: return@LaunchedEffect

        // 3) Tone curve extraction on OKLab L
        val tone = ToneExtractor.extract(
            perRef = perRef,
            sourceMuL = NaturalImagePrior.MU_OKLAB[0],
            sourceVarL = NaturalImagePrior.SIGMA_OKLAB[0][0],
        )

        // 4) Chroma-only MKL on (a, b)
        val srcAb = floatArrayOf(NaturalImagePrior.MU_OKLAB[1], NaturalImagePrior.MU_OKLAB[2])
        val srcAbSigma = floatArrayOf(
            NaturalImagePrior.SIGMA_OKLAB[1][1], NaturalImagePrior.SIGMA_OKLAB[1][2],
            NaturalImagePrior.SIGMA_OKLAB[2][1], NaturalImagePrior.SIGMA_OKLAB[2][2],
        )
        val tgtAb = floatArrayOf(agg.mu[1], agg.mu[2])
        val tgtAbSigma = floatArrayOf(
            agg.sigma[1][1], agg.sigma[1][2],
            agg.sigma[2][1], agg.sigma[2][2],
        )
        val chromaT = Mkl.solveChroma(
            muSrc_ab    = srcAb,
            sigmaSrc_ab = srcAbSigma,
            muTgt_ab    = tgtAb,
            sigmaTgt_ab = tgtAbSigma,
            nTarget     = perRef.sumOf { it.count },
        )

        // 5) Hue-band residual decomposition
        //    Compare synthetic source-after-chroma-MKL against target pixels.
        val syntheticSrc = SyntheticSource.sample(
            n = 20_000,
            mu = NaturalImagePrior.MU_OKLAB,
            sigma = NaturalImagePrior.SIGMA_OKLAB,
        )
        val srcAfterChroma = SyntheticSource.applyChroma(
            samples = syntheticSrc,
            chromaMatrix = chromaT.matrix,
            chromaBias = chromaT.bias,
            sourceAbMean = srcAb,
        )
        // Pool a sample of target pixels across all refs (cap ~50k total).
        val tgtPool = poolLabSamples(perRef, max = 50_000)
        val hsl = HueBandExtractor.extract(
            tgtLabSamples = tgtPool,
            srcLabSamples = srcAfterChroma,
        ).panel

        // 6) HQ residual — only when state.highQualityMatch is on.
        //    MKL+tone+HSL handle most of the look; IDT captures the non-
        //    Gaussian residual (skew, multimodality, clipped highlights) as
        //    an additive OKLab Δ that applies after the chroma stage.
        val hqResidual: FloatArray? = if (state.highQualityMatch) {
            val idtMaps = Idt.fit(
                source = srcAfterChroma,
                target = tgtPool,
                iters = 8,
            )
            val residual = Idt.bakeResidual(
                maps = idtMaps,
                gridSize = 33,
                chromaMatrix = chromaT.matrix,
                chromaBias   = chromaT.bias,
                sourceAbMean = srcAb,
            )
            FerradansSmoother.smoothGrid(residual, gridSize = 33)
            residual
        } else null

        // 7) Atomic application — not recorded in undo history; the
        //    reference/toggle change that triggered this cascade IS the
        //    undo unit.
        store.dispatch(
            LuxIntent.ApplyExtractedLook(
                toneLuma     = tone.toChannel(),
                hsl          = hsl,
                chromaMatrix = chromaT.matrix,
                chromaBias   = chromaT.bias,
                hqResidual   = hqResidual,
            ),
            recordInHistory = false,
        )

        // 8) MatchScore — Bures-Wasserstein on (post-chroma source) vs target
        //    Gaussians in OKLab. Closed-form, microseconds.
        val score = MatchScore.compute(
            srcMu = NaturalImagePrior.MU_OKLAB,
            srcSigma = NaturalImagePrior.SIGMA_OKLAB,
            chromaT = chromaT,
            srcAbMean = srcAb,
            tgtMu = agg.mu,
            tgtSigma = agg.sigma,
        )
        store.dispatch(LuxIntent.SetMatchScore(score), recordInHistory = false)
    }

    // Sheets
    var showPresets by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var pendingExport by remember {
        mutableStateOf<Triple<LutExporter.Format, String, LutExporter.Destination>?>(null)
    }
    // Export sweep state: bumping `sweepKey` triggers a fresh animation; the
    // line shown beneath is `sweepLine`.
    var sweepKey by remember { mutableStateOf(0) }
    var sweepLine by remember { mutableStateOf<String?>(null) }

    fun fireSweep(line: String) {
        sweepLine = line
        sweepKey += 1
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree != null) scope.launch {
            SafFolder.setFolder(context, tree)
            pendingExport?.let { (fmt, name, dest) ->
                val result = LutExporter.export(context, state, fmt, name, dest)
                if (result is LutExporter.Result.Saved) {
                    fireSweep(displayPath(result.uri, result.filename))
                }
                pendingExport = null
            }
        }
    }
    suspend fun runExport(fmt: LutExporter.Format, name: String, dest: LutExporter.Destination) {
        when (val result = LutExporter.export(context, state, fmt, name, dest)) {
            is LutExporter.Result.NeedsFolderPick -> {
                pendingExport = Triple(result.pendingFormat, result.pendingFilename, LutExporter.Destination.SAF_FOLDER)
                pickFolder.launch(null)
            }
            is LutExporter.Result.Failed -> android.util.Log.e("luxbuilder", "export failed: ${result.reason}")
            is LutExporter.Result.Saved -> fireSweep(displayPath(result.uri, result.filename))
            is LutExporter.Result.Shared -> fireSweep("share · ${result.filename}")
        }
    }

    // Routing: no references → moodboard landing; references → editor
    if (state.references.isEmpty()) {
        MoodboardLanding(
            onPickReferences = {
                pickReferences.launch(PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                ))
            },
        )
    } else {
        EditScreen(
            store = store,
            state = state,
            bitmap = bitmap,
            onAddReferences = {
                pickReferences.launch(PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                ))
            },
            onPickPreviewSource = {
                pickPreviewSource.launch(PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                ))
            },
            onShowPresets = { showPresets = true },
            onExport = { showExport = true },
        )
        if (showPresets) {
            PresetsSheet(
                presets = state.presets,
                canSave = !state.isIdentity,
                onSave = { name ->
                    store.dispatch(LuxIntent.SavePreset(name))
                },
                onLoad = { preset ->
                    store.dispatch(LuxIntent.LoadPreset(preset.id))
                    showPresets = false
                },
                onDelete = { preset ->
                    store.dispatch(LuxIntent.DeletePreset(preset.id))
                },
                onDismiss = { showPresets = false },
            )
        }
        if (showExport) {
            ExportSheet(
                onDismiss = { showExport = false },
                onConfirm = { fmt, name, dest ->
                    showExport = false
                    scope.launch { runExport(fmt, name, dest) }
                },
            )
        }
    }
    // Sweep overlay — sits at the root so it crosses the whole screen
    ExportSweep(
        key = if (sweepKey == 0) null else sweepKey,
        destinationLine = sweepLine,
    )
}

private fun displayPath(uri: android.net.Uri, filename: String): String {
    // The SAF URI is opaque ("content://com.android.externalstorage..."). Show
    // just the filename — that's all the photographer actually needs to confirm.
    return filename
}

/**
 * Pool OKLab pixel samples across per-ref stats with equal per-image weighting
 * (each ref contributes ≤ max/N pixels, regardless of its individual sample
 * count). Returns interleaved (L, a, b, …).
 */
private fun poolLabSamples(
    perRef: List<app.luxbuilder.photo.PhotoStats.RefStat>,
    max: Int,
): FloatArray {
    if (perRef.isEmpty()) return FloatArray(0)
    val perImage = max / perRef.size
    val out = FloatArray(perRef.size * perImage * 3)
    var offset = 0
    for (rs in perRef) {
        val available = rs.count
        val take = minOf(available, perImage)
        // Take a stride through the per-ref labSamples for uniform coverage
        val stride = (available / take).coerceAtLeast(1)
        var src = 0
        repeat(take) {
            out[offset]     = rs.labSamples[src * 3]
            out[offset + 1] = rs.labSamples[src * 3 + 1]
            out[offset + 2] = rs.labSamples[src * 3 + 2]
            offset += 3
            src += stride
        }
    }
    // If some refs had fewer samples, trim the buffer
    return if (offset < out.size) out.copyOf(offset) else out
}


/**
 * Empty / cold-launch state — moodboard-first. CTA opens the multi-image
 * picker so the user lands directly in the editor with refs + MKL already
 * running.
 */
@Composable
private fun MoodboardLanding(onPickReferences: () -> Unit) {
    val colors = Lux.colors
    Box(
        modifier = Modifier.fillMaxSize().background(colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(LuxSpacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "luxbuilder",
                style = Lux.type.display,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(LuxSpacing.md))
            Text(
                text = "BUILD A LOOK FROM REFERENCE PHOTOS",
                style = Lux.type.labelMono,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(LuxSpacing.xxl))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.accent)
                    .clickable { onPickReferences() }
                    .padding(horizontal = LuxSpacing.xl, vertical = LuxSpacing.md),
            ) {
                Text(
                    text = "PICK REFERENCE PHOTOS",
                    style = Lux.type.label,
                    color = colors.accentOn,
                )
            }
            Spacer(modifier = Modifier.height(LuxSpacing.lg))
            Text(
                text = "or share photos from any app into luxbuilder",
                style = Lux.type.caption,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
