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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.common.rememberMobileMotionEnabled

/** Stable, unframed mobile navigation and player actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MobilePlayerActionHeader(title: String, detail: String, episodic: Boolean,
    onBack: () -> Unit, onEpisodes: () -> Unit, onSubtitles: () -> Unit,
    onRotate: () -> Unit, onMore: () -> Unit, onQuality: (() -> Unit)?, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val compact = maxWidth < 640.dp
        val shortViewport = maxHeight < 400.dp
        val titleLines = when {
            shortViewport -> 1
            compact && maxHeight >= 720.dp && LocalDensity.current.fontScale > 1.3f -> 3
            else -> 2
        }
        val actions: @Composable () -> Unit = {
            FlowRow(Modifier.testTag("player-actions"),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (episodic) PlayerLineAction(PlayerActionGlyph.Episodes,"Episodes",onEpisodes)
                PlayerLineAction(PlayerActionGlyph.Captions,"Subtitles",onSubtitles)
                onQuality?.let { PlayerLineAction(PlayerActionGlyph.Quality,"Quality",it) }
                PlayerLineAction(PlayerActionGlyph.Rotate,"Rotate",onRotate)
                PlayerLineAction(PlayerActionGlyph.More,"More",onMore)
            }
        }
        val back: @Composable () -> Unit = {
                IconButton(onBack, Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back",tint = Color.White,modifier = Modifier.size(22.dp))
                }
        }
        val identity: @Composable (Modifier) -> Unit = { identityModifier ->
                Column(identityModifier,
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = Color.White, fontSize = if (compact) 15.sp else 19.sp,
                        lineHeight = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = titleLines, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("player-title"))
                    if (detail.isNotBlank()) Text(detail, color = Color.White.copy(alpha = .65f), fontSize = 12.sp,
                        lineHeight = 16.sp, maxLines = if (compact && !shortViewport) 2 else 1,
                        overflow = TextOverflow.Ellipsis,modifier = Modifier.fillMaxWidth().testTag("player-detail"))
                }
        }
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Navigation and actions share the first baseline; long identity text gets its own width.
                Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.Top) {
                    back()
                    Spacer(Modifier.weight(1f))
                    actions()
                }
                identity(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                back()
                identity(Modifier.weight(1f).padding(start = 12.dp,end = 8.dp))
                actions()
            }
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
        Canvas(Modifier.size(26.dp).playerActionReveal()) {
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

/** Rounds the exposed corners while letting the two play halves meet flush. */
private fun Path.roundedPlaybackHalf(half: Int, progress: Float) {
    rewind()
    val corners = (0..3).map { playbackMorphPoint(half*4+it,progress) to it }
        .fold(mutableListOf<Pair<Offset,Int>>()) { result, corner ->
            if (result.isEmpty() || (result.last().first-corner.first).getDistance() > .001f) result.add(corner)
            result
        }
    corners.forEachIndexed { index, (point, originalIndex) ->
        val before = corners[(index+corners.size-1)%corners.size].first-point
        val after = corners[(index+1)%corners.size].first-point
        val shared = if (half == 0) originalIndex == 1 || originalIndex == 2 else originalIndex == 0 || originalIndex == 3
        val rounding = .75f * if (shared) 1f-progress else 1f
        val incoming = point + before * (minOf(rounding,before.getDistance()*.45f)/before.getDistance())
        val outgoing = point + after * (minOf(rounding,after.getDistance()*.45f)/after.getDistance())
        if (index == 0) moveTo(incoming.x,incoming.y) else lineTo(incoming.x,incoming.y)
        quadraticTo(point.x,point.y,outgoing.x,outgoing.y)
    }
    close()
}

/** Aceternity's compatible paths retain slim pause bars and a smooth morph, with
 * softly rounded outer corners and no outline. Playback owns both glyph and label. */
@Composable
internal fun MobileMorphPlaybackIcon(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val motion = rememberMobileMotionEnabled()
    val progress = animateFloatAsState(if (isPlaying) 0f else 1f,
        if (motion) spring(dampingRatio = .85f, stiffness = 289f) else snap(),label = "playback-path-morph")
    val paths = remember { listOf(Path(),Path()) }
    val merged = remember { Path() }
    Canvas(modifier.playerActionReveal(rotate = false).semantics { contentDescription = if (isPlaying) "Pause" else "Play" }) {
        withTransform({ scale(size.width/24f,size.height/24f, pivot = Offset.Zero) }) {
            paths.forEachIndexed { half, path ->
                path.roundedPlaybackHalf(half,progress.value.coerceIn(0f,1f))
            }
            // One filled silhouette avoids a faint antialias seam where the play halves meet.
            if (merged.op(paths[0],paths[1],PathOperation.Union)) drawPath(merged,Color(0xFFF5F1FF))
            else paths.forEach { drawPath(it,Color(0xFFF5F1FF)) }
        }
    }
}
