package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.RecommendationPageCursor
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

    @Test
    fun recommendationPageReadAndWriteStayOnTheirInjectedDispatchers() = runBlocking {
        val cacheDirectory = temporaryFolder.newFolder("recommendation-page-cache")
        val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "recommendation-cache-io")
        }
        val computationExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "recommendation-cache-computation")
        }
        val ioDispatcher = RecordingDispatcher(ioExecutor.asCoroutineDispatcher())
        val computationDispatcher =
            RecordingDispatcher(computationExecutor.asCoroutineDispatcher())
        val store = AndroidCatalogCacheStore(
            cacheDir = cacheDirectory,
            ioDispatcher = ioDispatcher,
            computationDispatcher = computationDispatcher,
            fileWriter = { target, value ->
                target.parentFile?.mkdirs()
                target.writeText(value)
            },
        )
        val item = RecommendationDiscoveryItem(
            media = Media(
                id = 27205,
                type = MediaType.MOVIE,
                title = "Inception",
                year = "2010",
            ),
            evidence = "shared dream themes",
            sources = setOf("ANCHOR_RELATED"),
            sourceCount = 1,
            sourcePosition = 0,
        )
        val expected = CachedRecommendationCatalogPage(
            items = listOf(item),
            nextCursor = RecommendationPageCursor(
                page = 2,
                seenKeys = setOf(item.media.key),
            ),
            hasMore = true,
            savedAtMillis = System.currentTimeMillis(),
        )

        try {
            store.saveRecommendationCatalogPage("MOVIE|dreams", 1, expected)
            val restored = store.loadRecommendationCatalogPage(
                fingerprint = "MOVIE|dreams",
                page = 1,
                maxAgeMs = 60_000L,
            )

            assertEquals(expected.items, restored?.items)
            assertEquals(expected.nextCursor, restored?.nextCursor)
            assertEquals(true, restored?.hasMore)
            assertTrue(ioDispatcher.threadNames.isNotEmpty())
            assertTrue(
                ioDispatcher.threadNames.all {
                    it.startsWith("recommendation-cache-io")
                },
            )
            assertTrue(computationDispatcher.threadNames.isNotEmpty())
            assertTrue(
                computationDispatcher.threadNames.all {
                    it.startsWith("recommendation-cache-computation")
                },
            )
        } finally {
            ioExecutor.shutdownNow()
            computationExecutor.shutdownNow()
        }
    }

    @Test(expected = CancellationException::class)
    fun cacheIoCancellationIsNeverConvertedIntoACacheMiss() = runBlocking {
        val store = AndroidCatalogCacheStore(
            cacheDir = temporaryFolder.newFolder("cancelled-cache"),
            ioDispatcher = Dispatchers.Unconfined,
            computationDispatcher = Dispatchers.Unconfined,
            fileReader = { throw CancellationException("request replaced") },
        )

        store.loadRecommendationCatalogPage(
            fingerprint = "MOVIE|cancelled",
            page = 1,
            maxAgeMs = 60_000L,
        )
        Unit
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
