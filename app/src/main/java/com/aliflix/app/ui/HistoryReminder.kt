package com.aliflix.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** One offset drives both the finger and settling; dismissing never mutates history. */
@Composable
internal fun HistoryReminder(itemKey: String, inHistory: Boolean, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var visible by remember(itemKey) { mutableStateOf(false) }
    var offset by remember(itemKey) { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(itemKey, inHistory) {
        if (inHistory) { delay(450); visible = true } else visible = false
    }
    LaunchedEffect(visible, dragging) {
        if (visible && !dragging) { delay(6500); visible = false }
    }
    AnimatedVisibility(
        visible = visible && inHistory,
        enter = fadeIn(tween(220)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { -it / 3 },
        exit = fadeOut(tween(180)), modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.onSizeChanged { width = it.width.coerceAtLeast(1) }
                .graphicsLayer {
                    translationX = offset
                    alpha = (1f - abs(offset) / (width * 1.2f)).coerceIn(0f, 1f)
                }
                .draggable(
                    state = rememberDraggableState { offset += it },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { settling?.cancel(); dragging = true },
                    onDragStopped = { velocity ->
                        settling = scope.launch {
                            val dismiss = abs(offset) > width * .30f || abs(velocity) > 900f
                            val direction = if (abs(velocity) > 900f) sign(velocity) else sign(offset)
                            animate(offset, if (dismiss) direction * width * 1.3f else 0f,
                                initialVelocity = velocity,
                                animationSpec = spring(dampingRatio = 1f, stiffness = 500f)) { value, _ -> offset = value }
                            if (dismiss) visible = false
                            dragging = false
                        }
                    },
                ),
            shape = RoundedCornerShape(50), color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.22f),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("In your history", color = AliflixContentPrimary, fontSize = 12.sp)
                TextButton(onClick = { visible = false; onDelete() }) {
                    Text("Remove", color = AliflixAccentSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}
