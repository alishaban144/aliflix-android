package com.aliflix.app.player

import android.os.SystemClock
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.aliflix.app.data.PlaybackProgressStore
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class NoNativeServersException : Exception()

/** A short-lived, muted provider adapter. The browser never owns user playback. */
internal class NativeStreamResolver(
    private val activity: ComponentActivity,
    private val progress: PlaybackProgressStore,
    private val host: FrameLayout,
) : AutoCloseable {
    private var web: WebPlayerController? = null
    private var view: FrameLayout? = null
    private var closed = false
    private val children = mutableListOf<NativeStreamResolver>()

    suspend fun resolve(
        selection: PlaybackSelection,
        positionMs: Long,
        excluded: Set<String>,
        preferredServer: String? = null,
        onServer: (String) -> Unit,
    ): NativePlaybackRequest {
        val candidates = preferredNativeEmbeds(selection).filter { it.first !in excluded }
        if (preferredServer != null || candidates.size < 2) return resolveSingle(selection, positionMs, excluded, preferredServer, onServer)
        val winner = try { firstSuccessful(candidates.map { (name, _) -> suspend {
            val child = NativeStreamResolver(activity, progress, host)
            children.add(child)
            try { name to child.resolveSingle(selection, positionMs, excluded, name) {} }
            finally { child.close(); children.remove(child) }
        } }, parallelism = 2) } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            // Preferred embeds may all be unavailable while the catalogue has another server.
            return resolveSingle(selection, positionMs, excluded + candidates.map { it.first }, null, onServer)
        }
        onServer(winner.first)
        return winner.second
    }

    private suspend fun resolveSingle(
        selection: PlaybackSelection, positionMs: Long, excluded: Set<String>, preferredServer: String?,
        onServer: (String) -> Unit,
    ): NativePlaybackRequest = withTimeout(45_000) {
        val allDirect = preferredNativeEmbeds(selection)
        val direct = if (preferredServer != null) {
            allDirect.firstOrNull { it.first.equals(preferredServer, ignoreCase = true) }
        } else {
            allDirect.firstOrNull { it.first !in excluded }
        }
        val web = WebPlayerController(activity, progress, nativePreparation = true, nativeEmbedUrl = direct?.second)
        this@NativeStreamResolver.web = web
        view = web.viewFor(selection)
        host.addView(view, FrameLayout.LayoutParams(-1, -1))
        web.setVisible(true)
        if (direct != null) {
            onServer(direct.first)
            while (true) {
                web.preparedNativeRequest(0, positionMs)?.let { return@withTimeout it }
                delay(200)
            }
        }
        val discoveryDeadline = SystemClock.elapsedRealtime() + 12_000
        while (web.moviepireServers.value.isEmpty() && SystemClock.elapsedRealtime() < discoveryDeadline) {
            web.refreshNativeServers()
            if (!selection.source.provider.usesMoviepire && web.preparedNativeRequest(0, positionMs) != null) break
            delay(400)
        }
        val servers = orderedNativeServers(web.moviepireServers.value)
        val server = if (preferredServer != null) {
            servers.firstOrNull { it.label.equals(preferredServer, ignoreCase = true) }
                ?: servers.firstOrNull { it.label !in excluded }
        } else {
            servers.firstOrNull { it.label !in excluded }
        }
        if (servers.isNotEmpty() && server == null && preferredServer == null) throw NoNativeServersException()
        val label = server?.label ?: preferredServer ?: selection.source.provider.name.lowercase().replaceFirstChar { it.uppercase() }
        if (preferredServer == null && label in excluded) throw NoNativeServersException()
        onServer(label)
        val after = SystemClock.elapsedRealtime() + if (server != null && !server.selected) 2_000 else 0
        if (server != null && !server.selected) {
            web.selectMoviepireServer(server)
            while (web.moviepireServers.value.none { it.key == server.key && it.selected }) {
                delay(300); web.refreshNativeServers()
            }
        }
        while (true) {
            web.preparedNativeRequest(after, positionMs)?.let { return@withTimeout it }
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") error("No playable stream")
    }

    override fun close() {
        if (closed) return
        closed = true
        children.toList().forEach { it.close() }
        children.clear()
        web?.destroy(); web = null; view?.let { host.removeView(it) }; view = null
    }
}
