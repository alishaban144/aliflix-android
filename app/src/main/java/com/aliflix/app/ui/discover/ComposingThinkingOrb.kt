/*
 * Native Jetpack Compose port of the MIT-licensed "thinking-orbs" project:
 * https://github.com/Jakubantalik/thinking-orbs
 *
 * Copyright (c) 2026 Jakub Antalik
 *
 * Upstream state: composing -> ribbon
 * Upstream preset: size 64
 *
 * This file intentionally preserves the upstream geometry, timing, dot sizing,
 * depth shading and dark-theme painting behavior. Renderer adaptation only:
 * HTML Canvas/TypeScript -> Jetpack Compose Canvas/Kotlin.
 */

package com.aliflix.app.ui.discover

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exact upstream "composing" animation using the shipped 64px preset.
 * Background is fully transparent.
 *
 * Intended Aliflix usage:
 * - centered while Ask Aliflix is finding recommendations
 * - "Finding matches" displayed underneath by the caller
 *
 * Caller controls displayed size with Modifier.size(...).
 */
@Composable
fun ComposingThinkingOrb(
    modifier: Modifier = Modifier,
    paused: Boolean = false,
) {
    var frameNanos by remember { mutableLongStateOf(0L) }
    val reducedMotion = remember { !ValueAnimator.areAnimatorsEnabled() }

    LaunchedEffect(paused, reducedMotion) {
        if (paused || reducedMotion) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { frameNanos = it }
        }
    }

    Canvas(modifier = modifier) {
        val side = min(size.width, size.height)
        if (side <= 0f) return@Canvas

        val scaleToCanvas = side / LOGICAL_SIZE.toFloat()
        val offsetX = (size.width - side) / 2f
        val offsetY = (size.height - side) / 2f

        val t = if (paused || reducedMotion) {
            0.6
        } else {
            (frameNanos / 1_000_000_000.0) * SPEED
        }

        val dots = frameRibbon(t)

        for (dot in dots) {
            val w = dot.white.coerceIn(0.0, 1.0)
            val gray = ((1.0 - w) * 255.0).roundToIntCompat()
            val alpha = ((dot.alpha ?: 1.0).coerceIn(0.0, 1.0) * 255.0).roundToIntCompat()

            drawCircle(
                color = Color(gray, gray, gray, alpha),
                radius = (dot.r * scaleToCanvas).toFloat(),
                center = Offset(
                    x = offsetX + (dot.x * scaleToCanvas).toFloat(),
                    y = offsetY + (dot.y * scaleToCanvas).toFloat(),
                ),
            )
        }
    }
}

private data class ComposingDot(
    val x: Double,
    val y: Double,
    val z: Double,
    var r: Double,
    val white: Double,
    val alpha: Double? = null,
)

