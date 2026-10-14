package com.aliflix.app.player

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.common.rememberMobileMotionEnabled

/** Stable, unframed actions. Only the conventional navigation button has a surface. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MobilePlayerActionHeader(title: String, detail: String, episodic: Boolean,
    onBack: () -> Unit, onEpisodes: () -> Unit, onSubtitles: () -> Unit,
    onRotate: () -> Unit, onMore: () -> Unit, onQuality: (() -> Unit)?, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val compact = maxWidth < 640.dp
        val shortViewport = maxHeight < 400.dp
        val actions: @Composable () -> Unit = {
            FlowRow(Modifier.testTag("player-actions"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (episodic) PlayerLineAction(PlayerActionGlyph.Episodes,"Episodes",onEpisodes)
                PlayerLineAction(PlayerActionGlyph.Captions,"Audio & Subtitles",onSubtitles)
                onQuality?.let { PlayerLineAction(PlayerActionGlyph.Quality,"Quality",it) }
                PlayerLineAction(PlayerActionGlyph.Rotate,"Rotate",onRotate)
                if (!compact) PlayerLineAction(PlayerActionGlyph.More,"More",onMore)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack, Modifier.size(48.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = .07f))) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back",tint = Color.White,modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = Color.White, fontSize = if (compact) 15.sp else 19.sp,
                        lineHeight = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = if (shortViewport) 1 else 2, overflow = TextOverflow.Ellipsis)
                    if (detail.isNotBlank()) Text(detail, color = Color.White.copy(alpha = .65f), fontSize = 12.sp,
                        lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (compact) PlayerLineAction(PlayerActionGlyph.More,"More",onMore) else actions()
            }
            if (compact) Row(Modifier.fillMaxWidth().padding(start = 60.dp)) { actions() }
        }
    }
}

private enum class PlayerActionGlyph { Episodes, Captions, Quality, Rotate, More }

@Composable
private fun PlayerLineAction(glyph: PlayerActionGlyph, label: String, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Box(Modifier.size(48.dp).clickable(interactionSource = interactions, indication = null,
        role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        // A 24-unit optical grid and a shared rounded stroke keep every glyph equally light.
        Canvas(Modifier.size(26.dp)) {
            val ink = Color.White.copy(alpha = if (pressed) .60f else .94f)
            val stroke = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float,y1: Float,x2: Float,y2: Float) =
                drawLine(ink,Offset(x1,y1),Offset(x2,y2),1.65f,StrokeCap.Round)
            fun path(first: Offset, second: Offset, third: Offset, fourth: Offset? = null) {
                val p = Path().apply { listOfNotNull(first,second,third,fourth).forEachIndexed { i, v -> if (i==0) moveTo(v.x,v.y) else lineTo(v.x,v.y) } }
                drawPath(p,ink,style = stroke)
            }
            withTransform({ scale(size.width/24f,size.height/24f,pivot = Offset.Zero) }) {
                when (glyph) {
                    PlayerActionGlyph.Episodes -> {
                        line(6f,3f,18f,3f)
                        drawRoundRect(ink,Offset(3f,6f),Size(18f,15f),CornerRadius(2.5f),style = stroke)
                        path(Offset(10f,10f),Offset(15f,13.5f),Offset(10f,17f),Offset(10f,10f))
                    }
                    PlayerActionGlyph.Captions -> {
                        drawRoundRect(ink,Offset(2.5f,4.5f),Size(19f,15f),CornerRadius(3f),style = stroke)
                        line(6.5f,10.5f,10.5f,10.5f); line(14f,10.5f,17.5f,10.5f)
                        line(6.5f,14.5f,14f,14.5f); line(17f,14.5f,17.5f,14.5f)
                    }
                    PlayerActionGlyph.Quality -> {
                        path(Offset(3f,8f),Offset(3f,4f),Offset(7f,4f))
                        path(Offset(17f,4f),Offset(21f,4f),Offset(21f,8f))
                        path(Offset(3f,16f),Offset(3f,20f),Offset(7f,20f))
                        path(Offset(17f,20f),Offset(21f,20f),Offset(21f,16f))
                        line(7f,14.5f,7f,12.5f); line(12f,14.5f,12f,10.5f); line(17f,14.5f,17f,8.5f)
                    }
                    PlayerActionGlyph.Rotate -> {
                        val p = Path().apply {
                            moveTo(9f,6.5f); lineTo(17.5f,11.5f); quadraticTo(19f,12.4f,18f,14f)
                            lineTo(15f,19f); quadraticTo(14f,20.5f,12.5f,19.5f); lineTo(4f,14.5f)
                            quadraticTo(2.5f,13.6f,3.5f,12f); lineTo(6.5f,7f); quadraticTo(7.5f,5.5f,9f,6.5f)
                            close()
                        }
                        drawPath(p,ink,style = stroke)
                        path(Offset(13f,2.5f),Offset(18f,3f),Offset(21f,6f))
                        path(Offset(21f,2.5f),Offset(21f,6f),Offset(17.5f,6f))
                    }
                    PlayerActionGlyph.More -> listOf(5f,12f,19f).forEach { x -> drawCircle(ink,1.45f,Offset(x,12f)) }
                }
            }
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
