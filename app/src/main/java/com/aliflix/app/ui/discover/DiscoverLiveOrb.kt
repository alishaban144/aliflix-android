package com.aliflix.app.ui.discover

import android.os.SystemClock
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentInverse
import kotlin.math.*

@Stable
internal class DiscoverTouchTracker {
    var point by mutableStateOf<Offset?>(null)
    var lastTouchMillis by mutableLongStateOf(0L)
    var coordinates: LayoutCoordinates? = null
}

/** Observes the initial pointer pass, including drags consumed by children; never consumes input. */
internal fun Modifier.observeDiscoverTouches(tracker: DiscoverTouchTracker): Modifier =
    onGloballyPositioned { tracker.coordinates = it }.pointerInput(tracker) {
        awaitPointerEventScope {
            var primary: PointerId? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (primary == null) primary = event.changes.firstOrNull { it.pressed }?.id
                val change = event.changes.firstOrNull { it.id == primary }
                if (change != null) {
                    val coordinates = tracker.coordinates
                    if (coordinates?.isAttached == true) {
                        tracker.point = coordinates.localToRoot(change.position)
                        tracker.lastTouchMillis = SystemClock.uptimeMillis()
                    }
                    if (!change.pressed) primary = null
                }
            }
        }
    }

internal fun boundedOrbLook(point: Offset, center: Offset, radius: Float): Offset {
    if (radius <= 0f) return Offset.Zero
    val vector = Offset(((point.x-center.x)/radius).coerceIn(-1f,1f),
        ((center.y-point.y)/radius).coerceIn(-1f,1f))
    val length = vector.getDistance()
    return if (length > 1f) vector / length else vector
}
internal fun dampOrbLook(current: Offset, target: Offset, deltaSeconds: Float, recentering: Boolean): Offset {
    val rate = if (recentering) 3.5f else 10.46f // reference: 16% per frame at 60Hz.
    val p = 1f - exp(-rate * deltaSeconds.coerceIn(0f,.05f))
    return current + (target-current) * p
}

/** Live Orb's capsule eyes and spherical projection, in Aliflix's pearl/lavender palette. */
@Composable
internal fun DiscoverLiveOrb(tracker: DiscoverTouchTracker, modifier: Modifier = Modifier) {
    val motion = rememberMobileMotionEnabled()
    val resumed = rememberMobileResumed()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var look by remember { mutableStateOf(Offset(0f,.08f)) }
    var blink by remember { mutableFloatStateOf(0f) }
    val seconds = rememberMobileAnimationTime(motion && resumed)
    val currentSeconds by rememberUpdatedState(seconds)
    LaunchedEffect(motion, resumed) {
        if (!motion) { look = Offset(0f,.08f); blink = 0f }
        else if (resumed) {
            var previous = 0L
            while (true) withInfiniteAnimationFrameNanos { now ->
                val idle = SystemClock.uptimeMillis() - tracker.lastTouchMillis > 1800L
                val target = if (idle || tracker.point == null || bounds.width <= 0f) Offset(0f,.08f)
                    else boundedOrbLook(requireNotNull(tracker.point), bounds.center, bounds.width/2f)
                if (previous != 0L) look = dampOrbLook(look,target,(now-previous)/1_000_000_000f,idle)
                previous = now
                val t = currentSeconds % 4.2f
                blink = when {
                    t < 2f || t >= 2.18f -> 0f
                    t < 2.055f -> (t-2f)/.055f
                    t < 2.10f -> 1f
                    else -> 1f-(t-2.10f)/.08f
                }.coerceIn(0f,1f)
            }
        }
    }
    Canvas(modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
        val radius = size.minDimension * .47f
        drawCircle(Brush.radialGradient(
            colors = listOf(AliflixContentPrimary, lerp(AliflixContentPrimary, AliflixAccentSecondary, .32f)),
            center = center - Offset(radius * .28f, radius * .35f), radius = radius * 1.7f), radius)
        val yaw = look.x * .92f
        val pitch = -look.y * .78f
        val halfH = .128f + (.012f-.128f) * blink
        val rad = .054f + (.062f-.054f) * blink
        val norm = sqrt(.32f*.32f+.08f*.08f+1f)
        listOf(-1f,1f).forEach { side ->
            val path = Path()
            var started = false
            repeat(65) { index ->
                val angle = (index/64f*2f*PI).toFloat()
                val x = side*.32f/norm + cos(angle)*rad
                val y = .08f/norm + sin(angle)*rad + if (sin(angle) >= 0) halfH else -halfH
                val z = sqrt((1f-x*x-y*y).coerceAtLeast(0f))
                val py = y*cos(pitch)-z*sin(pitch)
                val pz = y*sin(pitch)+z*cos(pitch)
                val px = x*cos(yaw)+pz*sin(yaw)
                val finalZ = -x*sin(yaw)+pz*cos(yaw)
                // The eye disappears around the back of the sphere, as in the WebGL mask.
                if (finalZ >= 0f) {
                    val sx = center.x+px*radius; val sy = center.y-py*radius
                    if (!started) { path.moveTo(sx,sy); started = true } else path.lineTo(sx,sy)
                }
            }
            path.close()
            drawPath(path, AliflixContentInverse)
        }
    }
}