private fun frameRibbon(t: Double): List<ComposingDot> {
    val size = LOGICAL_SIZE.toDouble()
    val cx = size / 2.0
    val cy = size / 2.0
    val radius = (size / 2.0) * 0.78

    val camTilt = 0.3
    val projector = makeComposingProj(
        yaw = 0.0,
        tilt = camTilt,
        cx = cx,
        cy = cy,
        scale = 1.0,
    )

    val rs = (size / 300.0).pow(RS_POW)
    val dots = ArrayList<ComposingDot>()

    // Exact resolved size-64 upstream ribbon preset:
    // BASE ribbon: lanes=5, segs=88, ghostN=150, rBase=1.1, rDepth=1.7
    // count=.25 -> lanes=3, segs=44, ghostN=38
    // size=.85 -> radius values multiplied
    // extra: spin=0, bandMul=3.9, wobMul=1
    val ghostN = 38
    for (i in 0 until ghostN) {
        val d = fibComposingDir(i, ghostN)
        val p = projector(
            d[0] * radius,
            d[1] * radius,
            d[2] * radius,
        )
        val depth = (p[2] / radius + 1.0) / 2.0

        dots += ComposingDot(
            x = p[0],
            y = p[1],
            z = p[2],
            r = 0.8 * rs,
            white = 0.78,
            alpha = 0.1 + 0.22 * depth,
        )
    }

    val ya = 0.0
    val ta = 0.55

    val ux = cos(ya)
    val uy = 0.0
    val uz = sin(ya)

    val vx = -uz * sin(ta)
    val vy = cos(ta)
    val vz = ux * sin(ta)

    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx

    val baseLanes = 3
    val segs = 44
    val lanes = max(1, jsComposingRound(baseLanes * BAND_MUL).toInt())

    for (w in 0 until lanes) {
        val laneOff = (w - (lanes - 1) / 2.0) * 0.075
        val edge = abs(w - (lanes - 1) / 2.0) / max(1.0, (lanes - 1) / 2.0)

        for (k in 0 until segs) {
            val a = (k.toDouble() / segs) * TWO_PI

            val wob = (
                0.16 * sin(a * 3.0 - t * 1.7 + w * 0.22) +
                    0.07 * sin(a * 5.0 + t * 1.1)
                ) * WOB_MUL

            val radial = 1.0
            val off = laneOff + wob

            val x = ux * cos(a) + vx * sin(a) + nx * off
            val y = uy * cos(a) + vy * sin(a) + ny * off
            val z = uz * cos(a) + vz * sin(a) + nz * off

            val length = sqrt(x * x + y * y + z * z)
            val rr = radius * radial

            val p = projector(
                (x / length) * rr,
                (y / length) * rr,
                (z / length) * rr,
            )

            val depth = (p[2] / radius + 1.0) / 2.0

            dots += ComposingDot(
                x = p[0],
                y = p[1],
                z = p[2],
                r = (
                    (R_BASE + R_DEPTH * depth) *
                        (1.0 - 0.25 * edge) *
                        rs
                    ),
                white = 0.52 - 0.44 * depth + 0.18 * edge,
                alpha = 0.4 + 0.6 * depth,
            )
        }
    }

    return dots
        .asSequence()
        .filter { (it.alpha ?: 1.0) >= 0.02 }
        .onEach { it.r = max(R_MIN, it.r) }
        .sortedBy { it.z }
        .toList()
}

private typealias ComposingProjector = (Double, Double, Double) -> DoubleArray

private fun makeComposingProj(
    yaw: Double,
    tilt: Double,
    cx: Double,
    cy: Double,
    scale: Double,
): ComposingProjector {
    val st = sin(tilt)
    val ct = cos(tilt)
    val sy = sin(yaw)
    val cyw = cos(yaw)

    return { x, y, z ->
        val x1 = x * cyw + z * sy
        val z1 = -x * sy + z * cyw
        val y1 = y * ct - z1 * st
        val z2 = y * st + z1 * ct

        doubleArrayOf(
            cx + x1 * scale,
            cy - y1 * scale,
            z2,
        )
    }
}

private fun fibComposingDir(
    i: Int,
    n: Int,
): DoubleArray {
    val golden = PI * (3.0 - sqrt(5.0))
    val y = 1.0 - (2.0 * (i + 0.5)) / n
    val rad = sqrt(1.0 - y * y)
    val a = i * golden

    return doubleArrayOf(
        rad * cos(a),
        y,
        rad * sin(a),
    )
}

private fun jsComposingRound(value: Double): Double = floor(value + 0.5)

private fun Double.roundToIntCompat(): Int =
    round(this).toInt().coerceIn(0, 255)

private const val LOGICAL_SIZE = 64
private const val SPEED = 2.34
private const val BAND_MUL = 3.9
private const val WOB_MUL = 1.0
private const val R_BASE = 1.1 * 0.85
private const val R_DEPTH = 1.7 * 0.85
private const val RS_POW = 0.6
private const val R_MIN = 0.3
private const val TWO_PI = PI * 2.0
