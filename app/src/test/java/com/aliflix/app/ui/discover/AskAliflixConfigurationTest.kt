package com.aliflix.app.ui.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAliflixConfigurationTest {
    @Test
    fun testCanonicalOmdbGenresContainsRealImdbTaxonomy() {
        assertTrue("Sci-Fi" in CANONICAL_OMDB_GENRES)
        assertTrue("Film-Noir" in CANONICAL_OMDB_GENRES)
        assertTrue("Reality-TV" in CANONICAL_OMDB_GENRES)
        assertTrue("Action" in CANONICAL_OMDB_GENRES)
        assertFalse("Sci-Fi & Fantasy" in CANONICAL_OMDB_GENRES)
        assertFalse("Action & Adventure" in CANONICAL_OMDB_GENRES)
    }

    @Test
    fun testFilterPresets() {
        assertTrue("2020+" in YEAR_PRESETS)
        assertTrue("7+" in IMDB_RATING_PRESETS)
        assertTrue("80%+" in RT_RATING_PRESETS)
        assertTrue("70+" in METASCORE_PRESETS)
        assertTrue("English" in LANGUAGES)
        assertTrue("PG-13" in CONTENT_RATINGS)
        assertTrue("Best match" in SORT_MODES)
    }
}
