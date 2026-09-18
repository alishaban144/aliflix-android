@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.downloads

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    download.percentDownloaded >= 0 -> download.percentDownloaded / 100f
    estimate > 0 -> download.bytesDownloaded.toFloat() / estimate
    else -> null
}?.coerceIn(0f, 1f)

private fun SavedDownload.statusLabel(): String = when (download.state) {
    Download.STATE_COMPLETED -> "Downloaded"
    Download.STATE_STOPPED -> if (download.stopReason == 2) "Paused · Check storage limit" else "Paused"
    Download.STATE_QUEUED -> "Queued · Waiting to download"
    Download.STATE_REMOVING -> "Deleting"
    Download.STATE_RESTARTING -> "Restarting"
    Download.STATE_FAILED -> "Interrupted · Retry download"
    else -> "Downloading"
}

private fun List<DownloadQuality>.totalSizeLabel(): String = when {
    isEmpty() || any { it.bytes <= 0 } -> "Total size unavailable"
    any { it.estimated } -> "≈ ${downloadSize(sumOf { it.bytes })} total"
    else -> "${downloadSize(sumOf { it.bytes })} total"
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
    val selection = PlaybackSelection(media, seasonNumber = episode?.seasonNumber, episodeNumber = episode?.number)
    val id = playbackProgressKey(selection)
    var open by remember(id) { mutableStateOf(false) }
    var actionPending by remember(id) { mutableStateOf(false) }
    val store = dependencies ?: rememberDownloadUiDependencies()
    val entries by store.entries.collectAsState()
    val saved = entries.firstOrNull { it.id == id }
    val state = saved?.download?.state
    val active = state in setOf(Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_STOPPED, Download.STATE_RESTARTING)
    val fraction = saved?.progressFraction()
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
    val estimated = saved != null && saved.download.percentDownloaded < 0
    val status = saved?.statusLabel().orEmpty() + if (active && !queued && fraction != null) {
        " · ${if (estimated) "approximately " else ""}${(fraction * 100).toInt()} percent"
    } else ""
    LaunchedEffect(state) { if (active || state == Download.STATE_COMPLETED) open = false }
    LaunchedEffect(actionPending, state) {
        if (actionPending) { delay(1_000); actionPending = false }
    }
    Surface(onClick = {
        if (!actionPending) {
            val current = store.entries.value.firstOrNull { it.id == id }
            when (current?.download?.state) {
                Download.STATE_STOPPED -> { actionPending = true; store.resume(current) }
                Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> { actionPending = true; store.pause(id) }
                Download.STATE_COMPLETED, Download.STATE_REMOVING, Download.STATE_RESTARTING -> Unit
                else -> open = true
            }
        }
    }, enabled = enabled, modifier = Modifier.size(48.dp).semantics(mergeDescendants = true) {
        contentDescription = "$action, $title"
        stateDescription = status
        role = Role.Button
        if (active) progressBarRangeInfo = if (fraction != null && !queued) ProgressBarRangeInfo(fraction, 0f..1f)
            else ProgressBarRangeInfo.Indeterminate
    }, shape = RoundedCornerShape(if (compact) 14.dp else 16.dp), color = AliflixSurfaceSecondary,
        border = BorderStroke(1.dp, AliflixBorderSubtle)) {
        Box(contentAlignment = Alignment.Center) {
            if (active) {
                val ring = Modifier.size(38.dp).clearAndSetSemantics { }
                if (!queued && fraction != null) CircularProgressIndicator(
                    progress = { fraction }, modifier = ring, strokeWidth = 2.dp,
                    color = AliflixAccentSecondary, trackColor = AliflixBorderSubtle)
                else CircularProgressIndicator(modifier = ring, strokeWidth = 2.dp, color = AliflixAccentSecondary)
            }
            Icon(when {
                state == Download.STATE_COMPLETED -> Icons.Rounded.DownloadDone
                paused -> Icons.Rounded.Pause
                active -> Icons.Rounded.Pause
                state == Download.STATE_FAILED -> Icons.Rounded.Refresh
                else -> Icons.Rounded.Download
            }, null, modifier = Modifier.size(if (compact || active) 20.dp else 24.dp), tint = AliflixAccentSecondary)
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
    val scope = rememberCoroutineScope()
    val prefs = remember(activity, media.key) { PlaybackProviderRepository(activity).preferences.value }
    val host = remember(activity) { FrameLayout(activity) }
    var seasons by remember(media.key) { mutableStateOf<List<Season>>(emptyList()) }
    var season by remember(media.key) { mutableIntStateOf(initialEpisode?.seasonNumber ?: 1) }
    var episodes by remember(media.key, season) { mutableStateOf(listOfNotNull(initialEpisode?.takeIf { it.seasonNumber == season })) }
    var chosen by remember(media.key) {
        mutableStateOf(if (media.type == MediaType.MOVIE) setOf("movie") else
            initialEpisode?.let { setOf("${it.seasonNumber}:${it.number}") }.orEmpty())
    }
    val prepared = remember(media.key, season) { mutableStateMapOf<String, PreparedDownload>() }
    val quality = remember(media.key, season) { mutableStateMapOf<String, DownloadQuality>() }
    val preferred = remember(media.key, season) { mutableStateMapOf<String, Int>() }
    var sharedHeight by remember(media.key, season) { mutableStateOf<Int?>(null) }
    var language by remember(media.key) { mutableStateOf(prefs.preferredSubtitleLanguage.code) }
    val errors = remember(media.key, season, language) { mutableStateMapOf<String, String>() }
    var listLoading by remember(media.key, season) { mutableStateOf(media.type == MediaType.TV) }
    var listError by remember(media.key, season) { mutableStateOf<String?>(null) }
    var listRetry by remember(media.key, season) { mutableIntStateOf(0) }
    var noSubtitles by remember(media.key) { mutableStateOf(false) }
    var saving by remember(media.key) { mutableStateOf(false) }
    var error by remember(media.key, season, language, noSubtitles) { mutableStateOf<String?>(null) }
    var retry by remember(media.key, season) { mutableIntStateOf(0) }
    var languageMenu by remember(media.key) { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(media.key) {
        if (media.type == MediaType.TV) try {
            seasons = store.seasons(media)
            if (initialEpisode == null) season = seasons.firstOrNull { it.number > 0 }?.number ?: 1
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
    }
    LaunchedEffect(media.key, season, listRetry) {
        if (media.type == MediaType.TV) {
            listLoading = true
            listError = null
            try { episodes = store.episodes(media, season).filter { it.seasonNumber == season } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { listError = "Episodes unavailable. Retry when connected." }
            finally { listLoading = false }
        }
    }
    val selections = if (media.type == MediaType.MOVIE) listOf("movie" to PlaybackSelection(media)) else episodes.map {
        "${it.seasonNumber}:${it.number}" to PlaybackSelection(media, seasonNumber = it.seasonNumber, episodeNumber = it.number,
            episodeTitle = it.title, availableEpisodes = episodes)
    }.distinctBy { it.first }
    val savedById = entries.associateBy { it.id }
    val eligible = selections.filter { (_, selection) ->
        val saved = savedById[playbackProgressKey(selection)]
        saved == null || saved.download.state == Download.STATE_FAILED
    }.map { it.first }.toSet()
    val selectedKeys = chosen.intersect(eligible)
    LaunchedEffect(eligible, chosen) { if (chosen != selectedKeys) chosen = selectedKeys }
    var preparing by remember(media.key, season, selectedKeys, language, retry, episodes) {
        mutableStateOf(selectedKeys.isNotEmpty())
    }
    fun validated(key: String): Boolean {
        val item = prepared[key] ?: return false
        return item.playback.subtitleLanguage == language.lowercase() && item.qualities.isNotEmpty() &&
            quality[key] in item.qualities && key !in errors
    }
    LaunchedEffect(media.key, season, selectedKeys, language, retry, episodes) {
        val batch = selections.filter { it.first in selectedKeys && it.first !in errors }
            .map { (key, raw) -> key to raw.copy(source = prefs.sourceFor(media)) }
        val context = currentCoroutineContext()
        try {
            if (batch.isNotEmpty()) store.prepare(activity, host, batch, language, prepared.toMap(),
                onPrepared = { key, result ->
                    if (context.isActive) {
                        if (result.qualities.isEmpty()) errors[key] = "No downloadable qualities found. Retry this item."
                        else {
                            val height = preferred[key] ?: sharedHeight ?: quality[key]?.height ?: store.preferredHeight
                            val selected = result.qualities.firstOrNull { it.height == height }
                                ?: result.qualities.firstOrNull { it.height == preferredDownloadHeight(result.qualities.map { q -> q.height }, height) }
                                ?: result.qualities.first()
                            prepared[key] = result
                            quality[key] = selected
                            errors.remove(key)
                        }
                    }
                }, onError = { key, message -> if (context.isActive) errors[key] = message })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            batch.filterNot { validated(it.first) }.forEach { errors[it.first] = failure.message ?: "Video unavailable. Retry this item." }
        } finally { preparing = false }
    }
    val allValidated = selectedKeys.isNotEmpty() && selectedKeys.all { validated(it) }
    val ready = allValidated && !preparing && !listLoading
    val heightSets = if (ready) selectedKeys.map { prepared.getValue(it).qualities.map { q -> q.height }.toSet() } else emptyList()
    val shared = selectedKeys.size > 1 && ready && heightSets.distinct().size == 1
    val total = selectedKeys.mapNotNull { quality[it] }.totalSizeLabel()
    Dialog(onDismissRequest = { if (!saving) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 650.dp), shape = RoundedCornerShape(24.dp),
            color = AliflixSurfacePrimary, contentColor = AliflixContentPrimary) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Download", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = dismiss, enabled = !saving) { Icon(Icons.Rounded.Close, "Close") }
                }
                Text(media.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                AndroidView(factory = { host }, modifier = Modifier.size(1.dp).clearAndSetSemantics { })
                if (media.type == MediaType.TV) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var seasonMenu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { seasonMenu = true }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Season $season ▾") }
                            DropdownMenu(seasonMenu, { seasonMenu = false }) { seasons.forEach {
                                DropdownMenuItem(text = { Text(it.title) }, onClick = { chosen = emptySet(); season = it.number; seasonMenu = false })
                            } }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(enabled = !saving && !listLoading && eligible.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp), onClick = {
                            chosen = if (selectedKeys == eligible) emptySet() else eligible
                        }) { Text(if (selectedKeys == eligible && selectedKeys.isNotEmpty()) "Clear" else "Select all") }
                    }
                }
                if (listLoading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading episodes" })
                if (preparing && selectedKeys.isNotEmpty()) {
                    val finished = selectedKeys.count { validated(it) || it in errors }
                    Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                        Text("Preparing $finished of ${selectedKeys.size}", color = AliflixContentSecondary)
                        LinearProgressIndicator(progress = { finished.toFloat() / selectedKeys.size }, modifier = Modifier.fillMaxWidth())
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listError?.let { message -> item {
                        Text(message, color = AliflixError)
                        TextButton(onClick = { listRetry++ }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry loading episodes") }
                    } }
                    if (!listLoading && selections.isEmpty() && listError == null) item {
                        Text("No episodes available in this season.", color = AliflixContentSecondary)
                    }
                    if (ready && selectedKeys.size > 1 && !shared) item {
                        Text("Available qualities differ by episode. Choose quality for each episode below.", color = AliflixContentSecondary)
                    }
                    if (shared) item {
                        val selectedHeights = selectedKeys.map { quality.getValue(it).height }.distinct()
                        val label = if (selectedHeights.size == 1) quality.getValue(selectedKeys.first()).label else "Mixed qualities"
                        var menu by remember(selectedKeys, language) { mutableStateOf(false) }
                        Text("Quality for all ${selectedKeys.size} episodes", style = MaterialTheme.typography.labelLarge)
                        Box {
                            OutlinedButton(onClick = { menu = true }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("$label · $total ▾")
                            }
                            DropdownMenu(menu, { menu = false }) {
                                prepared.getValue(selectedKeys.first()).qualities.forEach { option ->
                                    val ownQualities = selectedKeys.associateWith { key -> prepared.getValue(key).qualities.first { it.height == option.height } }
                                    DropdownMenuItem(text = { Text("${option.label} · ${ownQualities.values.toList().totalSizeLabel()}") }, onClick = {
                                        sharedHeight = option.height
                                        ownQualities.forEach { (key, ownQuality) -> preferred[key] = ownQuality.height; quality[key] = ownQuality }
                                        menu = false
                                    }, trailingIcon = { if (selectedHeights.singleOrNull() == option.height) Icon(Icons.Rounded.Check, "Selected") })
                                }
                            }
                        }
                        if (selectedHeights.size > 1) Text("Your episode choices are kept. Choose a quality above to apply it to all selected episodes.", color = AliflixContentSecondary)
                    }
                    items(selections, key = { it.first }) { (key, selection) ->
                        val saved = savedById[playbackProgressKey(selection)]
                        val title = if (media.type == MediaType.TV) "E${selection.episodeNumber} · ${selection.episodeTitle.orEmpty()}" else media.title
                        val checked = key in selectedKeys
                        Column {
                            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(
                                if (media.type == MediaType.TV) Modifier.toggleable(value = checked,
                                    enabled = !saving && key in eligible, role = Role.Checkbox,
                                    onValueChange = { chosen = if (it) chosen + key else chosen - key }) else Modifier
                            ), verticalAlignment = Alignment.CenterVertically) {
                                if (media.type == MediaType.TV) Checkbox(checked, enabled = !saving && key in eligible, onCheckedChange = null)
                                Column(Modifier.weight(1f)) {
                                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    saved?.let { Text(it.statusLabel(), color = AliflixContentSecondary, style = MaterialTheme.typography.bodySmall) }
                                }
                                if (checked && preparing && !validated(key) && key !in errors) CircularProgressIndicator(
                                    Modifier.size(20.dp).semantics { contentDescription = "Preparing $title" }, strokeWidth = 2.dp)
                                else if (saved?.download?.state == Download.STATE_COMPLETED) Icon(Icons.Rounded.DownloadDone, null, tint = AliflixAccentSecondary)
                            }
                            if (checked) {
                                errors[key]?.let {
                                    Text(it, color = AliflixError)
                                    TextButton(onClick = { errors.remove(key); retry++ }, enabled = !saving && !preparing,
                                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Retry preparation, $title" }) { Text("Retry") }
                                }
                                if (ready && !shared) {
                                    val item = prepared.getValue(key)
                                    val selected = quality.getValue(key)
                                    var menu by remember(language, item) { mutableStateOf(false) }
                                    Box {
                                        OutlinedButton(onClick = { menu = true }, enabled = !saving,
                                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Quality for $title, ${selected.label}, ${selected.sizeLabel}" }) {
                                            Text("${selected.label} · ${selected.sizeLabel} ▾")
                                        }
                                        DropdownMenu(menu, { menu = false }) { item.qualities.forEach { q ->
                                            DropdownMenuItem(text = { Text("${q.label} · ${q.sizeLabel}") }, onClick = {
                                                preferred[key] = q.height; quality[key] = q; menu = false
                                            }, trailingIcon = { if (q == selected) Icon(Icons.Rounded.Check, "Selected") })
                                        } }
                                    }
                                }
                            }
                        }
                    }
                    if (ready && !shared) item { Text(total, color = AliflixContentSecondary) }
                }
                if (selectedKeys.isNotEmpty()) Box {
                    TextButton(onClick = { languageMenu = true }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Subtitles, null); Spacer(Modifier.width(8.dp))
                        Text(if (noSubtitles) "Subtitles: None ▾" else "Subtitles: ${SubtitleLanguage.entries.firstOrNull { it.code == language }?.displayName ?: language} ▾")
                    }
                    DropdownMenu(languageMenu, { languageMenu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { noSubtitles = true; languageMenu = false })
                        SubtitleLanguage.entries.forEach { lang -> DropdownMenuItem(text = { Text(lang.displayName) }, onClick = {
                            language = lang.code; noSubtitles = false; languageMenu = false
                        }) }
                    }
                }
                error?.let { Text(it, color = AliflixError, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                Button(enabled = ready && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
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
    deleting?.let { saved -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete download?") },
        confirmButton = { TextButton(onClick = { store.remove(saved.id); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    Column(Modifier.fillMaxSize()) {
        message?.let { Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(it, Modifier.weight(1f), color = AliflixError)
            IconButton(onClick = { store.message.value = null }) { Icon(Icons.Rounded.Close, "Dismiss") }
        } }
        if (entries.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No downloads", color = AliflixContentSecondary) }
        else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.id }) { saved ->
                val d = saved.download
                Surface(shape = RoundedCornerShape(18.dp), color = AliflixSurfaceSecondary) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(saved.selection.media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = AliflixContentPrimary)
                                if (saved.selection.media.type == MediaType.TV) Text("S${saved.selection.seasonNumber} E${saved.selection.episodeNumber}", color = AliflixContentSecondary)
                                Text("${saved.quality} · ${downloadSize(d.bytesDownloaded)}", color = AliflixContentSecondary)
                            }
                            if (d.state == Download.STATE_COMPLETED) IconButton(onClick = {
                                val app = activity.application as AliflixApplication
                                val position = app.playbackProgressStore.progressFor(saved.selection)?.takeIf { it.resumeEligible }?.positionSeconds ?: 0.0
                                LibraryStore(activity).markPlayed(saved.selection.media)
                                NativePlaybackLauncher.launch(activity, saved.playback.copy(positionMs = (position * 1000).toLong(), playing = true))
                            }) { Icon(Icons.Rounded.PlayArrow, "Play download", tint = AliflixAccentSecondary) }
                            else if (d.state !in setOf(Download.STATE_REMOVING, Download.STATE_RESTARTING)) IconButton(onClick = {
                                when (d.state) {
                                    Download.STATE_FAILED -> retrying = saved
                                    Download.STATE_STOPPED -> store.resume(saved)
                                    else -> store.pause(saved.id)
                                }
                            }) { Icon(if (d.state in setOf(Download.STATE_STOPPED, Download.STATE_FAILED)) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, "Pause or resume", tint = AliflixAccentSecondary) }
                            IconButton(onClick = { deleting = saved }) { Icon(Icons.Rounded.DeleteOutline, "Delete download", tint = AliflixContentSecondary) }
                        }
                        if (d.state == Download.STATE_DOWNLOADING) {
                            val fraction = if (d.percentDownloaded >= 0) d.percentDownloaded / 100f else if (saved.estimate > 0) d.bytesDownloaded.toFloat() / saved.estimate else -1f
                            if (fraction >= 0) LinearProgressIndicator(progress = { fraction.coerceIn(0f, .99f) }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (d.state != Download.STATE_COMPLETED) Text(when (d.state) {
                            Download.STATE_FAILED -> store.preferences.getString("error:${saved.id}", null) ?: "Interrupted · Retry"
                            Download.STATE_STOPPED -> if (d.stopReason == 2) "Free space or increase the download limit in Settings." else "Paused"
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DOWNLOADS", style = MaterialTheme.typography.labelMedium, color = AliflixContentSecondary)
        Surface(shape = RoundedCornerShape(17.dp), color = AliflixSurfaceSecondary) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Preferred quality", Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { menu = true }) { Text("${preferred}p ▾") }
                        DropdownMenu(menu, { menu = false }) { listOf(360, 480, 720, 1080, 2160).forEach {
                            DropdownMenuItem(text = { Text("${it}p") }, onClick = {
                                preferred = it; store.preferences.edit().putInt("quality", it).apply(); menu = false
                            })
                        } }
                    }
                }
                Text("Storage limit · ${limit.toInt()} GB")
                Slider(value = limit, onValueChange = { limit = it }, valueRange = 1f..500f, steps = 498,
                    onValueChangeFinished = { store.preferences.edit().putInt("limitGb", limit.toInt()).apply() })
                Text("${downloadSize(entries.sumOf { it.download.bytesDownloaded })} used", color = AliflixContentSecondary)
            }
        }
    }
}
