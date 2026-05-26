package app.luxbuilder.gpu

/**
 * AGSL fragment shader running the full luxbuilder grading pipeline live.
 *
 * Mirrors [app.luxbuilder.color.ColorPipeline] one-to-one — any change to
 * the pipeline math here must land in both places to keep the live preview
 * pixel-identical to what [app.luxbuilder.color.LutBaker] exports.
 *
 * Pipeline order (sRGB-encoded in → sRGB-encoded out):
 *   1. White balance     (Kelvin gains in linear sRGB)
 *   2. Contrast          (around midpoint, sRGB-encoded)
 *   3. LGG               (lift → gamma → gain, ASC CDL per stage, sRGB)
 *   4. Per-channel R/G/B tone curves   (sRGB-encoded; user-driven cast)
 *   5. ─── OKLab cascade (auto-extracted look) ───
 *      a. sRGB → linear sRGB → OKLab
 *      b. Chroma MKL on (a, b) — 2×2 matrix + 2-vector bias
 *      c. Master luma curve on OKLab L
 *      d. Hue-band shifts in OKLCh — 6 bands × (Δh, Δs, ΔL)
 *      e. OKLab → linear sRGB
 *   6. Saturation / vibrance   (sRGB-encoded)
 *
 * Uniforms (set via [ShaderUniforms.bind]):
 *   shader composable               source preview image
 *   shader toneCurve                1024×4 RGBA bitmap, rows: master-L, R, G, B
 *   float4 uHasCurve                per-row enable flag (luma, R, G, B)
 *
 *   float3 uWbGains                 per-channel multiplicative gains in linear sRGB
 *
 *   float4 uMklChromaMat            row-major 2×2 chroma MKL  (m00,m01,m10,m11)
 *   float2 uMklChromaBias           chroma MKL bias on (a, b)
 *   float  uMklStrength             0 = bypass, 1 = full chroma MKL
 *
 *   float  uContrast                1 = neutral, e.g. 1.2 = +20%
 *
 *   float3 uLift_slope,  uLift_offset,  uLift_power
 *   float3 uGamma_slope, uGamma_offset, uGamma_power
 *   float3 uGain_slope,  uGain_offset,  uGain_power
 *
 *   // 6 OKLCh hue-band shifts in OKLab UNITS: (hueShiftRad, logSatScale, lumaShift)
 *   float3 uHsl_red, uHsl_orange, uHsl_yellow, uHsl_green, uHsl_aqua, uHsl_blue
 *
 *   float  uSaturation, uVibrance
 */
object PipelineShader {

