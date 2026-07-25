package com.aliflix.app.player

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.PlaybackSource
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AniListResolverTest {
    @Test
    fun japaneseCandidateIsSelectedOverNonJapaneseMatch() = runTest {
        val resolver = AniListResolver {
            response(
                candidate(
                    id = 999,
                    englishTitle = "One Piece",
                    year = 1999,
                    format = "TV",
                    country = "US",
                ),
                candidate(
                    id = 21,
                    englishTitle = "One Piece",
                    year = 1999,
                    format = "TV",
                    country = "JP",
                ),
            )
        }
        val selection = PlaybackSelection(
            media = Media(
                id = 37854,
                type = MediaType.TV,
                title = "One Piece",
                year = "1999",
                isJapaneseAnime = true,
            ),
            seasonNumber = 1,
            episodeNumber = 12,
            source = PlaybackSource.miruro(),
        )

        assertEquals(
            "https://www.miruro.tv/watch/21/one-piece/12",
            resolver.resolveWatchUrl(selection),
        )
    }

    @Test
    fun laterSeasonUsesSeasonQueryIdAndEpisodeParameter() = runTest {
        val searches = mutableListOf<String>()
        val resolver = AniListResolver { requestBody ->
            val search = JSONObject(requestBody)
                .getJSONObject("variables")
                .getString("search")
            searches += search
            if (search == "My Hero Academia Season 3") {
                response(
                    candidate(
                        id = 100166,
                        englishTitle = "My Hero Academia Season 3",
                        year = 2018,
                        format = "TV",
                        country = "JP",
                    ),
                )
            } else {
                response()
            }
        }
        val selection = PlaybackSelection(
            media = Media(
                id = 65930,
                type = MediaType.TV,
                title = "My Hero Academia",
                year = "2018",
                isJapaneseAnime = true,
            ),
            seasonNumber = 3,
            episodeNumber = 4,
            source = PlaybackSource.miruro(),
        )

        assertEquals(
            "https://www.miruro.tv/watch/100166/my-hero-academia-season-3/4",
            resolver.resolveWatchUrl(selection),
        )
        assertEquals(
            listOf("My Hero Academia Season 3", "My Hero Academia"),
            searches,
        )
    }

    @Test
    fun nonJapaneseCandidatesAreRejected() = runTest {
        val resolver = AniListResolver {
            response(
                candidate(
                    id = 999,
                    englishTitle = "One Piece",
                    year = 1999,
                    format = "TV",
                    country = "US",
                ),
            )
        }
        val selection = PlaybackSelection(
            media = Media(
                id = 37854,
                type = MediaType.TV,
                title = "One Piece",
                year = "1999",
                isJapaneseAnime = true,
            ),
            source = PlaybackSource.miruro(),
        )

        try {
            resolver.resolveWatchUrl(selection)
            fail("Expected a non-Japanese result to be rejected")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("Japanese anime"))
        }
    }

    private fun response(vararg media: JSONObject): String =
        JSONObject()
            .put(
                "data",
                JSONObject().put(
                    "Page",
                    JSONObject().put("media", JSONArray(media.toList())),
                ),
            )
            .toString()

    private fun candidate(
        id: Int,
        englishTitle: String,
        year: Int,
        format: String,
        country: String,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("format", format)
        .put("countryOfOrigin", country)
        .put("startDate", JSONObject().put("year", year))
        .put(
            "title",
            JSONObject()
                .put("english", englishTitle)
                .put("romaji", englishTitle)
                .put("native", englishTitle),
        )
        .put("synonyms", JSONArray())
}
