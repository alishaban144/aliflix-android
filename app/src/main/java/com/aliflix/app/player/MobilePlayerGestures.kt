package com.aliflix.app.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val AliflixPurpleRadial = Color(0xFF6E59D9).copy(alpha = 0.26f)

internal data class SeekFeedbackState(
    val isForward: Boolean = false,
    val accumulatedSeconds: Int = 0,
    val token: Long = 0L,
)

/**
 * Controller state holder for double-tap and center seek feedback HUD.
 */
internal class SeekFeedbackController {
    var state by mutableStateOf<SeekFeedbackState?>(null)
        private set

    private var lastSeekAt = 0L
    private var tokenCounter = 0L

    fun triggerSeek(isForward: Boolean) {
        val now = System.currentTimeMillis()
        val current = state
        val isContinuation = current != null && current.isForward == isForward && (now - lastSeekAt) < 650L
        val accumulated = if (isContinuation) {
            current!!.accumulatedSeconds + (if (isForward) 15 else -15)
        } else {
            if (isForward) 15 else -15
        }
        lastSeekAt = now
        tokenCounter++
        state = SeekFeedbackState(
            isForward = isForward,
            accumulatedSeconds = accumulated,
            token = tokenCounter,
        )
    }

    fun dismiss() {
        state = null
    }
}

/**
 * Fullscreen video gesture layer:
 * - Single tap: toggle controls
 * - Double tap left 35%: seek -15s
 * - Double tap right 35%: seek +15s
 * - Middle 30%: neutral
 */
@Composable
internal fun MobileVideoGestureDetector(
    onToggleControls: () -> Unit,
    onSeekRewind: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    // pointerInput survives recomposition: always read the current controller callbacks.
    val toggleControls by rememberUpdatedState(onToggleControls)
    val seekRewind by rememberUpdatedState(onSeekRewind)
    val seekForward by rememberUpdatedState(onSeekForward)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { toggleControls() },
                    onDoubleTap = { offset ->
                        val xRatio = offset.x / size.width.toFloat()
                        if (xRatio < 0.35f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            seekRewind()
                        } else if (xRatio > 0.65f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            seekForward()
                        }
                    },
                )
            },
    )
}

/**
 * Seek feedback HUD overlay displaying over left or right side:
 * "+15s" / "+30s" or "-15s" / "-30s" accumulated seek text.
 * Fast scale-in + fade-in, auto-dismisses after 650ms.
 */
@Composable
internal fun SeekFeedbackHud(
    feedback: SeekFeedbackState?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (feedback == null) return

    LaunchedEffect(feedback.token) {
        delay(650L)
        onDismiss()
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.offset(x = if (feedback.isForward) 118.dp else (-118).dp)) {
            SeekButtonFeedback(feedback)
        }
    }
}
