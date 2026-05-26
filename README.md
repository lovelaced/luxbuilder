<div align="center">

<img src="assets/glyph.svg" alt="luxbuilder mark — a stylized tone curve inside a calibration frame" width="160">

# luxbuilder

*On-device LUT designer for the Lumix workflow. Build a look on your phone, hand it to Lumix Lab or to your camera.*

[![Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white&style=flat-square)](https://www.android.com/)
[![Build](https://img.shields.io/github/actions/workflow/status/lovelaced/luxbuilder/build.yml?style=flat-square&label=build)](../../actions)
[![Latest release](https://img.shields.io/github/v/release/lovelaced/luxbuilder?style=flat-square&display_name=tag)](../../releases)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)

</div>

<!-- TODO: hero screenshot — Edit screen with curve mid-drag and a real photo behind the preview.
     Capture: adb exec-out screencap -p > assets/screenshots/hero.png -->

---

The Lumix Lab → camera → SD card pipeline ends in beautiful LUT-graded JPEGs. It *starts* with a problem: LUT authoring is a desktop chore — Davinci on a workstation, `.cube` exports, format conversions, file transfers. luxbuilder collapses the upstream half of that chain onto your phone. Build the look on-device, hand it off to Lumix Lab via the share sheet, or drop a `.vlt` into your camera's SD card for in-body Real Time LUT.

## Install

1. Open the [Releases page](../../releases) and download `luxbuilder-vX.Y.Z.apk`.
2. On your phone, tap the APK from your file manager. Approve "Install unknown apps" once when prompted.
3. luxbuilder appears in your launcher and as a target in any image share sheet (for reference photos).

*Bleeding-edge builds: every push to `master` produces an APK as a workflow artifact under [Actions](../../actions). CI builds use a per-run debug keystore — uninstall the previous version before installing a new one.*

## Features

- **From-scratch designer.** Tone curve (monotonic-Hermite, guaranteed no overshoot), Lift / Gamma / Gain wheels (ASC CDL), six-color HSL panel, white balance + tint, saturation / vibrance / contrast. Six panels accessible via a single horizontal swipe.
- **Reference-driven auto-fit.** Drop in 1+ photos and luxbuilder fits a structured look in **OKLab** perceptual color space: per-reference white-balance neutralization (Shades-of-Gray p=6), robust multi-reference aggregation (Weiszfeld geometric median + Bures-Wasserstein barycenter), 2-D chroma MKL on (a, b) with OAS-shrunk covariance, master tone curve on L via histogram matching, and per-hue-band residual decomposition into the existing HSL panel. **Every value lands on a slider you can override** — no black-box AI.
- **HQ Match toggle.** Optional iterative refinement (8-iteration IDT, Pitié-Kokaram 2007) with Ferradans kNN smoothing over a 33³ OKLab residual grid. Closes the non-Gaussian gap on stylized references (teal-orange, faded film, deep crush) for ~300–500 ms extra at bake time.
- **MatchScore badge.** Live Bures-Wasserstein-derived 0–100 metric in the header; amber when ≥80.
- **Live preview at 60 fps.** The full OKLab grading pipeline runs as an AGSL fragment shader on the GPU. Every slider updates the photo without lag.
- **Native Lumix + pro grading integration.** Export `.cube` (33³, shares directly to Lumix Lab), `.vlt` (17³ Panasonic, SD-card-ready), and **`.cdl`** (ASC ColorDecisionList v1.2 — editable primary intent the colorist can re-tweak downstream). The `.cube` and `.vlt` go through a **65³ supersample + ACES 1.3 Reference Gamut Compression + integer-decimate** path that eliminates trilinear-interpolation banding on smooth gradients.
- **Sister to dsqueez.** Same Halide-inspired design language, monospace numerals, hairline borders, restrained motion. luxbuilder is dsqueez's denser cousin — a calibrated instrument rather than a single-purpose tool.
- **No ads, no accounts, no telemetry.** Photos and look data stay on-device.

<!-- TODO: 2×2 screenshot grid — tone curve, LGG wheels, HSL panel, color-match strength slider -->

## How it works

The grading pipeline runs sRGB-encoded input → sRGB-encoded output through six stages, with the auto-fit cascade living inside an OKLab block in the middle:

1. **White balance** (Kelvin-derived von-Kries gains in linear-sRGB)
2. **Contrast** (sRGB-encoded around midpoint)
3. **Lift / Gamma / Gain** (ASC CDL — slope/offset/power per channel)
4. **Per-channel R/G/B tone curves** (sRGB-encoded; intentional creative casts)
5. **OKLab cascade** — auto-extracted look:
   - sRGB → linear sRGB → OKLab (Ottosson 2020)
   - **Chroma MKL** on (a, b) — closed-form 2×2 with OAS-shrunk target covariance
   - **HQ residual** (if enabled) — additive OKLab Δ from baked IDT grid
   - **Master luma curve** on OKLab L — extracted from references via histogram matching, smoothed with Steffen monotonic cubic, 5-knee user-editable
   - **OKLCh hue bands** — 6 equispaced bands with triangular partition-of-unity assignment, chroma-gated, applied as (Δh, log Δsat, ΔL)
   - OKLab → linear sRGB → sRGB
6. **Saturation + vibrance** (luma-mix; vibrance is saturation-adaptive)

The same math runs live in the AGSL fragment shader (`app/src/main/kotlin/app/luxbuilder/gpu/PipelineShader.kt`) and on the CPU when baking the export LUT (`app/src/main/kotlin/app/luxbuilder/color/ColorPipeline.kt`), guaranteeing your preview is what you ship. A 26-test JUnit suite (`./gradlew test`) covers OKLab roundtrip, MKL identity, RobustAggregator convergence with outlier rejection, LutBaker gray-axis invariance, and CdlWriter format conformance.

For export, the bake walks a **65³ supersample grid**, applies the full pipeline + ACES 1.3 Reference Gamut Compression (sRGB-tuned: limit 1.10/1.20/1.25, threshold 0.85, power 1.2), and integer-decimates to 33³ for `.cube` or 17³ for `.vlt`. Both formats use R-fastest, G-middle, B-slowest axis order. CDL output emits three sequential ColorCorrection nodes (lift / gamma / gain) plus the saturation on the gain node.

## What it deliberately doesn't do (v1.3)

- **No imported-LUT preview.** v1 only previews your own edits, not arbitrary `.cube` / `.vlt` files you bring in. The infrastructure (3D-texture sampler shader path) exists but isn't wired.
- **No V-Log domain LUTs.** v1.3 emits sRGB-domain `.vlt` for the camera's creative photo styles. V-Log monitoring LUTs require a shaper LUT and are deferred.
- **HQ residual is bake-only.** The live preview shows the standard cascade (chroma MKL + tone + hue). HQ mode's IDT residual applies to `.cube`/`.vlt` exports but not the live shader — packing a 33³ residual into AGSL as a 2D texture is v1.4 work.
- **No neural / learned LUTs.** The MIT-Adobe FiveK-trained "smart defaults" encoder is explicitly out of scope; classical color science wins on transparency. See research log in `~/.claude/plans/`.
- **No cloud sync, accounts, telemetry, ads.**

## License

[MIT](LICENSE). Bundled fonts (Inter, JetBrains Mono) retain their SIL OFL 1.1 license.

### Credits

- **[Inter](https://rsms.me/inter/)** and **[JetBrains Mono](https://www.jetbrains.com/lp/mono/)** — SIL Open Font License 1.1
- **[LUTCalc](https://github.com/cameramanben/LUTCalc)** by Ben Turley — de facto reference for `.vlt` format encoding
- **Björn Ottosson** — *[A perceptual color space for image processing](https://bottosson.github.io/posts/oklab/)* (2020), the OKLab working space
- **Pitié & Kokaram (2007)** — *The Linear Monge-Kantorovitch Linear Colour Mapping* (chroma MKL) + *Automated colour grading using colour distribution transfer* (IDT)
- **Finlayson & Trezzi (2004)** — *Shades of Gray and Colour Constancy* (per-reference WB neutralization)
- **Agueh & Carlier (2011)** + **Álvarez-Esteban et al. (2015)** — Wasserstein barycenter theory and the fixed-point iteration
- **Chen et al. (2010)** — *Shrinkage Algorithms for MMSE Covariance Estimation* (OAS shrinkage)
- **Ferradans, Papadakis, Peyré, Aujol (2014)** — *Regularized Discrete Optimal Transport* (smoothness on the IDT transport)
- **ACES Reference Gamut Compression Specification** (2022) — gamut-mapping for the supersample bake
- **AndroidX, Jetpack Compose, Material 3** — Apache 2.0

Visual language inspired by [Halide](https://halide.cam/) and [Kino](https://lux.camera/kino) from Lux Optics. Sister to [dsqueez](https://github.com/lovelaced/dsqueez).

---

<details>
<summary><b>For contributors: build from source</b></summary>

### Prerequisites

- Android Studio (Jellyfish 2026.x or newer), **or**
- JDK 17 + Android SDK platform 37 + a 2026-stable Gradle / AGP / Kotlin toolchain

### Build

```bash
./gradlew installDebug    # build + install on a connected Pixel
./gradlew assembleDebug   # leaves APK at app/build/outputs/apk/debug/
```

### Architecture

```
app/src/main/kotlin/app/luxbuilder/
├── LuxApp.kt                       Application
├── MainActivity.kt                 Single activity · intent routing · auto-fit cascade orchestration
├── state/                          LuxState · LuxIntent · pure Reducer · UDF Store with 64-entry undo
├── color/                          Color math:
│   ├── OkLab · OkMat               OKLab + shared 3×3 SPD helpers
│   ├── ColorPipeline · LutBaker    Pure-Kotlin mirror of GPU shader · 65³ supersample bake
│   ├── GamutCompress               ACES 1.3 Reference Gamut Compression (sRGB-tuned)
│   ├── ToneCurve · ToneExtractor   Fritsch-Carlson UI curve · Steffen-smoothed extractor + 5-knee picker
│   ├── Mkl · RobustAggregator      2-D chroma MKL with OAS · Weiszfeld + Bures barycenter + LW shrinkage
│   ├── HueBandExtractor            6-band OKLCh residual with circular Tikhonov smoothing
│   ├── Idt · IdtRotations          Iterative Distribution Transfer · 13-basis rotation sequence
│   ├── FerradansSmoother           Separable 3-D Gaussian on the 33³ residual grid
│   ├── SyntheticSource             Gaussian sampler for source-side comparison
│   ├── MatchScore                  Bures-Wasserstein-based 0–100 metric
│   └── NaturalImagePrior           Shipped (μ, Σ) constants in OKLab
├── gpu/                            AGSL fragment shader (OKLab pipeline) + LuxState→uniform binding
├── io/                             CubeWriter · VltWriter · CdlWriter · SafFolder · LutExporter
├── photo/                          PhotoSource (linear-f16 decode) · PhotoStats (per-ref OKLab stats) · IlluminantEstimator (Shades-of-Gray)
├── share/                          SEND / SEND_MULTIPLE / VIEW intent parser
└── ui/
    ├── theme/                      Halide/Kino tokens extended for denser editor surface
    ├── components/                 ToneCurveEditor · LggPanel · HslPanelView · WbSliders · BasicSliders ·
    │                               LuxSlider · ReferenceStrip · TabStrip · PreviewSurface
    └── screens/                    EditScreen (pinned preview + pager + MATCH badge) · ExportSheet
```

### Testing

```bash
./gradlew test         # 26-test JUnit suite, runs on JVM in ~10s
```

Coverage: OkLab roundtrip + spot checks (9), Mkl chroma identity + degenerate-covariance (4), RobustAggregator single/multi/outlier (4), LutBaker gray-axis + RGC-bounded (6), CdlWriter format (3).

### Releasing

Tag a `v*` commit and push to fire the release workflow:

```bash
git tag -a v0.2.0 -m "Release notes"
git push origin v0.2.0
```

</details>

<div align="center"><sub>Built because color authoring shouldn't require a desktop.</sub></div>
