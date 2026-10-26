package com.aliflix.app.downloads

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

/** Stream keys are meaningful only inside this option's original source. */
internal data class DownloadOption(val quality: DownloadQuality, val source: PreparedDownload)
internal data class DownloadDiscovery(val options: List<DownloadOption> = emptyList(), val finished: Boolean = false) {
    val choices: List<DownloadOption> get() = relativeDownloadOptions(options)
    val default: DownloadOption? get() = choices.getOrNull(1) ?: choices.firstOrNull()
    fun choicesKeeping(selectedHeight: Int?): List<DownloadOption> {
        val tiers = choices
        if (tiers.size < 3 || tiers.any { it.quality.height == selectedHeight }) return tiers
        val selected = options.firstOrNull { it.quality.height == selectedHeight && it.quality.height > 0 } ?: return tiers
        // A choice made during discovery remains the middle tier when later
        // providers introduce new extremes. Unselected tiers use the midpoint.
        return listOf(tiers.first(), selected, tiers.last()).sortedByDescending { it.quality.height }
    }
}

internal fun relativeDownloadOptions(options: List<DownloadOption>): List<DownloadOption> {
    val known = options.filter { it.quality.height > 0 }.distinctBy { it.quality.height }.sortedByDescending { it.quality.height }
    if (known.isEmpty()) return options.take(1)
    if (known.size <= 3) return known
    val midpoint = (known.first().quality.height + known.last().quality.height) / 2.0
    return listOf(known.first(), known.drop(1).dropLast(1).minWith(compareBy<DownloadOption> { abs(it.quality.height - midpoint) }.thenBy { it.quality.height }), known.last())
}

internal fun closestDownloadQuality(qualities: List<DownloadQuality>, height: Int): DownloadQuality =
    qualities.minWith(compareBy<DownloadQuality> { if (it.height > 0 && height > 0) abs(it.height - height) else Int.MAX_VALUE }
        .thenBy { it.height })

internal fun DownloadDiscovery.prepared(): PreparedDownload {
    val first = requireNotNull(choices.firstOrNull()) { "No downloadable video found. Try again." }
    return first.source.copy(qualities = choices.map { it.quality }, options = options)
}

/** Four initial attempts, bounded global deadline, progressive results and structured loser cleanup. */
internal suspend fun <T> discoverDownloadOptions(
    providers: List<T>, attempt: suspend (T) -> PreparedDownload,
    onUpdate: (DownloadDiscovery) -> Unit = {}, budgetMs: Long = 30_000,
): DownloadDiscovery {
    val options = mutableListOf<DownloadOption>()
    withTimeoutOrNull(budgetMs) {
        supervisorScope {
            val channel = Channel<PreparedDownload?>(Channel.UNLIMITED)
            val slots = Semaphore(4)
            val jobs = providers.map { provider -> launch {
                slots.withPermit {
                    val result = try { attempt(provider) }
                    catch (timeout: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    channel.send(result)
                }
            } }
            try {
                for (i in providers.indices) {
                    channel.receive()?.let { source -> options += source.qualities.map { DownloadOption(it, source) } }
                    val enough = options.filter { it.quality.height > 0 }.distinctBy { it.quality.height }.size >= 3
                    onUpdate(DownloadDiscovery(options.toList(), enough))
                    if (enough) break
                }
            } finally { jobs.forEach { it.cancel() }; jobs.joinAll(); channel.close() }
        }
    }
    currentCoroutineContext().ensureActive()
    return DownloadDiscovery(options.toList(), true).also(onUpdate)
}

private val inspectionExecutor = java.util.concurrent.Executors.newFixedThreadPool(4) { task ->
    Thread(task, "download-inspection").apply { isDaemon = true }
}

/** Closing a blocked HTTP source makes cancellation independent of its read timeout. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun <T> inspectDownloadSource(source: androidx.media3.datasource.DataSource,
    read: () -> T): T = suspendCancellableCoroutine { continuation ->
    val future = inspectionExecutor.submit {
        if (continuation.isActive) {
            val result = runCatching(read)
            runCatching { source.close() }
            continuation.resumeWith(result)
        }
    }
    continuation.invokeOnCancellation { future.cancel(true); runCatching { source.close() } }
}
