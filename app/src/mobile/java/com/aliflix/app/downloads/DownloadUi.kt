@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.downloads

import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.common.AliflixSurface

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import kotlinx.coroutines.flow.StateFlow
import com.aliflix.app.AliflixApplication
import com.aliflix.app.BuildConfig
import com.aliflix.app.data.*
import com.aliflix.app.model.*
import com.aliflix.app.player.*
import com.aliflix.app.recommendation.RecommendationAiClient
import com.aliflix.app.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private fun SavedDownload.progressFraction(): Float? = when {
    percent >= 0 -> percent / 100f
    estimate > 0 -> downloadedBytes.toFloat() / estimate
    else -> null
}?.coerceIn(0f, 1f)

private fun SavedDownload.statusLabel(): String = when (download.state) {
    Download.STATE_COMPLETED -> "Downloaded"
    Download.STATE_STOPPED -> if (download.stopReason == 2) "Storage full" else "Paused"
    Download.STATE_QUEUED -> "Queued"
    Download.STATE_REMOVING -> "Deleting"
    Download.STATE_RESTARTING -> "Restarting"
    Download.STATE_FAILED -> "Retry"
    else -> "Downloading"
}

private fun List<DownloadQuality>.totalSizeLabel(): String = when {
    isEmpty() || any { it.bytes <= 0 } -> "—"
    any { it.estimated } -> "≈ ${downloadSize(sumOf { it.bytes })}"
    else -> "${downloadSize(sumOf { it.bytes })}"
}

internal interface DownloadUiDependencies {
    val entries: StateFlow<List<SavedDownload>>
    val preferredHeight: Int
    val requestNotifications: Boolean get() = true
    suspend fun seasons(media: Media): List<Season>
    suspend fun episodes(media: Media, season: Int): List<Episode>
    suspend fun prepare(activity: ComponentActivity, host: FrameLayout, selections: List<Pair<String, PlaybackSelection>>,
        language: String, cached: Map<String, PreparedDownload>, onPrepared: (String, PreparedDownload) -> Unit,
        onError: (String, String) -> Unit)
    suspend fun discover(activity: ComponentActivity, host: FrameLayout, selection: PlaybackSelection,
        language: String, onUpdate: (DownloadDiscovery) -> Unit): PreparedDownload {
        var result: PreparedDownload? = null
        prepare(activity, host, listOf("${playbackProgressKey(selection)}:$language" to selection), language, emptyMap(), { _, value -> result = value }, { _, _ -> })
        return requireNotNull(result) { "No downloadable video found. Try again." }.also { item ->
            onUpdate(DownloadDiscovery(item.options.ifEmpty { item.qualities.map { DownloadOption(it, item) } }, true))
        }
    }
    suspend fun prepareTier(activity: ComponentActivity, host: FrameLayout, selections: List<Pair<String, PlaybackSelection>>,
        language: String, cached: Map<String, PreparedDownload>, option: DownloadOption, audio: String,
        onPrepared: (String, PreparedDownload) -> Unit, onError: (String, String) -> Unit) =
        prepare(activity, host, selections, language, cached, onPrepared, onError)
    fun blockedIds(): Set<String>
    suspend fun enqueue(requests: List<DownloadRequest>)
    fun pause(id: String)
    fun resume(saved: SavedDownload)
}

