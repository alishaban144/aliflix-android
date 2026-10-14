package com.aliflix.app.player

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.common.rememberMobileMotionEnabled
import kotlin.math.abs

internal fun playerDockInfluence(distance: Float): Float = (1f - abs(distance) / 150f).coerceIn(0f,1f)

/** 21st / Preet Suthar: distance [-150,0,150], spring mass .1, stiffness 150, damping 12.
 * Touch targets stay fixed; magnification is restrained to fit a video overlay. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MobilePlayerActionHeader(title: String, detail: String, episodic: Boolean,
    onBack: () -> Unit, onEpisodes: () -> Unit, onSubtitles: () -> Unit,
    onRotate: () -> Unit, onMore: () -> Unit, onQuality: (() -> Unit)?, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val compact = maxWidth < 580.dp
        val motion = rememberMobileMotionEnabled()
        var pointer by remember { mutableStateOf<Offset?>(null) }
        var origin by remember { mutableStateOf(Offset.Zero) }
        val observe = Modifier.onGloballyPositioned { origin = it.localToRoot(Offset.Zero) }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull()
                    pointer = if (change != null && (change.pressed || change.type == PointerType.Mouse) && event.type != PointerEventType.Exit)
                        origin + change.position else null
                }
            }
        }
        val actions: @Composable () -> Unit = {
            FlowRow(observe.testTag("player-action-dock").padding(horizontal = 6.dp, vertical = 7.dp),
                maxItemsInEachRow = ((maxWidth.value - 4f) / 56f).toInt().coerceIn(1,5),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (episodic) PlayerDockAction(Icons.AutoMirrored.Rounded.ViewList,"Episodes",onEpisodes,pointer,motion)
                PlayerDockAction(Icons.Rounded.Subtitles,"Audio & Subtitles",onSubtitles,pointer,motion)
                onQuality?.let { PlayerDockAction(Icons.Rounded.HighQuality,"Quality",it,pointer,motion) }
                PlayerDockAction(Icons.Rounded.ScreenRotation,"Rotate",onRotate,pointer,motion)
                PlayerDockAction(Icons.Rounded.MoreHoriz,"More",onMore,pointer,motion)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlayerDockAction(Icons.AutoMirrored.Rounded.ArrowBack,"Back",onBack,null,motion)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = Color.White, fontSize = if (compact) 15.sp else 19.sp,
                        lineHeight = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (detail.isNotBlank()) Text(detail, color = Color.White.copy(alpha = .65f), fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!compact) actions()
            }
            if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
        }
    }
}

@Composable
private fun PlayerDockAction(icon: ImageVector, label: String, onClick: () -> Unit, pointer: Offset?, motion: Boolean) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val influence = if (pressed || hovered || focused) 1f else if (pointer == null) 0f
        else playerDockInfluence((pointer.x - bounds.center.x) / density)
    val amount = animateFloatAsState(influence, if (motion) spring(dampingRatio = 1.55f, stiffness = 1500f) else snap(), label = "player-dock-lift")
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 10.dp, bottomEnd = 16.dp, bottomStart = 10.dp)
    IconButton(onClick, interactionSource = interactions,
        modifier = Modifier.size(48.dp).onGloballyPositioned { bounds = it.boundsInRoot() }) {
        Box(Modifier.size(42.dp).graphicsLayer {
            scaleX = 1f + .12f * amount.value; scaleY = scaleX
            translationY = -3.dp.toPx() * amount.value
        }.clip(shape).background(Brush.verticalGradient(listOf(Color(0xA42A2834),Color(0x8A13131B))))
            .border(.6.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .25f),Color.White.copy(alpha = .05f))),shape),
            contentAlignment = Alignment.Center) {
            Icon(icon,label,tint = Color(0xFFF4F0FF),modifier = Modifier.size(20.dp).graphicsLayer {
                scaleX = 1f + .08f * amount.value; scaleY = scaleX
            })
        }
    }
}

private val pausePoints = listOf(Offset(6f,5f),Offset(8f,5f),Offset(8f,19f),Offset(6f,19f),
    Offset(16f,5f),Offset(18f,5f),Offset(18f,19f),Offset(16f,19f))
private val playPoints = listOf(Offset(8f,5f),Offset(13f,8.5f),Offset(13f,15.5f),Offset(8f,19f),
    Offset(13f,8.5f),Offset(18f,12f),Offset(18f,12f),Offset(13f,15.5f))

internal fun playbackMorphPoint(index: Int, playProgress: Float): Offset =
    pausePoints[index] + (playPoints[index] - pausePoints[index]) * playProgress.coerceIn(0f,1f)

/** Aceternity's two compatible four-point paths. Pause bars are half the source width,
 * with no added SVG stroke. The real playback state owns both the glyph and its label. */
@Composable
internal fun MobileMorphPlaybackIcon(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val motion = rememberMobileMotionEnabled()
    val progress = animateFloatAsState(if (isPlaying) 0f else 1f,
        if (motion) spring(dampingRatio = .85f, stiffness = 289f) else snap(),label = "playback-path-morph")
    val paths = remember { listOf(Path(),Path()) }
    Canvas(modifier.semantics { contentDescription = if (isPlaying) "Pause" else "Play" }) {
        withTransform({ scale(size.width/24f,size.height/24f, pivot = Offset.Zero) }) {
            paths.forEachIndexed { half, path ->
                path.rewind()
                repeat(4) { corner ->
                    val point = playbackMorphPoint(half*4+corner,progress.value)
                    if (corner == 0) path.moveTo(point.x,point.y) else path.lineTo(point.x,point.y)
                }
                path.close()
                drawPath(path,Color(0xFFF5F1FF))
            }
        }
    }
}
