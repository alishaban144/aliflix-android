package com.aliflix.app.data

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidCatalogCacheStoreDispatcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun loadingCacheUsesIoForFileAccessAndComputationForDecoding() = runBlocking {
        val cacheDirectory = temporaryFolder.newFolder("catalog-cache")
        File(cacheDirectory, "home-v4.json").writeText(HOME_JSON)
        val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "catalog-cache-io")
        }
        val computationExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "catalog-cache-computation")
        }
        val ioDispatcher = RecordingDispatcher(ioExecutor.asCoroutineDispatcher())
        val computationDispatcher =
            RecordingDispatcher(computationExecutor.asCoroutineDispatcher())

        try {
            val store = AndroidCatalogCacheStore(
                cacheDir = cacheDirectory,
                ioDispatcher = ioDispatcher,
                computationDispatcher = computationDispatcher,
            )

            val home = store.loadHome()

            assertEquals("Arrival", home?.hero?.title)
            assertTrue(
                ioDispatcher.threadNames.all { it.startsWith("catalog-cache-io") },
            )
            assertTrue(ioDispatcher.threadNames.isNotEmpty())
            assertTrue(
                computationDispatcher.threadNames.all {
                    it.startsWith("catalog-cache-computation")
                },
            )
            assertTrue(computationDispatcher.threadNames.isNotEmpty())
        } finally {
            ioExecutor.shutdownNow()
            computationExecutor.shutdownNow()
        }
    }

    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        val threadNames = ConcurrentLinkedQueue<String>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                threadNames += Thread.currentThread().name
                block.run()
            }
        }
    }

    private companion object {
        val HOME_JSON = """
            {
              "hero": {
                "id": 329865,
                "type": "movie",
                "title": "Arrival",
                "overview": "",
                "posterPath": null,
                "backdropPath": null,
                "year": "2016",
                "rating": 7.9,
                "genres": ["Science Fiction", "Drama"],
                "cast": [],
                "runtime": "116 min"
              },
              "rails": []
            }
        """.trimIndent()
    }
}
