package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotSearchRankerTest {
    @Test
    fun dreamDescriptionRanksInceptionAndPaprikaAheadOfUnrelatedMovies() {
        val inception = Media(
            id = 27205,
            type = MediaType.MOVIE,
            title = "Inception",
            overview = "A thief enters people's dreams and steals secrets from the subconscious.",
            rating = 8.4,
            genres = listOf("Science Fiction", "Thriller"),
        )
        val paprika = Media(
            id = 4977,
            type = MediaType.MOVIE,
            title = "Paprika",
            overview = "A psychologist uses a device that lets people enter other people's dreams.",
            rating = 7.8,
            genres = listOf("Animation", "Science Fiction"),
        )
        val unrelated = Media(
            id = 155,
            type = MediaType.MOVIE,
            title = "The Dark Knight",
            overview = "A masked hero fights a criminal mastermind in Gotham.",
            rating = 9.0,
            genres = listOf("Action", "Crime"),
        )

        val ranked = PlotSearchRanker.rank(
            "a movie where the protagonist goes into the dreams of others",
            listOf(unrelated, paprika, inception),
        )

        assertEquals(inception.key, ranked.first().key)
        assertTrue(ranked.indexOf(paprika) < ranked.indexOf(unrelated))
    }
}
