package com.aliflix.app.data

import com.aliflix.app.model.MediaType
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

class ImdbRatingCacheV2Test {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `legacy identity-poisoned cache is removed on upgrade`() {
        val directory = temporaryFolder.newFolder("imdb-v1-upgrade")
        val legacy = File(directory, "imdb-ratings-v1.json")
        legacy.writeText("""{"entries":[]}""")

        store(directory)

        assertFalse(legacy.exists())
    }

    @Test fun `unavailable and loading are never persisted to disk`() = runBlocking {
        val directory = temporaryFolder.newFolder("imdb-no-poison")
        val store = store(directory)
        store.saveImdbRating(
            "movie:1",
            ImdbRatingSnapshot(
                identity = ImdbTitleIdentity("tt1111111", "Obsession", 2026, MediaType.MOVIE),
                rating = null,
                voteCount = null,
                state = RatingSourceState.UNAVAILABLE,
            ),
        )
        store.saveImdbRating(
            "movie:2",
            ImdbRatingSnapshot(
                identity = ImdbTitleIdentity("tt2222222", "Obsession", 2026, MediaType.MOVIE),
                rating = null,
                voteCount = null,
                state = RatingSourceState.LOADING,
            ),
        )
        assertNull(store.loadImdbRating("movie:1", Long.MAX_VALUE))
        assertNull(store.loadImdbRating("movie:2", Long.MAX_VALUE))
        assertFalse(File(directory, "imdb-ratings-v2.json").exists())
    }

    @Test fun `verified imdb rating with valid score is persisted and restored`() = runBlocking {
        val directory = temporaryFolder.newFolder("imdb-positive")
        val store = store(directory)
        store.saveImdbRating(
            "movie:1199347",
            ImdbRatingSnapshot(
                identity = ImdbTitleIdentity("tt37287335", "Obsession", 2025, MediaType.MOVIE),
                rating = 7.8,
                voteCount = 325953,
                state = RatingSourceState.VERIFIED,
            ),
        )
        store.saveImdbRating(
            "movie:222222",
            ImdbRatingSnapshot(
                identity = ImdbTitleIdentity("tt9999999", "Unreleased Title", 2027, MediaType.MOVIE),
                rating = null,
                voteCount = null,
                state = RatingSourceState.NOT_RATED,
            ),
        )
        val loaded = store.loadImdbRating("movie:1199347", Long.MAX_VALUE)
        assertEquals(7.8, loaded?.rating ?: 0.0, 0.001)
        assertEquals(325953, loaded?.voteCount)
        assertEquals("tt37287335", loaded?.identity?.imdbId)
        assertEquals(RatingSourceState.VERIFIED, loaded?.state)

        val unrated = store.loadImdbRating("movie:222222", Long.MAX_VALUE)
        assertEquals(RatingSourceState.NOT_RATED, unrated?.state)
        assertNull(unrated?.rating)
        assertTrue(File(directory, "imdb-ratings-v2.json").exists())
    }

    private fun store(directory: File) = AndroidCatalogCacheStore(
        cacheDir = directory,
        ioDispatcher = Dispatchers.Unconfined,
        computationDispatcher = Dispatchers.Unconfined,
        fileWriter = { target, value -> target.parentFile?.mkdirs(); target.writeText(value) },
    )
}