    /** AGSL source. Bound via android.graphics.RuntimeShader on API 33+. */
    val SOURCE: String = """
        uniform shader composable;
        uniform shader toneCurve;
        uniform float4 uHasCurve;     // (luma, R, G, B) — 1.0 = active, 0.0 = identity

        uniform float3 uWbGains;

        uniform float4 uMklChromaMat;   // (m00, m01, m10, m11)
        uniform float2 uMklChromaBias;  // (bias_a, bias_b)
        uniform float  uMklStrength;

        uniform float  uContrast;

        uniform float3 uLift_slope;
        uniform float3 uLift_offset;
        uniform float3 uLift_power;
        uniform float3 uGamma_slope;
        uniform float3 uGamma_offset;
        uniform float3 uGamma_power;
        uniform float3 uGain_slope;
        uniform float3 uGain_offset;
        uniform float3 uGain_power;

        // Each anchor packs (hueShiftRad, logSatScale, lumaShift) in OKLab units.
        uniform float3 uHsl_red;
        uniform float3 uHsl_orange;
        uniform float3 uHsl_yellow;
        uniform float3 uHsl_green;
        uniform float3 uHsl_aqua;
        uniform float3 uHsl_blue;

        uniform float  uSaturation;
        uniform float  uVibrance;

        // ───── sRGB ↔ linear ─────
        float3 srgbToLinear(float3 c) {
            float3 lo = c / 12.92;
            float3 hi = pow((c + 0.055) / 1.055, float3(2.4));
            return mix(lo, hi, step(0.04045, c));
        }
        float3 linearToSrgb(float3 c) {
            float3 lo = 12.92 * c;
            float3 hi = 1.055 * pow(max(c, float3(0.0)), float3(1.0/2.4)) - 0.055;
            return mix(lo, hi, step(0.0031308, c));
        }

        // ───── OKLab (Ottosson 2020) ─────
        float3 linearToOklab(float3 c) {
            float l = 0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b;
            float m = 0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b;
            float s = 0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b;
            // Sign-preserving cube root
            float l_ = sign(l) * pow(abs(l), 1.0/3.0);
            float m_ = sign(m) * pow(abs(m), 1.0/3.0);
            float s_ = sign(s) * pow(abs(s), 1.0/3.0);
            return float3(
                0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
                1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
                0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_);
        }
        float3 oklabToLinear(float3 lab) {
            float l_ = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
            float m_ = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
            float s_ = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
            float l = l_ * l_ * l_;
            float m = m_ * m_ * m_;
            float s = s_ * s_ * s_;
            return float3(
                 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
        }

        // ───── ASC CDL — per-channel slope/offset/power ─────
        float3 cdl(float3 v, float3 slope, float3 offset, float3 power) {
            float3 t = max(v * slope + offset, float3(0.0));
            return pow(t, 1.0 / max(power, float3(1e-3)));
        }

        // ───── Tone-curve LUT sampling ─────
        // Sample row `row` (0..3) of the 1024×4 RGBA curve bitmap.
        float sampleCurveRow(float x, int row) {
            return toneCurve.eval(float2(clamp(x, 0.0, 1.0) * 1024.0, float(row) + 0.5)).r;
        }

        // ───── OKLCh hue-band shift ─────
        // For one anchor at hue `anchorH` (radians) with cfg = (Δh, logΔsat, Δlum),
        // returns weighted contributions accumulated into deltas.
        // Triangular falloff w = max(0, 1 − |Δh|/(π/3))  (half-width = 60°).
        float circDist(float a, float b) {
            float d = a - b;
            // Wrap into (-π, π]
            d = mod(d + 3.14159265, 6.28318530) - 3.14159265;
            return d;
        }
        float3 applyAnchor(float h, float anchorH, float3 cfg) {
            float d = circDist(h, anchorH);
            float ad = abs(d);
            float w = max(0.0, 1.0 - ad / (3.14159265 / 3.0));
            // Triangular squared for smoother roll-off — matches CPU extractor
            w = w * w;
            return float3(cfg.x * w, cfg.y * w, cfg.z * w);
        }

        half4 main(float2 coords) {
            half4 src = composable.eval(coords);
            float3 rgb = float3(src.rgb);

            // 1. White balance — multiplicative gains in linear sRGB
            if (any(notEqual(uWbGains, float3(1.0)))) {
                float3 lin = srgbToLinear(rgb);
                lin = lin * uWbGains;
                rgb = linearToSrgb(clamp(lin, 0.0, 1.0));
            }

            // 2. Contrast around midpoint, sRGB-encoded
            if (uContrast != 1.0) {
                rgb = (rgb - 0.5) * uContrast + 0.5;
            }

            // 3. LGG — lift → gamma → gain
            rgb = cdl(rgb, uLift_slope,  uLift_offset,  uLift_power);
            rgb = cdl(rgb, uGamma_slope, uGamma_offset, uGamma_power);
            rgb = cdl(rgb, uGain_slope,  uGain_offset,  uGain_power);

            // 4. Per-channel R/G/B tone curves (sRGB; intentional casts)
            if (uHasCurve.y > 0.5) rgb.r = sampleCurveRow(clamp(rgb.r, 0.0, 1.0), 1);
            if (uHasCurve.z > 0.5) rgb.g = sampleCurveRow(clamp(rgb.g, 0.0, 1.0), 2);
            if (uHasCurve.w > 0.5) rgb.b = sampleCurveRow(clamp(rgb.b, 0.0, 1.0), 3);

            // 5. ─── OKLab cascade ───
            float3 lin = srgbToLinear(clamp(rgb, 0.0, 1.0));
            float3 lab = linearToOklab(lin);

            //   5b. Chroma MKL on (a, b), mixed by uMklStrength
            if (uMklStrength > 0.0) {
                float2 ab = lab.yz;
                float2 mapped = float2(
                    uMklChromaMat.x * ab.x + uMklChromaMat.y * ab.y + uMklChromaBias.x,
                    uMklChromaMat.z * ab.x + uMklChromaMat.w * ab.y + uMklChromaBias.y
                );
                lab.yz = mix(ab, mapped, uMklStrength);
            }

            //   5c. Master luma curve on OKLab L
            if (uHasCurve.x > 0.5) {
                lab.x = sampleCurveRow(clamp(lab.x, 0.0, 1.0), 0);
            }

            //   5d. Hue-band shifts in OKLCh
            float h = atan(lab.z, lab.y);
            float C = length(lab.yz);
            float3 acc = float3(0.0);
            // 6 equispaced bands; user labels mapped to nearest OKLab hue
            // (matches the HueBandExtractor's band-to-anchor mapping).
            acc += applyAnchor(h, 0.5235987756, uHsl_orange); //  30° (red-orange)
            acc += applyAnchor(h, 1.5707963268, uHsl_yellow); //  90° (yellow)
            acc += applyAnchor(h, 2.6179938780, uHsl_green);  // 150° (green)
            acc += applyAnchor(h, 3.6651914292, uHsl_aqua);   // 210° (cyan/aqua)
            acc += applyAnchor(h, 4.7123889804, uHsl_blue);   // 270° (blue)
            acc += applyAnchor(h, 5.7595865316, uHsl_red);    // 330° (magenta/red)
            // Apply: hue rotation, log-saturation scale (multiplicative), L offset
            // Gate by chroma so achromatic pixels don't rotate.
            float chromaGate = clamp(C / 0.02, 0.0, 1.0);
            float dh  = acc.x * chromaGate;
            float ds  = acc.y * chromaGate;          // log-domain
            float dl  = acc.z * chromaGate;
            float hNew = h + dh;
            float CNew = C * exp(ds);
            lab.x += dl;
            lab.y = CNew * cos(hNew);
            lab.z = CNew * sin(hNew);

            //   5e. OKLab → linear sRGB → sRGB-encoded
            float3 linOut = oklabToLinear(lab);
            rgb = linearToSrgb(clamp(linOut, 0.0, 1.0));

            // 6. Saturation + vibrance
            if (uSaturation != 1.0 || uVibrance != 0.0) {
                float y = dot(rgb, float3(0.2126, 0.7152, 0.0722));
                if (uSaturation != 1.0) {
                    rgb = float3(y) + (rgb - float3(y)) * uSaturation;
                }
                if (uVibrance != 0.0) {
                    float mx = max(rgb.r, max(rgb.g, rgb.b));
                    float mn = min(rgb.r, min(rgb.g, rgb.b));
                    float cur = (mx > 0.0) ? (mx - mn) / mx : 0.0;
                    float vib = 1.0 + uVibrance * (1.0 - cur);
                    rgb = float3(y) + (rgb - float3(y)) * vib;
                }
            }

            return half4(clamp(rgb, 0.0, 1.0), src.a);
        }
    """.trimIndent()
}
