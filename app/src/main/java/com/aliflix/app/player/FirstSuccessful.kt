package com.aliflix.app.player

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A failed candidate cannot win. Cancellation waits for every loser's cleanup. */
internal suspend fun <T : Any> firstSuccessful(
    candidates: List<suspend () -> T>, parallelism: Int = 2,
): T = supervisorScope {
    require(candidates.isNotEmpty())
    val results = Channel<Result<T>>(candidates.size)
    val slots = Semaphore(parallelism.coerceAtLeast(1))
    val jobs = candidates.map { candidate -> launch {
        val result = try { Result.success(slots.withPermit { candidate() }) }
        catch (error: Exception) { ensureActive(); Result.failure(error) }
        results.send(result)
    } }
    try {
        var lastFailure: Throwable? = null
        repeat(candidates.size) {
            val result = results.receive()
            result.getOrNull()?.let { return@supervisorScope it }
            lastFailure = result.exceptionOrNull()
        }
        throw checkNotNull(lastFailure)
    } finally {
        jobs.forEach(Job::cancel)
        withContext(NonCancellable) { jobs.joinAll() }
        results.close()
    }
}
