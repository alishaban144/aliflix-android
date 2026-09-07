package com.aliflix.app.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.delay
import java.util.Locale

internal data class NativePlayerUi(
    val title: String = "Aliflix", val detail: String = "", val artwork: String? = null,
    val stage: String? = null, val error: String? = null, val server: String = "Auto",
    val ready: Boolean = false, val external: Boolean = false, val revision: Int = 0,
    val episodes: List<Episode> = emptyList(), val episodeNumber: Int? = null,
    val subtitleTracks: List<SubtitleTrack> = emptyList(), val subtitleLoading: Boolean = false,
    val subtitleError: String? = null, val message: String? = null,
    val segments: List<IntroSegment> = emptyList(),
)

private val PlayerInk = Color(0xFF08090F)
private val PlayerLilac = Color(0xFFC8B7FF)

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativePlayerScreen(
    state: NativePlayerUi, player: Player?, onBack: () -> Unit, onRetry: () -> Unit,
    onServer: () -> Unit, onStop: () -> Unit, onStopCast: () -> Unit,
    onWireless: () -> Unit, onFit: (Boolean) -> Unit, onRotate: () -> Unit,
    onSubtitleSearch: () -> Unit, onSubtitle: (SubtitleTrack) -> Unit, onEpisode: (Episode) -> Unit,
    onReceiver: () -> Unit = {},
) {
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var fill by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var seek by remember { mutableStateOf<Float?>(null) }
    var gesture by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it) } }
    val preparing = state.stage != null || (!state.ready && state.error == null && player?.mediaItemCount != 0)
    val playing = player?.isPlaying == true
    val ended = player?.playbackState == Player.STATE_ENDED
    val duration = (player?.duration ?: 0).coerceAtLeast(0)
    val position = (player?.currentPosition ?: 0).coerceIn(0, duration.coerceAtLeast(1))
    val next = state.episodes.dropWhile { it.number != state.episodeNumber }.drop(1).firstOrNull()
    LaunchedEffect(playing, controls, interaction, sheet, locked) {
        if (playing && controls && sheet == null && !locked) { delay(4500); controls = false }
    }
    LaunchedEffect(gesture) { if (gesture != null) { delay(900); gesture = null } }
    LaunchedEffect(state.error, ended) { if (state.error != null || ended) controls = true }
    Box(Modifier.fillMaxSize().pointerInput(locked, preparing, state.error) {
        detectTapGestures(onTap = { controls = !controls; interaction++ }, onDoubleTap = { offset ->
            if (!locked && !preparing && state.error == null && player?.isCurrentMediaItemSeekable == true) {
                val delta = if (offset.x < size.width / 2) -10_000 else 10_000
                player.seekTo((player.currentPosition + delta).coerceIn(0, player.duration.coerceAtLeast(0)))
                gesture = if (delta < 0) "−10 seconds" else "+10 seconds"
            }
        })
    }) {
        if (preparing || state.error != null || state.external || ended) {
            Box(Modifier.fillMaxSize().background(PlayerInk)) {
                AsyncImage(state.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .28f)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PlayerInk.copy(alpha = .35f), PlayerInk.copy(alpha = .95f)))))
            }
        }
        if (preparing || state.error != null) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedAliflixHeatmapLogo(Modifier.size(56.dp))
                Text("ALIFLIX", color = PlayerLilac, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                Text(state.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.detail.isNotBlank()) Text(state.detail, color = Color.White.copy(alpha = .65f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.error == null) {
                    LinearProgressIndicator(Modifier.width(180.dp).height(3.dp).clip(CircleShape), color = PlayerLilac, trackColor = Color.White.copy(alpha = .12f))
                    Text(state.stage ?: "Preparing your video", color = PlayerLilac, fontSize = 14.sp)
                    Text("Sit back. We'll start when it's ready.", color = Color.White.copy(alpha = .5f), fontSize = 12.sp)
                } else {
                    Text(state.error, color = Color.White.copy(alpha = .75f), textAlign = TextAlign.Center, fontSize = 14.sp)
                    Button(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Try again") }
                }
            }
        }
        if (state.external && !preparing && state.error == null) {
            Column(Modifier.align(Alignment.TopCenter).padding(top = 76.dp).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Playing on your TV", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Use other apps. Control playback from notifications.", color = Color.White.copy(alpha = .6f), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
        if (locked) {
            if (controls) PlayerIcon(Icons.Default.LockOpen, "Unlock controls", Modifier.align(Alignment.CenterEnd).padding(24.dp)) { locked = false; interaction++ }
        } else {
            AnimatedVisibility(controls || preparing || state.error != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .85f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        if (!preparing) {
                            Text("ALIFLIX", color = PlayerLilac, fontSize = 9.sp, letterSpacing = 2.sp)
                            Text(state.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (state.detail.isNotBlank()) Text(state.detail, color = Color.White.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (state.external) TextButton(onClick = onStopCast) { Icon(Icons.Default.CastConnected, null, tint = PlayerLilac); Spacer(Modifier.width(8.dp)); Text("Stop casting", color = PlayerLilac) }
                    else PlayerIcon(Icons.Default.Cast, "Wireless screen casting", onClick = onWireless)
                    PlayerIcon(Icons.Default.MoreVert, "Player options") { sheet = "Options" }
                }
            }
            if (!preparing && state.error == null) {
                AnimatedVisibility(controls || !playing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(34.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerIcon(Icons.Default.Replay10, "Rewind 10 seconds", enabled = player?.isCurrentMediaItemSeekable == true) { player?.seekTo((position - 10_000).coerceAtLeast(0)); interaction++ }
                        FilledIconButton(onClick = { if (ended) player?.seekTo(0); if (player?.playWhenReady == true && !ended) player.pause() else player?.play(); interaction++ },
                            modifier = Modifier.size(76.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = PlayerInk)) {
                            Icon(if (ended) Icons.Default.Replay else if (player?.playWhenReady == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (ended) "Play again" else if (player?.playWhenReady == true) "Pause" else "Play", Modifier.size(38.dp))
                        }
                        PlayerIcon(Icons.Default.Forward10, "Forward 10 seconds", enabled = player?.isCurrentMediaItemSeekable == true) { player?.seekTo((position + 10_000).coerceAtMost(duration)); interaction++ }
                    }
                }
                if (player?.playbackState == Player.STATE_BUFFERING) CircularProgressIndicator(Modifier.align(Alignment.Center).size(94.dp), color = PlayerLilac, strokeWidth = 2.dp)
                AnimatedVisibility(controls || !playing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .95f))))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(horizontal = 24.dp, vertical = 12.dp)) {
                        if (ended && next != null) Button(onClick = { onEpisode(next) }, modifier = Modifier.align(Alignment.End)) { Text("Next episode"); Icon(Icons.Default.SkipNext, null) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(playerTime((seek?.toLong() ?: position)), color = Color.White, fontSize = 12.sp)
                            Text("−${playerTime((duration - (seek?.toLong() ?: position)).coerceAtLeast(0))}", color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
                        }
                        Box(contentAlignment = Alignment.Center) {
                            LinearProgressIndicator(progress = { ((player?.bufferedPosition ?: 0).toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(3.dp), color = Color.White.copy(alpha = .25f), trackColor = Color.White.copy(alpha = .1f))
                            Slider(value = seek ?: position.toFloat(), onValueChange = { seek = it; interaction++ },
                                thumb = { Box(Modifier.size(12.dp).background(Color.White, CircleShape)) },
                                track = { SliderDefaults.Track(it, modifier = Modifier.height(4.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null) },
                                onValueChangeFinished = { seek?.let { player?.seekTo(it.toLong()) }; seek = null },
                                valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = player?.isCurrentMediaItemSeekable == true,
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" },
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = PlayerLilac, inactiveTrackColor = Color.Transparent))
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlayerChip(Icons.Default.Subtitles, "Audio & subtitles") { sheet = "Audio & subtitles" }
                            PlayerChip(Icons.Default.HighQuality, "Quality") { sheet = "Quality" }
                            PlayerChip(Icons.Default.Speed, "${player?.playbackParameters?.speed ?: 1f}×") { sheet = "Speed" }
                            if (state.episodes.isNotEmpty()) PlayerChip(Icons.Default.VideoLibrary, "Episodes") { sheet = "Episodes" }
                            PlayerIcon(Icons.Default.Lock, "Lock controls") { locked = true }
                            PlayerIcon(Icons.Default.AspectRatio, if (fill) "Fit video" else "Fill screen") { fill = !fill; onFit(fill) }
                            PlayerIcon(Icons.Default.ScreenRotation, "Rotate screen", onClick = onRotate)
                        }
                    }
                }
            }
        }
        val segment = state.segments.firstOrNull { it.isActive(position, duration) }
        if (!locked && !preparing && state.error == null && sheet == null && player?.isCurrentMediaItemSeekable == true) {
            AnimatedVisibility(segment != null, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(end = 24.dp, bottom = if (controls || !playing) 158.dp else 28.dp)) {
                segment?.let { marker ->
                    Button(onClick = { player.seekTo(marker.endMs); interaction++ },
                        shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = PlayerLilac, contentColor = PlayerInk),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(marker.kind.label, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp)); Icon(Icons.Default.SkipNext, null, Modifier.size(20.dp))
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        gesture?.let { Text(it, Modifier.align(Alignment.Center).background(PlayerInk.copy(alpha = .9f), RoundedCornerShape(14.dp)).padding(18.dp), color = Color.White) }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null; interaction++ }, containerColor = Color(0xFF161620), contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(sheet!!, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            when (sheet) {
                "Options" -> {
                    if (state.external) SheetOption("Stop casting", "Continue watching on this phone") { sheet = null; onStopCast() }
                    SheetOption("Server · ${state.server}", "Try another server") { sheet = null; onServer() }
                    SheetOption("Google Cast", "Play directly on a compatible TV") { sheet = null; onReceiver() }
                    SheetOption("Wireless display", "Connect using Android screen casting") { sheet = null; onWireless() }
                    SheetOption("Stop playback", "End this playback session", onClick = onStop)
                    state.message?.let { Text(it, color = PlayerLilac, fontSize = 13.sp) }
                }
                "Speed" -> listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    SheetOption("${speed}×${if (speed == 1f) " · Normal" else ""}", selected = player?.playbackParameters?.speed == speed) { player?.setPlaybackSpeed(speed); sheet = null }
                }
                "Quality" -> {
                    SheetOption("Auto", "Adapts to your connection", selected = player?.trackSelectionParameters?.overrides?.values?.none { it.type == C.TRACK_TYPE_VIDEO } == true) {
                        player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build() }; sheet = null
                    }
                    TrackOptions(player, C.TRACK_TYPE_VIDEO) { sheet = null }
                }
                "Audio & subtitles" -> {
                    Text("AUDIO", color = PlayerLilac, fontSize = 12.sp, letterSpacing = 2.sp)
                    TrackOptions(player, C.TRACK_TYPE_AUDIO) { }
                    Text("SUBTITLES", color = PlayerLilac, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 12.dp))
                    SheetOption("Off") { player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() } }
                    TrackOptions(player, C.TRACK_TYPE_TEXT) { }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("More subtitles", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = onSubtitleSearch) { Text("Refresh") }
                    }
                    if (state.subtitleLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PlayerLilac)
                    state.subtitleError?.let { Text(it, color = PlayerLilac, fontSize = 13.sp) }
                    if (!state.subtitleLoading && state.subtitleTracks.isEmpty()) Text("No additional subtitles available.", color = Color.White.copy(alpha = .6f))
                    var language by remember { mutableStateOf<String?>(null) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.subtitleTracks.map { it.languageName }.distinct().forEach { name -> FilterChip(selected = (language ?: state.subtitleTracks.firstOrNull()?.languageName) == name, onClick = { language = name }, label = { Text(name) }) }
                    }
                    state.subtitleTracks.filter { it.languageName == (language ?: state.subtitleTracks.firstOrNull()?.languageName) }.forEach { track ->
                        SheetOption(track.languageName, track.releaseName) {
                            player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build() }
                            onSubtitle(track)
                        }
                    }
                    state.message?.let { Text(it, color = PlayerLilac, fontSize = 13.sp) }
                }
                "Episodes" -> state.episodes.forEach { episode ->
                    SheetOption("${episode.number}. ${episode.title}", selected = episode.number == state.episodeNumber) { sheet = null; onEpisode(episode) }
                }
            }
        }
    }
}

