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
- **Color-match from references.** Drop in 1+ photos that look the way you want yours to look. luxbuilder fits a Monge-Kantorovich color transform (Pitié-Kokaram 2007) against a shipped natural-image prior and surfaces it as a starter LUT you tune by hand. **Not** a black-box AI — every step is classical color science you can override.
- **Live preview at 60 fps.** The full grading pipeline runs as an AGSL fragment shader on the GPU. Every slider, curve, and wheel updates the photo without lag.
- **Native Lumix workflow integration.** Export `.cube` (33³, shares directly to Lumix Lab) or `.vlt` (17³ Panasonic-native format, byte-compatible with LUTCalc for SD-card → camera Real Time LUT use). Filename validation matches the camera's strict ≤8 alphanumeric requirement.
- **Sister to dsqueez.** Same Halide-inspired design language, monospace numerals, hairline borders, restrained motion. luxbuilder is dsqueez's denser cousin — a calibrated instrument rather than a single-purpose tool.
- **No ads, no accounts, no telemetry.** Photos and look data stay on-device.

<!-- TODO: 2×2 screenshot grid — tone curve, LGG wheels, HSL panel, color-match strength slider -->

## How it works

The grading pipeline runs sRGB-encoded input → sRGB-encoded output through seven stages:

1. **White balance** (Kelvin-derived von-Kries gains in linear-RGB)
2. **MKL color-match** (3×3 transform + bias on linear-RGB, faded by strength)
3. **Contrast** (sRGB-encoded around midpoint)
4. **Lift / Gamma / Gain** (ASC CDL — slope/offset/power per channel)
5. **Tone curves** (master luma + per-channel R/G/B)
6. **HSL six-color** (hue-windowed deltas in HSV)
7. **Saturation + vibrance** (luma-mix; vibrance is saturation-adaptive)

The same math runs live in the AGSL fragment shader (`app/src/main/kotlin/app/luxbuilder/gpu/PipelineShader.kt`) and on the CPU when baking the export LUT (`app/src/main/kotlin/app/luxbuilder/color/ColorPipeline.kt`), guaranteeing your preview is what you ship.

For export, the pipeline samples at uniform neutral grid points to bake a 3D LUT — 33³ float32 for `.cube`, 17³ uint12 for `.vlt`. Both formats use R-fastest, G-middle, B-slowest axis order.

## What it deliberately doesn't do (v1)

- **No imported-LUT preview.** v1 only previews your own edits, not arbitrary `.cube` / `.vlt` files you bring in. The infrastructure (3D-texture sampler shader path) exists but isn't wired.
- **No iterative color match.** MKL is closed-form. Iterative Distribution Transfer (Pitié 2007) is a v1.5 "deeper match" toggle.
- **No V-Log domain LUTs.** v1 emits sRGB-domain `.vlt` for the camera's creative photo styles. V-Log monitoring LUTs require a shaper LUT and are deferred.
- **No presets / undo-redo UI.** The state machine has undo/redo (LuxStore), but no UI buttons in v1. Coming in v1.1.
- **No cloud sync, accounts, telemetry, ads.**

## License

[MIT](LICENSE). Bundled fonts (Inter, JetBrains Mono) retain their SIL OFL 1.1 license.

### Credits

- **[Inter](https://rsms.me/inter/)** and **[JetBrains Mono](https://www.jetbrains.com/lp/mono/)** — SIL Open Font License 1.1
- **[LUTCalc](https://github.com/cameramanben/LUTCalc)** by Ben Turley — de facto reference for `.vlt` format encoding
- **Pitié & Kokaram (2007)** — *The Linear Monge-Kantorovitch Linear Colour Mapping*, the closed-form color transfer math
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
├── MainActivity.kt                 Single activity, intent routing, export plumbing
├── state/                          LuxState · LuxIntent · pure Reducer · UDF Store with undo/redo
├── color/                          Pipeline math · monotonic Hermite tone curve · MKL · natural prior · LUT baker
├── gpu/                            AGSL fragment shader + LuxState→uniform binding
├── io/                             CubeWriter · VltWriter · SafFolder · LutExporter orchestrator
├── photo/                          PhotoSource (preview decode) · PhotoStats (μ + Σ for color-match)
├── share/                          SEND / SEND_MULTIPLE / VIEW intent parser
└── ui/
    ├── theme/                      Halide/Kino tokens extended for denser editor surface
    ├── components/                 ToneCurveEditor · LggPanel · HslPanelView · WbSliders · BasicSliders ·
    │                               LuxSlider · ReferenceStrip · TabStrip · PreviewSurface
    └── screens/                    EditScreen (pinned preview + pager) · ExportSheet
```

### Releasing

Tag a `v*` commit and push to fire the release workflow:

```bash
git tag -a v0.2.0 -m "Release notes"
git push origin v0.2.0
```

</details>

<div align="center"><sub>Built because color authoring shouldn't require a desktop.</sub></div>
