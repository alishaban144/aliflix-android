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
    }, enabled = enabled, modifier = Modifier.size(48.dp).semantics(mergeDescendants = true) {
        contentDescription = "$action, $title"
        stateDescription = status
        role = Role.Button
        if (active) progressBarRangeInfo = if (fraction != null && !queued) ProgressBarRangeInfo(fraction, 0f..1f)
            else ProgressBarRangeInfo.Indeterminate
    }, shape = if (compact) AliflixCorners.Small else AliflixCorners.Card,
        level = AliflixSurfaceLevel.Content,
    ) {
        Box(contentAlignment = Alignment.Center) {
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
            }, null, modifier = Modifier.size(if (compact || active) 20.dp else 24.dp), tint = AliflixAccentSecondary)
            }
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
    val entries by store.entries.collectAsState()
    val prefs = remember(activity, media.key) { PlaybackProviderRepository(activity).preferences.value }
    val session = remember(activity) { DownloadSession.get(activity) }
    val scope = session.scope
    val picker = remember(session, media.key, initialEpisode) {
        session.pickers.getOrPut("${media.key}:${initialEpisode?.seasonNumber}:${initialEpisode?.number}") {
            DownloadPickerState(media, initialEpisode, prefs.preferredSubtitleLanguage.code)
        }
    }
    val seasons = picker.seasons
    var season by picker::season
    val episodes = picker.episodes
    var chosen by picker::chosen
    val prepared = session.prepared
    val quality = remember(media.key, season) { mutableStateMapOf<String, DownloadQuality>() }
    var sharedHeight by picker::height
    var language by picker::language
    val errors = session.errors
    val listLoading = picker.loading
    val listError = picker.error
    var noSubtitles by picker::noSubtitles
    var saving by picker::saving
    var error by remember(media.key, season, language, noSubtitles) { mutableStateOf<String?>(null) }
    var retry by remember(media.key, season) { mutableIntStateOf(0) }
    var languageMenu by remember(media.key) { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(media.key, season) { if (activity.hasInternetConnection()) session.load(picker, media, initialEpisode, store) else error = "Connect to the internet and try again" }
    val rawSelections = if (media.type == MediaType.MOVIE) listOf("movie" to PlaybackSelection(media)) else episodes.map {
        "${it.seasonNumber}:${it.number}" to PlaybackSelection(media, seasonNumber = it.seasonNumber, episodeNumber = it.number,
            episodeTitle = it.title, availableEpisodes = episodes)
    }.distinctBy { it.first }
    val selections = rawSelections.map { (_, selection) -> session.key(selection, language) to selection }
    val chosenKeys = rawSelections.filter { it.first in chosen }.map { session.key(it.second, language) }.toSet()
    val savedById = entries.associateBy { it.id }
    val eligible = selections.filter { (_, selection) ->
        val saved = savedById[playbackProgressKey(selection)]
        saved == null || saved.download.state == Download.STATE_FAILED
    }.map { it.first }.toSet()
    val selectedKeys = chosenKeys.intersect(eligible)

    val preparing = selectedKeys.any { it in session.pending }
    fun validated(key: String): Boolean = prepared[key]?.let { it.qualities.isNotEmpty() && quality[key] in it.qualities && key !in errors } == true
    LaunchedEffect(media.key, season, language, retry, episodes, selectedKeys) {
        if (!activity.hasInternetConnection()) { error = "Connect to the internet and try again"; return@LaunchedEffect }
        session.prepare(store, selections.filter { it.first in selectedKeys }.map { (key, raw) -> key to raw.copy(source = prefs.sourceFor(media)) }, language)
    }
    LaunchedEffect(prepared.toMap(), sharedHeight, selectedKeys) {
        selectedKeys.forEach { key -> prepared[key]?.let { item ->
            val target = sharedHeight ?: store.preferredHeight
            if (quality[key] !in item.qualities || sharedHeight != null) {
                quality[key] = item.qualities.firstOrNull { it.height == preferredDownloadHeight(item.qualities.map { q -> q.height }, target) } ?: item.qualities.first()
            }
        } }
    }
    val allValidated = selectedKeys.isNotEmpty() && selectedKeys.all { validated(it) }
    val ready = allValidated && !preparing
    val total = selectedKeys.mapNotNull { quality[it] }.totalSizeLabel()
    val commonHeights = if (selectedKeys.isNotEmpty() && selectedKeys.all { prepared[it] != null })
        selectedKeys.map { key -> prepared.getValue(key).qualities.map { it.height }.toSet() }
            .reduce { a, b -> a.intersect(b) }.sortedDescending() else emptyList()
    val selectedLabels = selectedKeys.mapNotNull { quality[it]?.label }.distinct()
    val qualityLabel = selectedLabels.singleOrNull() ?: if (selectedLabels.isEmpty()) "Quality" else "Mixed"
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        AliflixSheet(Modifier.fillMaxWidth().windowInsetsPadding(AliflixInsets.Safe).padding(horizontal = AliflixSpacing.Content, vertical = AliflixSpacing.Large).heightIn(max = 700.dp), contentColor = AliflixContentPrimary) {
            Column(Modifier.downloadAtmosphere().padding(AliflixSpacing.Panel), verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Download", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    AliflixIconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, "Close") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Content)) {
                    coil.compose.AsyncImage(media.posterUrl, null, Modifier.size(64.dp, 96.dp).clip(AliflixCorners.Card),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Small)) {
                        Text(media.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(media.year, style = MaterialTheme.typography.labelMedium, color = AliflixContentSecondary)
                    }
                }

                if (media.type == MediaType.TV && initialEpisode == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.lazy.LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny),
        ) {
                            items(seasons.size) { index ->
                                val tab = seasons[index]
                                TextButton(
                                    onClick = { chosen = emptySet(); season = tab.number },
                                    enabled = !saving && !listLoading,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = if (season == tab.number) AliflixContentPrimary else AliflixContentSecondary,
                                        containerColor = if (season == tab.number) AliflixSurfaceRaised else Color.Transparent,
                                    ),
                                ) { Text("S${tab.number}", fontWeight = if (season == tab.number) FontWeight.Bold else FontWeight.Normal) }
                            }
                        }
                        TextButton(enabled = !saving && !listLoading && eligible.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp), onClick = {
                            chosen = if (selectedKeys == eligible) emptySet() else rawSelections.map { it.first }.toSet()
                        }) { Text(if (selectedKeys == eligible && selectedKeys.isNotEmpty()) "Clear" else "Select all") }
                    }
                }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { menu = true }, enabled = !saving && commonHeights.isNotEmpty(), shape = AliflixCorners.Card, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), border = null) {
                        Icon(Icons.Rounded.HighQuality, null); Spacer(Modifier.width(10.dp))
                        Text("$qualityLabel ▾")
                        Spacer(Modifier.weight(1f)); Text(total, style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(menu, { menu = false },
        shape = AliflixCorners.Chrome,
        containerColor = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated),
        tonalElevation = AliflixElevation.None,
        shadowElevation = AliflixElevation.None,
                    ) {
                        commonHeights.forEach { height ->
                                DropdownMenuItem(text = { Text(if (height > 0) "${height}p" else "Original") }, onClick = { sharedHeight = height; menu = false },
                                    trailingIcon = { if ((sharedHeight ?: store.preferredHeight) == height) Icon(Icons.Rounded.Check, null) })
                            }
                    }
                }
                if (listLoading) CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = "Loading episodes" }, strokeWidth = 2.dp)
                if (media.type == MediaType.TV && preparing && selectedKeys.isNotEmpty()) {
                    val finished = selectedKeys.count { validated(it) || it in errors }
                    Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                        Text("$finished / ${selectedKeys.size}", color = AliflixContentSecondary)
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Small)) {
                    listError?.let { message -> item {
                        Text(message, color = AliflixError)
                        TextButton(onClick = { session.load(picker, media, initialEpisode, store, force = true) }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
                    } }
                    if (!listLoading && selections.isEmpty() && listError == null) item {
                        Text("No episodes", color = AliflixContentSecondary)
                    }
                    items(selections, key = { it.first }) { (key, selection) ->
                        val saved = savedById[playbackProgressKey(selection)]
                        val title = if (media.type == MediaType.TV) "E${selection.episodeNumber} · ${selection.episodeTitle.orEmpty()}" else media.title
                        val checked = key in selectedKeys
                        AliflixSurface(shape = AliflixCorners.Card,
                            level = if (checked) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content,
                        ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny)) {
                            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(
                                if (media.type == MediaType.TV && initialEpisode == null) Modifier.toggleable(value = checked,
                                    enabled = !saving && key in eligible, role = Role.Checkbox,
                                    onValueChange = { val rawKey = "${selection.seasonNumber}:${selection.episodeNumber}"; chosen = if (it) chosen + rawKey else chosen - rawKey }) else Modifier
                            ), verticalAlignment = Alignment.CenterVertically) {
                                if (media.type == MediaType.TV && initialEpisode == null) {
                                    val checkColor by androidx.compose.animation.animateColorAsState(
                                        if (checked) AliflixAccentPrimary else AliflixSurfaceSecondary, label = "selection color")
                                    Box(Modifier.padding(horizontal = 12.dp).size(24.dp).clip(AliflixCorners.Small)
                                        .background(checkColor),
                                        contentAlignment = Alignment.Center) {
                                        androidx.compose.animation.AnimatedVisibility(checked,
                                            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = .5f),
                                            exit = androidx.compose.animation.fadeOut()) {
                                            Icon(Icons.Rounded.Check, null, Modifier.size(17.dp), tint = Color.White)
                                        }
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    val episode = episodes.firstOrNull { it.seasonNumber == selection.seasonNumber && it.number == selection.episodeNumber }
                                    (if (media.type == MediaType.MOVIE) media.imdbRating else episode?.imdbRating)?.takeIf { it > 0 }?.let { rating ->
                                        Text("IMDb ${"%.1f".format(java.util.Locale.ROOT, rating)}", color = Color(0xFFF5C518), style = MaterialTheme.typography.labelSmall)
                                    }
                                    saved?.let { Text(it.statusLabel(), color = AliflixContentSecondary, style = MaterialTheme.typography.bodySmall) }
                                }
                                if (checked && preparing && !validated(key) && key !in errors) CircularProgressIndicator(
                                    Modifier.size(20.dp).semantics { contentDescription = "Preparing $title" }, strokeWidth = 2.dp)
                                else if (saved != null) DownloadButton(media,
                                    if (media.type == MediaType.TV) Episode(selection.seasonNumber ?: 1, selection.episodeNumber ?: 1, selection.episodeTitle.orEmpty()) else null,
                                    compact = true, dependencies = store)
                            }
                            if (checked) {
                                errors[key]?.let {
                                    Text(it, color = AliflixError)
                                    TextButton(onClick = { errors.remove(key); retry++ }, enabled = !saving && !preparing,
                                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Retry preparation, $title" }) { Text("Retry") }
                                }
                                quality[key]?.let { selected ->
                                    Box {
                                        var qualityMenu by remember(key) { mutableStateOf(false) }
                                        TextButton(onClick = { qualityMenu = true }, enabled = !saving, shape = AliflixCorners.Small, colors = ButtonDefaults.textButtonColors(containerColor = AliflixAccentPrimary.copy(alpha = .14f))) {
                                            Text("${selected.label} · ${selected.sizeLabel}", color = AliflixAccentSecondary)
                                            Icon(Icons.Rounded.ExpandMore, "Quality", Modifier.size(18.dp))
                                        }
                                        DropdownMenu(qualityMenu, { qualityMenu = false },
        shape = AliflixCorners.Chrome,
        containerColor = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated),
        tonalElevation = AliflixElevation.None,
        shadowElevation = AliflixElevation.None,
                                        ) {
                                            prepared[key]?.qualities.orEmpty().forEach { option ->
                                                DropdownMenuItem(text = { Text("${option.label} · ${option.sizeLabel}") },
                                                    trailingIcon = { if (selected == option) Icon(Icons.Rounded.Check, null) },
                                                    onClick = { sharedHeight = null; quality[key] = option; qualityMenu = false })
                                            }
                                        }
                                    }
                                }

                            }
                        }
                    }

                }
                }
                if (selectedKeys.isNotEmpty()) Box {
                    TextButton(onClick = { languageMenu = true }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Subtitles, null); Spacer(Modifier.width(8.dp))
                        Text(if (noSubtitles) "Subtitles: None ▾" else "Subtitles: ${SubtitleLanguage.entries.firstOrNull { it.code == language }?.displayName ?: language} ▾")
                    }
                    DropdownMenu(languageMenu, { languageMenu = false }, modifier = Modifier.heightIn(max = 280.dp),
        shape = AliflixCorners.Chrome,
        containerColor = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated),
        tonalElevation = AliflixElevation.None,
        shadowElevation = AliflixElevation.None,
                    ) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { noSubtitles = true; languageMenu = false })
                        SubtitleLanguage.entries.forEach { lang -> DropdownMenuItem(text = { Text(lang.displayName) }, onClick = {
                            language = lang.code; noSubtitles = false; languageMenu = false
                        }) }
                    }
                }
                error?.let { Text(it, color = AliflixError, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                Button(enabled = ready && !saving, shape = AliflixCorners.Card, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
                    if (!saving && ready) {
                        saving = true; error = null
                        val selected = selectedKeys.map { prepared.getValue(it) to quality.getValue(it) }
                        val requestedLanguage = if (noSubtitles) "" else language
                        if (store.requestNotifications && Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
                        scope.launch {
                            try {
                                val blocked = store.blockedIds()
                                val requests = coroutineScope {
                                    val semaphore = Semaphore(3)
                                    selected.filter { playbackProgressKey(it.first.selection) !in blocked }.map { (item, selectedQuality) -> async {
                                        semaphore.withPermit { item.downloadRequest(selectedQuality, requestedLanguage, prefs.autoDisplaySubtitles) }
                                    } }.awaitAll()
                                }
                                val latestBlocked = store.blockedIds()
                                val pending = requests.filter { it.id !in latestBlocked }.distinctBy { it.id }
                                if (pending.isNotEmpty()) store.enqueue(pending)
                                dismiss()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "Download could not start." }
                            finally { saving = false }
                        }
                    }
                }) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Starting downloads")
                    } else Text(if (selectedKeys.size > 1) "Download ${selectedKeys.size} episodes" else "Download")
                }
            }
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
    var limit by remember { mutableFloatStateOf((store.limitBytes / DOWNLOAD_GB).toFloat()) }
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
                Text("Storage limit · ${limit.toInt()} GB")
                Slider(value = limit, onValueChange = { limit = it }, valueRange = 1f..200f, steps = 198,
                    onValueChangeFinished = { store.preferences.edit().putInt("limitGb", limit.toInt()).apply() })
                Text("${downloadSize(entries.sumOf { it.downloadedBytes })} used", color = AliflixContentSecondary)
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