@Composable private fun rememberDownloadUiDependencies(): DownloadUiDependencies {
    val context = LocalContext.current
    val store = rememberDownloads()
    return remember(context, store) {
        val repository = MobileEpisodeRepository(context, RecommendationAiClient(BuildConfig.RECOMMENDATION_AI_BASE_URL))
        object : DownloadUiDependencies {
            override val entries = store.entries
            override val preferredHeight get() = store.preferredHeight
            override suspend fun seasons(media: Media) = repository.seasons(media.id)
            override suspend fun episodes(media: Media, season: Int) = repository.episodes(media.id, season)
            override suspend fun prepare(activity: ComponentActivity, host: FrameLayout, selections: List<Pair<String, PlaybackSelection>>,
                language: String, cached: Map<String, PreparedDownload>, onPrepared: (String, PreparedDownload) -> Unit,
                onError: (String, String) -> Unit) =
                prepareDownloadBatch(activity, host, selections, language, cached, onPrepared, onError)
            override suspend fun discover(activity: ComponentActivity, host: FrameLayout, selection: PlaybackSelection,
                language: String, onUpdate: (DownloadDiscovery) -> Unit) = prepareDownload(activity, host, selection, language, onUpdate)
            override suspend fun prepareTier(activity: ComponentActivity, host: FrameLayout, selections: List<Pair<String, PlaybackSelection>>,
                language: String, cached: Map<String, PreparedDownload>, option: DownloadOption, audio: String,
                onPrepared: (String, PreparedDownload) -> Unit, onError: (String, String) -> Unit) =
                prepareDownloadBatch(activity, host, selections, language, cached, onPrepared, onError, option, audio)
            override fun blockedIds(): Set<String> = entries.value.filter { it.download.state != Download.STATE_FAILED }.map { it.id }.toSet() +
                store.manager.currentDownloads.filter { it.state != Download.STATE_FAILED }.map { it.request.id }
            override suspend fun enqueue(requests: List<DownloadRequest>) = store.enqueue(requests)
            override fun pause(id: String) = store.pause(id)
            override fun resume(saved: SavedDownload) = store.resume(saved)
        }
    }
}

