package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedContentEngineTest {
    @Test
    fun franchiseGenreAndCastBeatUnrelatedPopularity() {
        val source = Media(
            id = 1,
            type = MediaType.MOVIE,
            title = "The Dark Knight",
            year = "2008",
            genres = listOf("Action", "Crime", "Drama"),
            cast = listOf("Christian Bale", "Michael Caine"),
            overview = "Batman confronts a criminal mastermind in Gotham.",
        )
        val sequel = Media(
            id = 2,
            type = MediaType.MOVIE,
            title = "The Dark Knight Rises",
            year = "2012",
            genres = listOf("Action", "Crime", "Drama"),
            cast = listOf("Christian Bale", "Michael Caine"),
            overview = "Batman returns to defend Gotham from a dangerous enemy.",
        )
        val unrelated = Media(
            id = 3,
            type = MediaType.MOVIE,
            title = "A Popular Romance",
            year = "2009",
            rating = 9.9,
            genres = listOf("Romance"),
        )

        val ranked = RelatedContentEngine.rank(source, listOf(unrelated, sequel))

        assertEquals(sequel.key, ranked.first().key)
        assertTrue(
            RelatedContentEngine.similarity(source, sequel) >
                RelatedContentEngine.similarity(source, unrelated),
        )
    }

    @Test
    fun localSimilarityScoresOnlyTheDeterministicBoundedPrefix() {
        val source = Media(
            id = 1,
            type = MediaType.MOVIE,
            title = "Reference Point",
            genres = listOf("Crime", "Drama"),
            cast = listOf("Lead Actor"),
            overview = "A meticulous investigator follows a criminal conspiracy.",
        )
        val ordinaryPrefix = (2..41).map { id ->
            Media(
                id = id,
                type = MediaType.MOVIE,
                title = "Catalogue Film $id",
                genres = listOf("Drama"),
            )
        }
        val perfectButOutsideBound = source.copy(id = 9_999, title = "Reference Point II")
        val candidates = ordinaryPrefix + perfectButOutsideBound

        val first = RelatedContentEngine.rank(
            source = source,
            candidates = candidates,
            candidateScoreLimit = 24,
        )
        val second = RelatedContentEngine.rank(
            source = source,
            candidates = candidates,
            candidateScoreLimit = 24,
        )

        assertEquals(24, first.size)
        assertEquals(first.map(Media::key), second.map(Media::key))
        assertTrue(first.none { it.key == perfectButOutsideBound.key })
    }
}
