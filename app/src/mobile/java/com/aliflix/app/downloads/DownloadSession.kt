@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.downloads

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.aliflix.app.model.*
import com.aliflix.app.data.*
import kotlinx.coroutines.*

/** Activity-session work survives picker dismissal and navigation, and releases all WebViews on destruction. */
internal class DownloadSession private constructor(private val activity: ComponentActivity) {
    private val ratingsClient = CatalogClient()
    val host = FrameLayout(activity).apply {
        alpha = 0f
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    val pickers = mutableMapOf<String, DownloadPickerState>()
    val scope get() = activity.lifecycleScope
    fun load(state: DownloadPickerState, media: Media, episode: Episode?, store: DownloadUiDependencies, force: Boolean = false) {
        if (!activity.hasInternetConnection()) { state.error = "Connect to the internet and try again"; return }
        if (state.loadedSeason == state.season && !force) return
        val jobKey = "load:${media.key}"
        val requested = state.season
        jobs.remove(jobKey)?.cancel()
        state.loading = true; state.error = null
        jobs[jobKey] = scope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                if (media.type == MediaType.TV && episode == null) {
                    if (state.seasons.isEmpty()) {
                        val seasons = store.seasons(media)
                        ensureActive()
                        if (jobs[jobKey] !== owner) return@launch
                        state.seasons = seasons
                        if (state.loadedSeason == null && seasons.none { it.number == requested })
                            state.season = seasons.firstOrNull { it.number > 0 }?.number ?: 1
                    }
                    val targetSeason = state.season
                    val episodes = store.episodes(media, targetSeason).filter { it.seasonNumber == targetSeason }
                    ensureActive()
                    if (jobs[jobKey] !== owner || state.season != targetSeason) return@launch
                    state.episodes = episodes
                    state.chosen = episodes.map { "${it.seasonNumber}:${it.number}" }.toSet()
                    scope.launch {
                        try {
                            val rated = ratingsClient.mobileEpisodeRatings(media, targetSeason, episodes)
                            if (state.season == targetSeason) state.episodes = rated
                        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                    }
                }
                if (jobs[jobKey] === owner) state.loadedSeason = state.season
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (jobs[jobKey] === owner) state.error = "Episodes unavailable" }
            finally { if (jobs[jobKey] === owner) { state.loading = false; jobs.remove(jobKey) } }
        }
        jobs[jobKey]?.start()
    }
    val prepared = mutableStateMapOf<String, PreparedDownload>()
    val errors = mutableStateMapOf<String, String>()
    val pending = mutableStateMapOf<String, Boolean>()
    private val timestamps = mutableMapOf<String, Long>()
    private val jobs = mutableMapOf<String, Job>()
    val discoveries = mutableStateMapOf<String, DownloadDiscovery>()
    private val tierSignatures = mutableMapOf<String, String>()
    private val validatedTiers = mutableMapOf<String, String>()
    private fun route(option: DownloadOption, language: String, audio: String) =
        "${option.source.selection.source}:${option.source.server}:${option.quality.height}:$language:$audio"
    fun validated(key: String, option: DownloadOption, language: String, audio: String) =
        validatedTiers[key] == route(option, language, audio)
    fun retryEpisode(key: String) { prepared.remove(key); errors.remove(key); validatedTiers.remove(key); timestamps.remove(key) }
    fun cancelTierWork(mediaKey: String) {
        jobs.remove("tier:$mediaKey")?.cancel(); tierSignatures.remove("tier:$mediaKey")
        pending.keys.filter { it.startsWith("$mediaKey:") }.toList().forEach { pending.remove(it) }
    }
    fun cancelSelectionWork(mediaKey: String) {
        jobs.keys.filter { it == "tier:$mediaKey" || it.startsWith("discover:$mediaKey:") }.toList().forEach { jobs.remove(it)?.cancel() }
        tierSignatures.remove("tier:$mediaKey")
        pending.keys.filter { it.startsWith("$mediaKey:") }.toList().forEach { pending.remove(it) }
    }
    fun discover(store: DownloadUiDependencies, selection: PlaybackSelection, language: String, retry: Int = 0) {
        val id = key(selection, language)
        val jobKey = "discover:$id"
        if (retry == 0 && (jobs[jobKey]?.isActive == true || discoveries[id]?.finished == true &&
                android.os.SystemClock.elapsedRealtime() - (timestamps[id] ?: 0L) < 600_000)) return
        jobs.remove(jobKey)?.cancel()
        discoveries[id] = DownloadDiscovery(); errors.remove(id)
        jobs.keys.filter { it.startsWith("discover:${selection.media.key}") && it != jobKey }.toList().forEach { jobs.remove(it)?.cancel() }
        jobs[jobKey] = scope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                store.discover(activity, host, selection, language) { update ->
                    if (jobs[jobKey] === owner) discoveries[id] = update
                }
                if (jobs[jobKey] === owner) timestamps[id] = android.os.SystemClock.elapsedRealtime()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (jobs[jobKey] === owner) {
                discoveries[id] = (discoveries[id] ?: DownloadDiscovery()).copy(finished = true)
                errors[id] = "No download options available. Try again."
            } } finally { if (jobs[jobKey] === owner) jobs.remove(jobKey) }
        }
        jobs[jobKey]?.start()
    }
    fun validateTier(store: DownloadUiDependencies, selections: List<Pair<String, PlaybackSelection>>,
        language: String, option: DownloadOption, retry: Int = 0, audio: String = "Default") {
        if (selections.isEmpty()) return
        val group = "tier:${selections.first().second.media.key}"
        val route = route(option, language, audio)
        val signature = "$route:${selections.map { it.first }}:$retry"
        if (tierSignatures[group] == signature) return
        jobs.remove(group)?.cancel()
        pending.keys.filter { it.startsWith(selections.first().second.media.key) }.toList().forEach { pending.remove(it) }
        tierSignatures[group] = signature
        val cached = prepared.filter { (key, _) -> key !in errors && validatedTiers[key] == route &&
            android.os.SystemClock.elapsedRealtime() - (timestamps[key] ?: 0L) < 600_000 }.toMutableMap()
        val anchorKey = key(option.source.selection, language)
        cached[anchorKey] = option.source
        selections.forEach { (key, _) -> pending[key] = true; errors.remove(key) }
        jobs[group] = scope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                store.prepareTier(activity, host, selections, language, cached, option, audio, { key, value ->
                    if (jobs[group] === owner) {
                        prepared[key] = value; pending.remove(key); errors.remove(key)
                        validatedTiers[key] = route; timestamps[key] = android.os.SystemClock.elapsedRealtime()
                    }
                }, { key, _ -> if (jobs[group] === owner) { pending.remove(key); errors[key] = "Episode unavailable" } })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (jobs[group] === owner) selections.filter { it.first in pending }.forEach { errors[it.first] = "Episode unavailable" } }
            finally { if (jobs[group] === owner) { selections.forEach { pending.remove(it.first) }; jobs.remove(group) } }
        }
        jobs[group]?.start()
    }
    init {
        (activity.window.decorView as ViewGroup).addView(host, ViewGroup.LayoutParams(1, 1))
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                jobs.values.toList().forEach { it.cancel() }
                (host.parent as? ViewGroup)?.removeView(host)
                sessions.remove(activity)
            }
        })
    }
    fun key(selection: PlaybackSelection, language: String) = "${com.aliflix.app.data.playbackProgressKey(selection)}:$language"
    companion object {
        private val sessions = mutableMapOf<ComponentActivity, DownloadSession>()
        fun get(activity: ComponentActivity) = sessions.getOrPut(activity) { DownloadSession(activity) }
    }
}

internal class DownloadPickerState(media: Media, episode: Episode?, initialLanguage: String) {
    var seasons by mutableStateOf<List<Season>>(emptyList())
    var season by mutableIntStateOf(episode?.seasonNumber ?: 1)
    var episodes by mutableStateOf(listOfNotNull(episode))
    var chosen by mutableStateOf(if (media.type == MediaType.MOVIE) setOf("movie") else episode?.let { setOf("${it.seasonNumber}:${it.number}") }.orEmpty())
    var height by mutableStateOf<Int?>(null)
    var language by mutableStateOf(initialLanguage)
    var noSubtitles by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loadedSeason: Int? = null
}
