package com.aliflix.app.talk

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/** One session and one animated face in the player, with the full conversation a tap away. */
@Composable internal fun TalkPlayerBubble(session: TalkVoiceSession, onStart: () -> Unit, onClose: () -> Unit) {
    fun Modifier.measured(key:String):Modifier = if(!com.aliflix.app.BuildConfig.DEBUG)this else onGloballyPositioned { coordinates ->
        session.debugCardBounds[key]=coordinates.findRootCoordinates().localBoundingBoxOf(coordinates,clipBounds=false)
    }
    val state by session.state.collectAsState()
    val tasks by TalkTaskWork.get(androidx.compose.ui.platform.LocalContext.current).tasks.collectAsState()
    val running = tasks.lastOrNull { it.optString("status") in setOf("queued", "running") }
    var expanded by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val motion = com.aliflix.app.ui.common.rememberMobileMotionEnabled() && com.aliflix.app.ui.common.rememberMobileResumed() && !expanded
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp)) {
        val availableHeight = maxHeight
        val width = minOf(if (controls) 280.dp else 76.dp, maxWidth)
        val maxX = with(density) { (maxWidth - width).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { (maxHeight - if (typing) 150.dp else 80.dp).toPx().coerceAtLeast(0f) }
        val top = with(density) { (if(maxHeight>320.dp)96.dp else if(maxHeight>200.dp)48.dp else 0.dp).toPx() }.coerceAtMost(maxY)
        Surface(modifier = Modifier.align(Alignment.TopEnd).offset {
            IntOffset(dragX.coerceIn(-maxX, 0f).roundToInt(), (top+dragY).coerceIn(0f, maxY).roundToInt())
        }.width(width).testTag("talk-player-bubble"), shape = RoundedCornerShape(32.dp), color = Color(0xED11121D), shadowElevation = 8.dp) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(76.dp).measured("player-orb").semantics { contentDescription = "Talk controls" }.clickable { controls = !controls; if(!controls){typing=false;focus.clearFocus()} }.pointerInput(maxX, maxY, top) {
                        detectDragGestures { change, delta ->
                            change.consume()
                            dragX = (dragX + delta.x).coerceIn(-maxX, 0f)
                            dragY = (dragY + delta.y).coerceIn(-top, maxY-top)
                        }
                    }) { TalkLiveOrb(state.phase, { session.state.value.level }, motion, Modifier.fillMaxSize().testTag("talk-player-orb")) }
                    if (controls) {
                    IconButton(onClick = { if(state.textMode){session.setTextMode(false);onStart()} else if (state.phase in setOf("Ready", "Reconnect", "Paused", "Audio unavailable")) onStart() else session.toggleMute() },modifier=Modifier.measured("player-microphone")) {
                        Icon(if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Microphone", tint = Color.White)
                    }
                    IconButton(onClick = { if(availableHeight<170.dp){session.setTextMode(true);expanded=true}else{typing = !typing;if(!typing)focus.clearFocus()} },modifier=Modifier.measured("player-type")) { Icon(Icons.Rounded.Keyboard, "Type", tint = Color.White) }
                    IconButton(onClick = { expanded = true },modifier=Modifier.measured("player-expand")) { Icon(Icons.Rounded.OpenInFull, "Open conversation", tint = Color.White) }
                    IconButton(onClick = { focus.clearFocus();onClose() },modifier=Modifier.measured("player-close")) { Icon(Icons.Rounded.Close, "Close Talk", tint = Color.White) }
                    }
                }
                if (typing) Row(Modifier.padding(start = 12.dp, end = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(input, { input = it }, Modifier.weight(1f).testTag("talk-player-input"), singleLine = true)
                    IconButton(onClick = { val text = input.trim(); if (text.isNotEmpty()) { session.sendText(text); input = "" } }, enabled = input.isNotBlank()) {
                        Icon(Icons.Rounded.Send, "Send", tint = Color.White)
                    }
                }
                if (running != null) LinearProgressIndicator(
                    progress = { ((running.optInt("currentStep", 0)).toFloat() / (running.optJSONArray("commands")?.length() ?: 1).coerceAtLeast(1)).coerceIn(0f,1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp), color = Color(0xFFC4B5FD), trackColor = Color.Transparent)
            }
        }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        TalkVoiceScreen(session, onStart, onClose = { expanded = false })
    }
}
