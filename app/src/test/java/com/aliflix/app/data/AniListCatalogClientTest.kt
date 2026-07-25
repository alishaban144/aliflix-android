package com.aliflix.app.data

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AniListCatalogClientTest {
    @Test
    fun searchMapsCompleteJapaneseAnimeAndNormalizesAliases() = runTest {
        var capturedRequest = ""
        val client = AniListCatalogClient { request ->
            capturedRequest = request
            response(
                anime(
                    id = 21,
                    country = "JP",
                    englishTitle = "Pokémon: The First Movie",
                    romajiTitle = "Pocket Monsters: Mewtwo no Gyakushuu",
                    nativeTitle = "劇場版ポケットモンスター",
                    synonyms = listOf("Pokemon: The First Movie", "Mewtwo Strikes Back"),
                    format = "MOVIE",
                    year = 1998,
                    episodes = 1,
                    description = "<b>A legendary Pokémon</b><br>faces its creators.",
                    cover = "https://images.example/extra-large.jpg",
                    banner = "https://images.example/banner.jpg",
                    genres = listOf("Action", "Adventure", "Fantasy"),
                    averageScore = 72,
                ),
                anime(
                    id = 999,
                    country = "US",
                    englishTitle = "Not Japanese",
                ),
                anime(
                    id = 1000,
                    country = "JP",
                    englishTitle = "Adult Result",
                    isAdult = true,
                ),
            )
        }

        val result = client.search("  Pokémon  ")

        assertEquals(1, result.size)
        val item = result.single()
        assertEquals(21, item.aniListId)
        assertEquals("Pokémon: The First Movie", item.preferredTitle)
        assertEquals("MOVIE", item.format)
        assertEquals(1998, item.year)
        assertEquals(1, item.episodes)
        assertEquals("A legendary Pokémon faces its creators.", item.description)
        assertEquals("https://images.example/extra-large.jpg", item.coverImageUrl)
        assertEquals("https://images.example/banner.jpg", item.bannerImageUrl)
        assertEquals(listOf("Action", "Adventure", "Fantasy"), item.genres)
        assertEquals(72, item.averageScore)
        assertEquals("JP", item.countryOfOrigin)
        assertTrue("pokemon the first movie" in item.normalizedAliases)
        assertTrue("pocket monsters mewtwo no gyakushuu" in item.normalizedAliases)
        assertTrue("劇場版ポケットモンスター" in item.normalizedAliases)
        assertTrue("mewtwo strikes back" in item.normalizedAliases)

        val request = JSONObject(capturedRequest)
        val variables = request.getJSONObject("variables")
        assertEquals("Pokémon", variables.getString("search"))
        assertEquals("JP", variables.getString("countryOfOrigin"))
        assertEquals(
            listOf("SEARCH_MATCH", "POPULARITY_DESC"),
            variables.getJSONArray("sort").strings(),
        )
        val query = request.getString("query")
        assertTrue(query.contains("type: ANIME"))
        assertTrue(query.contains("isAdult: false"))
        assertTrue(query.contains("countryOfOrigin: ${'$'}countryOfOrigin"))
        assertFalse(query.contains("countryOfOrigin: JP"))
        assertEquals(
            1,
            query.split("countryOfOrigin: ${'$'}countryOfOrigin").size - 1,
        )
    }

    @Test
    fun trendingUsesTrendingOrderFallsBackToLargeCoverAndDeduplicatesIds() = runTest {
        var capturedRequest = ""
        val client = AniListCatalogClient { request ->
            capturedRequest = request
            response(
                anime(
                    id = 16498,
                    country = "jp",
                    englishTitle = null,
                    romajiTitle = "Shingeki no Kyojin",
                    nativeTitle = "進撃の巨人",
                    cover = null,
                    largeCover = "https://images.example/large.jpg",
                    averageScore = null,
                ),
                anime(
                    id = 16498,
                    country = "JP",
                    englishTitle = "Attack on Titan duplicate",
                ),
            )
        }

        val result = client.trending()

        assertEquals(1, result.size)
        assertEquals("Shingeki no Kyojin", result.single().preferredTitle)
        assertEquals("https://images.example/large.jpg", result.single().coverImageUrl)
        assertNull(result.single().averageScore)
        val variables = JSONObject(capturedRequest).getJSONObject("variables")
        assertTrue(variables.isNull("search"))
        assertEquals(
            listOf("TRENDING_DESC", "POPULARITY_DESC"),
            variables.getJSONArray("sort").strings(),
        )
    }

    @Test
    fun blankSearchReturnsWithoutCallingAniList() = runTest {
        var called = false
        val client = AniListCatalogClient {
            called = true
            response()
        }

        assertTrue(client.search("   ").isEmpty())
        assertFalse(called)
    }

    @Test
    fun graphqlErrorsBecomeMeaningfulIoExceptions() = runTest {
        val client = AniListCatalogClient {
            JSONObject()
                .put(
                    "errors",
                    JSONArray().put(JSONObject().put("message", "Too many requests.")),
                )
                .toString()
        }

        try {
            client.trending()
            fail("Expected AniList GraphQL errors to fail the request")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("Too many requests"))
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

    private fun anime(
        id: Int,
        country: String,
        englishTitle: String?,
        romajiTitle: String? = englishTitle,
        nativeTitle: String? = englishTitle,
        synonyms: List<String> = emptyList(),
        format: String? = "TV",
        year: Int? = 2024,
        episodes: Int? = 12,
        description: String? = "Description",
        cover: String? = "https://images.example/cover.jpg",
        largeCover: String? = null,
        banner: String? = null,
        genres: List<String> = listOf("Animation"),
        averageScore: Int? = 80,
        isAdult: Boolean = false,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("countryOfOrigin", country)
        .put("isAdult", isAdult)
        .put("format", format)
        .put("startDate", JSONObject().put("year", year))
        .put("episodes", episodes)
        .put(
            "title",
            JSONObject()
                .put("english", englishTitle)
                .put("romaji", romajiTitle)
                .put("native", nativeTitle),
        )
        .put("synonyms", JSONArray(synonyms))
        .put("description", description)
        .put(
            "coverImage",
            JSONObject()
                .put("extraLarge", cover)
                .put("large", largeCover),
        )
        .put("bannerImage", banner)
        .put("genres", JSONArray(genres))
        .put("averageScore", averageScore)

    private fun JSONArray.strings(): List<String> =
        (0 until length()).map(::getString)
}
