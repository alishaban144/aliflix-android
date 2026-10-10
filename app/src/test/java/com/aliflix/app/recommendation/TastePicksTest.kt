package com.aliflix.app.recommendation

import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test

class TastePicksTest {
    private fun title(id: Int, genres: List<String>, votes: Int = 1000) =
        Media(id, MediaType.MOVIE, "Title $id", posterPath = "/poster.jpg", rating = 7.5, tmdbVoteCount = votes, genres = genres, overview = if ("Science Fiction" in genres) "An astronaut is stranded on a distant planet and must survive." else "A couple falls in love.")
    @Test fun onlyPopularRelevantUnseenTitlesSurvive() {
        val anchor = title(1, listOf("Science Fiction", "Adventure"))
        val related = title(2, listOf("Science Fiction", "Adventure"))
        val unrelated = title(3, listOf("Romance", "Comedy"), 100000)
        val obscure = title(4, listOf("Science Fiction"), 2).copy(overview = "A superhero battles criminals.")
        assertEquals(listOf(related), TastePicks.rank(listOf(anchor, related, unrelated, obscure, related), listOf(anchor), setOf(anchor.key)))
    }
    @Test fun emptyHistoryAndBroadGenreOnlyNeverProducePersonalPicks() {
        val drama = title(1, listOf("Drama")).copy(overview = "")
        assertTrue(TastePicks.rank(listOf(title(2, listOf("Drama")).copy(overview = "")), listOf(drama), emptySet()).isEmpty())
        assertTrue(TastePicks.rank(listOf(drama), emptyList(), emptySet()).isEmpty())
    }
}
