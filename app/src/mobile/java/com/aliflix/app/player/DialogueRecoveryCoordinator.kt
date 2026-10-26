package com.aliflix.app.player

import kotlinx.coroutines.*

/** Per-attempt recovery. At most two requests exist at a time, so their deadlines
 * never include an executor queue. Fresh played evidence outranks stale crops.
 * The caller owns the overall deadline and source/seek/caption identity fence. */
internal object DialogueRecoveryCoordinator {
    suspend fun run(
        samples: suspend () -> List<PlayedDialoguePcm>,
        read: suspend (PlayedDialoguePcm) -> List<HeardWord>,
        onWords: (List<List<HeardWord>>) -> Unit,
        requestTimeoutMs: Long = 10_000,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        parallelism: Int = 2,
    ) = supervisorScope {
        val sent = mutableSetOf<Pair<Double, Int>>()
        val collected = mutableListOf<List<HeardWord>>()
        val active = mutableListOf<Job>()
        var framesSent = 0
        var newestEnd = Double.NEGATIVE_INFINITY
        var reserveUntil = Long.MAX_VALUE
        try {
            while (isActive && sent.size < 6) {
                active.removeAll { it.isCompleted }
                if (active.size < parallelism.coerceIn(1, 2)) {
                    val candidates = samples().filter { sample ->
                        sample.start.isFinite() && sample.frames.size in 200..1_000 &&
                            (sample.start to sample.frames.size) !in sent &&
                            sent.none { (start, count) -> start == sample.start &&
                                sample.start + sample.frames.size * .02 < start + count * .02 + 3.0 } &&
                            framesSent + sample.frames.size <= 6_000
                    }
                    // A newly completed exchange takes the next available slot.
                    val fresh = candidates.firstOrNull { it.start + it.frames.size * .02 >= newestEnd + 3.0 }
                    // Give new dialogue two seconds to claim the final slots.
                    // Paused playback can still use the remaining original crops.
                    val next = fresh ?: candidates.firstOrNull().takeIf { sent.size < 4 || clock() >= reserveUntil }
                    if (next != null) {
                        sent.add(next.start to next.frames.size)
                        if (sent.size == 4) reserveUntil = clock() + 2_000
                        framesSent += next.frames.size
                        newestEnd = maxOf(newestEnd, next.start + next.frames.size * .02)
                        active.add(launch {
                            try {
                                val words = withTimeoutOrNull(requestTimeoutMs) { read(next) }.orEmpty()
                                if (words.isNotEmpty()) {
                                    collected.add(words)
                                    onWords(collected.toList())
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Another independent clip can recover. */ }
                        })
                        continue
                    }
                }
                delay(250)
            }
            active.joinAll()
        } finally {
            active.forEach { it.cancel() }
        }
    }
}
