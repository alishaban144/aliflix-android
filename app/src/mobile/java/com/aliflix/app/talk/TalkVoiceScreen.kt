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
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
internal fun TalkVoiceScreen(session: TalkVoiceSession, onStart: () -> Unit, onClose: () -> Unit) {
    val state by session.state.collectAsState()
    var transcript by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    val motion = rememberMobileMotionEnabled() && rememberMobileResumed()
    BackHandler { if (transcript) transcript = false else if (privacy) privacy = false else onClose() }
    val idle = state.phase in listOf("Ready", "Resume", "Reconnect", "Microphone permission", "Audio unavailable", "Try again later", "Opened elsewhere")
    Box(Modifier.fillMaxSize().background(AliflixBackgroundBase).testTag("talk-screen")) {
        TalkPixelField("", state.phase, { state.level }, motion, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ALIFLIX", color = AliflixAccentSecondary, fontSize = 10.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                    Text("Talk", color = AliflixContentPrimary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
                AliflixIconButton(onClick = { privacy = !privacy }) { Icon(Icons.Rounded.PrivacyTip, "Privacy", tint = AliflixContentSecondary) }
                AliflixIconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close conversation", tint = AliflixContentPrimary) }
            }
            Box(Modifier.fillMaxWidth().weight(.56f),contentAlignment=Alignment.Center) {
                TalkLiveOrb(state.phase,{state.level},motion,Modifier.fillMaxWidth(.86f).widthIn(max=320.dp).aspectRatio(1f))
            }
            Box(Modifier.fillMaxWidth().weight(.32f),contentAlignment=Alignment.Center) {
                AnimatedContent(state.words,transitionSpec={
                    if(motion)(fadeIn(tween(260))+slideInVertically{it/5}).togetherWith(fadeOut(tween(140)))
                    else EnterTransition.None.togetherWith(ExitTransition.None)
                },label="clear-conversation-topic") { words->
                    Text(words, color=AliflixContentPrimary,fontSize=if(words.length>24)24.sp else 30.sp,
                        fontWeight=FontWeight.Medium,letterSpacing=1.sp,lineHeight=38.sp,maxLines=3,
                        textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp).testTag("talk-topic"))
                }
            }
            if(state.phase in listOf("Reconnect","Try again later","Audio unavailable","Microphone permission"))
                Text(state.phase,color=AliflixContentSecondary,fontSize=12.sp,modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=12.dp))
            Row(Modifier.fillMaxWidth().padding(bottom=20.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(20.dp,Alignment.CenterHorizontally)) {
                AliflixIconButton(onClick={transcript=!transcript},modifier=Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Subtitles,"Transcript",tint=if(transcript)AliflixAccentSecondary else AliflixContentSecondary,modifier=Modifier.size(23.dp))
                }
                AliflixSurface(onClick={if(idle)onStart() else session.toggleMute()},
                    modifier=Modifier.heightIn(min=60.dp).widthIn(min=if(idle)144.dp else 76.dp).then(if(motion)Modifier.animateContentSize()else Modifier),shape=CircleShape,level=AliflixSurfaceLevel.Content) {
                    Row(Modifier.background(Brush.horizontalGradient(listOf(AliflixAccentPrimary.copy(alpha=.12f),Color.Transparent)))
                        .padding(horizontal=24.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        AnimatedContent(if(idle)0 else if(state.muted)1 else 2,label="voice-control") { mode->
                            Icon(if(mode==1)Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                if(idle)null else if(state.muted)"Unmute microphone" else "Mute microphone",tint=AliflixAccentSecondary,modifier=Modifier.size(24.dp))
                        }
                        if(idle)Text(if(state.phase=="Ready")"Start" else "Resume",color=AliflixContentPrimary,fontWeight=FontWeight.SemiBold)
                    }
                }
                AliflixIconButton(onClick=session::suspendSession,modifier=Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Pause,"Pause conversation",tint=AliflixContentSecondary,modifier=Modifier.size(24.dp))
                }
            }
        }
        AnimatedVisibility(transcript || privacy, enter = if (motion) fadeIn() + slideInVertically { it / 4 } else EnterTransition.None,
            exit = if (motion) fadeOut() else ExitTransition.None, modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(Modifier.fillMaxWidth().fillMaxHeight(.65f).windowInsetsPadding(WindowInsets.navigationBars),
                color = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated), shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (privacy) "Privacy" else "Transcript", Modifier.weight(1f), color = AliflixContentPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        AliflixIconButton(onClick = { transcript = false; privacy = false }) { Icon(Icons.Rounded.Close, "Close panel") }
                    }
                    if (privacy) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep history", Modifier.weight(1f), color = AliflixContentPrimary)
                            AliflixLiquidToggle(state.keepHistory, session::setHistory)
                        }
                        TextButton(onClick = session::clearHistory, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("Delete history") }
                    } else {
                        val scroll = rememberScrollState()
                        LaunchedEffect(state.lines.size) { scroll.animateScrollTo(scroll.maxValue) }
                        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            state.lines.forEach { line ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (line.role == "user") "YOU" else "ALIFLIX", fontSize = 10.sp, letterSpacing = 2.sp, color = AliflixAccentSecondary)
                                    Text(line.text, color = AliflixContentPrimary, fontSize = 15.sp, lineHeight = 22.sp)
                                    line.sources.forEach { source -> SourceLink(source) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun SourceLink(source: TalkReference) {
    val context = LocalContext.current
    TextButton(onClick = {
        val uri = Uri.parse(source.url)
        if (uri.scheme == "https" && uri.host in setOf("www.tvmaze.com", "en.wikipedia.org", "www.wikidata.org"))
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

/** Live Orb's spherical projection with a rotating, audio-reactive glow. */
@Composable private fun TalkLiveOrb(phase:String,level:()->Float,motion:Boolean,modifier:Modifier=Modifier) {
    val tracker=remember{DiscoverTouchTracker()}
    val seconds=rememberMobileAnimationClock(motion)
    Box(modifier.observeDiscoverTouches(tracker).semantics {contentDescription="Aliflix voice: $phase"},contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val energy=level().coerceIn(0f,1f)
            val active=phase in listOf("Speaking","Listening","Thinking","Connecting","Reconnecting")
            val radius=size.minDimension*(.32f+energy*.035f)
            val theta=seconds.value*(if(phase=="Thinking")1.1f else .5f)
            val offset=Offset(center.x+cos(theta)*radius*.28f,center.y+sin(theta)*radius*.28f)
            for(i in 5 downTo 0) {
                val alpha=(if(active).055f else .025f)*(1f+energy)
                drawCircle(Brush.radialGradient(listOf(AliflixAccentPrimary.copy(alpha=alpha),AliflixAccentSecondary.copy(alpha=alpha*.4f),Color.Transparent),offset,radius*(1.3f+i*.07f)),radius*(1.3f+i*.07f),offset)
            }
        }
        DiscoverLiveOrb(tracker,Modifier.fillMaxSize(.69f).graphicsLayer {scaleX=1f+level()*.035f;scaleY=scaleX})
    }
}