@Composable internal fun DownloadButton(media: Media, episode: Episode? = null, compact: Boolean = false,
    dependencies: DownloadUiDependencies? = null) {
    val context = LocalContext.current
    val selection = PlaybackSelection(media, seasonNumber = episode?.seasonNumber, episodeNumber = episode?.number)
    val id = playbackProgressKey(selection)
    var open by remember(id) { mutableStateOf(false) }
    var actionPending by remember(id) { mutableStateOf(false) }
    val store = dependencies ?: rememberDownloadUiDependencies()
    val entries by store.entries.collectAsState()
    val seriesPicker = media.type == MediaType.TV && episode == null
    val saved = entries.firstOrNull { it.id == id && !seriesPicker }
    val state = saved?.download?.state
    val active = state in setOf(Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_STOPPED, Download.STATE_RESTARTING)
    val fraction = saved?.progressFraction()
    val animatedFraction by androidx.compose.animation.core.animateFloatAsState(fraction ?: 0f,
        animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.LinearEasing), label = "download progress")
    val paused = state == Download.STATE_STOPPED
    val queued = state in setOf(Download.STATE_QUEUED, Download.STATE_RESTARTING)
    val enabled = !actionPending && state !in setOf(Download.STATE_COMPLETED, Download.STATE_REMOVING, Download.STATE_RESTARTING)
    val action = when {
        paused -> "Resume download"
        active -> "Pause download"
        state == Download.STATE_COMPLETED -> "Downloaded"
        state == Download.STATE_REMOVING -> "Deleting download"
        state == Download.STATE_FAILED -> "Retry download"
        else -> "Download"
    }
    val title = if (episode == null) media.title else "${media.title}, season ${episode.seasonNumber}, episode ${episode.number}"
    val showLabel = episode != null && !compact
    val estimated = saved != null && saved.percent < 0
    val status = saved?.statusLabel().orEmpty() + if (active && !queued && fraction != null) {
        " · ${if (estimated) "approximately " else ""}${(fraction * 100).toInt()} percent"
    } else ""
    LaunchedEffect(state) { actionPending = false; if (active || state == Download.STATE_COMPLETED) open = false }
    LaunchedEffect(actionPending) {
        if (actionPending) { delay(1_000); actionPending = false }
    }
    AliflixSurface(onClick = {
        if (!actionPending) {
            val current = store.entries.value.firstOrNull { it.id == id && !seriesPicker }
            when (current?.download?.state) {
                Download.STATE_STOPPED -> { actionPending = true; store.resume(current) }
                Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> { actionPending = true; store.pause(id) }
                Download.STATE_COMPLETED, Download.STATE_REMOVING, Download.STATE_RESTARTING -> Unit
                else -> if (context.hasInternetConnection()) open = true else
                    android.widget.Toast.makeText(context, "Connect to the internet and try again", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }, enabled = enabled, modifier = (if (showLabel) Modifier.heightIn(min = 48.dp) else Modifier.size(48.dp)).semantics(mergeDescendants = true) {
        contentDescription = "$action, $title"
        stateDescription = status
        role = Role.Button
        if (active) progressBarRangeInfo = if (fraction != null && !queued) ProgressBarRangeInfo(fraction, 0f..1f)
            else ProgressBarRangeInfo.Indeterminate
    }, shape = if (showLabel) androidx.compose.foundation.shape.CircleShape else if (compact) AliflixCorners.Small else AliflixCorners.Card,
        level = AliflixSurfaceLevel.Content,
    ) {
        Row(
            modifier = if (showLabel) {
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            } else {
                Modifier.fillMaxSize()
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (showLabel) Arrangement.spacedBy(6.dp) else Arrangement.Center,
        ) {
        Box(contentAlignment = Alignment.Center, modifier = if (showLabel && active) Modifier.size(38.dp) else Modifier) {
            if (active) {
                val ring = Modifier.size(38.dp).clearAndSetSemantics { }
                if (!queued && fraction != null) CircularProgressIndicator(
                    progress = { animatedFraction }, modifier = ring, strokeWidth = 2.dp,
                    color = AliflixAccentSecondary, trackColor = AliflixBorderSubtle)
                else CircularProgressIndicator(modifier = ring, strokeWidth = 2.dp, color = AliflixAccentSecondary)
            }
            androidx.compose.animation.Crossfade(action, label = "download state") { currentAction ->
            Icon(when {
                state == Download.STATE_COMPLETED -> Icons.Rounded.DownloadDone
                currentAction == "Resume download" -> Icons.Rounded.PlayArrow
                active -> Icons.Rounded.Pause
                state == Download.STATE_FAILED -> Icons.Rounded.Refresh
                else -> Icons.Rounded.Download
            }, null, modifier = Modifier.size(if (showLabel) 16.dp else if (compact || active) 20.dp else 24.dp),
                tint = if (state == Download.STATE_COMPLETED) AliflixAccentSecondary else AliflixContentSecondary)
            }
        }
        if (showLabel) Text(action, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AliflixContentPrimary)
        }
    }
    if (open) DownloadPicker(media, episode, store) { open = false }
}

@Composable private fun rememberDownloads(): OfflineDownloads {
    val context = LocalContext.current
    return remember(context) { OfflineDownloads.get(context) }
}

@Composable internal fun downloadCount(): Int {
    val entries by rememberDownloads().entries.collectAsState()
    return entries.count { it.download.state != Download.STATE_REMOVING }
}

@Composable internal fun DownloadPicker(media: Media, initialEpisode: Episode?,
    dependencies: DownloadUiDependencies? = null, dismiss: () -> Unit) {
    val activity = LocalActivity.current as ComponentActivity
    val store = dependencies ?: rememberDownloadUiDependencies()
    val prefs = remember(activity, media.key) { PlaybackProviderRepository(activity).preferences.value }
    val session = remember(activity) { DownloadSession.get(activity) }
    val picker = remember(session, media.key, initialEpisode) {
        session.pickers.getOrPut("${media.key}:${initialEpisode?.seasonNumber}:${initialEpisode?.number}") {
            DownloadPickerState(media, initialEpisode, prefs.preferredSubtitleLanguage.code)
        }
    }
    val entries by store.entries.collectAsState()
    var season by picker::season
    var chosen by picker::chosen
    var language by picker::language
    var noSubtitles by picker::noSubtitles
    var saving by picker::saving
    var retry by remember(media.key, season) { mutableIntStateOf(0) }
    var discoveryRetry by remember(media.key, season) { mutableIntStateOf(0) }
    var error by remember(media.key, season) { mutableStateOf<String?>(null) }
    var expanded by remember(media.key) { mutableStateOf(false) }
    var height by picker::height
    var audioLabel by remember(media.key, season) { mutableStateOf("Default") }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(media.key, season) { session.load(picker, media, initialEpisode, store) }
    val raw = if (media.type == MediaType.MOVIE) listOf("movie" to PlaybackSelection(media)) else picker.episodes.map {
        "${it.seasonNumber}:${it.number}" to PlaybackSelection(media, it.seasonNumber, it.number, it.title, availableEpisodes = picker.episodes)
    }.distinctBy { it.first }
    val blocked = entries.filter { it.download.state != Download.STATE_FAILED }.map { it.id }.toSet()
    val selected = raw.filter { it.first in chosen && playbackProgressKey(it.second) !in blocked }
        .map { (_, value) -> session.key(value, language) to value.copy(source = prefs.sourceFor(media)) }
    val anchor = selected.minWithOrNull(compareBy<Pair<String, PlaybackSelection>> { it.second.seasonNumber ?: 0 }.thenBy { it.second.episodeNumber ?: 0 })
    val discovery = anchor?.let { session.discoveries[it.first] }
    LaunchedEffect(anchor?.first, discoveryRetry) { anchor?.let { session.discover(store, it.second, language, discoveryRetry) } }
    val choices = discovery?.choicesKeeping(height).orEmpty()
    val option = choices.firstOrNull { it.quality.height == height } ?: discovery?.default
    LaunchedEffect(discovery?.finished, choices, height) {
        if (discovery?.finished == true && choices.none { it.quality.height == height }) height = discovery.default?.quality?.height
    }
    LaunchedEffect(selected, option, discovery?.finished, retry, audioLabel) {
        if (selected.isEmpty()) session.cancelSelectionWork(media.key)
        else if (discovery?.finished == true && option != null) session.validateTier(store, selected, language, option, retry, audioLabel)
        else session.cancelTierWork(media.key)
    }
    val qualities = selected.associate { (key, _) -> key to session.prepared[key]?.let {
        if (it.qualities.isNotEmpty() && option != null) closestDownloadQuality(it.qualities, option.quality.height) else null
    } }
    val checking = selected.any { it.first in session.pending }
    val exceptions = selected.filter { (key, _) -> key in session.errors || session.prepared[key]?.let { item ->
        val owner = qualities[key]?.let(item::owner) ?: item
        option != null && (owner.selection.source != option.source.selection.source || owner.server != option.source.server)
    } == true }
    val ready = discovery?.finished == true && option != null && selected.isNotEmpty() && !checking &&
        selected.all { it.first !in session.errors && qualities[it.first] != null && option != null &&
            session.validated(it.first, option, language, audioLabel) }
    fun estimate(item: PreparedDownload, quality: DownloadQuality): DownloadQuality {
        val tracks = item.audioTracksFor(quality)
        val index = if (audioLabel == "All tracks") -1 else tracks.firstOrNull { it.label == audioLabel }?.index
        return estimatedDownloadQuality(quality, tracks, index)
    }
    val total = selected.mapNotNull { (key, _) -> qualities[key]?.let { quality ->
        session.prepared[key]?.let { estimate(it, quality) }
    } }.totalSizeLabel()
    val anchorEstimate = option?.let { estimate(it.source, it.quality) }
    val batch = selected.size > 1
    val tracks = option?.source?.audioTracksFor(option.quality).orEmpty()
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        AliflixSheet(Modifier.fillMaxWidth().windowInsetsPadding(AliflixInsets.Safe)
            .padding(horizontal = AliflixSpacing.Content, vertical = AliflixSpacing.Large).heightIn(max = 700.dp), contentColor = AliflixContentPrimary) {
            Column(Modifier.downloadAtmosphere().padding(AliflixSpacing.Panel), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Download", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    AliflixIconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, "Close") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    coil.compose.AsyncImage(media.posterUrl, null, Modifier.size(48.dp, 72.dp).clip(AliflixCorners.Card), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Column(Modifier.weight(1f)) {
                        Text(media.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (initialEpisode != null) "S${initialEpisode.seasonNumber} · E${initialEpisode.number}"
                            else if (media.type == MediaType.TV) "S$season · ${selected.size} episodes" else media.year,
                            color = AliflixContentSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (media.type == MediaType.TV && initialEpisode == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1f)) {
                        items(picker.seasons.size) { index -> val tab = picker.seasons[index]
                            TextButton(onClick = { session.cancelSelectionWork(media.key); chosen = emptySet(); height = null; season = tab.number }, enabled = !saving) {
                                Text("S${tab.number}", color = if (tab.number == season) AliflixContentPrimary else AliflixContentSecondary)
                            }
                        }
                    }
                    TextButton(onClick = { expanded = !expanded }, enabled = !saving) { Text("Episodes"); Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
                }
                if (picker.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (anchor != null && discovery?.finished != true) Text("Finding download options…", color = AliflixContentSecondary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(if (anchor == null) 0 else if (discovery?.finished == true) choices.size else 3) { index ->
                        androidx.compose.animation.Crossfade(choices.getOrNull(index), label = "download quality") { available ->
                            if (available == null) DownloadQualitySkeleton() else {
                                val title = when {
                                    choices.size == 3 && index == 0 -> "Best"
                                    choices.size == 3 && index == 1 -> "Balanced"
                                    choices.size >= 2 && index == choices.lastIndex -> "Smaller file"
                                    choices.size == 2 && index == 0 -> "Best"
                                    else -> available.quality.label
                                }
                                val active = available.quality.height == option?.quality?.height
                                AliflixSurface(shape = AliflixCorners.Card, level = if (active) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content) {
                                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = !saving) {
                                        height = available.quality.height; audioLabel = "Default"
                                    }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                            Text(available.quality.label, color = AliflixContentSecondary, style = MaterialTheme.typography.labelSmall)
                                        }
                                        val sized = estimate(available.source, available.quality)
                                        Text(if (batch && sized.bytes > 0) "≈ ${downloadSize(sized.bytes * selected.size)}"
                                            else sized.sizeLabel, color = AliflixContentSecondary, fontSize = 12.sp)
                                        Spacer(Modifier.width(12.dp))
                                        Icon(if (active) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                            if (active) "Selected" else null, tint = if (active) AliflixAccentPrimary else AliflixContentSecondary, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                if (checking) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Text("Checking episode availability…", color = AliflixContentSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (batch && option != null) Text(if (ready) "$total total" else if ((anchorEstimate?.bytes ?: 0) > 0)
                    "≈ ${downloadSize(requireNotNull(anchorEstimate).bytes * selected.size)} total · Estimate" else "Size unavailable",
                    color = AliflixContentSecondary, style = MaterialTheme.typography.labelMedium)
                if (tracks.isNotEmpty()) DownloadAudioMenu(tracks, audioLabel, !saving) { audioLabel = it }
                if (expanded || exceptions.isNotEmpty() || picker.error != null) LazyColumn(
                    Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    picker.error?.let { message -> item { Text(message, color = AliflixError); TextButton(onClick = { session.load(picker, media, initialEpisode, store, true) }) { Text("Retry") } } }
                    if (expanded) item { TextButton(onClick = { chosen = if (chosen.size == raw.size) emptySet() else raw.map { it.first }.toSet() }, enabled = !saving) { Text(if (chosen.size == raw.size) "Clear" else "Select all") } }
                    items(if (expanded) raw else raw.filter { (_, s) -> exceptions.any { it.second.episodeNumber == s.episodeNumber } }, key = { it.first }) { (rawKey, value) ->
                        val key = session.key(value, language)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (expanded) Checkbox(rawKey in chosen, enabled = !saving && playbackProgressKey(value) !in blocked,
                                onCheckedChange = { chosen = if (it) chosen + rawKey else chosen - rawKey })
                            Column(Modifier.weight(1f)) {
                                Text("E${value.episodeNumber} · ${value.episodeTitle.orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (key in session.errors) Text("Unavailable", color = AliflixError, fontSize = 12.sp)
                                else if (exceptions.any { it.first == key }) Text("Alternative found · ${qualities[key]?.label.orEmpty()}", color = AliflixContentSecondary, fontSize = 12.sp)
                            }
                            if (exceptions.any { it.first == key }) {
                                TextButton(onClick = { session.retryEpisode(key); retry++ }, enabled = !saving && !checking) { Text("Retry") }
                                TextButton(onClick = { chosen = chosen - rawKey }, enabled = !saving) { Text("Remove") }
                            }
                        }
                    }
                }
                if (selected.isNotEmpty()) Box {
                    var menu by remember { mutableStateOf(false) }
                    TextButton(onClick = { menu = true }, enabled = !saving) {
                        Icon(Icons.Rounded.Subtitles, null); Spacer(Modifier.width(8.dp))
                        Text(if (noSubtitles) "Subtitles: None" else "Subtitles: ${SubtitleLanguage.entries.firstOrNull { it.code == language }?.displayName ?: language}")
                        Icon(Icons.Rounded.ExpandMore, null)
                    }
                    DropdownMenu(menu, { menu = false }, modifier = Modifier.heightIn(max = 280.dp), containerColor = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated)) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { noSubtitles = true; menu = false })
                        SubtitleLanguage.entries.forEach { lang -> DropdownMenuItem(text = { Text(lang.displayName) }, onClick = { language = lang.code; noSubtitles = false; menu = false }) }
                    }
                }
                anchor?.let { session.errors[it.first]?.takeIf { discovery?.choices.isNullOrEmpty() } }?.let { message ->
                    Text(message, color = AliflixError); TextButton(onClick = { discoveryRetry++ }, enabled = !saving) { Text("Retry") }
                }
                error?.let { Text(it, color = AliflixError, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
                Button(enabled = ready && !saving, shape = AliflixCorners.Card, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = {
                    saving = true; error = null
                    val selectedItems = selected.map { (key, _) -> session.prepared.getValue(key) to requireNotNull(qualities[key]) }
                    val requestedLanguage = if (noSubtitles) "" else language
                    if (store.requestNotifications && Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
                    session.scope.launch {
                        try {
                            val requests = coroutineScope {
                                val slots = Semaphore(3)
                                selectedItems.map { (item, quality) -> async { slots.withPermit {
                                    val audioTracks = item.audioTracksFor(quality)
                                    val index = when (audioLabel) {
                                        "Default" -> null
                                        "All tracks" -> -1
                                        else -> audioTracks.firstOrNull { it.label == audioLabel }?.index ?: error("Selected audio is unavailable.")
                                    }
                                    item.downloadRequest(quality, requestedLanguage, prefs.autoDisplaySubtitles, index)
                                } } }.awaitAll()
                            }
                            val latestBlocked = store.blockedIds()
                            val requestsToQueue = requests.filter { it.id !in latestBlocked }.distinctBy { it.id }
                            if (requestsToQueue.isNotEmpty()) store.enqueue(requestsToQueue)
                            dismiss()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "Download could not start. Check audio/subtitles and try again." }
                        finally { saving = false }
                    }
                }) {
                    if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (saving) "Starting downloads" else if (selected.size > 1) "Download ${selected.size} episodes" else "Download")
                }
            }
        }
    }
}

@Composable private fun DownloadQualitySkeleton() {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "quality skeleton")
    val alpha by pulse.animateFloat(initialValue = .35f, targetValue = .7f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(850), androidx.compose.animation.core.RepeatMode.Reverse), label = "skeleton pulse")
    AliflixSurface(shape = AliflixCorners.Card) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.width(88.dp).height(10.dp).clip(AliflixCorners.Small).background(AliflixContentSecondary.copy(alpha = alpha)))
                Box(Modifier.width(44.dp).height(7.dp).clip(AliflixCorners.Small).background(AliflixContentSecondary.copy(alpha = alpha * .6f)))
            }
            Box(Modifier.width(54.dp).height(8.dp).clip(AliflixCorners.Small).background(AliflixContentSecondary.copy(alpha = alpha * .6f)))
        }
    }
}

@Composable internal fun DownloadsSection() {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val store = rememberDownloads()
    val entries by store.entries.collectAsState()
    val message by store.message.collectAsState()
    val activity = LocalActivity.current as ComponentActivity
    var deleting by remember { mutableStateOf<SavedDownload?>(null) }
    var retrying by remember { mutableStateOf<SavedDownload?>(null) }
    retrying?.let { saved ->
        val s = saved.selection
        DownloadPicker(s.media, if (s.media.type == MediaType.TV) Episode(s.seasonNumber ?: 1, s.episodeNumber ?: 1, s.episodeTitle.orEmpty()) else null) { retrying = null }
    }
    deleting?.let { saved ->
        Dialog(onDismissRequest = { deleting = null }) {
            AliflixSheet() {
                Column(Modifier.downloadAtmosphere().padding(AliflixSpacing.Large), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        coil.compose.AsyncImage(saved.selection.media.posterUrl, null, Modifier.size(48.dp, 72.dp).clip(AliflixCorners.Small), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Delete download?", style = MaterialTheme.typography.titleLarge)
                            Text(saved.selection.media.title, style = MaterialTheme.typography.bodyMedium, color = AliflixContentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { deleting = null }, modifier = Modifier.weight(1f).height(48.dp), shape = AliflixCorners.Card, border = null) { Text("Cancel") }
                        Button(onClick = { store.remove(saved.id); deleting = null }, modifier = Modifier.weight(1f).height(48.dp),
                            shape = AliflixCorners.Card, colors = ButtonDefaults.buttonColors(containerColor = AliflixError)) {
                            Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Delete")
                        }
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        message?.let { Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(it, Modifier.weight(1f), color = AliflixError)
            AliflixIconButton(onClick = { store.message.value = null }) { Icon(Icons.Rounded.Close, "Dismiss") }
        } }
        if (entries.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No downloads", color = AliflixContentSecondary) }
        else LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(AliflixSpacing.Content), verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium)) {
            items(entries, key = { it.id }) { saved ->
                val d = saved.download
                AliflixSurface(shape = AliflixCorners.Panel) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Small)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium)) {
                            coil.compose.AsyncImage(saved.selection.media.posterUrl, null,
                                modifier = Modifier.size(width = 68.dp, height = 102.dp).clip(AliflixCorners.Card),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny)) {
                                Text(saved.selection.media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = AliflixContentPrimary, style = MaterialTheme.typography.titleSmall)
                                if (saved.selection.media.type == MediaType.TV) Text("S${saved.selection.seasonNumber} E${saved.selection.episodeNumber}", color = AliflixContentSecondary)
                                Text("${saved.quality} · ${downloadSize(saved.downloadedBytes)}", color = AliflixContentSecondary)
                            }
                            if (d.state == Download.STATE_COMPLETED) FilledIconButton(modifier = Modifier.size(52.dp), shape = AliflixCorners.Card, colors = IconButtonDefaults.filledIconButtonColors(containerColor = AliflixAccentPrimary, contentColor = Color.White), onClick = {
                                val app = activity.application as AliflixApplication
                                val position = app.playbackProgressStore.progressFor(saved.selection)?.takeIf { it.resumeEligible }?.positionSeconds ?: 0.0
                                (activity.application as com.aliflix.app.AliflixApplication).libraryStore.markPlayed(saved.selection.media)
                                NativePlaybackLauncher.launch(activity, saved.playback.copy(positionMs = (position * 1000).toLong(), playing = true))
                            }) { Icon(Icons.Rounded.PlayArrow, "Play download", modifier = Modifier.size(30.dp)) }
                            else if (d.state !in setOf(Download.STATE_REMOVING, Download.STATE_RESTARTING)) DownloadButton(
                                saved.selection.media, if (saved.selection.media.type == MediaType.TV)
                                    Episode(saved.selection.seasonNumber ?: 1, saved.selection.episodeNumber ?: 1, saved.selection.episodeTitle.orEmpty()) else null, compact = true)
                            AliflixIconButton(onClick = { deleting = saved }) { Icon(Icons.Rounded.DeleteOutline, "Delete download", tint = AliflixContentSecondary) }
                        }
                        if (d.state == Download.STATE_DOWNLOADING) {
                            val fraction = if (saved.percent >= 0) saved.percent / 100f else if (saved.estimate > 0) saved.downloadedBytes.toFloat() / saved.estimate else -1f
                            val smooth by androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, .99f),
                                animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.LinearEasing), label = "transfer progress")
                            if (fraction >= 0) LinearProgressIndicator(progress = { smooth }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (d.state != Download.STATE_COMPLETED) Text(when (d.state) {
                            Download.STATE_FAILED -> "Retry"
                            Download.STATE_STOPPED -> if (d.stopReason == 2) "Storage full" else "Paused"
                            Download.STATE_QUEUED -> if (store.manager.notMetRequirements != 0) "Waiting for connection" else "Queued"
                            Download.STATE_REMOVING -> "Deleting"
                            else -> "Downloading"
                        }, color = AliflixContentSecondary)
                    }
                }
            }
        }
    }
}

