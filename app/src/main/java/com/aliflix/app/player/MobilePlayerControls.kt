package com.aliflix.app.player

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
import androidx.compose.material.icons.rounded.Cast
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

/**
 * Top bar matching reference:
 * - 34dp circular translucent dark-grey button, white 18dp chevron, 48dp hit area.
 * - Single-line uppercase title with 12dp gap.
 * - 4 compact 30x30dp rounded-rect buttons on the right (Episodes for TV, Cast, Rotate, More).
 */
@Composable
internal fun MobilePlayerTopBar(
    title: String,
    isTv: Boolean,
    onBack: () -> Unit,
    onEpisodes: () -> Unit,
    onCast: () -> Unit,
    onRotate: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Top-left 34dp circular translucent back button with 48dp touch target
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title: single line, 16sp, SemiBold/Bold, uppercase
        Text(
            text = title.uppercase(Locale.ROOT),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Top-right compact buttons row (8dp gap between buttons)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isTv) {
                CompactTopButton(
                    icon = Icons.AutoMirrored.Rounded.ViewList,
                    contentDescription = "Episodes",
                    onClick = onEpisodes,
                )
            }
            CompactTopButton(
                icon = Icons.Rounded.Cast,
                contentDescription = "Cast",
                onClick = onCast,
            )
            CompactTopButton(
                icon = Icons.Rounded.ScreenRotation,
                contentDescription = "Rotate",
                onClick = onRotate,
            )
            CompactTopButton(
                icon = Icons.Rounded.MoreVert,
                contentDescription = "More",
                onClick = onMore,
            )
        }
    }
}

/**
 * Compact top-right button:
 * - 30x30dp visual container, RoundedCornerShape(8.dp)
 * - Translucent surface, 18dp white rounded icon, no stroke
 * - Invisible touch target of at least 48x48dp
 */
@Composable
private fun CompactTopButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Seek Back 15s
        Box(
            modifier = Modifier
                .size(52.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSeekBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Seek15Icon(
                isForward = false,
                modifier = Modifier.size(40.dp),
            )
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
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        // Seek Forward 15s
        Box(
            modifier = Modifier
                .size(52.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSeekForward,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Seek15Icon(
                isForward = true,
                modifier = Modifier.size(40.dp),
            )
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

            if (!isForward) {
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
