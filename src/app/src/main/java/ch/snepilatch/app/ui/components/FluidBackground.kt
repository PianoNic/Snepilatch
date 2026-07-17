package ch.snepilatch.app.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Fluid, flowing album-art background for the full-screen player — a native AGSL port of spicy-lyrics'
 * Kawarp (github.com/better-lyrics/kawarp) WebGL effect. It reproduces Kawarp's real pipeline:
 *   1. WARP  — a two-octave simplex-noise domain warp of the cover, strongest at the centre.
 *   2. BLUR  — a heavy blur that melts the warped art into flowing colour blobs (Kawarp runs 8 Kawase
 *              passes at 128px; we use a large native RenderEffect blur to the same end).
 *   3. GRADE — un-scale, vignette, saturation boost (1.5) and temporal dithering.
 * The warp clock runs fast while playing and nearly freezes when paused. Android 13+ (RuntimeShader);
 * older devices fall back to a static blurred cover.
 */
@Composable
fun FluidAlbumBackground(
    artUrl: String?,
    isPlaying: Boolean,
    baseColor: Color,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || artUrl == null) {
        BlurredAlbumBackdrop(artUrl, baseColor, modifier)
        return
    }
    KawarpBackground(artUrl, isPlaying, baseColor, modifier)
}

/** Pre-33 (or no-art) fallback: solid accent colour under a heavily blurred cover. */
@Composable
private fun BlurredAlbumBackdrop(artUrl: String?, baseColor: Color, modifier: Modifier) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().background(baseColor))
        var displayedArt by remember { mutableStateOf(artUrl) }
        if (artUrl != null) displayedArt = artUrl
        Crossfade(targetState = displayedArt, animationSpec = tween(800), label = "bgArt") { url ->
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(80.dp)
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun KawarpBackground(
    artUrl: String,
    isPlaying: Boolean,
    baseColor: Color,
    modifier: Modifier
) {
    val warpShader = remember { RuntimeShader(WARP_AGSL) }
    val gradeShader = remember { RuntimeShader(GRADE_AGSL) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val time = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        var last = 0L
        while (true) {
            val frame = withFrameNanos { it }
            if (last != 0L) {
                val dt = (frame - last) / 1_000_000_000f
                time.floatValue += dt * (if (isPlaying) 1f else 0.15f)
            }
            last = frame
        }
    }

    // Song-change transition: the new cover's warped background slides in from the right while the old
    // one slides out to the left (spicy-lyrics' SDB_StaticBG slide), with the accent colour behind
    // already cross-animating in PlayerBackground. `progress` runs 0→1 over the slide.
    var currentUrl by remember { mutableStateOf(artUrl) }
    var previousUrl by remember { mutableStateOf<String?>(null) }
    val progress = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(artUrl) {
        if (artUrl != currentUrl) {
            previousUrl = currentUrl
            currentUrl = artUrl
            progress.snapTo(0f)
            progress.animateTo(1f, tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            previousUrl = null
        }
    }

    Box(modifier.onSizeChanged { size = it }) {
        // Accent colour behind, in case a clamped warp ever samples a transparent texel / during slide.
        Box(Modifier.fillMaxSize().background(baseColor))
        val p = progress.value
        // Outgoing cover: 0 → -width (slides off to the left).
        previousUrl?.let { prev ->
            WarpedLayer(
                prev, warpShader, gradeShader, size, time,
                Modifier.graphicsLayer { translationX = -size.width * p }
            )
        }
        // Incoming cover: +width → 0 (slides in from the right).
        WarpedLayer(
            currentUrl, warpShader, gradeShader, size, time,
            Modifier.graphicsLayer { translationX = size.width * (1f - p) }
        )
    }
}

/** One warped-cover layer: a low-res album image run through the Kawarp warp→blur→grade RenderEffect
 *  chain. The warp/grade RuntimeShaders are shared across layers (identical uniforms every frame). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun WarpedLayer(
    artUrl: String,
    warpShader: RuntimeShader,
    gradeShader: RuntimeShader,
    size: IntSize,
    time: androidx.compose.runtime.FloatState,
    modifier: Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Kawarp works at 128px; the upscale strips fine detail so warp+blur reads as flowing colour blobs.
    val lowResModel = remember(artUrl) {
        coil.request.ImageRequest.Builder(context).data(artUrl).size(128).allowHardware(false)
            .crossfade(false).build()
    }
    AsyncImage(
        model = lowResModel,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                if (w > 0f && h > 0f) {
                    val t = time.floatValue
                    warpShader.setFloatUniform("size", w, h)
                    warpShader.setFloatUniform("u_time", t)
                    warpShader.setFloatUniform("u_intensity", WARP_INTENSITY)
                    gradeShader.setFloatUniform("size", w, h)
                    gradeShader.setFloatUniform("u_time", t)
                    gradeShader.setFloatUniform("u_saturation", SATURATION)
                    gradeShader.setFloatUniform("u_dithering", DITHERING)
                    gradeShader.setFloatUniform("u_scale", SCALE)

                    // warp(source) -> blur -> grade, as a native RenderEffect chain.
                    val warp = RenderEffect.createRuntimeShaderEffect(warpShader, "u_texture")
                    val blurred = RenderEffect.createBlurEffect(
                        BLUR_RADIUS, BLUR_RADIUS, warp, Shader.TileMode.CLAMP
                    )
                    val grade = RenderEffect.createRuntimeShaderEffect(gradeShader, "u_texture")
                    renderEffect = RenderEffect.createChainEffect(grade, blurred).asComposeRenderEffect()
                }
            }
    )
}

// Kawarp static-mode options (spicy-lyrics KawarpOptionsStatic): warpIntensity 1, saturation 1.5,
// dithering 0.008, scale 1. Kawarp blurs 8 Kawase passes at 128px; a wide native blur matches the look.
private const val WARP_INTENSITY = 1.0f
private const val SATURATION = 1.5f
private const val DITHERING = 0.008f
private const val SCALE = 1.0f
private const val BLUR_RADIUS = 120f

// Pass 1 — WARP: Kawarp's warp shader (two-octave 2D simplex-noise domain warp), translated GLSL→AGSL
// (vec→float2/3/4, texture2D→eval, overloaded mod289 split, resolution-normalised from fragCoord).
private const val WARP_AGSL = """
uniform shader u_texture;
uniform float2 size;
uniform float u_time;
uniform float u_intensity;

float3 mod289_3(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float2 mod289_2(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float3 permute(float3 x) { return mod289_3(((x * 34.0) + 1.0) * x); }

float snoise(float2 v) {
    const float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    float2 i = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy = x12.xy - i1;
    i = mod289_2(i);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m;
    m = m * m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 ox = floor(x + 0.5);
    float3 a0 = x - ox;
    m = m * (1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h));
    float3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float t = u_time * 0.05;
    float2 center = uv - 0.5;
    float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));
    float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));
    float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));
    float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));
    float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));
    float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;
    float2 warpedUV = clamp(uv + warp * u_intensity, 0.0, 1.0);
    return u_texture.eval(warpedUV * size);
}
"""

// Pass 3 — GRADE: Kawarp's output shader (scale, vignette, saturation, temporal dithering), GLSL→AGSL.
// Runs on the blurred-warp result (fed in as u_texture via the RenderEffect chain).
private const val GRADE_AGSL = """
uniform shader u_texture;
uniform float2 size;
uniform float u_saturation;
uniform float u_dithering;
uniform float u_time;
uniform float u_scale;

float hash3(float3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

half4 main(float2 fragCoord) {
    float2 vtc = fragCoord / size;
    float2 uv = clamp((vtc - 0.5) / u_scale + 0.5, 0.0, 1.0);
    float4 color = float4(u_texture.eval(uv * size));
    float2 center = vtc - 0.5;
    float vignette = 1.0 - dot(center, center) * 0.3;
    color.rgb = color.rgb * vignette;
    float gray = dot(color.rgb, float3(0.299, 0.587, 0.114));
    color.rgb = mix(float3(gray), color.rgb, u_saturation);
    // spicy-lyrics wraps the Kawarp canvas in CSS `filter: saturate(2.5) brightness(0.65)` — apply it
    // here so the final look matches (moodier + more vivid than raw Kawarp).
    float gray2 = dot(color.rgb, float3(0.299, 0.587, 0.114));
    color.rgb = mix(float3(gray2), color.rgb, 2.5);
    color.rgb = color.rgb * 0.65;
    float2 pixelPos = floor(vtc * size);
    float noise = hash3(float3(pixelPos, floor(u_time * 60.0)));
    color.rgb = color.rgb + (noise - 0.5) * u_dithering;
    return half4(color);
}
"""
