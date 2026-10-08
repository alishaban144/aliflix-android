package com.aliflix.app.player

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aliflix.app.data.PlaybackProgressStore
import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.model.*
import kotlinx.coroutines.*

/** A muted preparation can transfer from details to Play without restarting its requests. */
internal object DetailPreloadStore {
    data class Entry(val selection: PlaybackSelection, val server: String, val request: NativePlaybackRequest)
    class Session(val selection: PlaybackSelection, val positionMs: Long, val scope: CoroutineScope) {
        val at = SystemClock.elapsedRealtime()
        lateinit var result: Deferred<Entry?>
        var transferred = false
        fun matches(other: PlaybackSelection) = playbackProgressKey(selection) == playbackProgressKey(other) &&
            selection.source == other.source && SystemClock.elapsedRealtime() - at < 120_000
        fun cancel() = scope.cancel()
    }
    var current: Session? = null
    var claimed: Session? = null

    suspend fun take(selection: PlaybackSelection, positionMs: Long): Triple<PlaybackSelection, String, NativePlaybackRequest>? {
        val session = claimed.also { claimed = null } ?: return null
        try {
            if (!session.matches(selection) || kotlin.math.abs(session.positionMs - positionMs) > 3_000) return null
            // Providers are already racing. Keep a bounded handoff rather than discard their progress.
            val entry = withTimeoutOrNull(12_000) { session.result.await() } ?: return null
            val selected = entry.selection.copy(media = selection.media, availableEpisodes = selection.availableEpisodes)
            PlaybackStartupTiming.mark("details_preload_reused")
            return Triple(selected, entry.server, entry.request.copy(positionMs = positionMs, selectionJson = selected.nativeJson()))
        } finally { session.cancel() }
    }
}

internal fun claimDetailPreload(selection: PlaybackSelection) {
    DetailPreloadStore.claimed?.cancel()
    val session = DetailPreloadStore.current
    DetailPreloadStore.current = null
    if (session?.matches(selection) == true) {
        session.transferred = true
        DetailPreloadStore.claimed = session
    } else {
        session?.cancel()
        DetailPreloadStore.claimed = null
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun DetailPlaybackPreload(media: Media, episode: Episode?, episodes: List<Episode>, enabled: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preferences = remember(activity) { com.aliflix.app.data.PlaybackProviderRepository(activity) }.preferences.collectAsState().value
    val selection = remember(media.key, episode?.seasonNumber, episode?.number, preferences) {
        PlaybackSelection(media, episode?.seasonNumber, episode?.number, episode?.title, episodes, preferences.sourceFor(media))
    }
    LaunchedEffect(selection.key, selection.source, enabled, lifecycle) {
        if (!enabled || (media.type == MediaType.TV && episode == null)) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val network = activity.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = network.getNetworkCapabilities(network.activeNetwork)
            val offline = com.aliflix.app.downloads.OfflineDownloads.get(activity).manager.downloadIndex
                .getDownload(playbackProgressKey(selection))
            if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true ||
                offline?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) return@repeatOnLifecycle
            val progress = PlaybackProgressStore(activity)
            val position = ((progress.progressFor(selection)?.takeUnless { it.completed }?.positionSeconds ?: 0.0) * 1000).toLong()
            val parent = activity.findViewById<ViewGroup>(android.R.id.content)
            val host = FrameLayout(activity).apply {
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isClickable = false
                isFocusable = false
            }
            parent.addView(host, 0, ViewGroup.LayoutParams(-1, -1))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val session = DetailPreloadStore.Session(selection, position, scope)
            DetailPreloadStore.current?.cancel()
            session.result = scope.async {
                try {
                    withTimeoutOrNull(30_000) {
                        firstSuccessful(initialPlaybackRace(playbackSourceFallbacks(selection, preferences)).map { candidate -> suspend {
                            val adapter = NativeStreamResolver(activity, progress, host)
                            try {
                                var server = candidate.source.identity.displayName
                                val request = withTimeout(resolveBudgetMillis(candidate.source.identity)) {
                                    adapter.resolve(candidate, position, emptySet(), parallelism = 2) { server = it }
                                }
                                DetailPreloadStore.Entry(candidate, server, request)
                            } finally { adapter.close() }
                        } }, parallelism = 4)
                    }
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    null
                } finally {
                    host.removeAllViews()
                    parent.removeView(host)
                }
            }
            DetailPreloadStore.current = session
            try { awaitCancellation() }
            finally {
                if (DetailPreloadStore.current === session) DetailPreloadStore.current = null
                if (!session.transferred) session.cancel()
            }
        }
    }
}
