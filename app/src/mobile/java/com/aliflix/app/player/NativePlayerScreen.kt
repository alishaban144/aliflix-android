package com.aliflix.app.player

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.aliflix.app.model.Episode
import com.aliflix.app.ui.launch.AnimatedAliflixHeatmapLogo
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentPrimaryContainer
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

internal data class NativePlayerUi(
    val title: String = "Aliflix",
    val detail: String = "",
    val artwork: String? = null,
    val stage: String? = null,
    val error: String? = null,
    val server: String = "Auto",
    val ready: Boolean = false,
    val external: Boolean = false,
    val revision: Int = 0,
    val episodes: List<Episode> = emptyList(),
    val episodeNumber: Int? = null,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val activeSubtitleTrack: SubtitleTrack? = null,
    val subtitleLoading: Boolean = false,
    val subtitleError: String? = null,
    val message: String? = null,
    val segments: List<IntroSegment> = emptyList(),
)

private val PlayerInk = Color(0xFF07080C)
private val AliflixSurface = Color(0xFF10131A)
private val AliflixSurfaceElevated = Color(0xFF181C26)

private data class HudFeedback(
    val icon: ImageVector,
    val text: String,
    val progress: Float? = null,
)

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativePlayerScreen(
    state: NativePlayerUi,
    player: Player?,
    settings: PlayerSettings = PlayerSettings(),
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onServer: () -> Unit = {},
    onStop: () -> Unit = {},
    onStopCast: () -> Unit = {},
    onWireless: () -> Unit = {},
    onFit: (Boolean) -> Unit = {},
    onRotate: () -> Unit = {},
    onSubtitleSearch: () -> Unit = {},
    onSubtitle: (SubtitleTrack) -> Unit = {},
    onSubtitleDisable: () -> Unit = {},
    onSubtitleDelayChange: (Int) -> Unit = {},
    onSubtitleFontSizeChange: (Float) -> Unit = {},
    onSubtitleOpacityChange: (Float) -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    onEpisode: (Episode) -> Unit = {},
    onReceiver: () -> Unit = {},
    onBrightnessSwipe: (Float) -> Float = { 0.5f },
    onVolumeSwipe: (Float) -> Float = { 0.5f },
    onControlsVisibilityChanged: (Boolean) -> Unit = {},
) {
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var fill by rememberSaveable { mutableStateOf(settings.resizeModeZoom) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var seek by remember { mutableStateOf<Float?>(null) }
    var hudFeedback by remember { mutableStateOf<HudFeedback?>(null) }
    var hudTimerJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(controls) { onControlsVisibilityChanged(controls) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it) } }
    val preparing = state.stage != null || (!state.ready && state.error == null && player?.mediaItemCount != 0)
    val playing = player?.isPlaying == true
    val ended = player?.playbackState == Player.STATE_ENDED
    val duration = (player?.duration ?: 0).coerceAtLeast(0)
    val position = (player?.currentPosition ?: 0).coerceIn(0, duration.coerceAtLeast(1))
    val next = state.episodes.dropWhile { it.number != state.episodeNumber }.drop(1).firstOrNull()

    // Next episode countdown
    var countdownCancelled by remember(state.episodeNumber) { mutableStateOf(false) }
    val remainingMs = duration - position
    val isNearEnd = duration > 45_000 && remainingMs in 1..25_000
    val showCountdown = next != null && isNearEnd && !countdownCancelled && !ended && !preparing && state.error == null

    LaunchedEffect(showCountdown, remainingMs) {
        if (showCountdown && remainingMs <= 1200 && next != null) {
            onEpisode(next)
        }
    }

    fun showHud(icon: ImageVector, text: String, progress: Float? = null) {
        hudFeedback = HudFeedback(icon, text, progress)
        hudTimerJob?.cancel()
        hudTimerJob = coroutineScope.launch {
            delay(1200)
            hudFeedback = null
        }
    }

    LaunchedEffect(playing, controls, interaction, sheet) {
        if (playing && controls && sheet == null) {
            delay(4500)
            controls = false
        }
    }
    LaunchedEffect(state.error, ended) { if (state.error != null || ended) controls = true }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(preparing, state.error) {
                if (preparing || state.error != null) return@pointerInput
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange > 1.15f && !fill) {
                        fill = true
                        onFit(true)
                        showHud(Icons.Default.AspectRatio, "Fill Screen")
                    } else if (zoomChange < 0.85f && fill) {
                        fill = false
                        onFit(false)
                        showHud(Icons.Default.AspectRatio, "Fit to Screen")
                    }
                }
            }
            .pointerInput(preparing, state.error) {
                if (preparing || state.error != null) return@pointerInput
                detectTapGestures(
                    onTap = {
                        controls = !controls
                        interaction++
                    },
                    onDoubleTap = { offset ->
                        if (player?.isCurrentMediaItemSeekable == true) {
                            val delta = if (offset.x < size.width / 2) -10_000 else 10_000
                            val target = (player.currentPosition + delta).coerceIn(0, player.duration.coerceAtLeast(0))
                            player.seekTo(target)
                            interaction++
                            if (delta < 0) showHud(Icons.Default.Replay10, "−10s")
                            else showHud(Icons.Default.Forward10, "+10s")
                        }
                    }
                )
            }
            .pointerInput(preparing, state.error) {
                if (preparing || state.error != null) return@pointerInput
                var dragStartedInValidZone = false
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        dragStartedInValidZone = offset.y in (size.height * 0.20f)..(size.height * 0.80f)
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (!dragStartedInValidZone) return@detectVerticalDragGestures
                        change.consume()
                        val isLeft = change.position.x < size.width / 2
                        val delta = -dragAmount / (size.height * 0.7f)
                        if (isLeft) {
                            val level = onBrightnessSwipe(delta)
                            val icon = if (level > 0.65f) Icons.Default.BrightnessHigh else if (level > 0.35f) Icons.Default.BrightnessMedium else Icons.Default.BrightnessLow
                            showHud(icon, "Brightness ${(level * 100).toInt()}%", level)
                        } else {
                            val level = onVolumeSwipe(delta)
                            @Suppress("DEPRECATION")
                            val icon = if (level <= 0.01f) Icons.Default.VolumeMute else if (level < 0.5f) Icons.Default.VolumeDown else Icons.Default.VolumeUp
                            showHud(icon, "Volume ${(level * 100).toInt()}%", level)
                        }
                    }
                )
            }
    ) {
        // Artwork Background
        if (preparing || state.error != null || state.external || ended) {
            Box(Modifier.fillMaxSize().background(PlayerInk)) {
                AsyncImage(state.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.28f)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PlayerInk.copy(alpha = 0.4f), PlayerInk.copy(alpha = 0.96f)))))
            }
        }

        // Preparing / Loading Overlay
        if (preparing) {
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 520.dp).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AnimatedAliflixHeatmapLogo(Modifier.size(56.dp))
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.detail.isNotBlank()) {
                    Text(state.detail, color = Color.White.copy(alpha = 0.65f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
                LinearProgressIndicator(
                    Modifier.width(180.dp).height(3.dp).clip(CircleShape),
                    color = AliflixAccentPrimary,
                    trackColor = Color.White.copy(alpha = 0.12f)
                )
                Text(state.stage ?: "Preparing your video", color = AliflixAccentSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Sit back. We'll start when it's ready.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
        }

        // In-Player Failure / Error Card (Keeping user inside player)
        if (state.error != null && !preparing) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 440.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xF510131A),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                shadowElevation = 24.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = AliflixError, modifier = Modifier.size(42.dp))
                    Text("Playback Problem", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(state.error, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center, fontSize = 13.sp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onServer,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Dns, null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("Switch Server", color = Color.White, fontSize = 13.sp)
                        }
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("Retry", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // External TV Casting Notice
        if (state.external && !preparing && state.error == null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xE610131A),
                border = BorderStroke(1.dp, AliflixAccentPrimary.copy(alpha = 0.4f)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp).padding(horizontal = 24.dp)
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.CastConnected, null, tint = AliflixAccentSecondary, modifier = Modifier.size(22.dp))
                    Column {
                        Text("Playing on your TV", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Use other apps. Control playback from this screen.", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                }
            }
        }

        // TOP CONTROLS BAR (NO ALIFLIX WORDMARK)
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.88f), Color.Black.copy(alpha = 0.4f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(42.dp).background(Color.White.copy(alpha = 0.08f), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(22.dp))
                }

                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        state.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.detail.isNotBlank()) {
                        Text(
                            state.detail,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.episodes.isNotEmpty()) {
                        IconButton(
                            onClick = { sheet = "Episodes"; interaction++ },
                            modifier = Modifier.size(40.dp).background(Color.White.copy(alpha = 0.08f), CircleShape)
                        ) {
                            Icon(Icons.Default.VideoLibrary, "Episodes", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }

                    // Google Cast Button (Official Cast Picker)
                    if (state.external) {
                        IconButton(
                            onClick = { sheet = "CastOptions"; interaction++ },
                            modifier = Modifier.size(40.dp).background(AliflixAccentPrimaryContainer, CircleShape)
                        ) {
                            Icon(Icons.Default.CastConnected, "Cast active", tint = AliflixAccentSecondary, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        IconButton(
                            onClick = { onReceiver(); interaction++ },
                            modifier = Modifier.size(40.dp).background(Color.White.copy(alpha = 0.08f), CircleShape)
                        ) {
                            Icon(Icons.Default.Cast, "Cast to TV", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }

                    IconButton(
                        onClick = { sheet = "More"; interaction++ },
                        modifier = Modifier.size(40.dp).background(Color.White.copy(alpha = 0.08f), CircleShape)
                    ) {
                        Icon(Icons.Default.MoreVert, "More", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

            // CENTER CONTROLS (Tapping video hides/shows ALL including these)
            if (!preparing && state.error == null) {
                AnimatedVisibility(
                    visible = controls,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(220)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rewind 10s
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                            modifier = Modifier.size(52.dp).clickable(enabled = player?.isCurrentMediaItemSeekable == true) {
                                player?.seekTo((position - 10_000).coerceAtLeast(0))
                                interaction++
                            }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Replay10, "Rewind 10s", tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                        }

                        // Play/Pause button in Aliflix Accent
                        Surface(
                            shape = CircleShape,
                            color = AliflixAccentPrimary,
                            border = BorderStroke(2.dp, Color.White.copy(alpha = 0.25f)),
                            shadowElevation = 10.dp,
                            modifier = Modifier.size(76.dp).clickable {
                                player?.let { p ->
                                    if (ended) p.seekTo(0)
                                    if (p.playWhenReady && !ended) p.pause() else p.play()
                                }
                                interaction++
                            }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (ended) Icons.Default.Replay else if (player?.playWhenReady == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (ended) "Play again" else if (player?.playWhenReady == true) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                        }

                        // Forward 10s
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                            modifier = Modifier.size(52.dp).clickable(enabled = player?.isCurrentMediaItemSeekable == true) {
                                player?.seekTo((position + 10_000).coerceAtMost(duration))
                                interaction++
                            }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                        }
                    }
                }

                // IN-PLAYER BUFFERING INDICATOR (pulsing ring loader)
                if (player?.playbackState == Player.STATE_BUFFERING) {
                    Box(modifier = Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.4f,
                            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                            label = "pulseScale"
                        )
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.35f,
                            targetValue = 0.05f,
                            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                            label = "pulseAlpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale; alpha = pulseAlpha }
                                .background(AliflixAccentPrimary, CircleShape)
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.size(52.dp),
                            color = AliflixAccentPrimary,
                            strokeWidth = 3.dp,
                            trackColor = Color.White.copy(alpha = 0.15f)
                        )
                    }
                }

                // BOTTOM CONTROLS BAR (SINGLE ROW, NO HORIZONTAL SCROLL)
                AnimatedVisibility(
                    visible = controls,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(220)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.95f))))
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        // Time display
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(playerTime(seek?.toLong() ?: position), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("−${playerTime((duration - (seek?.toLong() ?: position)).coerceAtLeast(0))}", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                        }

                        // Progress slider with buffered bar
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            LinearProgressIndicator(
                                progress = { ((player?.bufferedPosition ?: 0).toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                                color = Color.White.copy(alpha = 0.3f),
                                trackColor = Color.White.copy(alpha = 0.12f)
                            )
                            Slider(
                                value = seek ?: position.toFloat(),
                                onValueChange = { seek = it; interaction++ },
                                thumb = { Box(Modifier.size(14.dp).background(Color.White, CircleShape).border(2.dp, AliflixAccentPrimary, CircleShape)) },
                                track = { SliderDefaults.Track(it, modifier = Modifier.height(4.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null) },
                                onValueChangeFinished = { seek?.let { player?.seekTo(it.toLong()) }; seek = null },
                                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                                enabled = player?.isCurrentMediaItemSeekable == true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AliflixAccentPrimary, inactiveTrackColor = Color.Transparent)
                            )
                        }

                        // SINGLE ROW BOTTOM ACTIONS - NO HORIZONTAL SCROLL
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlayerActionChip(
                                    icon = Icons.Default.Subtitles,
                                    label = "Audio & Subs",
                                    badgeActive = state.activeSubtitleTrack != null,
                                    onClick = { sheet = "Audio & subtitles"; interaction++ }
                                )
                                PlayerActionChip(
                                    icon = Icons.Default.HighQuality,
                                    label = "Quality",
                                    onClick = { sheet = "Quality"; interaction++ }
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlayerActionButton(
                                    icon = Icons.Default.AspectRatio,
                                    label = if (fill) "Fit video" else "Fill screen",
                                    active = fill,
                                    onClick = {
                                        fill = !fill
                                        onFit(fill)
                                        showHud(Icons.Default.AspectRatio, if (fill) "Fill Screen" else "Fit to Screen")
                                        interaction++
                                    }
                                )
                                PlayerActionButton(
                                    icon = Icons.Default.ScreenRotation,
                                    label = "Rotate screen",
                                    onClick = { onRotate(); interaction++ }
                                )
                                PlayerActionButton(
                                    icon = Icons.Default.MoreHoriz,
                                    label = "More settings",
                                    onClick = { sheet = "More"; interaction++ }
                                )
                            }
                        }
                    }
                }
            }

        // NEXT EPISODE COUNTDOWN CARD
        AnimatedVisibility(
            visible = showCountdown,
            enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 2 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(end = 16.dp, bottom = if (controls) 140.dp else 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xF212151E),
                border = BorderStroke(1.dp, AliflixAccentPrimary.copy(alpha = 0.6f)),
                shadowElevation = 16.dp,
                modifier = Modifier.widthIn(max = 360.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val sec = (remainingMs / 1000).toInt().coerceIn(1, 25)
                        CircularProgressIndicator(
                            progress = { (remainingMs.toFloat() / 25_000f).coerceIn(0f, 1f) },
                            color = AliflixAccentPrimary,
                            trackColor = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                        Text("$sec", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("UP NEXT", color = AliflixAccentSecondary, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
                        Text("E${next!!.number} · ${next.title}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(
                        onClick = { next?.let { onEpisode(it) } },
                        modifier = Modifier.size(36.dp).background(AliflixAccentPrimary, CircleShape)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play Now", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { countdownCancelled = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Intro / Skip Segment Button
        val segment = state.segments.firstOrNull { it.isActive(position, duration) }
        if (!preparing && state.error == null && sheet == null && player?.isCurrentMediaItemSeekable == true && !showCountdown) {
            AnimatedVisibility(
                segment != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(end = 20.dp, bottom = if (controls) 140.dp else 24.dp)
            ) {
                segment?.let { marker ->
                    Button(
                        onClick = { player.seekTo(marker.endMs); interaction++ },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(marker.kind.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp))
                    }
                }
            }
        }

        // HUD Indicator (Brightness / Volume / Pinch / Seek)
        AnimatedVisibility(
            visible = hudFeedback != null,
            enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.88f),
            exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = 0.88f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            hudFeedback?.let { item ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xF212151E),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    shadowElevation = 16.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(item.icon, contentDescription = null, tint = AliflixAccentSecondary, modifier = Modifier.size(34.dp))
                        Text(item.text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (item.progress != null) {
                            LinearProgressIndicator(
                                progress = { item.progress },
                                modifier = Modifier.width(110.dp).height(4.dp).clip(CircleShape),
                                color = AliflixAccentPrimary,
                                trackColor = Color.White.copy(alpha = 0.15f),
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    // MODAL BOTTOM SHEETS
    if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null; interaction++ },
            containerColor = Color(0xFF141620),
            contentColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = sheet!!,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                when (sheet) {
                    "More" -> {
                        SheetOption("Playback Speed", "${player?.playbackParameters?.speed ?: 1f}×") {
                            sheet = "Speed"
                        }
                        SheetOption("Server · ${state.server}", "Switch to another available server") {
                            sheet = null
                            onServer()
                        }
                        if (state.external) {
                            SheetOption("Stop Casting", "Disconnect TV and continue watching on phone") {
                                sheet = null
                                onStopCast()
                            }
                        } else {
                            SheetOption("Google Cast", "Cast video to Chromecast or compatible TV") {
                                sheet = null
                                onReceiver()
                            }
                            SheetOption("Wireless Display", "Connect using Android screen mirroring") {
                                sheet = null
                                onWireless()
                            }
                        }
                        SheetOption("Stop Playback", "End this playback session and return", onClick = onStop)
                    }

                    "CastOptions" -> {
                        SheetOption("Stop Casting", "Disconnect TV and return playback to phone") {
                            sheet = null
                            onStopCast()
                        }
                        SheetOption("Switch Device", "Connect to another Google Cast receiver") {
                            sheet = null
                            onReceiver()
                        }
                    }

                    "Speed" -> {
                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                            SheetOption(
                                "${speed}×${if (speed == 1.0f) " · Normal" else ""}",
                                selected = player?.playbackParameters?.speed == speed
                            ) {
                                player?.setPlaybackSpeed(speed)
                                onSpeedChange(speed)
                                sheet = null
                            }
                        }
                    }

                    "Quality" -> {
                        SheetOption("Auto", "Adapts dynamically to network connection", selected = player?.trackSelectionParameters?.overrides?.values?.none { it.type == C.TRACK_TYPE_VIDEO } == true) {
                            player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build() }
                            sheet = null
                        }
                        TrackOptions(player, C.TRACK_TYPE_VIDEO) { sheet = null }
                    }

                    "Audio & subtitles" -> {
                        // Subtitle Sync / Delay
                        Text("SUBTITLE SYNC", color = AliflixAccentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.05f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Time Offset", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    val offsetSec = settings.subtitleDelaySeconds
                                    val offsetText = if (offsetSec == 0.0) "0.0s" else if (offsetSec > 0) "+%.1fs".format(Locale.ROOT, offsetSec) else "%.1fs".format(Locale.ROOT, offsetSec)
                                    Text(offsetText, color = if (offsetSec != 0.0) AliflixAccentSecondary else Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FilledTonalIconButton(
                                        onClick = { onSubtitleDelayChange(settings.subtitleDelayTenths - 1) },
                                        modifier = Modifier.size(38.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White)
                                    ) {
                                        Text("−0.1", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                    if (settings.subtitleDelayTenths != 0) {
                                        TextButton(onClick = { onSubtitleDelayChange(0) }) {
                                            Text("Reset", color = AliflixAccentSecondary, fontSize = 11.sp)
                                        }
                                    }
                                    FilledTonalIconButton(
                                        onClick = { onSubtitleDelayChange(settings.subtitleDelayTenths + 1) },
                                        modifier = Modifier.size(38.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White)
                                    ) {
                                        Text("+0.1", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        // Subtitle Size Selection
                        Text("SUBTITLE SIZE", color = AliflixAccentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(13f to "Small", 16f to "Normal", 19f to "Medium", 23f to "Large").forEach { (size, label) ->
                                FilterChip(
                                    selected = kotlin.math.abs(settings.subtitleFontSizeSp - size) < 1.5f,
                                    onClick = { onSubtitleFontSizeChange(size) },
                                    label = { Text(label, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AliflixAccentPrimaryContainer,
                                        selectedLabelColor = AliflixAccentSecondary,
                                        labelColor = Color.White
                                    )
                                )
                            }
                        }

                        // Subtitle Background Opacity
                        Text("BACKGROUND OPACITY", color = AliflixAccentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0f to "0%", 0.25f to "25%", 0.5f to "50%", 0.75f to "75%", 1f to "100%").forEach { (op, label) ->
                                FilterChip(
                                    selected = kotlin.math.abs(settings.subtitleBackgroundOpacity - op) < 0.1f,
                                    onClick = { onSubtitleOpacityChange(op) },
                                    label = { Text(label, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AliflixAccentPrimaryContainer,
                                        selectedLabelColor = AliflixAccentSecondary,
                                        labelColor = Color.White
                                    )
                                )
                            }
                        }

                        // Subtitle Preview
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Black.copy(alpha = settings.subtitleBackgroundOpacity),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(
                                "Aliflix Subtitle Preview",
                                color = Color.White,
                                fontSize = settings.subtitleFontSizeSp.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        Spacer(Modifier.height(4.dp))
                        Text("AUDIO", color = AliflixAccentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        TrackOptions(player, C.TRACK_TYPE_AUDIO) { }

                        Spacer(Modifier.height(4.dp))
                        Text("SUBTITLES", color = AliflixAccentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        SheetOption(
                            "Off",
                            selected = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true && state.activeSubtitleTrack == null
                        ) {
                            player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
                            onSubtitleDisable()
                        }
                        TrackOptions(player, C.TRACK_TYPE_TEXT) { }

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("More Subtitles", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            TextButton(onClick = onSubtitleSearch) { Text("Search", color = AliflixAccentSecondary) }
                        }
                        if (state.subtitleLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AliflixAccentPrimary)
                        state.subtitleError?.let { Text(it, color = AliflixError, fontSize = 13.sp) }
                        if (!state.subtitleLoading && state.subtitleTracks.isEmpty()) {
                            Text("No external subtitles found for this title.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        var language by remember { mutableStateOf<String?>(null) }
                        val languages = state.subtitleTracks.map { it.languageName }.distinct()
                        if (languages.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                languages.forEach { name ->
                                    FilterChip(
                                        selected = (language ?: languages.firstOrNull()) == name,
                                        onClick = { language = name },
                                        label = { Text(name) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AliflixAccentPrimaryContainer,
                                            selectedLabelColor = AliflixAccentSecondary,
                                            labelColor = Color.White
                                        )
                                    )
                                }
                            }
                            state.subtitleTracks.filter { it.languageName == (language ?: languages.firstOrNull()) }.forEach { track ->
                                SheetOption(track.languageName, track.releaseName, selected = state.activeSubtitleTrack?.id == track.id) {
                                    player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build() }
                                    onSubtitle(track)
                                }
                            }
                        }
                    }

                    "Episodes" -> {
                        state.episodes.forEach { episode ->
                            SheetOption(
                                "${episode.number}. ${episode.title}",
                                selected = episode.number == state.episodeNumber
                            ) {
                                sheet = null
                                onEpisode(episode)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerActionChip(
    icon: ImageVector,
    label: String,
    badgeActive: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, if (badgeActive) AliflixAccentPrimary.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f)),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = if (badgeActive) AliflixAccentSecondary else Color.White, modifier = Modifier.size(16.dp))
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (badgeActive) {
                Box(Modifier.size(6.dp).background(AliflixAccentPrimary, CircleShape))
            }
        }
    }
}

@Composable
private fun PlayerActionButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(38.dp).background(if (active) AliflixAccentPrimaryContainer else Color.White.copy(alpha = 0.08f), CircleShape)
    ) {
        Icon(icon, contentDescription = label, tint = if (active) AliflixAccentSecondary else Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SheetOption(
    title: String,
    subtitle: String = "",
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AliflixAccentPrimaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) AliflixAccentSecondary else Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Icon(Icons.Default.Check, "Selected", tint = AliflixAccentSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun TrackOptions(player: Player?, type: Int, onSelect: () -> Unit) {
    val groups = player?.currentTracks?.groups.orEmpty().filter { it.type == type }
    groups.forEach { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.forEach { index ->
            val format = group.getTrackFormat(index)
            val label = if (type == C.TRACK_TYPE_VIDEO) "${format.height.takeIf { it > 0 } ?: "Auto"}p"
            else format.label ?: format.language?.let { Locale.forLanguageTag(it).displayLanguage }
            ?: if (type == C.TRACK_TYPE_AUDIO) "Original audio" else "Subtitles"
            SheetOption(label, selected = group.isTrackSelected(index)) {
                player?.let {
                    it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                        .build()
                }
                onSelect()
            }
        }
    }
    if (groups.isEmpty() && type == C.TRACK_TYPE_AUDIO) {
        Text("Original audio", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(14.dp))
    }
}

internal fun playerTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}
