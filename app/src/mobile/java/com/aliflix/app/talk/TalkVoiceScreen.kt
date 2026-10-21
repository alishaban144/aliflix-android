package com.aliflix.app.talk

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.aliflix.app.ui.discover.DiscoverLiveOrb
import com.aliflix.app.ui.discover.DiscoverTouchTracker
import com.aliflix.app.ui.discover.observeDiscoverTouches
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.theme.*
import kotlin.math.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TalkVoiceScreen(session: TalkVoiceSession, onStart: () -> Unit, onClose: () -> Unit, touchTracker:DiscoverTouchTracker?=null) {
    val state by session.state.collectAsState()
    val chats by session.history.chats.collectAsState()
    val syncStatus by session.history.status.collectAsState()
    var recent by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var draft by rememberSaveable(state.conversationId) { mutableStateOf("") }
    var deleteHistory by remember { mutableStateOf(false) }
    val localTracker=remember { DiscoverTouchTracker() }
    val tracker=touchTracker ?: localTracker
    val motion=rememberMobileMotionEnabled() && rememberMobileResumed()
    val keyboardOpen=WindowInsets.ime.getBottom(LocalDensity.current)>0
    BackHandler { if(recent)recent=false else if(voices)voices=false else if(privacy)privacy=false else onClose() }
    LaunchedEffect(Unit) { onStart() }
    val idle=state.phase in listOf("Ready","Resume","Reconnect","Microphone permission","Audio unavailable","Try again later","Opened elsewhere")
    fun send(){if(draft.isNotBlank()){session.sendText(draft);draft=""}}
    Box(Modifier.fillMaxSize().background(AliflixBackgroundBase).observeDiscoverTouches(tracker).testTag("talk-screen")) {
        TalkPixelField("",state.phase,{state.level},motion&&!state.textMode,Modifier.fillMaxSize().graphicsLayer { alpha=if(state.textMode).15f else .52f })
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding().padding(horizontal=20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ALIFLIX",color=AliflixAccentSecondary,fontSize=9.sp,letterSpacing=3.sp,fontWeight=FontWeight.Bold)
                    Text("Talk",color=AliflixContentPrimary,fontSize=25.sp,fontWeight=FontWeight.SemiBold)
                }
                AliflixIconButton(onClick={session.setTextMode(!state.textMode);onStart()},modifier=Modifier.testTag("talk-chat-mode").semantics { stateDescription=if(state.textMode)"Text chat selected" else "Voice selected" }) {
                    Icon(if(state.textMode)Icons.Rounded.GraphicEq else Icons.Rounded.EditNote,if(state.textMode)"Voice conversation" else "Chat",tint=AliflixAccentSecondary)
                }
                AliflixIconButton(onClick={recent=true;session.history.refresh()},modifier=Modifier.testTag("talk-recent-chats")) { Icon(Icons.Rounded.History,"Recent chats",tint=AliflixContentSecondary) }
                Box {
                    AliflixIconButton(onClick={settings=true}) { Icon(Icons.Rounded.MoreHoriz,"Conversation settings",tint=AliflixContentSecondary) }
                    DropdownMenu(expanded=settings,onDismissRequest={settings=false}) {
                        DropdownMenuItem(text={Text("New chat")},onClick={session.newChat();draft="";settings=false},leadingIcon={Icon(Icons.Rounded.AddComment,null)})
                        DropdownMenuItem(text={Text("Choose voice")},onClick={voices=true;settings=false},leadingIcon={Icon(Icons.Rounded.GraphicEq,null)},modifier=Modifier.testTag("talk-voice-picker"))
                        DropdownMenuItem(text={Text("Privacy")},onClick={privacy=true;settings=false},leadingIcon={Icon(Icons.Rounded.PrivacyTip,null)})
                    }
                }
                AliflixIconButton(onClick=onClose) { Icon(Icons.Rounded.Close,"Close conversation",tint=AliflixContentPrimary) }
            }
            if(state.textMode) {
                Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your cinema companion",color=AliflixContentPrimary,fontSize=13.sp,fontWeight=FontWeight.Medium)
                        Text(talkConnectionLabel(state),color=AliflixContentTertiary,fontSize=11.sp,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                    }
                    AliflixIconButton(onClick={session.newChat();draft=""}) { Icon(Icons.Rounded.AddComment,"New chat",tint=AliflixAccentSecondary) }
                }
                val list=rememberLazyListState()
                var following by remember { mutableStateOf(true) }
                LaunchedEffect(list) { snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling,more) -> if(scrolling)following=!more } }
                LaunchedEffect(state.lines.size,state.lines.lastOrNull()?.text,state.sending,following) {
                    if(state.lines.isNotEmpty()&&following&&!list.isScrollInProgress)list.scrollToItem(state.lines.size-1+(if(state.sending)1 else 0))
                }
                if(state.lines.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(!keyboardOpen)TalkLiveOrb(state.phase,{state.level},motion,Modifier.size(116.dp),tracker)
                        Text("What are you in the mood for?",color=AliflixContentPrimary,fontSize=19.sp,fontWeight=FontWeight.Medium)
                        if(!keyboardOpen)Text("Discover a new release, follow a mood, or talk through a story.",color=AliflixContentTertiary,fontSize=13.sp,lineHeight=20.sp,modifier=Modifier.padding(horizontal=20.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        if(!keyboardOpen)listOf("Recent sci-fi","Find a series","Something unexpected").forEach { prompt ->
                            SuggestionChip(onClick={session.sendText(prompt)},label={Text(prompt)},shape=RoundedCornerShape(16.dp))
                        }
                    }
                } else Box(Modifier.weight(1f).fillMaxWidth()) {
                  LazyColumn(Modifier.fillMaxSize().testTag("talk-messages"),state=list,verticalArrangement=Arrangement.spacedBy(18.dp),contentPadding=PaddingValues(top=16.dp,bottom=24.dp)) {
                    items(state.lines.size,key={it}) { index ->
                        val line=state.lines[index]
                        Column(Modifier.fillMaxWidth(),horizontalAlignment=if(line.role=="user")Alignment.End else Alignment.Start) {
                            Surface(color=if(line.role=="user")AliflixAccentSecondary.copy(alpha=.14f) else Color.Transparent,
                                shape=RoundedCornerShape(22.dp,22.dp,if(line.role=="user")6.dp else 22.dp,if(line.role=="user")22.dp else 6.dp),
                                modifier=Modifier.widthIn(max=520.dp)) {
                                SelectionContainer { Text(line.text,Modifier.padding(horizontal=if(line.role=="user")16.dp else 2.dp,vertical=12.dp),color=AliflixContentPrimary,fontSize=16.sp,lineHeight=25.sp) }
                            }
                            line.sources.distinctBy { it.url }.take(4).forEach { SourceLink(it) }
                            if(line.role=="assistant"&&(!state.sending||index<state.lines.lastIndex)) {
                                val clipboard=LocalClipboard.current
                                val actionScope=rememberCoroutineScope()
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    IconButton(onClick={actionScope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("Aliflix reply",line.text))) }}) { Icon(Icons.Rounded.ContentCopy,"Copy reply",tint=AliflixContentTertiary,modifier=Modifier.size(16.dp)) }
                                    if(index==state.lines.lastIndex)TextButton(onClick=session::retryLastMessage) { Icon(Icons.Rounded.Refresh,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("Try again",fontSize=11.sp) }
                                }
                            }
                        }
                    }
                    if(state.sending)item(key="response-status") { Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }.testTag("talk-response-status")) {
                        Icon(Icons.Rounded.AutoAwesome,null,tint=AliflixAccentSecondary,modifier=Modifier.size(16.dp));Spacer(Modifier.width(10.dp))
                        Text(if(state.lines.lastOrNull()?.role=="assistant")"Writing…" else "Thinking through your request…",color=AliflixContentTertiary,fontSize=12.sp)
                    } }
                  }
                  if(!following&&list.canScrollForward)FilledTonalIconButton(onClick={following=true},modifier=Modifier.align(Alignment.BottomCenter)) { Icon(Icons.Rounded.ArrowDownward,"Latest message") }
                }
                if(state.phase in listOf("Reconnect","Try again later","Audio unavailable")) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(state.phase=="Try again later")state.phase else "Connection unavailable",Modifier.weight(1f),color=AliflixContentSecondary,fontSize=12.sp)
                    TextButton(onClick=session::retryLastMessage) { Text("Retry") }
                }
                Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=8.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value=draft,onValueChange={draft=it.take(1600)},modifier=Modifier.weight(1f).testTag("talk-composer"),
                        placeholder={Text("Message Aliflix",fontSize=14.sp)},maxLines=5,shape=RoundedCornerShape(24.dp),
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}),
                        colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=AliflixAccentSecondary,unfocusedBorderColor=AliflixContentTertiary.copy(alpha=.3f),focusedTextColor=AliflixContentPrimary,unfocusedTextColor=AliflixContentPrimary))
                    val stop=state.sending&&draft.isBlank()
                    IconButton(onClick={if(stop)session.stopAnswer()else send()},enabled=stop||draft.isNotBlank(),modifier=Modifier.size(48.dp).clip(CircleShape).background(AliflixAccentSecondary.copy(alpha=if(!stop&&draft.isBlank()).12f else .85f)).testTag("talk-send")) {
                        Icon(if(stop)Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,if(stop)"Stop response" else "Send message",tint=if(!stop&&draft.isBlank())AliflixContentTertiary else AliflixBackgroundBase)
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(.56f),contentAlignment=Alignment.Center) { TalkLiveOrb(state.phase,{state.level},motion,Modifier.fillMaxWidth(.86f).widthIn(max=320.dp).aspectRatio(1f),tracker) }
                Box(Modifier.fillMaxWidth().weight(.32f),contentAlignment=Alignment.Center) { TalkDottedTopic(state.words,{state.level},motion) }
                if(idle&&state.phase!="Ready") Text(state.phase,color=AliflixContentSecondary,fontSize=12.sp,modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=12.dp))
                Row(Modifier.fillMaxWidth().padding(bottom=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp,Alignment.CenterHorizontally)) {
                    AliflixIconButton(onClick=session::stopAnswer,modifier=Modifier.size(52.dp)) { Icon(Icons.Rounded.Stop,"Stop response",tint=AliflixContentSecondary) }
                    AliflixIconButton(onClick={if(idle)onStart()else session.toggleMute()},modifier=Modifier.size(64.dp)) {
                        AnimatedContent(if(idle)0 else if(state.muted)1 else 2,label="voice-control") { mode -> Icon(if(mode==0)Icons.Rounded.Refresh else if(mode==1)Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            if(idle)"Retry connection" else if(state.muted)"Unmute microphone" else "Mute microphone",tint=AliflixAccentSecondary,modifier=Modifier.size(29.dp)) }
                    }
                    AliflixIconButton(onClick=onClose,modifier=Modifier.size(52.dp)) { Icon(Icons.Rounded.CallEnd,"End conversation",tint=AliflixContentSecondary) }
                }
            }
        }
        if(recent||privacy||voices) ModalBottomSheet(onDismissRequest={recent=false;privacy=false;voices=false},containerColor=AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated)) {
            Column(Modifier.fillMaxWidth().padding(horizontal=22.dp).navigationBarsPadding().padding(bottom=20.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(recent)"Recent chats" else if(voices)"Voice" else "Privacy",Modifier.weight(1f),color=AliflixContentPrimary,fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                    AliflixIconButton(onClick={recent=false;privacy=false;voices=false}) { Icon(Icons.Rounded.Close,"Close panel",tint=AliflixContentSecondary) }
                    if(recent)AliflixIconButton(onClick={session.newChat();session.setTextMode(true);recent=false}) { Icon(Icons.Rounded.AddComment,"New chat",tint=AliflixAccentSecondary) }
                }
                if(recent) {
                    if(syncStatus.isNotBlank())TextButton(onClick={session.history.refresh()}) { Text(syncStatus,color=AliflixContentTertiary,fontSize=11.sp) }
                    if(!state.keepHistory)Text("New messages won't be saved",color=AliflixContentSecondary,modifier=Modifier.padding(vertical=20.dp))
                    else if(chats.isEmpty())Text("No recent chats",color=AliflixContentSecondary,modifier=Modifier.padding(vertical=24.dp))
                    LazyColumn(Modifier.heightIn(max=440.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        items(chats,key={it.id}) { chat ->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                AliflixSurface(onClick={session.openChat(chat.id);recent=false},modifier=Modifier.weight(1f),shape=RoundedCornerShape(18.dp)) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(chat.title,color=AliflixContentPrimary,fontSize=15.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        Text(android.text.format.DateUtils.getRelativeTimeSpanString(chat.updatedAt,System.currentTimeMillis(),60000).toString(),color=AliflixContentTertiary,fontSize=11.sp,modifier=Modifier.padding(top=5.dp))
                                    }
                                }
                                AliflixIconButton(onClick={session.history.delete(chat.id)}) { Icon(Icons.Rounded.DeleteOutline,"Delete chat",tint=AliflixContentTertiary) }
                            }
                        }
                    }
                } else if(voices) VOICES.forEach { voice ->
                    TextButton(onClick={session.selectVoice(voice);voices=false},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text(voice,Modifier.weight(1f),color=AliflixContentPrimary);if(state.voice==voice)Icon(Icons.Rounded.Check,"Selected",tint=AliflixAccentSecondary) }
                } else {
                    Row(Modifier.fillMaxWidth().heightIn(min=60.dp),verticalAlignment=Alignment.CenterVertically) { Text("Keep history",Modifier.weight(1f),color=AliflixContentPrimary);AliflixLiquidToggle(state.keepHistory,session::setHistory) }
                    Text("Chats sync with your account when history is on. Recent chats are kept for 90 days; long conversations retain recent messages. Voice capture stops when you leave Talk.",color=AliflixContentTertiary,fontSize=12.sp,lineHeight=19.sp)
                    TextButton(onClick={deleteHistory=true},modifier=Modifier.heightIn(min=48.dp)) { Icon(Icons.Rounded.DeleteOutline,null);Spacer(Modifier.width(8.dp));Text("Delete history") }
                }
            }
        }
        if(deleteHistory)AlertDialog(onDismissRequest={deleteHistory=false},title={Text("Delete chat history?")},text={Text("This removes saved conversations from this account and its other devices.")},
            confirmButton={TextButton(onClick={session.clearHistory();deleteHistory=false}){Text("Delete")}},dismissButton={TextButton(onClick={deleteHistory=false}){Text("Keep chats")}})
    }
}
internal fun talkConnectionLabel(state:TalkVoiceState):String = when(state.phase) {
    "Connecting","Reconnecting" -> "Connecting securely…"
    "Opening chat" -> "Opening saved conversation…"
    "Reconnect","Try again later","Audio unavailable" -> "Saved locally · Connection needs a retry"
    "Resume" -> "Paused · Send a message to continue"
    else -> if(state.keepHistory)"History on · Synced when signed in" else "History off · This conversation only"
}
@Composable private fun SourceLink(source: TalkReference) {
    val context = LocalContext.current
    TextButton(onClick = {
        val uri = Uri.parse(source.url)
        if (uri.scheme == "https" && uri.userInfo==null && uri.port==-1 && uri.host?.let{it.contains('.')&&!it.matches(Regex("[0-9.]+"))&&!it.endsWith(".local")}==true)
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
        Column { Text(source.title, color = AliflixAccentSecondary, fontSize = 11.sp)
            Text(source.license, color = AliflixContentTertiary, fontSize = 10.sp) }
    }
}

/** Native adaptation of Edu Calvo's Pixel Flow Field: sample once per word,
 * scatter into a coarse flow lattice, decay touch wakes, settle into readable
 * type. No bitmap reads, per-cell composables or object allocation per frame. */
@Composable
internal fun TalkPixelField(words: String, phase: String, level: () -> Float, motion: Boolean, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val field = remember(size, words) { if (size.width > 0 && size.height > 0) TalkParticleField(size.width, size.height, words) else null }
    val frame = remember { mutableLongStateOf(0) }
    val started = remember(field) { mutableLongStateOf(0) }
    var pointer by remember { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(field, motion) {
        if (motion && field != null) {
            var entering = true
            while (entering) withFrameNanos { now ->
                if (started.longValue == 0L) started.longValue = now
                frame.longValue = now
                entering = now - started.longValue < 1_200_000_000L
            }
            while (true) withInfiniteAnimationFrameNanos { now -> frame.longValue = now }
        }
    }
    Canvas(modifier.onSizeChanged { size = it }.semantics { contentDescription = words }
        .then(if (motion) Modifier.pointerInput(field) { detectDragGestures(onDragEnd = { pointer = Offset.Unspecified }, onDragCancel = { pointer = Offset.Unspecified }) { change, _ -> pointer = change.position } } else Modifier)) {
        val f = field ?: return@Canvas
        val elapsed = if (!motion) 100f else if (started.longValue == 0L) 0f
            else ((frame.longValue - started.longValue).coerceAtLeast(0) / 1_000_000_000f)
        f.draw(this, elapsed, level(), phase, pointer, motion)
    }
}

private class TalkParticleField(private val width: Int, private val height: Int, text: String) {
    private val step = max(11f, sqrt(width.toFloat() * height / 8192f))
    private val cols = (width / step).roundToInt().coerceIn(44, 96)
    private val rows = (height.toFloat() / width * cols).roundToInt().coerceAtMost(8192 / cols)
    private val count = cols * rows
    private val dx = width.toFloat() / cols; private val dy = height.toFloat() / rows
    private val cover = FloatArray(count)
    private val wakeX = FloatArray(count); private val wakeY = FloatArray(count)
    private val paths = Array(7) { Path() }
    private val nativePaths = Array(7) { paths[it].asAndroidPath() }
    private val waves = FloatArray(cols)
    private val rowWave = FloatArray(rows) { (1 - abs((it + .5f) * dy / height - .47f)).coerceAtLeast(0f) }
    private val colors = Array(7) { i -> androidx.compose.ui.graphics.lerp(Color(0xFF29213F), Color(0xFFE5D9FF), i / 6f) }
    private val flowCols = (cols + 3) / 4 + 1; private val flowRows = (rows + 3) / 4 + 1
    private val flowX = FloatArray(flowCols * flowRows); private val flowY = FloatArray(flowCols * flowRows)
    init {
        val bitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
        val phrase = text.uppercase(Locale.ROOT).trim().take(32)
        val split = phrase.lastIndexOf(' ', phrase.length / 2 + 3)
        val lines = if (phrase.length > 13 && split > 0) listOf(phrase.substring(0, split), phrase.substring(split + 1)) else listOf(phrase)
        paint.textSize = cols * .14f
        val widest = lines.maxOf { paint.measureText(it) }.coerceAtLeast(1f)
        if (widest > cols * .86f) paint.textSize *= cols * .86f / widest
        val lineHeight = paint.textSize * 1.35f
        lines.forEachIndexed { i, line -> canvas.drawText(line, cols / 2f, rows * .47f + (i - (lines.size - 1) / 2f) * lineHeight, paint) }
        val pixels = IntArray(count); bitmap.getPixels(pixels, 0, cols, 0, 0, cols, rows)
        for (i in pixels.indices) cover[i] = android.graphics.Color.alpha(pixels[i]) / 255f
        bitmap.recycle()
    }
    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, time: Float, level: Float, phase: String, pointer: Offset, motion: Boolean) = with(scope) {
        paths.forEach { it.reset() }
        val talking = phase == "Speaking"; val thinking = phase == "Thinking"
        val speed = if (thinking) .8f else .3f
        for (r in 0 until flowRows) for (c in 0 until flowCols) {
            val i = r * flowCols + c
            val angle = sin(c * .37f + time * speed) * 1.7f + cos(r * .29f - time * speed) * 1.5f
            flowX[i] = cos(angle) * (if (thinking) 13 else 7); flowY[i] = sin(angle) * (if (talking) 10 else 6)
        }
        for (c in 0 until cols) waves[c] = level * (if (talking) 12 else 6) * sin(c * .23f + time * 6)
        val reveal = if (motion) (1 - exp(-time * 2.5f)).coerceIn(0f, 1f) else 1f
        val spread = 1f - reveal
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = r * cols + c; val coverage = cover[i]
            val homeX = (c + .5f) * dx; val homeY = (r + .5f) * dy
            val cell = (r / 4) * flowCols + c / 4
            if (motion && pointer.isSpecified) {
                val px = homeX - pointer.x; val py = homeY - pointer.y; val distance = px * px + py * py
                if (distance < 14_400f) { val strength = (1 - distance / 14_400f) * .08f; wakeX[i] += px * strength; wakeY[i] += py * strength }
            }
            wakeX[i] *= .92f; wakeY[i] *= .92f
            val calm = 1 - coverage * .94f
            val x = homeX + if (motion) flowX[cell] * calm + wakeX[i] + ((i * 73 % 199) - 99) * spread * 4 else 0f
            val wave = waves[c] * rowWave[r]
            val y = homeY + if (motion) flowY[cell] * calm + wakeY[i] + ((i * 37 % 151) - 75) * spread * 5 + wave else 0f
            if (x < 0 || y < 0 || x > width || y > height) continue
            val tier = if (coverage > .08f) (coverage * 5 + 1).toInt().coerceIn(1, 6) else 0
            val radius = min(dx, dy) * (if (tier == 0) .10f + level * .06f else .28f + coverage * .11f) * reveal
            nativePaths[tier].addRect(x - radius, y - radius, x + radius, y + radius, android.graphics.Path.Direction.CW)
        }
        for (i in paths.indices) drawPath(paths[i], colors[i])
    }
}

