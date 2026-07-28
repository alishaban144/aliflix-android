package com.aliflix.app.data

import com.aliflix.app.model.Episode
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.Season
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendationMetadataParserTest {
    private val client = CatalogClient { error("Network is forbidden in fixture tests") }

    @Test
    fun parsesVerifiedMovieMetadataFromTitlePage() {
        val html = """
            <main>
              <span class="runtime">2h 28m</span>
              <p><strong>Status</strong> Released</p>
              <p><strong>Original Language</strong> English</p>
              <ol class="people">
                <li class="profile">
                  <p><a href="/person/525">Christopher Nolan</a></p>
                  <p>Director</p>
                </li>
              </ol>
            </main>
        """.trimIndent()

        val result = client.parseVerifiedRecommendationMetadata(
            html,
            MediaType.MOVIE,
        )

        assertEquals(148, result.runtimeMinutes)
        assertEquals("English", result.originalLanguage)
        assertEquals("Released", result.status)
        assertEquals("Christopher Nolan", result.director)
        assertNull(result.averageEpisodeRuntimeMinutes)
    }

    @Test
    fun derivesTvSeasonCountAndAverageEpisodeLength() {
        val result = client.parseVerifiedRecommendationMetadata(
            html = "<main><p><strong>Original Language</strong> English</p></main>",
            type = MediaType.TV,
            seasons = listOf(
                Season(1, "Season 1"),
                Season(2, "Season 2"),
                Season(3, "Season 3"),
            ),
            episodes = listOf(
                Episode(1, 1, "One", runtime = "42m"),
                Episode(1, 2, "Two", runtime = "48m"),
            ),
        )

        assertEquals(3, result.seasonCount)
        assertEquals(45, result.averageEpisodeRuntimeMinutes)
        assertNull(result.runtimeMinutes)
    }

    @Test
    fun malformedHtmlReturnsNullableMetadataInsteadOfInventingFacts() {
        val result = client.parseVerifiedRecommendationMetadata(
            "<broken><strong>Runtime maybe tomorrow",
            MediaType.MOVIE,
        )

        assertNull(result.runtimeMinutes)
        assertNull(result.originalLanguage)
        assertNull(result.status)
        assertNull(result.director)
    }
}
