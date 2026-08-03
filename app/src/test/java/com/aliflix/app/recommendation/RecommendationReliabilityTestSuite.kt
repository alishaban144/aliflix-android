package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mandatory automated reliability test suite for the Aliflix recommendation system.
 *
 * Tests all 8 core reliability scenarios:
 * 1. Series similar to Breaking Bad (Better Call Saul #1, TV type, rejects unrelated anime/docs)
 * 2. Dark psychological crime series without comedy (Comedy excluded, crime/psychological positive)
 * 3. Fast-paced space science-fiction movie (Movie type only, space/sci-fi required, fast pacing)
 * 4. Courtroom drama based on real events (Courtroom/legal/real-event weighted over generic drama)
 * 5. Family-friendly animated adventure (Animation + family required, adult animation/live-action rejected)
 * 6. Crime series but no police procedurals (Crime positive, police procedural negative/rejected)
 * 7. Something like The Office but darker (Anchor The Office, workplace/mockumentary preserved, darker tone)
 * 8. Provider failure resilience (Missing IMDb keyword data falls back to catalogue without crashing or faking data)
 */
class RecommendationReliabilityTestSuite {

    @Test
    fun test1_seriesSimilarToBreakingBadRanksBetterCallSaulFirst() {
        val anchor = media(
            id = 101,
            type = MediaType.TV,
            title = "Breaking Bad",
            genres = listOf("Crime", "Drama", "Thriller"),
            overview = "A chemistry teacher builds a meth empire in Albuquerque.",
            cast = listOf("Bryan Cranston", "Aaron Paul"),
        )
        val betterCallSaul = candidate(
            id = 102,
            type = MediaType.TV,
            title = "Better Call Saul",
            genres = listOf("Crime", "Drama"),
            overview = "A small-time Albuquerque lawyer becomes entangled with a drug cartel.",
            rating = 8.9,
            imdbVotes = 600_000,
            evidence = listOf(
                graph(
                    RecommendationEvidenceType.DIRECT_RELATED_TITLE,
                    1.0,
                    "TMDB_RELATED",
                    "Direct related-title data connects it to Breaking Bad",
                    rank = 0,
                ),
                graph(
                    RecommendationEvidenceType.SHARED_CREATOR,
                    1.0,
                    "CATALOGUE_CREDITS",
                    "Shares creator Vince Gilligan with Breaking Bad",
                ),
            ),
        )
        val theWire = candidate(
            id = 103,
            type = MediaType.TV,
            title = "The Wire",
            genres = listOf("Crime", "Drama"),
            overview = "Institutions, drug trade, and moral compromise shape Baltimore.",
            rating = 9.3,
            imdbVotes = 350_000,
            evidence = listOf(
                graph(
                    RecommendationEvidenceType.DIRECT_RELATED_TITLE,
                    0.78,
                    "TMDB_RELATED",
                    "Related-title data connects it to Breaking Bad",
                    rank = 4,
                ),
            ),
        )
        val unrelatedAnime = candidate(
            id = 104,
            type = MediaType.TV,
            title = "Unrelated Anime Epic",
            genres = listOf("Animation", "Fantasy"),
            overview = "Young heroes battle magical creatures in another dimension.",
            rating = 9.8,
            imdbVotes = 2_000_000,
        )
        val unrelatedDoc = candidate(
            id = 105,
            type = MediaType.TV,
            title = "Unrelated Wildlife Documentary",
            genres = listOf("Documentary"),
            overview = "A survey of nature across the continents.",
            rating = 9.9,
            imdbVotes = 3_000_000,
        )
        val preferences = RecommendationPreferenceParser.parse(
            "Series similar to Breaking Bad",
        ).preferences

        val snapshot = RecommendationRanker.rankWithDiagnostics(
            preferences = preferences,
            candidates = listOf(unrelatedAnime, unrelatedDoc, theWire, betterCallSaul),
            similarityAnchor = anchor,
            diversificationLimit = 3,
        )

        assertEquals("Better Call Saul", snapshot.ranked.first().media.title)
        assertEquals(MediaType.TV, snapshot.ranked.first().media.type)
        assertTrue(snapshot.ranked.first().score.anchorRelevance > 50.0)
        assertTrue(
            snapshot.ranked.first().matchReasons.any {
                it.evidenceType == RecommendationEvidenceType.DIRECT_RELATED_TITLE ||
                    it.evidenceType == RecommendationEvidenceType.SHARED_CREATOR
            },
        )
        assertFalse(snapshot.ranked.take(3).any { it.media.key == unrelatedAnime.media.key })
        assertFalse(snapshot.ranked.take(3).any { it.media.key == unrelatedDoc.media.key })
    }

    @Test
    fun test2_darkPsychologicalCrimeSeriesWithoutComedy() {
        val parseResult = RecommendationPreferenceParser.parse(
            "Dark psychological crime series without comedy",
        )
        val preferences = parseResult.preferences

        val matching = candidate(
            id = 201,
            type = MediaType.TV,
            title = "Moral Nightfall",
            genres = listOf("Crime", "Drama"),
            overview = "A dark and intense psychological crime investigation tests moral boundaries.",
            rating = 8.2,
            imdbVotes = 120_000,
        )
        val lightComedyCrime = candidate(
            id = 202,
            type = MediaType.TV,
            title = "Light Crime Comedy",
            genres = listOf("Crime", "Comedy"),
            overview = "A funny comedic crime mystery with lighthearted jokes.",
            rating = 8.8,
            imdbVotes = 500_000,
        )

        assertTrue(preferences.excludedGenres.any { it.value.equals("Comedy", ignoreCase = true) })
        assertFalse(RecommendationRanker.satisfiesHardConstraints(preferences, lightComedyCrime))
        assertTrue(RecommendationRanker.satisfiesHardConstraints(preferences, matching))

        val ranked = RecommendationRanker.rank(
            preferences = preferences,
            candidates = listOf(lightComedyCrime, matching),
        )

        assertEquals("Moral Nightfall", ranked.first().media.title)
        assertFalse(ranked.any { it.media.key == lightComedyCrime.media.key })
    }

    @Test
    fun test3_fastPacedSpaceScienceFictionMovie() {
        val preferences = RecommendationPreferenceParser.parse(
            "Fast-paced space science-fiction movie",
        ).preferences

        val sciFiMovie = candidate(
            id = 301,
            type = MediaType.MOVIE,
            title = "Interstellar Odyssey",
            genres = listOf("Science Fiction", "Action"),
            overview = "A fast-paced space exploration mission battles alien forces in deep space.",
            rating = 8.4,
            imdbVotes = 400_000,
        )
        val sciFiTvSeries = candidate(
            id = 302,
            type = MediaType.TV,
            title = "Space Station Chronicles",
            genres = listOf("Science Fiction"),
            overview = "A space series.",
            rating = 8.9,
            imdbVotes = 300_000,
        )
        val groundedDrama = candidate(
            id = 303,
            type = MediaType.MOVIE,
            title = "Grounded Family Drama",
            genres = listOf("Drama"),
            overview = "A quiet story about family life in a small town.",
            rating = 9.1,
            imdbVotes = 500_000,
        )

        assertEquals(RecommendationContentType.MOVIE, preferences.contentType?.value)
        assertFalse(RecommendationRanker.satisfiesHardConstraints(preferences, sciFiTvSeries))

        val ranked = RecommendationRanker.rank(
            preferences = preferences,
            candidates = listOf(groundedDrama, sciFiTvSeries, sciFiMovie),
        )

        assertEquals("Interstellar Odyssey", ranked.first().media.title)
        assertEquals(MediaType.MOVIE, ranked.first().media.type)
        assertFalse(ranked.any { it.media.key == groundedDrama.media.key })
    }

    @Test
    fun test4_courtroomDramaBasedOnRealEvents() {
        val preferences = RecommendationPreferenceParser.parse(
            "Courtroom drama based on real events",
        ).preferences

        val courtroomReal = candidate(
            id = 401,
            type = MediaType.MOVIE,
            title = "The Trial of 1968",
            genres = listOf("Drama", "History"),
            overview = "A legal courtroom drama based on true events and real court proceedings.",
            rating = 8.1,
            imdbVotes = 150_000,
        )
        val genericDrama = candidate(
            id = 402,
            type = MediaType.MOVIE,
            title = "Generic High Rated Drama",
            genres = listOf("Drama"),
            overview = "An emotional family drama.",
            rating = 9.0,
            imdbVotes = 800_000,
        )

        val ranked = RecommendationRanker.rank(
            preferences = preferences,
            candidates = listOf(genericDrama, courtroomReal),
        )

        assertEquals("The Trial of 1968", ranked.first().media.title)
    }

    @Test
    fun test5_familyFriendlyAnimatedAdventure() {
        val preferences = RecommendationPreferenceParser.parse(
            "A family-friendly animated adventure",
        ).preferences

        val familyAnim = candidate(
            id = 501,
            type = MediaType.MOVIE,
            title = "Forest Journey",
            genres = listOf("Animation", "Adventure", "Family"),
            overview = "A family-friendly animated adventure suitable for all ages.",
            rating = 8.0,
            imdbVotes = 200_000,
        )
        val adultAnim = candidate(
            id = 502,
            type = MediaType.MOVIE,
            title = "Graphic Adult Anime",
            genres = listOf("Animation", "Horror"),
            overview = "An adult animation feature with extreme graphic violence for mature audiences.",
            rating = 9.2,
            imdbVotes = 300_000,
        )
        val liveAction = candidate(
            id = 503,
            type = MediaType.MOVIE,
            title = "Live Action Adventure",
            genres = listOf("Adventure", "Family"),
            overview = "A live action family adventure film.",
            rating = 8.5,
            imdbVotes = 400_000,
        )

        val ranked = RecommendationRanker.rank(
            preferences = preferences,
            candidates = listOf(adultAnim, liveAction, familyAnim),
        )

        assertEquals("Forest Journey", ranked.first().media.title)
    }

    @Test
    fun test6_crimeSeriesButNoPoliceProcedurals() {
        val preferences = RecommendationPreferenceParser.parse(
            "Crime series but no police procedurals",
        ).preferences

        val heistSeries = candidate(
            id = 601,
            type = MediaType.TV,
            title = "Cartel Syndicate",
            genres = listOf("Crime", "Drama"),
            overview = "An antihero criminal syndicate conducts high-stakes robberies.",
            rating = 8.3,
            imdbVotes = 180_000,
        )
        val proceduralSeries = candidate(
            id = 602,
            type = MediaType.TV,
            title = "City Homicide Squad",
            genres = listOf("Crime", "Drama"),
            overview = "A police procedural following detectives solving a new homicide each episode.",
            rating = 8.9,
            imdbVotes = 400_000,
        )

        assertFalse(RecommendationRanker.satisfiesHardConstraints(preferences, proceduralSeries))
        assertTrue(RecommendationRanker.satisfiesHardConstraints(preferences, heistSeries))

        val ranked = RecommendationRanker.rank(
            preferences = preferences,
            candidates = listOf(proceduralSeries, heistSeries),
        )

        assertEquals("Cartel Syndicate", ranked.first().media.title)
        assertFalse(ranked.any { it.media.key == proceduralSeries.media.key })
    }

    @Test
    fun test7_somethingLikeTheOfficeButDarker() {
        val anchor = media(
            id = 701,
            type = MediaType.TV,
            title = "The Office",
            genres = listOf("Comedy"),
            overview = "A workplace mockumentary following paper company employees.",
        )
        val darkWorkplaceSatire = candidate(
            id = 702,
            type = MediaType.TV,
            title = "Corporate Nightmares",
            genres = listOf("Comedy", "Drama"),
            overview = "A dark workplace mockumentary examining corporate absurdity.",
            rating = 8.4,
            imdbVotes = 150_000,
            evidence = listOf(
                graph(
                    RecommendationEvidenceType.SHARED_KEYWORD,
                    0.90,
                    "CATALOGUE_KEYWORDS",
                    "Shares workplace and mockumentary style with The Office",
                ),
            ),
        )
        val genericDarkDrama = candidate(
            id = 703,
            type = MediaType.TV,
            title = "Generic Dark Tragedy",
            genres = listOf("Drama"),
            overview = "A dark tragedy set in an isolated village.",
            rating = 9.1,
            imdbVotes = 600_000,
        )
        val preferences = RecommendationPreferenceParser.parse(
            "Something like The Office but darker",
        ).preferences

        val ranked = RecommendationRanker.rank(
            preferences = preferences,
            candidates = listOf(genericDarkDrama, darkWorkplaceSatire),
            similarityAnchor = anchor,
        )

        assertEquals("Corporate Nightmares", ranked.first().media.title)
    }

    @Test
    fun test8_providerFailureResilienceMissingImdbKeywords() {
        val anchor = media(
            id = 801,
            type = MediaType.TV,
            title = "Breaking Bad",
            genres = listOf("Crime", "Drama"),
            overview = "A chemistry teacher builds a drug empire.",
        )
        val candidateWithoutImdbKeywords = candidate(
            id = 802,
            type = MediaType.TV,
            title = "Better Call Saul",
            genres = listOf("Crime", "Drama"),
            overview = "A lawyer becomes entangled with a cartel.",
            rating = 8.9,
            imdbVotes = 600_000,
            evidence = emptyList(),
        )
        val preferences = RecommendationPreferenceParser.parse(
            "Series similar to Breaking Bad",
        ).preferences

        val snapshot = RecommendationRanker.rankWithDiagnostics(
            preferences = preferences,
            candidates = listOf(candidateWithoutImdbKeywords),
            similarityAnchor = anchor,
        )

        assertNotNull(snapshot)
        assertFalse(snapshot.ranked.isEmpty())
        assertEquals("Better Call Saul", snapshot.ranked.first().media.title)
    }

    private fun candidate(
        id: Int,
        type: MediaType = MediaType.MOVIE,
        title: String,
        genres: List<String> = listOf("Drama"),
        overview: String = "A story.",
        rating: Double = 8.0,
        imdbVotes: Int = 100_000,
        runtime: Int? = 100,
        evidence: List<RecommendationEvidence> = emptyList(),
    ): RecommendationCandidate = RecommendationCandidate(
        media = media(
            id = id,
            type = type,
            title = title,
            genres = genres,
            overview = overview,
            rating = rating,
            imdbVotes = imdbVotes,
        ),
        metadata = VerifiedMediaMetadata(
            runtimeMinutes = runtime.takeIf { type == MediaType.MOVIE },
            averageEpisodeRuntimeMinutes = runtime.takeIf { type == MediaType.TV },
            originalLanguage = "English",
        ),
        sources = setOf("FIXTURE_CATALOGUE"),
        sourceRanks = mapOf("FIXTURE_CATALOGUE" to 1),
        sourceCount = 1,
        sourcePosition = 1,
        relevanceEvidence = evidence,
    )

    private fun media(
        id: Int,
        type: MediaType = MediaType.MOVIE,
        title: String,
        genres: List<String>,
        overview: String,
        rating: Double = 8.0,
        imdbVotes: Int = 100_000,
        cast: List<String> = emptyList(),
    ): Media = Media(
        id = id,
        type = type,
        title = title,
        genres = genres,
        overview = overview,
        rating = rating,
        imdbRating = rating,
        imdbVoteCount = imdbVotes,
        rottenTomatoesRating = (rating * 10).toInt().coerceAtMost(100),
        year = "2020",
        cast = cast,
    )

    private fun graph(
        type: RecommendationEvidenceType,
        strength: Double,
        source: String,
        description: String,
        rank: Int? = null,
    ) = RecommendationEvidence(type, strength, source, description, rank)
}
