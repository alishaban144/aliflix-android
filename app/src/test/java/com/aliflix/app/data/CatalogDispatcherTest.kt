package com.aliflix.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

class CatalogDispatcherTest {
    @Test
    fun searchKeepsRetrievalOnIoAndOrchestrationOnComputation() = runTest {
        RecordingDispatcher("catalog-io").use { ioDispatcher ->
            RecordingDispatcher("catalog-computation").use { computationDispatcher ->
                val loaderThreads = CopyOnWriteArrayList<String>()
                val client = CatalogClient(
                    pageLoader = { url ->
                        loaderThreads += Thread.currentThread().name
                        when {
                            "/search/movie" in url -> inceptionSearchHtml
                            "/search/tv" in url -> "<main></main>"
                            else -> error("Unexpected request: $url")
                        }
                    },
                    ioDispatcher = ioDispatcher,
                    computationDispatcher = computationDispatcher,
                )

                val results = client.search("Inception movie")

                assertEquals("Inception", results.first().title)
                assertTrue(loaderThreads.isNotEmpty())
                assertTrue(
                    "Expected only catalog-io but recorded $loaderThreads",
                    loaderThreads.all { it.startsWith("catalog-io") },
                )
                assertTrue(computationDispatcher.executedThreads.isNotEmpty())
                assertTrue(
                    computationDispatcher.executedThreads.all {
                        it.startsWith("catalog-computation")
                    },
                )
            }
        }
    }

    private class RecordingDispatcher(
        private val threadName: String,
    ) : CoroutineDispatcher(), Closeable {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, threadName)
        }
        val executedThreads = CopyOnWriteArrayList<String>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            executor.execute {
                executedThreads += Thread.currentThread().name
                block.run()
            }
        }

        override fun close() {
            executor.shutdownNow()
        }
    }

    private companion object {
        val inceptionSearchHtml = """
            <main>
              <div data-object-id="movie-27205">
                <a data-media-type="movie" href="/movie/27205-inception">
                  <img class="poster" alt="Inception" src="/inception.jpg" />
                </a>
                <a data-media-type="movie" href="/movie/27205-inception">
                  <h2>Inception</h2>
                </a>
                <span class="release_date">July 16, 2010</span>
                <p>A thief enters shared dreams to steal secrets.</p>
              </div>
            </main>
        """.trimIndent()
    }
}
