package com.aliflix.app.talk

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import kotlin.math.roundToInt

/** The halo belongs to the orb; a separate contextual palette never wraps it in a ring. */
@Composable internal fun TalkPlayerBubble(session: TalkVoiceSession, onStart: () -> Unit, onClose: () -> Unit) {
    fun Modifier.measured(key: String): Modifier = if (!com.aliflix.app.BuildConfig.DEBUG) this else onGloballyPositioned {
        session.debugCardBounds[key] = it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = false)
    }
    val state by session.state.collectAsState()
    val context = LocalContext.current
    val work = TalkTaskWork.get(context)
    val tasks by work.tasks.collectAsState()
    val running = tasks.lastOrNull { it.optString("conversationId") == state.conversationId && it.optBoolean("cancellable") &&
        it.optString("status") in setOf("queued", "running", "awaiting_user_input") }
    var expanded by remember { mutableStateOf(false) }
    var readReply by remember { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val draft = rememberSaveable(state.conversationId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var dragX by rememberSaveable { mutableFloatStateOf(0f) }
    var dragY by rememberSaveable { mutableFloatStateOf(0f) }
    var panelHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val composerFocus = remember { FocusRequester() }
    val motion = com.aliflix.app.ui.common.rememberMobileMotionEnabled() && com.aliflix.app.ui.common.rememberMobileResumed()
    fun dismissComposer() { typing = false; focus.clearFocus(); keyboard?.hide() }
    fun dismissConversation() { expanded = false; readReply = false; dismissComposer() }
    fun send() {
        val text = draft.value.text.trim()
        if (text.isNotEmpty()) {
            session.sendText(text); draft.value = TextFieldValue(); dismissComposer()
            controls = false; readReply = true; expanded = true
        }
    }
    fun askScene(dialogue: Boolean = false) {
        session.askScene(dialogue); controls = false; dismissComposer()
        if (state.textMode) { readReply = true; expanded = true }
    }
    BackHandler(enabled = !expanded && (controls || typing)) { if (typing) dismissComposer() else controls = false }
    LaunchedEffect(typing, expanded) { if (typing && !expanded) { withFrameNanos {}; composerFocus.requestFocus(); keyboard?.show() } }
    LaunchedEffect(controls, typing, running?.optString("taskId")) {
        if (!controls) listOf("player-microphone", "player-type", "player-expand", "player-close", "player-cancel", "player-explain", "player-replay", "player-dialogue").forEach(session.debugCardBounds::remove)
        if (!typing) session.debugCardBounds.remove("player-input")
        if (running == null) session.debugCardBounds.remove("player-cancel")
    }
    LaunchedEffect(expanded) {
        if (expanded) listOf("player-input", "player-send").forEach(session.debugCardBounds::remove)
        else listOf("player-full-input", "player-full-close", "player-full-send").forEach(session.debugCardBounds::remove)
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp).testTag("talk-player-bubble")) {
        val availableHeight = maxHeight
        val orbSize = 76.dp
        val maxX = with(density) { (maxWidth - orbSize).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { (maxHeight - orbSize).toPx().coerceAtLeast(0f) }
        val top = with(density) { (if (maxHeight > 320.dp) 96.dp else if (maxHeight > 200.dp) 48.dp else 0.dp).toPx() }.coerceAtMost(maxY)
        val orbX = (maxX + dragX).coerceIn(0f, maxX)
        val orbY = (top + dragY).coerceIn(0f, maxY)
        val paletteWidth = minOf(280.dp, maxWidth)
        val gap = with(density) { 12.dp.toPx() }
        val palettePixels = with(density) { paletteWidth.toPx() }
        val orbPixels = with(density) { orbSize.toPx() }
        val roomLeft = orbX >= palettePixels + gap
        val roomRight = maxX + orbPixels - (orbX + orbPixels) >= palettePixels + gap
        val paletteX = when { roomLeft -> orbX - palettePixels - gap; roomRight -> orbX + orbPixels + gap
            else -> (orbX + orbPixels / 2 - palettePixels / 2).coerceIn(0f, (maxX + orbPixels - palettePixels).coerceAtLeast(0f)) }
        val estimatedHeight = panelHeight.takeIf { it > 0 } ?: with(density) { (if (typing) 230.dp else 140.dp).roundToPx() }
        val heightPixels = with(density) { maxHeight.toPx() }
        val paletteY = (if (roomLeft || roomRight) orbY else if (orbY + orbPixels + gap + estimatedHeight <= heightPixels) orbY + orbPixels + gap else orbY - estimatedHeight - gap)
            .coerceIn(0f, (heightPixels - estimatedHeight).coerceAtLeast(0f))
        LaunchedEffect(typing, expanded, availableHeight) { if (typing && !expanded && availableHeight < 220.dp) { focus.clearFocus(); expanded = true } }
        Box(Modifier.absoluteOffset { IntOffset(paletteX.roundToInt(), paletteY.roundToInt()) }) {
            AnimatedVisibility(controls && !expanded,
                enter = if (motion) fadeIn() + scaleIn(initialScale = .94f, transformOrigin = TransformOrigin(if (roomLeft) 1f else 0f, .2f)) else EnterTransition.None,
                exit = if (motion) fadeOut() + scaleOut(targetScale = .96f) else ExitTransition.None) {
                Surface(Modifier.width(paletteWidth).onSizeChanged { panelHeight = it.height }.measured("player-palette"),
                    shape = RoundedCornerShape(28.dp), color = Color(0xF2151422), shadowElevation = 14.dp) {
                    Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF252237).copy(alpha = .65f), Color(0xFF12121D).copy(alpha = .5f)))).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            val answering = state.sending || state.phase == "Speaking"
                            ScenePaletteButton(if (answering) Icons.Rounded.StopCircle else Icons.Rounded.AutoAwesome,
                                if (answering) "Stop reply" else "Explain this scene", Modifier.measured("player-explain"), accent = true, active = answering) {
                                if (answering) session.stopAnswer() else askScene()
                            }
                            ScenePaletteButton(Icons.Rounded.Replay, "Replay this exchange", Modifier.measured("player-replay"), accent = true) { controls = false; dismissComposer(); session.replayScene() }
                            ScenePaletteButton(Icons.Rounded.FormatQuote, "Explain this dialogue", Modifier.measured("player-dialogue"), accent = true) { askScene(dialogue = true) }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = .07f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val idle = state.phase in setOf("Ready", "Resume", "Reconnect", "Paused", "Microphone permission", "Audio unavailable", "Try again later", "Opened elsewhere")
                            ScenePaletteButton(if (idle) Icons.Rounded.Refresh else if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                if (idle) "Retry connection" else if (state.textMode || state.muted) "Unmute microphone" else "Mute microphone",
                                Modifier.measured("player-microphone").semantics { stateDescription = if (state.muted) "Muted" else state.phase }, active = !state.textMode && !state.muted) {
                                if (state.textMode) { session.setTextMode(false); onStart() } else if (idle) onStart() else session.toggleMute()
                            }
                            ScenePaletteButton(Icons.Rounded.Keyboard, "Type", Modifier.measured("player-type").semantics { selected = typing }, active = typing) {
                                if (!typing && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) session.setTextMode(true)
                                typing = !typing; if (!typing) { focus.clearFocus(); keyboard?.hide() }
                            }
                            ScenePaletteButton(Icons.Rounded.OpenInFull, "Open conversation", Modifier.measured("player-expand")) { focus.clearFocus(); keyboard?.hide(); expanded = true }
                            ScenePaletteButton(Icons.Rounded.Close, "Close Talk", Modifier.measured("player-close")) { dismissComposer(); onClose() }
                        }
                        AnimatedVisibility(typing && !expanded) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = .24f)).padding(start = 14.dp, end = 4.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).heightIn(min = 48.dp).measured("player-input")) {
                                BasicTextField(draft.value, { if (it.text.length <= 1600) draft.value = it }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .focusRequester(composerFocus).testTag("talk-player-input").semantics { contentDescription = "Message Aliflix" },
                                    textStyle = TextStyle(color = Color(0xFFF4F0FF), fontSize = 15.sp, lineHeight = 21.sp), cursorBrush = SolidColor(AliflixAccentSecondary),
                                    minLines = 1, maxLines = 3, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                                    decorationBox = { inner -> Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) { if (draft.value.text.isEmpty()) Text("Message Aliflix", color = Color(0xFF9E99B0), fontSize = 15.sp); inner() } })
                                }
                                FilledIconButton(onClick = ::send, enabled = draft.value.text.isNotBlank(), modifier = Modifier.size(48.dp).measured("player-send"),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = AliflixAccentSecondary, contentColor = Color(0xFF181322), disabledContainerColor = Color.White.copy(alpha = .04f))) {
                                    Icon(Icons.AutoMirrored.Rounded.Send, "Send", Modifier.size(21.dp))
                                }
                            }
                        }
                        if (running != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(progress = { ((running.optJSONArray("results")?.length() ?: 0).toFloat() / (running.optJSONArray("commands")?.length() ?: 1).coerceAtLeast(1)).coerceIn(0f, 1f) },
                                    modifier = Modifier.weight(1f).height(2.dp), color = AliflixAccentSecondary, trackColor = Color.White.copy(alpha = .06f))
                                ScenePaletteButton(Icons.Rounded.Cancel, "Cancel current task", Modifier.measured("player-cancel")) { work.cancel(running.getString("taskId")) }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.absoluteOffset { IntOffset(orbX.roundToInt(), orbY.roundToInt()) }.size(orbSize).measured("player-orb").semantics {
            contentDescription = "Talk controls"; stateDescription = if (controls) "Expanded, ${state.phase}" else state.phase
            customActions = listOf(CustomAccessibilityAction("Move to other side") { dragX = if (orbX > maxX / 2) -maxX else 0f; true })
        }.clickable(role = Role.Button, onClickLabel = if (controls) "Collapse controls" else "Expand controls") { controls = !controls; if (!controls) dismissComposer() }
            .pointerInput(maxX, maxY, top) { detectDragGestures(onDragStart = { controls = false; dismissComposer() }) { change, delta -> change.consume(); dragX = (dragX + delta.x).coerceIn(-maxX, 0f); dragY = (dragY + delta.y).coerceIn(-top, maxY - top) } }) {
            TalkLiveOrb(state.phase, { session.state.value.level }, motion && !expanded, Modifier.fillMaxSize().testTag("talk-player-orb"))
        }
    }
    if (expanded) Dialog(onDismissRequest = ::dismissConversation, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        TalkVoiceScreen(session, onStart, onClose = ::dismissConversation, playerOverlay = true, composerDraft = draft, showComposer = typing, showTranscript = readReply)
    }
}

@Composable private fun ScenePaletteButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, accent: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).clip(CircleShape)
        .background(if (active) AliflixAccentSecondary.copy(alpha = .19f) else if (accent) Color.White.copy(alpha = .045f) else Color.Transparent)) {
        Icon(icon, label, Modifier.size(if (accent) 23.dp else 21.dp), tint = if (accent || active) AliflixAccentSecondary else Color(0xFFD5D0E3))
    }
}
