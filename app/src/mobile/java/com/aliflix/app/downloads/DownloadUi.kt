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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.exoplayer.offline.Download
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

@Composable internal fun DownloadButton(media: Media, episode: Episode? = null, compact: Boolean = false) {
    var open by remember(media.key, episode?.number) { mutableStateOf(false) }
    val store = rememberDownloads()
    val entries by store.entries.collectAsState()
    val selection = PlaybackSelection(media, seasonNumber = episode?.seasonNumber, episodeNumber = episode?.number)
    val saved = entries.firstOrNull { it.id == playbackProgressKey(selection) }
    Surface(onClick = { open = true }, modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(16.dp), color = AliflixSurfaceSecondary,
        border = BorderStroke(1.dp, AliflixBorderSubtle)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (saved?.download?.state == Download.STATE_COMPLETED) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                "Download", tint = AliflixAccentSecondary)
        }
    }
    if (open) DownloadPicker(media, episode) { open = false }
}

@Composable private fun rememberDownloads(): OfflineDownloads {
    val context = LocalContext.current
    return remember(context) { OfflineDownloads.get(context) }
}

@Composable internal fun downloadCount(): Int {
    val entries by rememberDownloads().entries.collectAsState()
    return entries.count { it.download.state != Download.STATE_REMOVING }
}

