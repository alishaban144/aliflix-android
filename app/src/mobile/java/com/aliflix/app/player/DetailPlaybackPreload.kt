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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aliflix.app.data.PlaybackProgressStore
import com.aliflix.app.data.PlaybackProviderRepository
import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.model.*
import kotlinx.coroutines.*

/** One details-owned request. No playback service, audio focus, or history writes. */
internal object DetailPreloadStore {
    data class Entry(val owner: Any, val selection: PlaybackSelection, val server: String,
        val request: NativePlaybackRequest, val at: Long = SystemClock.elapsedRealtime())
    var ready: Entry? = null
    var claimed: Entry? = null
    var cancel: (() -> Unit)? = null
    fun take(selection: PlaybackSelection, positionMs: Long): Triple<PlaybackSelection, String, NativePlaybackRequest>? {
        val entry = claimed.also { claimed = null } ?: return null
        if (playbackProgressKey(entry.selection) != playbackProgressKey(selection) ||
            SystemClock.elapsedRealtime() - entry.at > 120_000 ||
            kotlin.math.abs(entry.request.positionMs - positionMs) > 3_000) return null
        val selected = entry.selection.copy(media = selection.media, availableEpisodes = selection.availableEpisodes)
        return Triple(selected, entry.server, entry.request.copy(positionMs = positionMs, selectionJson = selected.nativeJson()))
    }
}

internal fun claimDetailPreload(selection: PlaybackSelection) {
    DetailPreloadStore.claimed = DetailPreloadStore.ready?.takeIf {
        playbackProgressKey(it.selection) == playbackProgressKey(selection) &&
            SystemClock.elapsedRealtime() - it.at < 120_000 &&
            selection.source.provider == PlaybackProviderId.CINEJOY
    }
    DetailPreloadStore.ready = null
    DetailPreloadStore.cancel?.invoke()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun DetailPlaybackPreload(media: Media, episode: Episode?, episodes: List<Episode>, enabled: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val progress = remember(activity) { PlaybackProgressStore(activity) }
    val preferences by remember(activity) { PlaybackProviderRepository(activity) }
        .preferences.collectAsState()
    val selection = remember(media.key, episode?.seasonNumber, episode?.number) {
        PlaybackSelection(media, episode?.seasonNumber, episode?.number, episode?.title, episodes,
            PlaybackSource(PlaybackProviderId.CINEJOY))
    }
    LaunchedEffect(selection.key, enabled, lifecycle) {
        if (!enabled || (media.type == MediaType.TV && episode == null)) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val owner = Any()
            val job = currentCoroutineContext().job
            val cancel: () -> Unit = { job.cancel() }
            DetailPreloadStore.cancel = cancel
            val parent = activity.findViewById<ViewGroup>(android.R.id.content)
            val host = FrameLayout(activity).apply {
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isClickable = false
            }
            parent.addView(host, 0, ViewGroup.LayoutParams(-1, -1))
            try {
                val network = activity.getSystemService(android.net.ConnectivityManager::class.java)
                val caps = network.getNetworkCapabilities(network.activeNetwork)
                val offline = com.aliflix.app.downloads.OfflineDownloads.get(activity).manager.downloadIndex
                    .getDownload(playbackProgressKey(selection))
                if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true ||
                    offline?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) return@repeatOnLifecycle
                val position = ((PlaybackProgressStore(activity).progressFor(selection)?.takeUnless { it.completed }?.positionSeconds ?: 0.0) * 1000).toLong()
                // One provider at a time; bounded work leaves bandwidth and CPU for details artwork.
                withTimeoutOrNull(30_000) {
                    for (candidate in playbackSourceFallbacks(selection, preferences).take(3)) {
                        ensureActive()
                        val adapter = NativeStreamResolver(activity, progress, host)
                        try {
                            var server = candidate.source.provider.displayName
                            val request = withTimeoutOrNull(resolveBudgetMillis(candidate.source.provider)) {
                                adapter.resolve(candidate, position, emptySet(), validateSingle = false, parallelism = 1) { server = it }
                            } ?: continue
                            adapter.close()
                            StartupStreamCache.awaitPlayable(activity, request)
                            ensureActive()
                            DetailPreloadStore.ready = DetailPreloadStore.Entry(owner, candidate, server, request)
                            break
                        } catch (cancelled: CancellationException) {
                            currentCoroutineContext().ensureActive()
                        } catch (_: Exception) {
                            // A failed speculative load must never affect details or foreground playback.
                        } finally { adapter.close() }
                    }
                }
                awaitCancellation()
            } finally {
                if (DetailPreloadStore.ready?.owner === owner) DetailPreloadStore.ready = null
                if (DetailPreloadStore.cancel === cancel) DetailPreloadStore.cancel = null
                host.removeAllViews()
                parent.removeView(host)
            }
        }
    }
}
