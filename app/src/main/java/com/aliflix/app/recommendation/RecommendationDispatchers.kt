package com.aliflix.app.recommendation

import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Execution contexts used by recommendation orchestration.
 *
 * State publication remains owned by the caller's scope (normally Main), while
 * blocking data work and CPU-heavy ranking are isolated on these dispatchers.
 */
data class RecommendationDispatchers(
    val io: CoroutineDispatcher,
    val computation: CoroutineDispatcher,
) {
    companion object {
        val Default = RecommendationDispatchers(
            io = Dispatchers.IO,
            computation = Dispatchers.Default,
        )
    }
}

/** A single-owner execution context for MediaPipe's non-thread-safe embedder. */
interface SemanticExecutionContext : Closeable {
    val dispatcher: CoroutineDispatcher
}

class DedicatedSemanticExecutionContext(
    threadName: String = "aliflix-semantic",
) : SemanticExecutionContext {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, threadName).apply {
            isDaemon = true
        }
    }
    private val coroutineDispatcher: ExecutorCoroutineDispatcher =
        executor.asCoroutineDispatcher()

    override val dispatcher: CoroutineDispatcher = coroutineDispatcher

    override fun close() {
        coroutineDispatcher.close()
    }
}
