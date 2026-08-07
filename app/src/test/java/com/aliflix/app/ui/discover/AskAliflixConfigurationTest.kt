package com.aliflix.app.ui.discover

import com.aliflix.app.recommendation.RecommendationMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAliflixConfigurationTest {
    @Test fun `movie and series genre sets remain canonical and separate`() {
        assertTrue("TV Movie" in MOVIE_GENRES)
        assertFalse("TV Movie" in TV_GENRES)
        assertTrue("Sci-Fi & Fantasy" in TV_GENRES)
        assertFalse("Sci-Fi & Fantasy" in MOVIE_GENRES)
    }

    @Test fun `movie filters omit series status and rotten tomatoes thresholds`() {
        val groups = askFilterGroups(RecommendationMediaKind.MOVIE)
        assertEquals(listOf("Genre", "Mood & tone", "Story & themes", "Characters", "Setting", "Era", "Runtime", "Rating", "Language", "Discovery style", "Exclude"), groups.map { it.title })
        assertFalse(groups.flatMap { it.options }.any { it.startsWith("RT ") })
    }

    @Test fun `series filters expose status and canonical runtime choices`() {
        val groups = askFilterGroups(RecommendationMediaKind.SERIES)
        assertEquals(SERIES_STATUS, groups.single { it.title == "Series status" }.options)
        assertEquals(TV_RUNTIMES, groups.single { it.title == "Runtime" }.options)
    }
}
