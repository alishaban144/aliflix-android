package com.aliflix.app.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.aliflix.app.model.Episode
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.delay

private val ScrimColor = Color.Black.copy(alpha = 0.28f)

/**
 * Complete Mobile Player UI Overlay reproducing the specification design:
 * - Scrim over video when controls are visible (black 28% opacity)
 * - Top Left: 34dp translucent circular back button + single-line uppercase title
 * - Top Right: compact 30x30dp rounded-rect buttons (Episodes [TV only], Cast, Rotate, More)
 * - Center: Replay 15s, 64dp translucent outline circular Play/Pause, Forward 15s
 * - Bottom: Elapsed & remaining time labels, 3dp Aliflix purple progress track, 16dp thumb
 * - Double-tap seeking with accumulating feedback HUD
 * - Clean auto-hide after 3s during playback
 */
@Composable
internal fun MobilePlayerOverlay(
    selection: PlaybackSelection,
    controller: WebPlayerController,
    onClose: () -> Unit,
    onSelectEpisode: (Episode) -> Unit,
    subtitlesActive: Boolean,
    subtitleLanguage: String?,
    onOpenSubtitles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPlaying by controller.isPlaying.collectAsState()
    val currentPositionMs by controller.currentPositionMs.collectAsState()
    val durationMs by controller.durationMs.collectAsState()
    val bufferedPositionMs by controller.bufferedPositionMs.collectAsState()
    val moviepireServers by controller.moviepireServers.collectAsState()

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTv = selection.media.type == MediaType.TV

    val seasonEpisodeText = if (isTv && selection.seasonNumber != null && selection.episodeNumber != null) {
        "S${selection.seasonNumber} E${selection.episodeNumber}"
    } else {
        null
    }
    val displayTitle = if (isTv) {
        listOfNotNull(selection.media.title, seasonEpisodeText, selection.episodeTitle?.takeIf(String::isNotBlank))
            .joinToString(" · ")
    } else {
        selection.media.title
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }
    var episodesSheetVisible by remember { mutableStateOf(false) }
    var moreSheetVisible by remember { mutableStateOf(false) }
    var interactionToken by remember { mutableIntStateOf(0) }

    val seekFeedback = remember { SeekFeedbackController() }

    // Auto-hide controls after 3 seconds of inactivity while playing
    LaunchedEffect(isPlaying, controlsVisible, isScrubbing, episodesSheetVisible, moreSheetVisible, interactionToken) {
        if (isPlaying && controlsVisible && !isScrubbing && !episodesSheetVisible && !moreSheetVisible) {
            delay(3000L)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // Video Gesture Detector (Single tap toggle, double tap seek)
        MobileVideoGestureDetector(
            onToggleControls = {
                controlsVisible = !controlsVisible
                interactionToken++
            },
            onSeekRewind = {
                seekFeedback.triggerSeek(false)
                controller.seekBy(-15_000L)
                interactionToken++
            },
            onSeekForward = {
                seekFeedback.triggerSeek(true)
                controller.seekBy(15_000L)
                interactionToken++
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Subtle dark scrim when controls are visible
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ScrimColor),
            )
        }

        // Visible Controls Overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                // Top Bar
                MobilePlayerTopBar(
                    title = displayTitle,
                    isTv = isTv,
                    onBack = onClose,
                    onEpisodes = {
                        episodesSheetVisible = true
                        interactionToken++
                    },
                    onCast = {
                        controller.openCastPicker()
                        interactionToken++
                    },
                    onRotate = {
                        controller.toggleOrientation()
                        interactionToken++
                    },
                    onMore = {
                        moreSheetVisible = true
                        interactionToken++
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                // Center Playback Controls
                MobilePlayerCenterControls(
                    isPlaying = isPlaying,
                    onPlayPause = {
                        controller.togglePlayPause()
                        interactionToken++
                    },
                    onSeekBack = {
                        seekFeedback.triggerSeek(false)
                        controller.seekBy(-15_000L)
                        interactionToken++
                    },
                    onSeekForward = {
                        seekFeedback.triggerSeek(true)
                        controller.seekBy(15_000L)
                        interactionToken++
                    },
                    modifier = Modifier.align(Alignment.Center),
                )

                // Bottom Timeline
                MobilePlayerTimeline(
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs,
                    bufferedPositionMs = bufferedPositionMs,
                    onSeek = { targetMs ->
                        controller.seekTo(targetMs)
                        interactionToken++
                    },
                    onScrubbingChanged = { scrubbing ->
                        isScrubbing = scrubbing
                        if (scrubbing) interactionToken++
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp),
                )
            }
        }

        // Seek Feedback HUD (Accumulates repeated seeks)
        SeekFeedbackHud(
            feedback = seekFeedback.state,
            onDismiss = { seekFeedback.dismiss() },
        )

        // Episodes Panel (Landscape) / Bottom Sheet (Portrait)
        MobileEpisodesOverlay(
            visible = episodesSheetVisible,
            isLandscape = isLandscape,
            episodes = selection.availableEpisodes,
            currentSeason = selection.seasonNumber,
            currentEpisode = selection.episodeNumber,
            onSelectEpisode = { episode ->
                onSelectEpisode(episode)
                interactionToken++
            },
            onDismiss = { episodesSheetVisible = false },
        )

        // More Menu Sheet
        MobilePlayerMoreSheet(
            visible = moreSheetVisible,
            selection = selection,
            servers = moviepireServers,
            subtitlesActive = subtitlesActive,
            subtitleLanguage = subtitleLanguage,
            onOpenSubtitles = onOpenSubtitles,
            onSelectSpeed = { speed ->
                controller.setPlaybackSpeed(speed)
                interactionToken++
            },
            onSelectServer = { server ->
                controller.selectMoviepireServer(server)
                interactionToken++
            },
            onOpenProviderOptions = {
                controller.showProviderOptions()
                interactionToken++
            },
            onDismiss = { moreSheetVisible = false },
        )
    }
}
