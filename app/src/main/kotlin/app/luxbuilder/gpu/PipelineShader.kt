package app.luxbuilder.gpu

/**
 * AGSL fragment shader running the full luxbuilder grading pipeline live.
 *
 * Mirrors [app.luxbuilder.color.ColorPipeline] one-to-one — any change to
 * the pipeline math here must land in both places to keep the live preview
 * pixel-identical to what [app.luxbuilder.color.LutBaker] exports.
 *
 * Pipeline order (sRGB-encoded in → sRGB-encoded out):
 *   1. White balance     (Kelvin gains precomputed on host, passed as vec3 in linear-RGB scale factors)
 *   2. MKL color-match   (3×3 + bias on linear-RGB, mixed by mklStrength)
 *   3. Contrast          (sRGB-encoded, around midpoint)
 *   4. LGG               (lift → gamma → gain; ASC CDL slope/offset/power)
 *   5. Tone curve        (master luma applied as luminance delta)
 *   6. HSL six-color     (RGB→HSV, hue-windowed deltas, HSV→RGB)
 *   7. Saturation / vibrance
 *
 * Uniforms (set via PipelineShader.bind()):
 *   uniform shader composable           // the source preview image
 *   uniform shader toneCurve            // 1024×1 R8 BitmapShader, the master luma curve
 *   uniform float   uHasToneCurve       // 1.0 if curve != identity, else 0.0
 *
 *   uniform float3  uWbGains            // per-channel multiplicative gains in linear sRGB
 *
 *   uniform float   uMklStrength        // 0 = bypass, 1 = full
 *   uniform float3  uMklRow0, uMklRow1, uMklRow2   // 3×3 matrix rows
 *   uniform float3  uMklBias
 *
 *   uniform float   uContrast           // 1 = neutral; e.g. 1.2 = +20%
 *
 *   uniform float3  uLift_slope, uLift_offset, uLift_power
 *   uniform float3  uGamma_slope, uGamma_offset, uGamma_power
 *   uniform float3  uGain_slope, uGain_offset, uGain_power
 *
 *   uniform float3  uHsl_hue            // hue shifts per anchor (radians) — packed in 2 floats below
 *   // For simplicity v1 passes 6 vec3s for the 6 HSL anchors. Each anchor is (hueShiftDeg, satScale, valScale).
 *   uniform float3  uHsl_red, uHsl_orange, uHsl_yellow, uHsl_green, uHsl_aqua, uHsl_blue
 *
 *   uniform float   uSaturation         // 1 = neutral
 *   uniform float   uVibrance           // 0 = neutral
 */
object PipelineShader {

