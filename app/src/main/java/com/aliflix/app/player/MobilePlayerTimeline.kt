package com.aliflix.app.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val AliflixPurple = Color(0xFF6E59D9)
private val UnplayedTrackColor = Color.White.copy(alpha = 0.32f)
private val BufferedTrackColor = Color.White.copy(alpha = 0.22f)
private val TimeTextColor = Color.White.copy(alpha = 0.82f)
private val BubbleBackground = Color(0xFF141722)

/**
 * Bottom timeline matching reference specification:
 * - Positioned ~30dp from left and right safe edges, ~28dp above bottom safe edge.
 * - Time labels immediately above track: elapsed time on the left, remaining time on the right.
 * - 3dp normal progress track, expanding to 5dp on touch/drag.
 * - 16dp purple circular thumb, expanding to 18dp on drag.
 * - Dark rounded timestamp bubble above thumb during scrub.
 * - No extra controls underneath.
 */
@Composable
internal fun MobilePlayerTimeline(
    currentPositionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
    onSeek: (Long) -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val totalDurationMs = durationMs.coerceAtLeast(0L)
    val displayedCurrentMs = if (isDragging) {
        (scrubFraction * totalDurationMs).toLong().coerceIn(0L, totalDurationMs)
    } else {
        currentPositionMs.coerceIn(0L, totalDurationMs.coerceAtLeast(1L))
    }

    val progressFraction = if (totalDurationMs > 0L) {
        (displayedCurrentMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val bufferedFraction = if (totalDurationMs > 0L) {
        (bufferedPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val currentSeconds = displayedCurrentMs / 1000.0
    val durationSeconds = totalDurationMs / 1000.0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp, vertical = 8.dp),
    ) {
        // Time labels immediately above the progress track
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Elapsed time, e.g. 32:47
            Text(
                text = formatPlaybackTime(currentSeconds),
                color = TimeTextColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.weight(1f))
            // Remaining time, e.g. -1:09:13
            Text(
                text = formatRemainingTime(currentSeconds, durationSeconds),
                color = TimeTextColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Progress track with scrubbing support
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val trackHeight by animateDpAsState(
                targetValue = if (isDragging) 5.dp else 3.dp,
                label = "trackHeight",
            )
            val thumbSize by animateDpAsState(
                targetValue = if (isDragging) 18.dp else 16.dp,
                label = "thumbSize",
            )

            // Scrubber gesture area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .pointerInput(totalDurationMs) {
                        detectTapGestures(
                            onPress = { offset ->
                                if (totalDurationMs > 0L) {
                                    val fraction = (offset.x / widthPx).coerceIn(0f, 1f)
                                    scrubFraction = fraction
                                    isDragging = true
                                    onScrubbingChanged(true)
                                    val targetMs = (fraction * totalDurationMs).toLong()
                                    onSeek(targetMs)
                                    val success = tryAwaitRelease()
                                    if (success) {
                                        isDragging = false
                                        onScrubbingChanged(false)
                                    }
                                }
                            },
                        )
                    }
                    .pointerInput(totalDurationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                if (totalDurationMs > 0L) {
                                    isDragging = true
                                    onScrubbingChanged(true)
                                    scrubFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                                    val targetMs = (scrubFraction * totalDurationMs).toLong()
                                    onSeek(targetMs)
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                                onScrubbingChanged(false)
                            },
                            onDragCancel = {
                                isDragging = false
                                onScrubbingChanged(false)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                if (totalDurationMs > 0L) {
                                    val fraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                                    scrubFraction = fraction
                                    val targetMs = (fraction * totalDurationMs).toLong()
                                    onSeek(targetMs)
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                // Background unplayed track, buffered track, played track
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackHeight),
                ) {
                    val cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)

                    // Unplayed base
                    drawRoundRect(
                        color = UnplayedTrackColor,
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = cornerRadius,
                    )

                    // Buffered range
                    if (bufferedFraction > 0f) {
                        drawRoundRect(
                            color = BufferedTrackColor,
                            topLeft = Offset.Zero,
                            size = Size(size.width * bufferedFraction, size.height),
                            cornerRadius = cornerRadius,
                        )
                    }

                    // Played progress
                    if (progressFraction > 0f) {
                        drawRoundRect(
                            color = AliflixPurple,
                            topLeft = Offset.Zero,
                            size = Size(size.width * progressFraction, size.height),
                            cornerRadius = cornerRadius,
                        )
                    }
                }

                // Thumb
                val thumbOffsetPx = (widthPx * progressFraction).coerceIn(0f, widthPx)
                Box(
                    modifier = Modifier
                        .offset {
                            val halfThumb = (thumbSize.toPx() / 2f).roundToInt()
                            IntOffset(
                                x = (thumbOffsetPx - halfThumb).roundToInt().coerceAtLeast(0),
                                y = 0,
                            )
                        }
                        .size(thumbSize)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(AliflixPurple),
                )

                // Timestamp bubble above thumb while dragging
                if (isDragging) {
                    val bubbleWidthPx = 160f
                    val bubbleOffsetPx = (thumbOffsetPx - bubbleWidthPx / 2f).coerceIn(0f, (widthPx - bubbleWidthPx).coerceAtLeast(0f))
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    x = bubbleOffsetPx.roundToInt(),
                                    y = -46.dp.roundToPx(),
                                )
                            }
                            .shadow(8.dp, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .background(BubbleBackground)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = formatPlaybackTime(currentSeconds),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Format playback elapsed time (e.g. 32:47 or 1:42:00)
 */
internal fun formatPlaybackTime(seconds: Double): String {
    val totalSeconds = seconds.toLong().coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val remainingSeconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        "%d:%02d".format(minutes, remainingSeconds)
    }
}

/**
 * Format remaining playback time (e.g. -1:09:13 or -45:20)
 */
internal fun formatRemainingTime(currentSeconds: Double, durationSeconds: Double): String {
    if (durationSeconds <= 0.0) return "0:00"
    val remaining = (durationSeconds - currentSeconds).coerceAtLeast(0.0)
    return "-${formatPlaybackTime(remaining)}"
}
