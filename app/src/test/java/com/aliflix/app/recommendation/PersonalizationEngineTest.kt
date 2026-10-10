package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationEngineTest {
    private val sciFiMovie = Media(
        id = 1,
        type = MediaType.MOVIE,
        title = "Dream Worlds",
        year = "2012",
        genres = listOf("Science Fiction", "Thriller"),
        overview = "An astronaut is stranded on a distant planet and must survive.",
    )
    private val dramaSeries = Media(
        id = 2,
        type = MediaType.TV,
        title = "City Stories",
        year = "2020",
        genres = listOf("Drama"),
    )

    @Test
    fun noLibrarySignalsMeansNoInventedPercentage() {
        assertNull(PersonalizationEngine.match(sciFiMovie, emptyList()))
    }

    @Test
    fun matchChangesWhenLikesChange() {
        val similar = Media(
            id = 3,
            type = MediaType.MOVIE,
            title = "Another Dream",
            year = "2014",
            genres = listOf("Science Fiction"),
            overview = "A castaway astronaut struggles for survival after a spaceship crashes.",
        )

        val movieTaste = PersonalizationEngine.match(similar, listOf(sciFiMovie))
        val seriesTaste = PersonalizationEngine.match(similar, listOf(dramaSeries))

        assertNotNull(movieTaste)
        assertNull(seriesTaste)
        assertEquals(PersonalMatchTier.GOOD, movieTaste!!.tier)
    }

    @Test
    fun exactLikedTitleGetsAValidScore() {
        val match = PersonalizationEngine.match(sciFiMovie, listOf(sciFiMovie))

        assertEquals(PersonalMatchTier.LIKED, match!!.tier)
    }
}