    /** AGSL source. Bound via android.graphics.RuntimeShader on API 33+. */
    val SOURCE: String = """
        uniform shader composable;
        uniform shader toneCurve;
        uniform float  uHasToneCurve;

        uniform float3 uWbGains;

        uniform float  uMklStrength;
        uniform float3 uMklRow0;
        uniform float3 uMklRow1;
        uniform float3 uMklRow2;
        uniform float3 uMklBias;

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

        // ───── ASC CDL — per-channel slope/offset/power ─────
        float3 cdl(float3 v, float3 slope, float3 offset, float3 power) {
            float3 t = max(v * slope + offset, float3(0.0));
            return pow(t, 1.0 / max(power, float3(1e-3)));
        }

        // ───── HSV decomposition ─────
        float3 rgb2hsv(float3 c) {
            float4 K = float4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
            float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));
            float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return float3(abs(q.z + (q.w - q.y) / (6.0 * d + e)),
                          d / (q.x + e),
                          q.x);
        }
        float3 hsv2rgb(float3 c) {
            float4 K = float4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
            float3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
            return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
        }

        float hueDistance(float a, float bDeg) {
            // a in degrees, bDeg in degrees
            float d = abs(a - bDeg);
            d = mod(d, 360.0);
            return min(d, 360.0 - d);
        }

        // For a single anchor with hueShiftDeg in [-30,+30], satScale, valScale.
        // Returns accumulated (deltaHueDeg, satScaleMul, valScaleMul).
        float3 applyAnchor(float hueDeg, float anchorH, float3 cfg) {
            // cfg: x = hueShiftDeg (-30..+30), y = satShiftNorm (-1..+1 as scale offset), z = lumaShiftNorm
            float d = hueDistance(hueDeg, anchorH);
            float w = max(0.0, 1.0 - d / 60.0);
            w = w * w;
            return float3(cfg.x * w, 1.0 + cfg.y * w, 1.0 + cfg.z * w);
        }

        // Sample the 1024×1 luma tone curve. toneCurve is bound to a BitmapShader
        // — sample at (x*1024, 0.5) and read the red channel.
        float sampleCurve(float x) {
            return toneCurve.eval(float2(clamp(x, 0.0, 1.0) * 1024.0, 0.5)).r;
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

            // 2. MKL color-match in linear-RGB, mixed by strength
            if (uMklStrength > 0.0) {
                float3 lin = srgbToLinear(rgb);
                float3 mapped = float3(dot(uMklRow0, lin),
                                       dot(uMklRow1, lin),
                                       dot(uMklRow2, lin)) + uMklBias;
                lin = mix(lin, mapped, uMklStrength);
                rgb = linearToSrgb(clamp(lin, 0.0, 1.0));
            }

            // 3. Contrast around midpoint, in sRGB-encoded
            if (uContrast != 1.0) {
                rgb = (rgb - 0.5) * uContrast + 0.5;
            }

            // 4. LGG: lift → gamma → gain (all in sRGB-encoded)
            rgb = cdl(rgb, uLift_slope,  uLift_offset,  uLift_power);
            rgb = cdl(rgb, uGamma_slope, uGamma_offset, uGamma_power);
            rgb = cdl(rgb, uGain_slope,  uGain_offset,  uGain_power);

            // 5. Tone curve — master luma applied as luminance delta
            if (uHasToneCurve > 0.5) {
                float y = dot(rgb, float3(0.2126, 0.7152, 0.0722));
                float yNew = sampleCurve(y);
                float dy = yNew - y;
                rgb = rgb + float3(dy);
            }

            // 6. HSL six-color
            float3 hsv = rgb2hsv(clamp(rgb, 0.0, 1.0));
            float hDeg = hsv.x * 360.0;
            float hueShift = 0.0;
            float satScale = 1.0;
            float valScale = 1.0;
            float3 acc;
            acc = applyAnchor(hDeg,   0.0, uHsl_red);    hueShift += acc.x; satScale *= acc.y; valScale *= acc.z;
            acc = applyAnchor(hDeg,  30.0, uHsl_orange); hueShift += acc.x; satScale *= acc.y; valScale *= acc.z;
            acc = applyAnchor(hDeg,  60.0, uHsl_yellow); hueShift += acc.x; satScale *= acc.y; valScale *= acc.z;
            acc = applyAnchor(hDeg, 120.0, uHsl_green);  hueShift += acc.x; satScale *= acc.y; valScale *= acc.z;
            acc = applyAnchor(hDeg, 180.0, uHsl_aqua);   hueShift += acc.x; satScale *= acc.y; valScale *= acc.z;
            acc = applyAnchor(hDeg, 240.0, uHsl_blue);   hueShift += acc.x; satScale *= acc.y; valScale *= acc.z;
            if (hueShift != 0.0 || satScale != 1.0 || valScale != 1.0) {
                hsv.x = fract((hDeg + hueShift) / 360.0 + 1.0);
                hsv.y = clamp(hsv.y * satScale, 0.0, 1.0);
                hsv.z = clamp(hsv.z * valScale, 0.0, 1.0);
                rgb = hsv2rgb(hsv);
            }

            // 7. Saturation + vibrance
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
