package com.aliflix.app.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.aliflix.app.model.Episode
import com.aliflix.app.model.PlaybackSelection
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
    val captionDragging: Boolean = false,
    val title: String = "Aliflix",
    val detail: String = "",
    val artwork: String? = null,
    val stage: String? = null,
    val error: String? = null,
    val server: String = "Auto",
    val availableServers: List<String> = emptyList(),
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
    val playbackSelection: PlaybackSelection? = null,
)

private val PlayerInk = Color(0xFF07080C)
private val PlayerScrimColor = Color.Black.copy(alpha = 0.28f)

private data class HudFeedback(
    val icon: ImageVector,
    val text: String,
    val progress: Float? = null,
)

/**
 * Phone player using the approved v3.1.75 mobile playback redesign:
 * - Compact top bar (back, uppercase title, Episodes / Cast / Rotate / More)
 * - Outlined 64dp play/pause with Â±15 second seek controls
 * - 35% / 30% / 35% double-tap seek zones with cumulative Â±15/Â±30/Â±45 HUD
 * - Redesigned purple timeline with scrub bubble
 * - Redesigned Episodes panel and playback settings (More) menu
 * - 3-second auto-hide during playback
 *
 * Native Media3 playback, subtitles, quality, servers, casting, rotation,
 * fit/fill, brightness/volume gestures, skip intro and next-episode behavior
 * are preserved from the previous native player.
 */
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
    onSelectServer: (String) -> Unit = {},
    onStop: () -> Unit = {},
    onStopCast: () -> Unit = {},
    onWireless: () -> Unit = {},
    onFit: (Boolean) -> Unit = {},
    onRotate: () -> Unit = {},
    onSubtitleSearch: () -> Unit = {},
    onSubtitle: (SubtitleTrack) -> Unit = {},
    onSubtitleDisable: () -> Unit = {},
    onSubtitleDelayChange: (Int) -> Unit = {},
    onSubtitleVerticalOffsetChange: (Int) -> Unit = {},
    onSubtitleFontSizeChange: (Float) -> Unit = {},
    onSubtitleOpacityChange: (Float) -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    onEpisode: (Episode) -> Unit = {},
    onReceiver: () -> Unit = {},
    onBrightnessSwipe: (Float) -> Float = { 0.5f },
    onVolumeSwipe: (Float) -> Float = { 0.5f },
    onControlsVisibilityChanged: (Boolean) -> Unit = {},
    onOverlayVisibilityChanged: (Boolean) -> Unit = {},
) {
    val subtitleTracks = remember(state.subtitleTracks) { normalizeMobileSubtitleTracks(state.subtitleTracks) }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var isScrubbing by remember { mutableStateOf(false) }
    var episodesVisible by remember { mutableStateOf(false) }
    var moreVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var fill by rememberSaveable { mutableStateOf(settings.resizeModeZoom) }
    var hudFeedback by remember { mutableStateOf<HudFeedback?>(null) }
    var hudTimerJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val seekFeedback = remember { SeekFeedbackController() }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val overlayOpen = sheet != null || episodesVisible || moreVisible
    LaunchedEffect(overlayOpen) { onOverlayVisibilityChanged(overlayOpen) }
    LaunchedEffect(controls) { onControlsVisibilityChanged(controls) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it) } }
    val preparing = state.stage != null || (!state.ready && state.error == null && player?.mediaItemCount != 0)
    val playing = player?.isPlaying == true
    val ended = player?.playbackState == Player.STATE_ENDED
    val duration = (player?.duration ?: 0L).coerceAtLeast(0L)
    val position = (player?.currentPosition ?: 0L).coerceIn(0L, duration.coerceAtLeast(1L))
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

    fun seekByMs(deltaMs: Long) {
        player?.let { p ->
            if (p.isCurrentMediaItemSeekable) {
                p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, p.duration.coerceAtLeast(0L)))
            }
        }
    }

    fun seekWithFeedback(forward: Boolean) {
        seekFeedback.triggerSeek(forward)
        seekByMs(if (forward) 15_000L else -15_000L)
        interaction++
    }

    // Auto-hide controls after 3 seconds of inactivity while playing
    LaunchedEffect(playing, controls, interaction, overlayOpen, isScrubbing, state.captionDragging) {
        if (playing && controls && !overlayOpen && !isScrubbing && !state.captionDragging) {
            delay(3000)
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
        // Fullscreen tap / double-tap seek layer (35% / 30% / 35% zones)
        if (!preparing && state.error == null) {
            MobileVideoGestureDetector(
                onToggleControls = {
                    controls = !controls
                    interaction++
                },
                onSeekRewind = { seekWithFeedback(false) },
                onSeekForward = { seekWithFeedback(true) },
                modifier = Modifier.fillMaxSize(),
            )
        }

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
                Text("Sit back. Playback will begin shortly.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
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

        // Subtle dark scrim when controls are visible
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().background(PlayerScrimColor))
        }

        // Redesigned controls overlay (compact top bar, outlined center controls, purple timeline)
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                MobilePlayerTopBar(
                    title = state.title,
                    // The approved top bar shows the Episodes button for TV content;
                    // the native player knows episode availability directly.
                    isTv = state.episodes.isNotEmpty(),
                    onBack = onBack,
                    onEpisodes = {
                        episodesVisible = true
                        interaction++
                    },
                    onSubtitles = {
                        sheet = "Audio & subtitles"
                        interaction++
                    },
                    onRotate = {
                        onRotate()
                        interaction++
                    },
                    onMore = {
                        moreVisible = true
                        interaction++
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                if (!preparing && state.error == null) {
                    MobilePlayerCenterControls(
                        isPlaying = playing,
                        onPlayPause = {
                            player?.let { p ->
                                if (ended) p.seekTo(0)
                                if (p.playWhenReady && !ended) p.pause() else p.play()
                            }
                            interaction++
                        },
                        onSeekBack = { seekWithFeedback(false) },
                        onSeekForward = { seekWithFeedback(true) },
                        hideSeekBack = seekFeedback.state?.isForward == false,
                        hideSeekForward = seekFeedback.state?.isForward == true,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                MobilePlayerTimeline(
                    currentPositionMs = position,
                    durationMs = duration,
                    bufferedPositionMs = player?.bufferedPosition ?: 0L,
                    onSeek = { targetMs ->
                        player?.seekTo(targetMs)
                        interaction++
                    },
                    onScrubbingChanged = { scrubbing ->
                        isScrubbing = scrubbing
                        if (scrubbing) interaction++
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp),
                )
            }
        }

        // IN-PLAYER BUFFERING INDICATOR (pulsing ring loader)
        if (!preparing && state.error == null && player?.playbackState == Player.STATE_BUFFERING) {
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
                        Text("E${next!!.number} Â· ${next.title}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OpenPlayerButton(
                        onClick = { next?.let { onEpisode(it) } },
                        modifier = Modifier.size(36.dp).background(AliflixAccentPrimary, CircleShape)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play Now", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    OpenPlayerButton(
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
        if (!preparing && state.error == null && !overlayOpen && player?.isCurrentMediaItemSeekable == true && !showCountdown) {
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

        // HUD Indicator (Brightness / Volume / Pinch)
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

        // Cumulative double-tap / Â±15s seek feedback HUD
        SeekFeedbackHud(
            feedback = seekFeedback.state,
            onDismiss = { seekFeedback.dismiss() },
        )

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    // Redesigned Episodes panel (landscape side panel / portrait bottom sheet)
    MobileEpisodesOverlay(
        visible = episodesVisible,
        isLandscape = isLandscape,
        episodes = state.episodes,
        currentSeason = state.episodes.firstOrNull { it.number == state.episodeNumber }?.seasonNumber,
        currentEpisode = state.episodeNumber,
        onSelectEpisode = { episode ->
            episodesVisible = false
            onEpisode(episode)
            interaction++
        },
        onDismiss = { episodesVisible = false },
    )

    // Redesigned playback settings (More) menu
    val playbackSelection = state.playbackSelection
    if (playbackSelection != null) {
        val serverOptions = remember(state.availableServers, state.server) {
            (state.availableServers.ifEmpty { listOf(state.server).filter { it.isNotBlank() } })
                .map { name -> MoviepireServerOption(key = name, label = name, selected = name.equals(state.server, ignoreCase = true)) }
        }
        MobilePlayerMoreSheet(
            visible = moreVisible,
            selection = playbackSelection,
            servers = serverOptions,
            subtitlesActive = state.activeSubtitleTrack != null,
            subtitleLanguage = state.activeSubtitleTrack?.languageName,
            onOpenSubtitles = {
                moreVisible = false
                sheet = "Audio & subtitles"
            },
            onSelectSpeed = { speed ->
                player?.setPlaybackSpeed(speed)
                onSpeedChange(speed)
                interaction++
            },
            onSelectServer = { option ->
                moreVisible = false
                onSelectServer(option.key)
            },
            onOpenProviderOptions = {
                moreVisible = false
                onServer()
            },
            onDismiss = { moreVisible = false },
            currentSpeed = player?.playbackParameters?.speed,
            onStopPlayback = {
                moreVisible = false
                onStop()
            },
            onOpenWirelessDisplay = {
                moreVisible = false
                onWireless()
            },
            castActive = state.external,
            onCast = {
                moreVisible = false
                if (state.external) {
                    sheet = "CastOptions"
                } else {
                    onReceiver()
                }
                interaction++
            },
        )
    }

    // MODAL BOTTOM SHEETS (Audio & subtitles, Quality, Cast options)
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
                                        Text("âˆ’0.1", fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
                        if (!state.subtitleLoading && subtitleTracks.isEmpty()) {
                            Text("No external subtitles found for this title.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        var language by remember { mutableStateOf<String?>(null) }
                        val languages = remember(subtitleTracks) {
                            subtitleTracks.map { it.languageName }.distinct().sortedWith(
                                compareBy<String> { name ->
                                    if (name.equals("EN", ignoreCase = true) || name.equals("English", ignoreCase = true)) 0 else 1
                                }.thenBy { it }
                            )
                        }
                        if (languages.isNotEmpty()) {
                            val activeLanguage = language?.takeIf { l -> languages.any { it.equals(l, ignoreCase = true) } }
                                ?: state.activeSubtitleTrack?.let { normalizeMobileSubtitleTracks(listOf(it)).first().languageName }?.takeIf { l -> languages.any { it.equals(l, ignoreCase = true) } }
                                ?: languages.firstOrNull()
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                languages.forEach { name ->
                                    FilterChip(
                                        selected = activeLanguage?.equals(name, ignoreCase = true) == true,
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
                            subtitleTracks.filter { it.languageName.equals(activeLanguage, ignoreCase = true) }.forEach { track ->
                                SheetOption(track.languageName, track.releaseName, selected = state.activeSubtitleTrack?.id == track.id) {
                                    player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build() }
                                    onSubtitle(track)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenPlayerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, onClick = onClick
        ), contentAlignment = Alignment.Center
    ) { content() }
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