@Composable internal fun DownloadSettings() {
    val store = rememberDownloads()
    val entries by store.entries.collectAsState()
    var preferred by remember { mutableIntStateOf(store.preferredHeight) }
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Small)) {
        Text("Downloads", style = MaterialTheme.typography.labelMedium, color = AliflixContentSecondary)
        AliflixSurface(shape = AliflixCorners.Card,
            level = AliflixSurfaceLevel.Content,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Small)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Preferred quality", Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { menu = true }) { Text("${preferred}p ▾") }
                        DropdownMenu(menu, { menu = false },
        shape = AliflixCorners.Chrome,
        containerColor = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated),
        tonalElevation = AliflixElevation.None,
        shadowElevation = AliflixElevation.None,
                        ) { listOf(360, 480, 720, 1080, 2160).forEach {
                            DropdownMenuItem(text = { Text("${it}p") }, onClick = {
                                preferred = it; store.preferences.edit().putInt("quality", it).apply(); menu = false
                            })
                        } }
                    }
                }
                HorizontalDivider(color = AliflixBorderSubtle)
                DownloadStorageLimit(store, remember(entries) { store.cache.cacheSpace })
            }
        }
    }
}

internal fun Modifier.downloadAtmosphere(): Modifier = background(
    Brush.linearGradient(listOf(AliflixAtmosphere.Start, AliflixAtmosphere.Middle, AliflixAtmosphere.End))
).drawBehind {
    drawCircle(Brush.radialGradient(listOf(AliflixAtmosphere.Glow, Color.Transparent),
        center = androidx.compose.ui.geometry.Offset(size.width, 0f), radius = size.width * .8f),
        radius = size.width * .8f, center = androidx.compose.ui.geometry.Offset(size.width, 0f))
    repeat(3) { index ->
        drawArc(AliflixAtmosphere.Arc.copy(alpha = AliflixAtmosphere.ArcAlpha - index * AliflixAtmosphere.ArcStep), 95f, 130f, false,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * .45f + index * 18.dp.toPx(), -size.width * .38f),
            size = androidx.compose.ui.geometry.Size(size.width * .9f, size.width * .9f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(.8.dp.toPx()))
    }
}

@Composable private fun DownloadAudioMenu(tracks: List<DownloadAudioTrack>, name: String, enabled: Boolean,
    modifier: Modifier = Modifier, onChoice: (String) -> Unit) {
    Box(modifier) {
        var menu by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { menu = true }, enabled = enabled && tracks.size > 1,
            shape = AliflixCorners.Card, border = null, colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = AliflixContentSecondary), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Rounded.Audiotrack, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text("Audio: ${if (tracks.size == 1) tracks.single().label else name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tracks.size > 1) Icon(Icons.Rounded.ExpandMore, "Audio tracks", Modifier.size(18.dp))
        }
        DropdownMenu(menu, { menu = false }, shape = AliflixCorners.Chrome,
            containerColor = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated), tonalElevation = AliflixElevation.None, shadowElevation = AliflixElevation.None) {
            (listOf("Default", "All tracks") + tracks.map { it.label }).distinct().forEach { label ->
                DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (name == label) Icon(Icons.Rounded.Check, null) },
                    onClick = { onChoice(label); menu = false })
            }
        }
    }
}
