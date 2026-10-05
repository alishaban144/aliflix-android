package com.aliflix.app.player

import kotlinx.coroutines.*
import kotlin.math.ceil

/** One deadline for network, evidence collection, scans AND CPU work. The UI
 * watchdog runs independently of the analysis worker. A late worker result is
 * never returned, even if native I/O temporarily ignores cancellation.
 */
internal object AudioSyncDeadline {
    // Leave one second for main-thread presentation and correction persistence.
    const val TOTAL_MS = 19_000L
    suspend fun <T> run(
        clock: () -> Long,
        onRemaining: (Int) -> Unit,
        onExpired: () -> Unit,
        work: suspend CoroutineScope.(checkDeadline: () -> Unit) -> T,
    ): T? = supervisorScope {
        val deadline = clock() + TOTAL_MS
        val worker = async {
            val context = currentCoroutineContext()
            work {
                context.ensureActive()
                if (clock() >= deadline) throw CancellationException("audio_sync_deadline")
            }
        }
        val watchdog = launch {
            while (clock() < deadline) {
                onRemaining(ceil((deadline - clock()).coerceAtLeast(0) / 1000.0).toInt())
                delay(minOf(250, (deadline - clock()).coerceAtLeast(1)))
            }
            onExpired()
            worker.cancel()
        }
        try {
            worker.await().takeIf { clock() < deadline }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive() // User/identity cancellation propagates.
            if (clock() >= deadline) { onExpired(); null } else throw cancelled
        } finally { watchdog.cancel() }
    }
}
