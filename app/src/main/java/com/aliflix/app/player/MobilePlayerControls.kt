package com.aliflix.app.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Embedded-first mobile player identity and one low-contrast action pill. */
@Composable
internal fun MobilePlayerTopBar(
    title: String,
    isTv: Boolean,
    onBack: () -> Unit,
    onEpisodes: () -> Unit,
    onSubtitles: () -> Unit,
    onRotate: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String = "",
    onQuality: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val compact = maxWidth < 560.dp
        val identity: @Composable () -> Unit = {
            androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f))) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        val actions: @Composable () -> Unit = {
            Row(Modifier.clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.07f)), verticalAlignment = Alignment.CenterVertically) {
                if (isTv) CompactTopButton(Icons.AutoMirrored.Rounded.ViewList, "Episodes", onEpisodes)
                CompactTopButton(Icons.Rounded.Subtitles, "Audio & Subtitles", onSubtitles)
                onQuality?.let { CompactTopButton(Icons.Rounded.HighQuality, "Quality", it) }
                CompactTopButton(Icons.Rounded.ScreenRotation, "Rotate", onRotate)
                CompactTopButton(Icons.Rounded.MoreVert, "More", onMore)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            identity()
            androidx.compose.foundation.layout.Column(
                Modifier.weight(1f).padding(start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(title, color = Color.White, fontSize = if (compact) 15.sp else 20.sp,
                    lineHeight = if (compact) 20.sp else 25.sp,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold)
                if (detail.isNotBlank()) Text(detail, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp,
                    lineHeight = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            actions()
        }
    }
}

@Composable
private fun CompactTopButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
    }
}

/**
 * Center playback controls matching reference:
 * [ BACK 15 ]      [ PLAY/PAUSE ]      [ FORWARD 15 ]
 *
 * PLAY/PAUSE:
 * - 64dp circular control
 * - transparent/dark translucent interior
 * - 1.5dp white outline
 * - white play/pause glyph approx 30dp
 *
 * SEEK BACK/FORWARD:
 * - visual icon approx 40dp
 * - white circular arrow with "15" integrated inside
 * - ~56dp horizontal space between controls
 * - touch targets 48x48dp+
 */
@Composable
internal fun MobilePlayerCenterControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    feedback: SeekFeedbackState? = null,
    hideSeekBack: Boolean = false,
    hideSeekForward: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Seek Back 15s
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(52.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !hideSeekBack,
                    onClick = onSeekBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !hideSeekBack && feedback?.isForward != false,
                enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.85f),
                exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.85f),
            ) {
                Seek15Icon(
                    isForward = false,
                    modifier = Modifier.size(40.dp),
                )
            }
            if (feedback?.isForward == false) SeekButtonFeedback(feedback)
        }

        // Play / Pause 64dp outlined circle
        Box(
            modifier = Modifier
                .size(72.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayPause,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.28f))
                    .border(BorderStroke(1.5.dp, Color.White), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(targetState = isPlaying, transitionSpec = {
                    (fadeIn(tween(180, delayMillis = 45)) + scaleIn(tween(220), initialScale = .65f)) togetherWith
                        (fadeOut(tween(100)) + scaleOut(tween(160), targetScale = 1.2f))
                }, label = "play-pause") { active ->
                Icon(
                    imageVector = if (active) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (active) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
                }
            }
        }

        // Seek Forward 15s
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(52.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !hideSeekForward,
                    onClick = onSeekForward,
                ),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !hideSeekForward && feedback?.isForward != true,
                enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.85f),
                exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.85f),
            ) {
                Seek15Icon(
                    isForward = true,
                    modifier = Modifier.size(40.dp),
                )
            }
            if (feedback?.isForward == true) SeekButtonFeedback(feedback)
        }
    }
}

/**
 * Replay/Forward circular arrow icon with number "15" integrated inside.
 */
@Composable
internal fun Seek15Icon(
    isForward: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(38.dp)) {
            val strokeWidth = 1.6.dp.toPx()
            val diameter = size.minDimension - strokeWidth * 2
            val radius = diameter / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            if (isForward) {
                // Counter-clockwise circular arc with top arrow pointing left
                val startAngle = 60f
                val sweepAngle = 265f
                drawArc(
                    color = tint,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(diameter, diameter),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                // Arrowhead at the start of the arc (around top)
                val arrowTipAngleRad = Math.toRadians((startAngle + sweepAngle).toDouble())
                val tipX = center.x + radius * cos(arrowTipAngleRad).toFloat()
                val tipY = center.y + radius * sin(arrowTipAngleRad).toFloat()
                val arrowPath = Path().apply {
                    moveTo(tipX, tipY)
                    lineTo(tipX - 4.5.dp.toPx(), tipY - 4.dp.toPx())
                    moveTo(tipX, tipY)
                    lineTo(tipX + 1.dp.toPx(), tipY - 5.5.dp.toPx())
                }
                drawPath(
                    path = arrowPath,
                    color = tint,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            } else {
                // Clockwise circular arc with top arrow pointing right
                val startAngle = 120f
                val sweepAngle = -265f
                drawArc(
                    color = tint,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(diameter, diameter),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                // Arrowhead at the end of the arc
                val arrowTipAngleRad = Math.toRadians((startAngle + sweepAngle).toDouble())
                val tipX = center.x + radius * cos(arrowTipAngleRad).toFloat()
                val tipY = center.y + radius * sin(arrowTipAngleRad).toFloat()
                val arrowPath = Path().apply {
                    moveTo(tipX, tipY)
                    lineTo(tipX + 4.5.dp.toPx(), tipY - 4.dp.toPx())
                    moveTo(tipX, tipY)
                    lineTo(tipX - 1.dp.toPx(), tipY - 5.5.dp.toPx())
                }
                drawPath(
                    path = arrowPath,
                    color = tint,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = "15",
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun SeekButtonFeedback(feedback: SeekFeedbackState) {
    androidx.compose.runtime.key(feedback.token) {
        androidx.compose.animation.AnimatedVisibility(visibleState = androidx.compose.runtime.remember {
            androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
        }, enter = fadeIn(tween(120)) + scaleIn(tween(240), initialScale = .68f)) {
            Box(Modifier.size(52.dp).background(Color(0x336E59D9), CircleShape), contentAlignment = Alignment.Center) {
                Text(if (feedback.accumulatedSeconds > 0) "+${feedback.accumulatedSeconds}s" else "${feedback.accumulatedSeconds}s",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
