package com.aliflix.app.talk

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.cos
import kotlin.math.sin

internal fun talkVoiceHaloReady(state: TalkVoiceState): Boolean =
    !state.textMode && !state.muted &&
        state.phase in setOf("Listening", "Speaking", "Thinking")

@Composable
internal fun TalkOrbHalo(compact: Boolean, strength: () -> Float, speech: () -> Float, phase: () -> Float) {
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) runCatching { TalkHaloShader() }.getOrNull() else null }
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val unit = size.minDimension
        val reach = unit * if (compact) .424f else .49f
        val feather = unit * if (compact) .069f else .118f
        val fallback = listOf(Color(0xFFAD92F5), Color(0xFF8D7AEA), Color(0xFFE5B9E4)).map { color ->
            Brush.radialGradient(
                0f to color, .47f to color.copy(alpha = .55f), 1f to color.copy(alpha = 0f),
                center = Offset.Zero, radius = feather,
            )
        }
        onDrawBehind {
            val visibility = strength()
            if (visibility <= .001f) return@onDrawBehind
            val energy = speech()
            val angle = phase()
            if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                shader.program.setFloatUniform("origin", center.x, center.y)
                shader.program.setFloatUniform("radius", unit * .33f)
                shader.program.setFloatUniform("limit", reach / (unit * .33f))
                shader.program.setFloatUniform("spread", if (compact) .133f else .212f)
                shader.program.setFloatUniform("clock", angle)
                shader.program.setFloatUniform("energy", energy)
                shader.program.setFloatUniform("visibility", visibility)
                drawCircle(shader.brush, radius = reach)
            } else {
                repeat(9) { index ->
                    val direction = index * .698132f
                    val wave = sin(direction * 3 - angle) + .4f * cos(direction * 2 + angle)
                    val distance = unit * (.331f + wave * (.006f + energy * .012f))
                    val location = center + Offset(cos(direction), sin(direction)) * distance
                    val hue = ((index / 3) + 2) % fallback.size
                    val alpha = visibility * (.15f + .09f * cos(direction - angle) + .22f * energy)
                    translate(location.x, location.y) {
                        drawCircle(fallback[hue], radius = feather, center = Offset.Zero, alpha = alpha)
                    }
                }
            }
        }
    })
}

@RequiresApi(33)
private class TalkHaloShader {
    val program = RuntimeShader(HALO_SHADER)
    val brush = ShaderBrush(program)
}

// Soft light follows several overlapping angular waves around the face. Both the
// colour and the falloff are continuous at the wraparound, with zero alpha at the edge.
private const val HALO_SHADER = """
uniform float2 origin;
uniform float radius;
uniform float limit;
uniform float spread;
uniform float clock;
uniform float energy;
uniform float visibility;

half4 main(float2 position) {
    float2 point = (position - origin) / radius;
    float r = length(point);
    float angle = atan(point.y, point.x + 0.00001);
    float curl = 0.62 * sin(angle * 3.0 - clock * 1.6)
        + 0.28 * cos(angle * 5.0 + clock * 0.8)
        + 0.22 * sin(angle * 2.0 + clock * 1.1);
    float contour = 1.018 + curl * (0.035 + energy * 0.17);
    float distance = max(0.0, r - contour) / (spread * (1.0 + energy * 0.16));
    float soft = exp(-1.55 * distance * distance);
    float outer = 1.0 - smoothstep(limit - 0.16, limit, r);
    float inner = smoothstep(0.84, 1.02, r);
    float shimmer = 0.53 + 0.26 * sin(angle * 2.0 + clock * 0.68)
        + 0.18 * cos(angle * 3.0 - clock);
    half3 violet = half3(0.52, 0.42, 0.91);
    half3 mist = half3(0.77, 0.66, 0.97);
    half3 rose = half3(0.90, 0.69, 0.84);
    half3 tint = mix(violet, mist, half(shimmer));
    tint = mix(tint, rose, half(0.44 * pow(max(0.0, cos(angle - clock + 1.1)), 3.0)));
    half alpha = half(visibility * soft * outer * inner *
        (0.26 + energy * 0.36) * (0.52 + shimmer * 0.48));
    return half4(tint * alpha, alpha);
}
"""