/** Native adaptation of Unlumen Glow's five-second, linear conic rotation.
 * A feathered radial alpha replaces CSS blur: no rings, clipped layers or resets
 * between listening/speaking. The uninterrupted clock and PCM envelope are separate. */
@Composable internal fun TalkLiveOrb(phase:String,level:()->Float,motion:Boolean,modifier:Modifier=Modifier,tracker:DiscoverTouchTracker=remember{DiscoverTouchTracker()}) {
    val seconds=rememberMobileAnimationClock(motion)
    val currentLevel by rememberUpdatedState(level)
    var energy by remember{mutableFloatStateOf(0f)}
    LaunchedEffect(motion){
        if(!motion){energy=0f;return@LaunchedEffect}
        var previous=0L
        while(true)withInfiniteAnimationFrameNanos{now->
            if(previous!=0L){val target=currentLevel().coerceIn(0f,1f);val dt=((now-previous)/1_000_000_000f).coerceIn(0f,.05f)
                // Prompt onset, long soft release: fast syllables remain visible
                // while the light never collapses between phonemes.
                val tau=if(target>energy).045f else .27f
                energy+=(target-energy)*(1f-exp(-dt/tau))
            };previous=now
        }
    }
    val ready by animateFloatAsState(if(phase in listOf("Listening","Speaking","Thinking","Muted"))1f else .12f,tween(if(motion)650 else 0),label="listening-light")
    var bounds by remember {mutableStateOf(IntSize.Zero)}
    val lights=remember(bounds){
        if(bounds.width<=0||bounds.height<=0)null else{
            val cx=bounds.width/2f;val cy=bounds.height/2f;val extent=min(bounds.width,bounds.height)*.475f
            val sweep=android.graphics.SweepGradient(cx,cy,intArrayOf(
                Color(0xFFA58BE4).toArgb(),Color(0xFFF1DEFF).toArgb(),Color(0xFF8E80CB).toArgb(),
                Color(0xFFACCCDD).toArgb(),Color(0xFFA58BE4).toArgb()),floatArrayOf(0f,.19f,.48f,.78f,1f))
            fun light(stops:FloatArray,colors:IntArray):ShaderBrush{
                val mask=android.graphics.RadialGradient(cx,cy,extent,colors,stops,android.graphics.Shader.TileMode.CLAMP)
                return ShaderBrush(android.graphics.ComposeShader(sweep,mask,android.graphics.PorterDuff.Mode.DST_IN))
            }
            val aura=light(floatArrayOf(0f,.52f,.64f,.77f,.9f,1f),intArrayOf(-1,-1,0xc0ffffff.toInt(),0x68ffffff,0x16ffffff,0))
            val rim=light(floatArrayOf(0f,.58f,.64f,.69f,.76f,.88f,1f),intArrayOf(0,0,0xcaffffff.toInt(),0x88ffffff.toInt(),0x30ffffff,0x08ffffff,0))
            aura to rim
        }
    }
    Box(modifier.onSizeChanged{bounds=it}.semantics {contentDescription="Aliflix voice: $phase"},contentAlignment=Alignment.Center){
        Canvas(Modifier.fillMaxSize()){
            val extent=size.minDimension*.475f;val breath=1f+sin(seconds.value*.82f)*.006f+energy*.02f
            lights?.let{(aura,rim)->
                rotate(seconds.value*72f){scale(breath){drawCircle(aura,extent,alpha=ready*(.53f+energy*.37f))}}
                // A second feathered light moves independently along the inner
                // corona, rather than drawing a hard ring or restarting a pulse.
                rotate(-seconds.value*31f+38f){scale(1f+energy*.012f){drawCircle(rim,extent,alpha=ready*(.17f+energy*.36f))}}
            }
        }
        DiscoverLiveOrb(tracker,Modifier.fillMaxSize(.66f).graphicsLayer{
            scaleX=1f+energy*.012f;scaleY=scaleX
            translationY=if(motion)sin(seconds.value*.95f)*.65.dp.toPx() else 0f
        },conversing=phase=="Speaking",voiceCompanion=true,voiceEnergy={energy})
    }
}
