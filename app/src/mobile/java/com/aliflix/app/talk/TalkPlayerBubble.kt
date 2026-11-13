package com.aliflix.app.talk

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
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

@Composable internal fun TalkPlayerBubble(session: TalkVoiceSession, onStart: () -> Unit, onClose: () -> Unit, orbTransition: Modifier = Modifier,
    tracker: com.aliflix.app.ui.discover.DiscoverTouchTracker = remember { com.aliflix.app.ui.discover.DiscoverTouchTracker() }) {
    fun Modifier.measured(key: String): Modifier = if (!com.aliflix.app.BuildConfig.DEBUG) this else onGloballyPositioned {
        session.debugCardBounds[key] = it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = false)
    }
    val state by rememberTalkPresentation(session)
    var controls by rememberSaveable { mutableStateOf(false) }
    val typing = state.textMode
    val draft = rememberSaveable(state.conversationId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var dragX by rememberSaveable { mutableFloatStateOf(0f) }
    var dragY by rememberSaveable { mutableFloatStateOf(0f) }
    var panelHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val composerFocus = remember { FocusRequester() }
    val motion = com.aliflix.app.ui.common.rememberMobileMotionEnabled() && com.aliflix.app.ui.common.rememberMobileResumed()
    val activity = talkOrbActivity(state, typing && !state.sending)
    val appearance = remember(activity) { talkOrbAppearance(activity) }
    val openingLine = remember(state.conversationId) { state.lines.size }
    val lastUser = state.lines.indexOfLast { it.role == "user" }
    val currentLines = state.lines.drop(maxOf(openingLine,lastUser))
    val historyScroll = rememberLazyListState()
    val textMeasurer = rememberTextMeasurer()
    LaunchedEffect(controls, lastUser) {
        if (controls) historyScroll.scrollToItem(maxOf(openingLine, lastUser).coerceAtMost(state.lines.size))
    }
    fun dismissComposer() { focus.clearFocus(); keyboard?.hide() }
    fun returnToVoice() {
        dismissComposer()
        if(state.textMode || state.muted) { session.setTextMode(false); onStart() }
    }
    val returnToVoiceOnDrag by rememberUpdatedState(::returnToVoice)
    fun send() {
        val text = draft.value.text.trim()
        if (text.isNotEmpty()) {
            session.sendText(text); draft.value = TextFieldValue(); dismissComposer(); controls = true
        }
    }
    BackHandler(enabled = controls || typing) { if (typing) returnToVoice() else controls = false }
    LaunchedEffect(state.sending,lastUser) { if(state.sending && lastUser>=openingLine) controls=true }
    LaunchedEffect(controls, typing) {
        listOf("player-microphone", "player-expand", "player-full-input", "player-full-close", "player-full-send").forEach(session.debugCardBounds::remove)
        if (!controls) listOf("player-palette", "player-type", "player-close", "player-send", "player-exchange").forEach(session.debugCardBounds::remove)
        if (!typing) session.debugCardBounds.remove("player-input")
    }
    val composer = remember(state.conversationId, draft) { movableContentOf<Modifier, Boolean> { modifier, compact ->
        LaunchedEffect(compact) { withFrameNanos {}; composerFocus.requestFocus(); keyboard?.show() }
        Row(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha=.24f)).padding(start=14.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
            BasicTextField(draft.value,{if(it.text.length<=1600)draft.value=it},Modifier.weight(1f).heightIn(min=48.dp)
                .focusRequester(composerFocus).measured("player-input").testTag("talk-player-input").semantics { contentDescription="Message Aliflix" },
                textStyle=TextStyle(color=Color(0xFFF4F0FF),fontSize=15.sp,lineHeight=21.sp),cursorBrush=SolidColor(AliflixAccentSecondary),
                singleLine=compact,minLines=1,maxLines=if(compact)1 else 3,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}),
                decorationBox={inner->Box(Modifier.fillMaxWidth().padding(vertical=12.dp)) { inner() }})
            IconButton(onClick=::send,enabled=draft.value.text.isNotBlank(),modifier=Modifier.size(48.dp).measured("player-send")) {
                Icon(Icons.AutoMirrored.Rounded.Send,"Send",tint=AliflixAccentSecondary,modifier=Modifier.size(21.dp))
            }
        }
    } }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp).testTag("talk-player-bubble")) {
        val orbSize = 76.dp
        val maxX = with(density) { (maxWidth - orbSize).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { (maxHeight - orbSize).toPx().coerceAtLeast(0f) }
        val top = with(density) { (if (maxHeight > 320.dp) 96.dp else 0.dp).toPx() }.coerceAtMost(maxY)
        val orbX = (maxX + dragX).coerceIn(0f, maxX)
        val orbY = (top + dragY).coerceIn(0f, maxY)
        val paletteWidth = minOf(380.dp, maxWidth)
        val gap = with(density) { 8.dp.toPx() }
        val palettePixels = with(density) { paletteWidth.toPx() }
        val orbPixels = with(density) { orbSize.toPx() }
        val roomLeft = orbX >= palettePixels + gap
        val paletteX = if(roomLeft) orbX-palettePixels-gap else (orbX+orbPixels-palettePixels).coerceIn(0f,(maxX+orbPixels-palettePixels).coerceAtLeast(0f))
        val heightPixels = with(density) { maxHeight.toPx() }
        val downStart=if(roomLeft)orbY else orbY+orbPixels+gap
        val upEnd=if(roomLeft)orbY+orbPixels else orbY-gap
        val growDown=heightPixels-downStart>=upEnd
        val panelLimit=with(density) { (if(growDown)heightPixels-downStart else upEnd).coerceAtLeast(100.dp.toPx()).toDp() }
        val estimatedHeight = (panelHeight.takeIf { it > 0 } ?: with(density) { 180.dp.roundToPx() }).coerceAtMost(with(density){panelLimit.roundToPx()})
        val paletteY = (if(growDown)downStart else upEnd-estimatedHeight)
            .coerceIn(0f,(heightPixels-estimatedHeight).coerceAtLeast(0f))
        Box(Modifier.absoluteOffset { IntOffset(paletteX.roundToInt(), paletteY.roundToInt()) }) {
            AnimatedVisibility(controls,
                enter = if(motion) fadeIn(androidx.compose.animation.core.tween(220))+scaleIn(initialScale=.96f) else EnterTransition.None,
                exit = if(motion) fadeOut(androidx.compose.animation.core.tween(150)) else ExitTransition.None) {
                Surface(Modifier.width(paletteWidth).heightIn(max=panelLimit).animateContentSize(androidx.compose.animation.core.tween(if(motion)260 else 0)).onSizeChanged { panelHeight=it.height }.measured("player-palette").testTag("talk-inline-conversation"),
                    shape=RoundedCornerShape(28.dp),color=Color(0xFF151422),shadowElevation=14.dp) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        // In a landscape IME viewport, share the controls/composer row so history retains a touchable reading area.
                        val compactComposer=typing&&LocalConfiguration.current.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.End) {
                            if(compactComposer)composer(Modifier.weight(1f),true)
                            ScenePaletteButton(if(typing)Icons.Rounded.GraphicEq else Icons.Rounded.Keyboard,
                                if(typing)"Voice conversation" else "Type",Modifier.measured("player-type").semantics { selected=typing },active=typing) {
                                if(typing) returnToVoice() else { session.setTextMode(true); onStart() }
                            }
                            ScenePaletteButton(Icons.Rounded.Close,"Close Talk",Modifier.measured("player-close")) { dismissComposer();onClose() }
                        }
                        val bodyStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=16.sp,lineHeight=25.sp)
                        val textWidth=with(density){(paletteWidth-64.dp).roundToPx().coerceAtLeast(1)}
                        val currentHeight=currentLines.filter { it.text.isNotBlank() }.sumOf { line ->
                            textMeasurer.measure(line.text,bodyStyle,constraints=Constraints(maxWidth=textWidth)).size.height + with(density){20.dp.roundToPx()}
                        }
                        val readingHeight=with(density){currentHeight.toDp()}.coerceAtLeast(96.dp)
                            .coerceAtMost((panelLimit-if(typing&&!compactComposer)132.dp else 80.dp).coerceAtLeast(64.dp))
                        if(typing||state.lines.isNotEmpty())LazyColumn(Modifier.fillMaxWidth().height(readingHeight).weight(1f,fill=false)
                            .measured("player-exchange").testTag("talk-inline-exchange"),state=historyScroll) {
                            itemsIndexed(state.lines,key={index,_->"line-$index"}) { _,line ->
                                if(line.text.isNotBlank())androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(line.text,Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=10.dp),
                                        color=if(line.role=="user")Color(0xFFBCB2D6) else Color(0xFFF4F0FF),style=bodyStyle)
                                }
                            }
                            item(key="live-edge") { Spacer(Modifier.height(if(currentLines.isEmpty())readingHeight else
                                (96.dp-with(density){currentHeight.toDp()}).coerceAtLeast(0.dp))) }
                        }
                        if(typing&&talkShowsResponseProgress(state.sending,state.answerComplete))TalkThinking(motion,Modifier.padding(horizontal=10.dp))
                        if(typing&&!compactComposer)composer(Modifier,false)
                    }
                }
            }
        }
        // Clip the interaction layer too: clipping only the halo Canvas leaves
        // Compose's bounded pressed indication as a visible rectangular square.
        Box(Modifier.absoluteOffset { IntOffset(orbX.roundToInt(),orbY.roundToInt()) }.then(orbTransition).size(orbSize).clip(CircleShape).testTag("talk-player-orb-container").measured("player-orb").semantics {
            contentDescription="Talk controls";stateDescription=appearance.label
            customActions=listOf(CustomAccessibilityAction("Move to other side") { dragX=if(orbX>maxX/2)-maxX else 0f;true })
        }.clickable(role=Role.Button,onClickLabel=if(controls)"Collapse conversation" else "Expand conversation") { controls=!controls;if(!controls)returnToVoice() }
            .pointerInput(maxX,maxY,top) { detectDragGestures(onDragStart={controls=false;returnToVoiceOnDrag()}) { change,delta -> change.consume();dragX=(dragX+delta.x).coerceIn(-maxX,0f);dragY=(dragY+delta.y).coerceIn(-top,maxY-top) } }) {
            TalkLiveOrb(state.phase,{session.state.value.level},motion,Modifier.fillMaxSize().testTag("talk-player-orb"),activity=activity,tracker=tracker,
                haloStyle=TalkHaloStyle.LED, haloReady=session.playerVoiceReady() && talkVoiceHaloReady(state),
                eyesClosed=!state.textMode && !session.playerVoiceReady(),
                playerThinking=!typing&&state.phase!="Speaking"&&talkShowsResponseProgress(state.sending,state.answerComplete))
        }
    }
}

@Composable private fun ScenePaletteButton(icon:ImageVector,label:String,modifier:Modifier=Modifier,active:Boolean=false,onClick:()->Unit) {
    IconButton(onClick=onClick,modifier=modifier.size(48.dp).clip(CircleShape).background(if(active)AliflixAccentSecondary.copy(alpha=.19f)else Color.Transparent)) {
        Icon(icon,label,Modifier.size(21.dp),tint=if(active)AliflixAccentSecondary else Color(0xFFD5D0E3))
    }
}
