package com.aliflix.app.data

import com.aliflix.app.model.RatingSourceState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RottenTomatoesCacheV3Test {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `unavailable and loading are never persisted`() = runBlocking {
        val directory = temporaryFolder.newFolder("rt-v3-no-poison")
        val store = store(directory)
        store.saveRottenTomatoesRating("movie:1", RottenTomatoesSnapshot(null, RatingSourceState.UNAVAILABLE))
        store.saveRottenTomatoesRating("movie:2", RottenTomatoesSnapshot(null, RatingSourceState.LOADING))
        assertNull(store.loadRottenTomatoesRating("movie:1", Long.MAX_VALUE))
        assertNull(store.loadRottenTomatoesRating("movie:2", Long.MAX_VALUE))
        assertFalse(File(directory, "rotten-tomatoes-ratings-v3.json").exists())
    }

    @Test fun `verified and confirmed not rated are restored`() = runBlocking {
        val directory = temporaryFolder.newFolder("rt-v3-positive")
        val store = store(directory)
        store.saveRottenTomatoesRating("movie:1", RottenTomatoesSnapshot(97, RatingSourceState.VERIFIED))
        store.saveRottenTomatoesRating("movie:2", RottenTomatoesSnapshot(null, RatingSourceState.NOT_RATED))
        assertEquals(97, store.loadRottenTomatoesRating("movie:1", Long.MAX_VALUE)?.rating)
        assertEquals(RatingSourceState.NOT_RATED, store.loadRottenTomatoesRating("movie:2", Long.MAX_VALUE)?.state)
        assertTrue(File(directory, "rotten-tomatoes-ratings-v3.json").exists())
    }

    @Test fun `legacy negative cache generations are ignored`() = runBlocking {
        val directory = temporaryFolder.newFolder("rt-v3-legacy")
        File(directory, "rotten-tomatoes-ratings-v2.json").writeText("""{"entries":[{"key":"movie:1","state":"NOT_RATED","fetchedAt":${System.currentTimeMillis()}}]}""")
        val store = store(directory)
        assertNull(store.loadRottenTomatoesRating("movie:1", Long.MAX_VALUE))
        assertFalse(File(directory, "rotten-tomatoes-ratings-v2.json").exists())
    }

    private fun store(directory: File) = AndroidCatalogCacheStore(
        cacheDir = directory,
        ioDispatcher = Dispatchers.Unconfined,
        computationDispatcher = Dispatchers.Unconfined,
        fileWriter = { target, value -> target.parentFile?.mkdirs(); target.writeText(value) },
    )
}
