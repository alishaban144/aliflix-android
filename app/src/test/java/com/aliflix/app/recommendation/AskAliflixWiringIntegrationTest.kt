package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.discover.AskAliflixRequest
import com.aliflix.app.ui.discover.AskAliflixRequestMapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAliflixWiringIntegrationTest {
    @Test
    fun groqIsTheDefaultForFreshSettingsAndUnspecifiedRequests() {
        assertEquals(
            RecommendationAiModel.GROQ_QWEN_3_8_27B,
            RecommendationAiModel.fromWorkerValue(null),
        )
        assertEquals(
            RecommendationAiModel.GROQ_QWEN_3_8_27B,
            RecommendationAiModel.fromWorkerValue("unknown-model"),
        )

        val mapped = AskAliflixRequestMapper.map(
            request = AskAliflixRequest.Describe(MediaType.MOVIE, "space adventure"),
            requestId = "00000000-0000-4000-8000-000000000014",
        ).workerRequest
        val direct = V3RecommendationRequest(
            requestId = "00000000-0000-4000-8000-000000000015",
            query = "space adventure",
            mediaType = "movie",
        )

        assertEquals(RecommendationAiModel.GROQ_QWEN_3_8_27B.workerValue, mapped.aiModel)
        assertEquals(RecommendationAiModel.GROQ_QWEN_3_8_27B.workerValue, direct.aiModel)
    }

    @Test
    fun explicitSavedGeminiChoiceIsStillHonored() {
        assertEquals(
            RecommendationAiModel.GEMINI_3_5_FLASH,
            RecommendationAiModel.fromWorkerValue("gemini-3.5-flash"),
        )
        assertEquals(
            RecommendationAiModel.GEMINI_3_7_FLASH,
            RecommendationAiModel.fromWorkerValue("gemini-3.7-flash"),
        )
    }

    @Test
    fun selectedAiModelIsSerializedExplicitly() {
        val json = AskAliflixRequestMapper.map(
            request = AskAliflixRequest.Describe(MediaType.MOVIE, "space adventure"),
            requestId = "00000000-0000-4000-8000-000000000010",
            aiModel = RecommendationAiModel.GEMINI_3_7_FLASH,
        ).workerRequest.toJson()

        assertEquals("gemini-3.7-flash", json.getString("aiModel"))
        assertEquals("gemini-3.7-flash", json.getString("geminiModel"))
    }

    @Test
    fun selectedGroqModelIsSerializedForTheWorker() {
        val json = AskAliflixRequestMapper.map(
            request = AskAliflixRequest.Describe(MediaType.MOVIE, "space adventure"),
            requestId = "00000000-0000-4000-8000-000000000012",
            aiModel = RecommendationAiModel.GROQ_QWEN_3_8_27B,
        ).workerRequest.toJson()

        assertEquals("groq-qwen-3.8-27b", json.getString("aiModel"))
        assertEquals("groq-qwen-3.8-27b", json.getString("geminiModel"))
        assertEquals(20, json.getInt("pageSize"))
    }

    @Test
    fun filterModeKeepsItsLargerCataloguePage() {
        val json = AskAliflixRequestMapper.map(
            request = AskAliflixRequest.Filters(CatalogDiscoverySpec(RecommendationMediaKind.MOVIE)),
            requestId = "00000000-0000-4000-8000-000000000013",
        ).workerRequest.toJson()

        assertEquals(24, json.getInt("pageSize"))
    }

    @Test
    fun describeForwardsRawQueryAndSelectedTvType() {
        val mapped = AskAliflixRequestMapper.map(
            AskAliflixRequest.Describe(MediaType.TV, "  kids with supernatural powers  "),
            "00000000-0000-4000-8000-000000000001",
        )
        val json = mapped.workerRequest.toJson()
        assertEquals("kids with supernatural powers", json.getString("query"))
        assertEquals("tv", json.getString("mediaType"))
        assertEquals("describe", json.getString("mode"))
        assertFalse(json.getString("query").contains("Series —"))
    }

    @Test
    fun refinedDescribeSummaryKeepsTheCompleteActiveRequest() {
        val mapped = AskAliflixRequestMapper.map(
            AskAliflixRequest.Describe(
                mediaType = MediaType.MOVIE,
                text = "alien contact, less scary",
                previousText = "alien contact",
                refinementText = "less scary",
            ),
            "00000000-0000-4000-8000-000000000016",
        )

        assertEquals("Movies — “alien contact, less scary”", mapped.summary)
        assertEquals("alien contact, less scary", mapped.workerRequest.query)
        assertEquals("alien contact", mapped.workerRequest.previousQuery)
        assertEquals("less scary", mapped.workerRequest.refinementQuery)
    }

    @Test
    fun seriesStatusIsForwardedForDescribeAndNeverConvertedIntoQueryText() {
        val json = AskAliflixRequestMapper.map(
            AskAliflixRequest.Describe(
                mediaType = MediaType.TV,
                text = "people trapped in a mysterious place",
                requiredStatus = "Returning Series",
            ),
            "00000000-0000-4000-8000-000000000006",
        ).workerRequest.toJson()

        assertEquals("returning", json.getJSONObject("filters").getString("seriesStatus"))
        assertEquals("people trapped in a mysterious place", json.getString("query"))
    }

    @Test
    fun seriesStatusIsAvailableAndForwardedInEveryAskMode() {
        val anchor = Media(id = 1396, type = MediaType.TV, title = "Breaking Bad")
        val similarFilters = AskAliflixRequestMapper.map(
            AskAliflixRequest.Similar(
                outputMediaType = MediaType.TV,
                anchor = anchor,
                requiredStatus = "Ended",
            ),
            "00000000-0000-4000-8000-000000000007",
        ).workerRequest.toJson().getJSONObject("filters")
        val filterModeFilters = AskAliflixRequestMapper.map(
            AskAliflixRequest.Filters(
                CatalogDiscoverySpec(
                    mediaKind = RecommendationMediaKind.SERIES,
                    requiredStatus = "Returning Series",
                ),
            ),
            "00000000-0000-4000-8000-000000000008",
        ).workerRequest.toJson().getJSONObject("filters")

        assertEquals("ended", similarFilters.getString("seriesStatus"))
        assertEquals("returning", filterModeFilters.getString("seriesStatus"))
    }

    @Test
    fun similarPreservesAnchorTmdbIdentityAndRequestedOutputType() {
        val anchor = Media(id = 1396, type = MediaType.TV, title = "Breaking Bad")
        val json = AskAliflixRequestMapper.map(
            AskAliflixRequest.Similar(outputMediaType = MediaType.TV, anchor = anchor),
            "00000000-0000-4000-8000-000000000002",
        ).workerRequest.toJson()
        assertEquals("similar", json.getString("mode"))
        assertEquals("tv", json.getString("mediaType"))
        assertEquals(1396, json.getJSONObject("anchor").getInt("tmdbId"))
        assertEquals("tv", json.getJSONObject("anchor").getString("mediaType"))
        assertEquals("", json.getString("query"))
    }

    @Test
    fun multiAnchorBlendsTitlesAndSendsAnchorsArray() {
        val anchor1 = Media(id = 157336, type = MediaType.MOVIE, title = "Interstellar")
        val anchor2 = Media(id = 335984, type = MediaType.MOVIE, title = "Blade Runner 2049")
        val json = AskAliflixRequestMapper.map(
            AskAliflixRequest.Similar(outputMediaType = MediaType.MOVIE, anchors = listOf(anchor1, anchor2)),
            "00000000-0000-4000-8000-000000000005",
        ).workerRequest.toJson()
        assertEquals("similar", json.getString("mode"))
        assertEquals("movie", json.getString("mediaType"))
        val anchorsArray = json.getJSONArray("anchors")
        assertEquals(2, anchorsArray.length())
        assertEquals(157336, anchorsArray.getJSONObject(0).getInt("tmdbId"))
        assertEquals(335984, anchorsArray.getJSONObject(1).getInt("tmdbId"))
        assertEquals("", json.getString("query"))
    }

    @Test
    fun similarAnchorRequiresCanonicalTmdbIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            V3RecommendationAnchor(tmdbId = 0, title = "Breaking Bad", mediaType = "tv")
        }
    }

    @Test
    fun filtersSerializeEverySupportedTmdbConstraintWithoutOmdbFields() {
        val spec = CatalogDiscoverySpec(
            mediaKind = RecommendationMediaKind.SERIES,
            includedGenres = listOf("Crime"), excludedGenres = listOf("Comedy"),
            runtimeMinimumMinutes = 40, runtimeMaximumMinutes = 70,
            yearMinimum = 2021, yearMaximum = 2025, minimumTmdb = 7.5,
            originalLanguage = "ko", countries = listOf("KR"), discoveryText = "serial killers",
            productionCompanies = listOf(ProductionCompanyFilter(41077, "A24")),
        )
        val filters = AskAliflixRequestMapper.map(
            AskAliflixRequest.Filters(spec), "00000000-0000-4000-8000-000000000003",
        ).workerRequest.toJson().getJSONObject("filters")
        assertEquals(2021, filters.getInt("minimumYear"))
        assertEquals("ko", filters.getString("originalLanguage"))
        assertEquals("KR", filters.getJSONArray("originCountries").getString(0))
        assertEquals(41077, filters.getJSONArray("productionCompanyIds").getInt(0))
        assertEquals(7.5, filters.getDouble("minimumTmdbRating"), 0.0)
        assertEquals("most_popular", filters.getString("sortBy"))
        assertFalse(filters.has("minimumImdb"))
        assertFalse(filters.has("minimumRottenTomatoes"))
    }

    @Test
    fun animationChoicesSerializeOnlyCanonicalTmdbGenreAndOriginCountryConstraints() {
        val japanese = AskAliflixRequestMapper.map(
            AskAliflixRequest.Filters(
                CatalogDiscoverySpec(
                    mediaKind = RecommendationMediaKind.SERIES,
                    includedGenres = listOf("Sci-Fi & Fantasy"),
                    excludedGenres = listOf("Animation"),
                    originalLanguage = "ko",
                    countries = listOf("US"),
                    animationFilter = AnimationFilter.JAPANESE_ANIMATION,
                ),
            ),
            "00000000-0000-4000-8000-000000000009",
        ).workerRequest.toJson().getJSONObject("filters")

        assertFalse(japanese.has("originalLanguage"))
        assertEquals("JP", japanese.getJSONArray("originCountries").getString(0))
        val japaneseIncluded = japanese.getJSONArray("includedGenres")
        assertEquals(2, japaneseIncluded.length())
        assertEquals("Sci-Fi & Fantasy", japaneseIncluded.getString(0))
        assertEquals("Animation", japaneseIncluded.getString(1))
        assertEquals(0, japanese.getJSONArray("excludedGenres").length())

        val american = AskAliflixRequestMapper.map(
            AskAliflixRequest.Filters(
                CatalogDiscoverySpec(
                    mediaKind = RecommendationMediaKind.MOVIE,
                    animationFilter = AnimationFilter.AMERICAN_ANIMATION,
                ),
            ),
            "00000000-0000-4000-8000-000000000010",
        ).workerRequest.toJson().getJSONObject("filters")
        assertEquals("US", american.getJSONArray("originCountries").getString(0))
        val americanIncluded = american.getJSONArray("includedGenres")
        assertEquals(1, americanIncluded.length())
        assertEquals("Animation", americanIncluded.getString(0))
    }

    @Test
    fun selectedSortIsForwardedToTheWorker() {
        val filters = AskAliflixRequestMapper.map(
            AskAliflixRequest.Filters(
                CatalogDiscoverySpec(
                    mediaKind = RecommendationMediaKind.MOVIE,
                    sortBy = RecommendationSort.HIGHEST_RATED,
                ),
            ),
            "00000000-0000-4000-8000-000000000011",
        ).workerRequest.toJson().getJSONObject("filters")

        assertEquals("highest_rated", filters.getString("sortBy"))
    }

    @Test
    fun responseMappingRetainsTmdbMetadataAndCursor() {
        val response = V3RecommendationResponse.fromJson(JSONObject("""
            {"requestId":"r","totalResults":137,"nextCursor":"cursor","hasMore":true,"results":[{
              "tmdbId":60059,"mediaType":"tv","title":"Better Call Saul","originalTitle":"Better Call Saul",
              "overview":"A lawyer's transformation","posterPath":"/better-call-saul.jpg","releaseDate":"2015-02-08","genres":["Crime","Drama"],
              "runtimeMinutes":47,"originalLanguage":"en","originCountries":["US"],"tmdbRating":8.7,
              "tmdbVoteCount":6000,"status":"Ended","matchLevel":"Exceptional","finalScore":0.92,
              "matchReasons":["Recommended by TMDB"],"retrievalSources":["recommendations:page-1"]}
        ]} """))
        val item = response.results.single()
        assertEquals(listOf("Crime", "Drama"), item.genres)
        assertEquals(47, item.runtimeMinutes)
        assertEquals(8.7, item.tmdbRating!!, 0.0)
        assertEquals("Ended", item.status)
        assertEquals(listOf("Recommended by TMDB"), item.matchReasons)
        assertEquals(
            "https://image.tmdb.org/t/p/w500/better-call-saul.jpg",
            Media(id = item.tmdbId, type = MediaType.TV, title = item.title, posterPath = item.posterPath).posterUrl,
        )
        assertEquals("cursor", response.nextCursor)
        assertEquals(137, response.totalResults)
        assertTrue(response.hasMore)
    }
}
