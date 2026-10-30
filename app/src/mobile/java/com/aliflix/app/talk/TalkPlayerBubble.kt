package com.aliflix.app.talk

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import kotlin.math.roundToInt

/** The orb remains the spatial anchor while its contextual controls unfold inward. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TalkPlayerBubble(session: TalkVoiceSession, onStart: () -> Unit, onClose: () -> Unit) {
    fun Modifier.measured(key:String):Modifier = if(!com.aliflix.app.BuildConfig.DEBUG)this else onGloballyPositioned { coordinates ->
        session.debugCardBounds[key]=coordinates.findRootCoordinates().localBoundingBoxOf(coordinates,clipBounds=false)
    }
    val state by session.state.collectAsState()
    val context=LocalContext.current
    val work=TalkTaskWork.get(context)
    val tasks by work.tasks.collectAsState()
    val running = tasks.lastOrNull { it.optString("conversationId")==state.conversationId && it.optBoolean("cancellable") &&
        it.optString("status") in setOf("queued", "running", "awaiting_user_input") }
    var expanded by remember { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val draft = rememberSaveable(state.conversationId,stateSaver=TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var dragX by rememberSaveable { mutableFloatStateOf(0f) }
    var dragY by rememberSaveable { mutableFloatStateOf(0f) }
    var measuredHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val composerFocus=remember{FocusRequester()}
    val motion = com.aliflix.app.ui.common.rememberMobileMotionEnabled() && com.aliflix.app.ui.common.rememberMobileResumed()
    fun dismissComposer(){typing=false;focus.clearFocus();keyboard?.hide()}
    fun dismissConversation(){expanded=false;dismissComposer()}
    fun send(){val text=draft.value.text.trim();if(text.isNotEmpty()){session.sendText(text);draft.value=TextFieldValue();dismissComposer()}}
    BackHandler(enabled=!expanded&&(controls||typing)) { if(typing)dismissComposer()else controls=false }
    LaunchedEffect(typing,expanded){if(typing&&!expanded){withFrameNanos{};composerFocus.requestFocus();keyboard?.show()}}
    LaunchedEffect(controls,typing,running?.optString("taskId")) {
        if(!controls)listOf("player-microphone","player-type","player-expand","player-close","player-cancel").forEach(session.debugCardBounds::remove)
        if(!typing)session.debugCardBounds.remove("player-input")
        if(running==null)session.debugCardBounds.remove("player-cancel")
    }
    LaunchedEffect(expanded){if(!expanded)listOf("player-full-input","player-full-close","player-full-send").forEach(session.debugCardBounds::remove)}
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp)) {
        val availableHeight = maxHeight
        // Preserve 48dp targets, wrapping contextual actions rather than clipping on narrow windows.
        val width = minOf(if(controls) (76+48*(if(running!=null)5 else 4)+8).dp else 76.dp, maxWidth)
        val maxX = with(density) { (maxWidth - width).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { (maxHeight.toPx()-measuredHeight.coerceAtLeast(76.dp.roundToPx())).coerceAtLeast(0f) }
        val top = with(density) { (if(maxHeight>320.dp)96.dp else if(maxHeight>200.dp)48.dp else 0.dp).toPx() }.coerceAtMost(maxY)
        LaunchedEffect(typing,expanded,availableHeight){if(typing&&!expanded&&availableHeight<180.dp){focus.clearFocus();expanded=true}}
        Surface(modifier = Modifier.align(Alignment.TopEnd).offset {
            IntOffset(dragX.coerceIn(-maxX, 0f).roundToInt(), (top+dragY).coerceIn(0f, maxY).roundToInt())
        }.animateContentSize(animationSpec=if(motion)spring(dampingRatio=.92f,stiffness=650f)else snap(),alignment=Alignment.TopEnd)
            .width(width).onSizeChanged{measuredHeight=it.height}.testTag("talk-player-bubble"),
            shape = RoundedCornerShape(38.dp), color = Color(0xF211121D), border=BorderStroke(1.dp,AliflixAccentSecondary.copy(alpha=.14f)),shadowElevation = 8.dp) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (controls) FlowRow(Modifier.weight(1f).padding(start=4.dp),horizontalArrangement=Arrangement.End) {
                        IconButton(onClick={dismissComposer();onClose()},modifier=Modifier.size(48.dp).measured("player-close")) { Icon(Icons.Rounded.Close,"Close Talk",tint=Color.White) }
                        IconButton(onClick={focus.clearFocus();keyboard?.hide();expanded=true},modifier=Modifier.size(48.dp).measured("player-expand")) { Icon(Icons.Rounded.OpenInFull,"Open conversation",tint=Color.White) }
                        if(running!=null)IconButton(onClick={work.cancel(running.getString("taskId"))},modifier=Modifier.size(48.dp).measured("player-cancel")) {
                            Icon(Icons.Rounded.Cancel,"Cancel current task",tint=AliflixAccentSecondary)
                        }
                        IconButton(onClick={
                            if(!typing&&androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)session.setTextMode(true)
                            typing=!typing;if(!typing){focus.clearFocus();keyboard?.hide()}
                        },modifier=Modifier.size(48.dp).measured("player-type").semantics{selected=typing}) {
                            Icon(Icons.Rounded.Keyboard,"Type",tint=if(typing)AliflixAccentSecondary else Color.White)
                        }
                        val idle=state.phase in setOf("Ready","Resume","Reconnect","Paused","Microphone permission","Audio unavailable","Try again later","Opened elsewhere")
                        IconButton(onClick={if(state.textMode){session.setTextMode(false);onStart()}else if(idle)onStart()else session.toggleMute()},
                            modifier=Modifier.size(48.dp).measured("player-microphone").semantics{stateDescription=if(state.muted)"Muted"else state.phase}) {
                            Icon(if(idle)Icons.Rounded.Refresh else if(state.muted)Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                if(idle)"Retry connection"else if(state.textMode||state.muted)"Unmute microphone"else "Mute microphone",tint=AliflixAccentSecondary)
                        }
                    }
                    Box(Modifier.size(76.dp).measured("player-orb").semantics {
                        contentDescription="Talk controls";stateDescription=if(controls)"Expanded, ${state.phase}"else state.phase
                        customActions=listOf(CustomAccessibilityAction("Move to other side") {dragX=if(dragX< -maxX/2)0f else -maxX;true})
                    }.clickable(role=Role.Button,onClickLabel=if(controls)"Collapse controls"else "Expand controls") {controls=!controls;if(!controls)dismissComposer()}
                        .pointerInput(maxX,maxY,top,direction){detectDragGestures {change,delta->
                            change.consume();dragX=(dragX+if(direction==LayoutDirection.Rtl)-delta.x else delta.x).coerceIn(-maxX,0f)
                            dragY=(dragY+delta.y).coerceIn(-top,maxY-top)
                        }}) {
                        TalkLiveOrb(state.phase,{session.state.value.level},motion&&!expanded,Modifier.fillMaxSize().testTag("talk-player-orb"))
                    }
                }
                if (typing&&!expanded) Row(Modifier.padding(start=12.dp,end=8.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextField(draft.value,{draft.value=if(it.text.length<=1600)it else it.copy(text=it.text.take(1600))},Modifier.weight(1f).focusRequester(composerFocus).measured("player-input").testTag("talk-player-input").semantics{contentDescription="Message Aliflix"},
                        singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}))
                    IconButton(onClick=::send,enabled=draft.value.text.isNotBlank(),modifier=Modifier.size(48.dp).measured("player-send")) { Icon(Icons.AutoMirrored.Rounded.Send,"Send",tint=AliflixAccentSecondary) }
                }
                if (running != null) LinearProgressIndicator(
                    progress={((running.optJSONArray("results")?.length()?:0).toFloat()/(running.optJSONArray("commands")?.length()?:1).coerceAtLeast(1)).coerceIn(0f,1f)},
                    modifier=Modifier.fillMaxWidth().height(2.dp),color=if(running.optString("status")=="awaiting_user_input")Color(0xFFF2C879)else AliflixAccentSecondary,trackColor=Color.Transparent)
            }
        }
    }
    if (expanded) Dialog(onDismissRequest=::dismissConversation,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        TalkVoiceScreen(session,onStart,onClose=::dismissConversation,playerOverlay=true,composerDraft=draft,showComposer=typing)
    }
}
