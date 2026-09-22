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
        if (state.loading || (state.loadedSeason == state.season && !force)) return
        state.loading = true
        state.error = null
        scope.launch {
            try {
                if (media.type == MediaType.TV && episode == null) {
                    if (state.seasons.isEmpty()) {
                        state.seasons = store.seasons(media)
                        if (state.loadedSeason == null) state.season = state.seasons.firstOrNull { it.number > 0 }?.number ?: 1
                    }
                    state.episodes = store.episodes(media, state.season).filter { it.seasonNumber == state.season }
                    state.chosen = state.episodes.map { "${it.seasonNumber}:${it.number}" }.toSet()
                    val ratingSeason = state.season
                    val ratingEpisodes = state.episodes
                    scope.launch {
                        try {
                            val rated = ratingsClient.mobileEpisodeRatings(media, ratingSeason, ratingEpisodes)
                            if (state.season == ratingSeason) state.episodes = rated
                        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                    }
                }
                state.loadedSeason = state.season
                val prefs = PlaybackProviderRepository(activity).preferences.value
                val selections = if (media.type == MediaType.MOVIE) listOf(PlaybackSelection(media)) else state.episodes.map {
                    PlaybackSelection(media, seasonNumber = it.seasonNumber, episodeNumber = it.number, episodeTitle = it.title, availableEpisodes = state.episodes)
                }
                val blocked = store.blockedIds()
                prepare(store, selections.filter { playbackProgressKey(it) !in blocked }.map {
                    key(it, state.language) to it.copy(source = prefs.sourceFor(media))
                }, state.language)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.error = "Episodes unavailable" }
            finally { state.loading = false }
        }
    }
    val prepared = mutableStateMapOf<String, PreparedDownload>()
    val errors = mutableStateMapOf<String, String>()
    val pending = mutableStateMapOf<String, Boolean>()
    private val timestamps = mutableMapOf<String, Long>()
    private val jobs = mutableMapOf<String, Job>()
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
    fun prepare(store: DownloadUiDependencies, selections: List<Pair<String, PlaybackSelection>>, language: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!activity.hasInternetConnection()) { selections.forEach { errors[it.first] = "Connect to the internet and try again" }; return }
        val batch = selections.filter { (key, _) ->
            if (now - (timestamps[key] ?: 0L) > 10 * 60_000L) prepared.remove(key)
            key !in prepared && key !in pending && key !in errors
        }
        if (batch.isEmpty()) return
        batch.forEach { pending[it.first] = true }
        val jobKey = batch.first().first
        jobs[jobKey] = activity.lifecycleScope.launch {
            try {
                store.prepare(activity, host, batch, language, prepared.toMap(), { key, result ->
                    pending.remove(key); prepared[key] = result; timestamps[key] = android.os.SystemClock.elapsedRealtime(); errors.remove(key)
                }, { key, _ -> pending.remove(key); errors[key] = "Unavailable" })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { batch.filter { it.first !in prepared }.forEach { errors[it.first] = "Unavailable" } }
            finally { batch.forEach { pending.remove(it.first) }; jobs.remove(jobKey) }
        }
    }
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