@Composable private fun DownloadPicker(media: Media, initialEpisode: Episode?, dismiss: () -> Unit) {
    val activity = LocalActivity.current as ComponentActivity
    val store = rememberDownloads()
    val scope = rememberCoroutineScope()
    val prefs = remember { PlaybackProviderRepository(activity).preferences.value }
    val repository = remember { MobileEpisodeRepository(activity, RecommendationAiClient(BuildConfig.RECOMMENDATION_AI_BASE_URL)) }
    val host = remember { FrameLayout(activity) }
    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    var season by remember { mutableIntStateOf(initialEpisode?.seasonNumber ?: 1) }
    var episodes by remember { mutableStateOf(listOfNotNull(initialEpisode)) }
    var chosen by remember { mutableStateOf(if (media.type == MediaType.MOVIE) setOf("movie") else initialEpisode?.let { setOf("${it.seasonNumber}:${it.number}") }.orEmpty()) }
    val prepared = remember { mutableStateMapOf<String, PreparedDownload>() }
    val quality = remember { mutableStateMapOf<String, DownloadQuality>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    var loading by remember { mutableStateOf<String?>(null) }
    var listLoading by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(prefs.preferredSubtitleLanguage.code) }
    var noSubtitles by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var languageMenu by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(media.key) {
        if (media.type == MediaType.TV) try {
            seasons = repository.seasons(media.id)
            if (initialEpisode == null) season = seasons.firstOrNull { it.number > 0 }?.number ?: 1
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
    }
    LaunchedEffect(season) {
        if (media.type == MediaType.TV) {
            listLoading = true
            try { episodes = repository.episodes(media.id, season) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (initialEpisode == null) error = "Episodes unavailable. Retry when connected." }
            finally { listLoading = false }
        }
    }
    val selections = if (media.type == MediaType.MOVIE) listOf("movie" to PlaybackSelection(media)) else episodes.map {
        "${it.seasonNumber}:${it.number}" to PlaybackSelection(media, seasonNumber = it.seasonNumber, episodeNumber = it.number,
            episodeTitle = it.title, availableEpisodes = episodes)
    }
    LaunchedEffect(chosen, language, retry, episodes) {
        loading = "preparing"
        try {
            coroutineScope {
                val semaphore = Semaphore(2)
                selections.filter { it.first in chosen }.map { (key, raw) -> launch {
                    semaphore.withPermit {
                        if (prepared[key]?.playback?.subtitleLanguage == language.lowercase()) return@withPermit
                        errors.remove(key)
                        try {
                            val selection = raw.copy(source = prefs.sourceFor(media))
                            val result = prepareDownload(activity, host, selection, language)
                            prepared[key] = result
                            quality[key] = result.qualities.firstOrNull { it.height == preferredDownloadHeight(result.qualities.map { q -> q.height }, store.preferredHeight) }
                                ?: result.qualities.first()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { errors[key] = failure.message ?: "Video unavailable. Retry." }
                    }
                } }.joinAll()
            }
        } finally { loading = null }
    }
    Dialog(onDismissRequest = { if (!saving) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 650.dp), shape = RoundedCornerShape(24.dp),
            color = AliflixSurfacePrimary, contentColor = AliflixContentPrimary) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Download", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = dismiss, enabled = !saving) { Icon(Icons.Rounded.Close, "Close") }
                }
                Text(media.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // Resolver views stay attached to the Activity while their muted provider frames prepare.
                AndroidView(factory = { host }, modifier = Modifier.size(1.dp))
                if (media.type == MediaType.TV) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var seasonMenu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { seasonMenu = true }, enabled = !saving) { Text("Season $season ▾") }
                            DropdownMenu(seasonMenu, { seasonMenu = false }) { seasons.forEach {
                                DropdownMenuItem(text = { Text(it.title) }, onClick = { season = it.number; chosen = emptySet(); seasonMenu = false })
                            } }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(enabled = !saving && !listLoading, onClick = {
                            chosen = if (chosen.size == selections.size) emptySet() else selections.map { it.first }.toSet()
                        }) { Text(if (chosen.size == selections.size && chosen.isNotEmpty()) "Clear" else "Select all") }
                    }
                }
                if (listLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(selections, key = { it.first }) { (key, selection) ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (media.type == MediaType.TV) Checkbox(key in chosen, enabled = !saving, onCheckedChange = {
                                    chosen = if (it) chosen + key else chosen - key
                                })
                                Text(if (media.type == MediaType.TV) "E${selection.episodeNumber} · ${selection.episodeTitle.orEmpty()}" else "Quality",
                                    Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (key in chosen && loading != null && (prepared[key]?.playback?.subtitleLanguage != language.lowercase()) && key !in errors) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            if (key in chosen) {
                                errors[key]?.let { Text(it, color = AliflixError); TextButton(onClick = { prepared.remove(key); retry++ }) { Text("Retry") } }
                                val item = prepared[key]
                                val selected = quality[key]
                                if (item != null && selected != null) {
                                    var menu by remember { mutableStateOf(false) }
                                    Box {
                                        OutlinedButton(onClick = { menu = true }, enabled = !saving) { Text("${selected.label} · ${selected.sizeLabel} ▾") }
                                        DropdownMenu(menu, { menu = false }) { item.qualities.forEach { q ->
                                            DropdownMenuItem(text = { Text("${q.label} · ${q.sizeLabel}") }, onClick = { quality[key] = q; menu = false })
                                        } }
                                    }
                                }
                            }
                        }
                    }
                }
                if (chosen.any { prepared[it]?.hasOriginalSubtitles == false }) Box {
                    TextButton(onClick = { languageMenu = true }, enabled = !saving) {
                        Icon(Icons.Rounded.Subtitles, null); Spacer(Modifier.width(8.dp))
                        Text(if (noSubtitles) "None ▾" else "${SubtitleLanguage.entries.firstOrNull { it.code == language }?.displayName ?: language} ▾")
                    }
                    DropdownMenu(languageMenu, { languageMenu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { noSubtitles = true; languageMenu = false })
                        SubtitleLanguage.entries.forEach { lang -> DropdownMenuItem(text = { Text(lang.displayName) }, onClick = {
                            language = lang.code; noSubtitles = false; languageMenu = false
                        }) }
                    }
                }
                error?.let { Text(it, color = AliflixError) }
                val ready = chosen.isNotEmpty() && loading == null && chosen.all { it in prepared && it in quality && it !in errors }
                Button(enabled = ready && !saving, modifier = Modifier.fillMaxWidth(), onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
                    saving = true; error = null
                    scope.launch {
                        try {
                            val requests = coroutineScope {
                                val semaphore = Semaphore(3)
                                chosen.map { key -> async {
                                    semaphore.withPermit { prepared.getValue(key).downloadRequest(quality.getValue(key),
                                        if (noSubtitles) "" else language, prefs.autoDisplaySubtitles) }
                                } }.awaitAll()
                            }
                            store.enqueue(requests); dismiss()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Download could not start." }
                        finally { saving = false }
                    }
                }) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(if (chosen.size > 1) "Download ${chosen.size} episodes" else "Download")
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