@Composable private fun PlayerIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, modifier.size(48.dp), enabled = enabled) { Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else .35f)) }
}
@Composable private fun PlayerChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick) { Icon(icon, null, Modifier.size(20.dp), Color.White); Spacer(Modifier.width(8.dp)); Text(label, color = Color.White, fontSize = 12.sp) }
}
@Composable private fun SheetOption(title: String, subtitle: String = "", selected: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) PlayerLilac.copy(alpha = .12f) else Color.Transparent)
        .clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) PlayerLilac else Color.White)
            if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = .55f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Default.Check, "Selected", tint = PlayerLilac)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable private fun TrackOptions(player: Player?, type: Int, onSelect: () -> Unit) {
    val groups = player?.currentTracks?.groups.orEmpty().filter { it.type == type }
    groups.forEach { group -> (0 until group.length).filter { group.isTrackSupported(it) }.forEach { index ->
        val format = group.getTrackFormat(index)
        val label = if (type == C.TRACK_TYPE_VIDEO) "${format.height.takeIf { it > 0 } ?: "Auto"}p" else
            format.label ?: format.language?.let { Locale.forLanguageTag(it).displayLanguage } ?: if (type == C.TRACK_TYPE_AUDIO) "Original audio" else "Subtitles"
        SheetOption(label, selected = group.isTrackSelected(index)) {
            player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build() }
            onSelect()
        }
    } }
    if (groups.isEmpty() && type == C.TRACK_TYPE_AUDIO) Text("Original audio", color = Color.White.copy(alpha = .6f), modifier = Modifier.padding(14.dp))
}

internal fun playerTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}
