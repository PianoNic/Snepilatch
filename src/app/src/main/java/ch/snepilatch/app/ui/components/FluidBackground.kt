package ch.snepilatch.app.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
 * A fluid, flowing "liquid" version of the album art for the full-screen player background — our
 * take on spicy-lyrics' Kawarp WebGL effect. On Android 13+ (API 33) it runs an AGSL fragment shader
 * that domain-warps the (softly pre-blurred) cover over time, boosting saturation and darkening it for
 * legibility; the warp advances quickly while playing and nearly freezes when paused (like Kawarp's
 * audio-reactive speed). On older devices it falls back to the previous blurred-cover look.
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
    FluidShaderBackground(artUrl, isPlaying, baseColor, modifier)
}

/** Pre-33 (or no-art) fallback: solid accent colour under a heavily blurred cover. */
@Composable
private fun BlurredAlbumBackdrop(artUrl: String?, baseColor: Color, modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(baseColor))
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
private fun FluidShaderBackground(
    artUrl: String,
    isPlaying: Boolean,
    baseColor: Color,
    modifier: Modifier
) {
    val shader = remember { RuntimeShader(FLUID_AGSL) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val time = remember { mutableFloatStateOf(0f) }

    // Advance the shader clock every frame; fast while playing, a slow drift when paused. Keyed on
    // isPlaying so the speed actually changes when playback toggles (the clock itself persists).
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

    androidx.compose.foundation.layout.Box(
        modifier.onSizeChanged { size = it }
    ) {
        // Accent colour behind, in case the warp ever samples an out-of-bounds (transparent) texel.
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(baseColor))
        AsyncImage(
            model = artUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Inner blur runs on the image's OWN layer; the outer graphicsLayer then samples that
            // blurred result as the shader's `image` input (nesting keeps the two RenderEffects
            // from clobbering each other).
            modifier = Modifier
                .fillMaxSize()
                .blur(18.dp)
                .graphicsLayer {
                    if (size.width > 0 && size.height > 0) {
                        shader.setFloatUniform("size", size.width.toFloat(), size.height.toFloat())
                        shader.setFloatUniform("time", time.floatValue)
                        renderEffect = RenderEffect
                            .createRuntimeShaderEffect(shader, "image")
                            .asComposeRenderEffect()
                    }
                }
        )
    }
}

// AGSL domain-warp of the cover: two fbm-driven distortion fields flow the UVs over time, then we
// zoom in slightly so warped samples never read past the edges. Saturation is pushed and brightness
// pulled down to echo spicy-lyrics' `saturate(2.5) brightness(0.65)`.
private const val FLUID_AGSL = """
uniform shader image;
uniform float2 size;
uniform float time;

float hash(float2 p) {
    p = fract(p * float2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * noise(p);
        p = p * 2.0;
        a = a * 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float t = time * 0.06;
    float2 q = float2(fbm(uv * 2.5 + t), fbm(uv * 2.5 + float2(3.1, 1.7) - t));
    float2 w = float2(
        fbm(uv * 2.5 + 3.0 * q + float2(1.2, 4.3) + t * 0.7),
        fbm(uv * 2.5 + 3.0 * q + float2(6.1, 2.9) - t * 0.9)
    );
    float2 duv = uv + (w - 0.5) * 0.32;
    duv = 0.5 + (duv - 0.5) * 0.82;
    half4 col = image.eval(duv * size);
    float l = dot(col.rgb, half3(0.299, 0.587, 0.114));
    col.rgb = mix(half3(l), col.rgb, 1.7);
    col.rgb = col.rgb * 0.72;
    col.a = 1.0;
    return col;
}
"""
