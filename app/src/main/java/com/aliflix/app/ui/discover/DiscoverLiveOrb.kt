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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.drawWithCache
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

internal data class OrbExpression(val gaze: Offset, val openness: Float, val asymmetry: Float = 0f)

/** Live Orb's capsule eyes and spherical projection, in Aliflix's pearl/lavender palette. */
@Composable
internal fun DiscoverLiveOrb(tracker: DiscoverTouchTracker, modifier: Modifier = Modifier, conversing: Boolean = false, voiceCompanion: Boolean = false, voiceEnergy:()->Float={0f},attention:Offset?=null,
    expression: OrbExpression? = null, motionEnabled: Boolean = true, eyesClosed: Boolean = false,
    blinkOnConnect: Boolean = false) {
    val motion = rememberMobileMotionEnabled() && motionEnabled
    val resumed = rememberMobileResumed()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var look by remember { mutableStateOf(Offset(0f,.08f)) }
    var blink by remember { mutableFloatStateOf(0f) }
    val seconds = rememberMobileAnimationClock(motion && resumed)
    var idleLook by remember { mutableStateOf(Offset(0f,.08f)) }
    val speaking by rememberUpdatedState(conversing)
    val speechEnergy by rememberUpdatedState(voiceEnergy)
    val contentAttention by rememberUpdatedState(attention)
    val expressionTarget by rememberUpdatedState(expression)
    var openness by remember { mutableFloatStateOf(expression?.openness ?: 1f) }
    var asymmetry by remember { mutableFloatStateOf(expression?.asymmetry ?: 0f) }
    val paths = remember { arrayOf(Path(),Path()) }
    val eyeCircle = remember { FloatArray(130).also { points ->
        for(index in 0..64) { val angle=index/64f*2f*PI; points[index*2]=cos(angle).toFloat();points[index*2+1]=sin(angle).toFloat() }
    } }
    // Conversation attention is stable. Pointer interaction remains responsive;
    // speech does not choose random gaze destinations.
    LaunchedEffect(voiceCompanion,motion,resumed) {
        idleLook=Offset(0f,if(voiceCompanion).06f else .08f)
    }
    LaunchedEffect(voiceCompanion,motion,resumed,eyesClosed,blinkOnConnect) {
        blink=0f
        if(eyesClosed)return@LaunchedEffect
        if(blinkOnConnect&&motion&&resumed) {
            kotlinx.coroutines.delay(190)
            repeat(2) {
                blink=1f
                kotlinx.coroutines.delay(95)
                blink=0f
                kotlinx.coroutines.delay(180)
            }
        }
        if(voiceCompanion&&motion&&resumed)while(true) {
            kotlinx.coroutines.delay(kotlin.random.Random.nextLong(2800,5200))
            var waited=0
            while(speaking&&speechEnergy()>.18f&&waited<360){kotlinx.coroutines.delay(40);waited+=40}
            blink=1f
            kotlinx.coroutines.delay(105)
            blink=0f
            if(kotlin.random.Random.nextFloat()<.12f) {
                kotlinx.coroutines.delay(180);blink=1f;kotlinx.coroutines.delay(90);blink=0f
            }
        }
    }
    LaunchedEffect(motion, resumed, voiceCompanion, if(motion)null else expression) {
        if (!motion) { look = expression?.gaze ?: Offset(0f,.08f); blink = 0f;openness=expression?.openness ?: 1f;asymmetry=expression?.asymmetry ?: 0f }
        else if (resumed) {
            var previous = 0L
            while (true) withInfiniteAnimationFrameNanos { now ->
                val idle = SystemClock.uptimeMillis() - tracker.lastTouchMillis > 1800L
                val point=contentAttention ?: if(!idle)tracker.point else null
                val target = if(point==null||bounds.width<=0f)expressionTarget?.gaze ?: idleLook else boundedOrbLook(point,bounds.center,bounds.width/2f)
                if (previous != 0L) {
                    val dt=((now-previous)/1_000_000_000f).coerceIn(0f,.05f)
                    look = dampOrbLook(look,target,dt,idle && point==null && expressionTarget==null)
                    val blend=1f-exp(-12f*dt)
                    openness+=((expressionTarget?.openness ?: 1f)-openness)*blend
                    asymmetry+=((expressionTarget?.asymmetry ?: 0f)-asymmetry)*blend
                }
                previous = now
                val t = seconds.value % if(voiceCompanion)2.9f else 4.2f
                if(!voiceCompanion)blink = when {
                    t < 2f || t >= 2.18f -> 0f
                    t < 2.055f -> (t-2f)/.055f
                    t < 2.10f -> 1f
                    else -> 1f-(t-2.10f)/.08f
                }.coerceIn(0f,1f)
            }
        }
    }
    val companionBlink by androidx.compose.animation.core.animateFloatAsState(if (eyesClosed) 1f else blink,
        androidx.compose.animation.core.tween(if (!motion) 0 else if (eyesClosed || blink > 0f) 55 else 140),
        label="companion-blink")
    Canvas(modifier.onGloballyPositioned { bounds = it.boundsInRoot() }.drawWithCache {
        val radius=size.minDimension*.47f
        val midpoint=Offset(size.width/2f,size.height/2f)
        val body=Brush.radialGradient(colors=listOf(AliflixContentPrimary,lerp(AliflixContentPrimary,AliflixAccentSecondary,.32f)),
            center=midpoint-Offset(radius*.28f,radius*.35f),radius=(radius*1.7f).coerceAtLeast(1f))
        onDrawBehind { drawCircle(body,radius) }
    }) {
        val radius = size.minDimension * .47f
        val yaw = look.x * .92f
        val pitch = -look.y * .78f
        val cosYaw=cos(yaw);val sinYaw=sin(yaw);val cosPitch=cos(pitch);val sinPitch=sin(pitch)
        val lid=if(voiceCompanion)companionBlink else blink
        val rad = .054f + (.062f-.054f) * lid
        val norm = sqrt(.32f*.32f+.08f*.08f+1f)
        for (eye in 0..1) {
            val side=if(eye==0)-1f else 1f
            val eyeX=side*.32f/norm
            val eyeY=.08f/norm
            if (lid >= .9f && (eyesClosed || blinkOnConnect)) {
                val z=sqrt(1f-eyeX*eyeX-eyeY*eyeY)
                val py=eyeY*cosPitch-z*sinPitch
                val pz=eyeY*sinPitch+z*cosPitch
                if (-eyeX*sinYaw+pz*cosYaw>0f) {
                    val px=eyeX*cosYaw+pz*sinYaw
                    val middle=Offset(center.x+px*radius,center.y-py*radius)
                    val halfWidth=radius*.126f
                    drawLine(AliflixContentInverse,middle-Offset(halfWidth,0f),middle+Offset(halfWidth,0f),
                        strokeWidth=(radius*.067f).coerceAtLeast(1f),cap=StrokeCap.Round)
                }
                continue
            }
            val openHeight=.128f*openness.coerceIn(.65f,1.12f)*(1f+side*asymmetry.coerceIn(-.1f,.1f))
            val halfH = openHeight + (.012f-openHeight) * lid
            val path = paths[eye].apply { reset() }
            var started = false
            repeat(65) { index ->
                val sine=eyeCircle[index*2+1]
                val x = side*.32f/norm + eyeCircle[index*2]*rad
                val y = .08f/norm + sine*rad + if (sine >= 0) halfH else -halfH
                val z = sqrt((1f-x*x-y*y).coerceAtLeast(0f))
                val py = y*cosPitch-z*sinPitch
                val pz = y*sinPitch+z*cosPitch
                val px = x*cosYaw+pz*sinYaw
                val finalZ = -x*sinYaw+pz*cosYaw
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
