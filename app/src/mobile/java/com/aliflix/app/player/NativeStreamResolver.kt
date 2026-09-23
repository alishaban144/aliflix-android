package com.aliflix.app.player

import android.os.SystemClock
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.aliflix.app.data.PlaybackProgressStore
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.PlaybackProviderId
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class NoNativeServersException : Exception()

internal fun <T> selectNativeServer(servers: List<T>, preferredServer: String?, excluded: Set<String>,
    strictPreferredServer: Boolean = false, label: (T) -> String): T? {
    if (strictPreferredServer) {
        require(!preferredServer.isNullOrBlank()) { "A server is required for pinned preparation." }
        return servers.firstOrNull { label(it) == preferredServer && label(it) !in excluded }
            ?: throw IllegalStateException("Server '$preferredServer' is unavailable for this episode. Retry this episode.")
    }
    return preferredServer?.let { preferred -> servers.firstOrNull { label(it).equals(preferred, ignoreCase = true) } }
        ?: servers.firstOrNull { label(it) !in excluded }
}

/** A short-lived, muted provider adapter. The browser never owns user playback. */
internal class NativeStreamResolver(
    private val activity: ComponentActivity,
    private val progress: PlaybackProgressStore,
    private val host: FrameLayout,
) : AutoCloseable {
    private var web: WebPlayerController? = null
    private var view: FrameLayout? = null
    private var closed = false
    private var reportServers: (List<String>) -> Unit = {}
    private val children = mutableListOf<NativeStreamResolver>()

    suspend fun resolve(
        selection: PlaybackSelection,
        positionMs: Long,
        excluded: Set<String>,
        preferredServer: String? = null,
        onServers: (List<String>) -> Unit = {},
        strictPreferredServer: Boolean = false,
        validateSingle: Boolean = true,
        onServer: (String) -> Unit,
    ): NativePlaybackRequest {
        reportServers = onServers
        if (strictPreferredServer) require(!preferredServer.isNullOrBlank()) { "A server is required for pinned preparation." }
        val catalogueProvider = selection.source.provider in setOf(PlaybackProviderId.RAMOFLIX, PlaybackProviderId.DORABY)
        val embeds = if (catalogueProvider) FmovieNativeCatalog().embeds(selection) else preferredNativeEmbeds(selection)
        if (embeds.isNotEmpty()) onServers(embeds.map { it.first })
        val history = activity.getSharedPreferences("native-resolver-performance", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val candidates = embeds.filter { it.first !in excluded }.sortedBy { (name, _) ->
            val key = "${selection.source.provider}:$name"
            if (now - history.getLong("$key:at", 0) < 30 * 60_000) history.getLong("$key:ms", 5_000) else 5_000
        }
        if (strictPreferredServer && (catalogueProvider || embeds.any { it.first == preferredServer })) {
            selectNativeServer(embeds, preferredServer, excluded, true) { it.first }
        }
        if (catalogueProvider && candidates.isEmpty()) throw NoNativeServersException()
        if (preferredServer != null || candidates.size < 2) return resolveSingle(selection, positionMs, excluded, preferredServer, embeds, strictPreferredServer, onServer).also { close(); if (validateSingle) StartupStreamCache.awaitPlayable(activity, it) }
        val winner = try { firstSuccessful(candidates.map { (name, _) -> suspend {
            val child = NativeStreamResolver(activity, progress, host)
            children.add(child)
            val started = SystemClock.elapsedRealtime()
            val key = "${selection.source.provider}:$name"
            try {
                val request = child.resolveSingle(selection, positionMs, excluded, name, embeds) {}
                child.close()
                StartupStreamCache.awaitPlayable(activity, request)
                history.edit().putLong("$key:ms", SystemClock.elapsedRealtime() - started).putLong("$key:at", now).apply()
                name to request
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                history.edit().putLong("$key:ms", 20_000).putLong("$key:at", now).apply()
                throw error
            }
            finally { child.close(); children.remove(child) }
        } }, parallelism = 2) } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            // Preferred embeds may all be unavailable while the catalogue has another server.
            if (catalogueProvider) throw error
            return resolveSingle(selection, positionMs, excluded + candidates.map { it.first }, null, embeds, onServer = onServer).also { close(); StartupStreamCache.awaitPlayable(activity, it) }
        }
        onServer(winner.first)
        return winner.second
    }

    private suspend fun resolveSingle(
        selection: PlaybackSelection, positionMs: Long, excluded: Set<String>, preferredServer: String?,
        allDirect: List<Pair<String, String>>,
        strictPreferredServer: Boolean = false,
        onServer: (String) -> Unit,
    ): NativePlaybackRequest = withTimeout(10_000) {
        val direct = if (strictPreferredServer) {
            allDirect.firstOrNull { it.first == preferredServer && it.first !in excluded }
        } else if (preferredServer != null) {
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
        val discoveryDeadline = SystemClock.elapsedRealtime() + 4_000
        while (web.moviepireServers.value.isEmpty() && SystemClock.elapsedRealtime() < discoveryDeadline) {
            web.refreshNativeServers()
            if (!selection.source.provider.usesMoviepire && web.preparedNativeRequest(0, positionMs) != null) break
            delay(400)
        }
        val servers = orderedNativeServers(web.moviepireServers.value)
        if (servers.isNotEmpty()) reportServers(servers.map { it.label })
        val server = selectNativeServer(servers, preferredServer, excluded, strictPreferredServer) { it.label }
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
