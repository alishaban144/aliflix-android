package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationRankingGoldenTest {
    @Test
    fun seriesSimilarToBreakingBadRanksBetterCallSaulFirstFromRelationshipEvidence() {
        val anchor = media(
            id = 1,
            type = MediaType.TV,
            title = "Breaking Bad",
            genres = listOf("Crime", "Drama", "Thriller"),
            overview = "A chemistry teacher builds a drug empire in Albuquerque.",
            cast = listOf("Bryan Cranston", "Aaron Paul"),
        )
        val betterCallSaul = candidate(
            id = 2,
            type = MediaType.TV,
            title = "Better Call Saul",
            genres = listOf("Crime", "Drama"),
            overview = "A small-time Albuquerque lawyer becomes entangled with a cartel.",
            rating = 8.9,
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
            id = 3,
            type = MediaType.TV,
            title = "The Wire",
            genres = listOf("Crime", "Drama"),
            overview = "Institutions, crime, and moral compromise shape Baltimore.",
            rating = 9.3,
            evidence = listOf(
                graph(
                    RecommendationEvidenceType.DIRECT_RELATED_TITLE,
                    0.78,
                    "TMDB_RELATED",
                    "Related-title data connects it to Breaking Bad",
                    rank = 4,
                ),
                graph(
                    RecommendationEvidenceType.SHARED_KEYWORD,
                    0.80,
                    "CATALOGUE_KEYWORDS",
                    "Shares crime and moral-compromise themes with Breaking Bad",
                ),
            ),
        )
        val unrelatedAnime = candidate(
            id = 4,
            type = MediaType.TV,
            title = "Unrelated Anime Epic",
            genres = listOf("Animation", "Fantasy"),
            overview = "Young heroes battle magical creatures in another world.",
            rating = 9.8,
            imdbVotes = 2_000_000,
        )
        val unrelatedPrestige = candidate(
            id = 5,
            type = MediaType.TV,
            title = "Unrelated Prestige Nature Series",
            genres = listOf("Documentary"),
            overview = "A survey of wildlife across the planet.",
            rating = 9.9,
            imdbVotes = 3_000_000,
        )
        val preferences = RecommendationPreferenceParser.parse(
            "Series similar to Breaking Bad",
        ).preferences

        val snapshot = RecommendationRanker.rankWithDiagnostics(
            preferences = preferences,
            candidates = listOf(
                unrelatedAnime,
                unrelatedPrestige,
                theWire,
                betterCallSaul,
            ),
            similarityAnchor = anchor,
            diversificationLimit = 3,
        )

        assertEquals("Better Call Saul", snapshot.ranked.first().media.title)
        assertTrue(snapshot.ranked.first().score.anchorRelevance > 50.0)
        assertTrue(
            snapshot.ranked.first().matchReasons.any {
                it.evidenceType == RecommendationEvidenceType.DIRECT_RELATED_TITLE
            },
        )
        assertTrue(snapshot.ranked.first().explanation.contains("Breaking Bad"))
        assertFalse(snapshot.ranked.take(3).any { it.media.key == unrelatedAnime.media.key })
        assertFalse(snapshot.ranked.take(3).any { it.media.key == unrelatedPrestige.media.key })
        assertTrue(
            snapshot.rejectedLowConfidence.map { it.media.key }.containsAll(
                listOf(unrelatedAnime.media.key, unrelatedPrestige.media.key),
            ),
        )

        val withoutBetterCallSaul = RecommendationRanker.rank(
            preferences = preferences,
            candidates = listOf(unrelatedAnime, unrelatedPrestige, theWire),
            similarityAnchor = anchor,
        )
        assertEquals("The Wire", withoutBetterCallSaul.first().media.title)
        assertTrue(
            withoutBetterCallSaul.first().relevanceEvidence.any {
                it.isAnchorGraphEvidence
            },
        )
    }

    @Test
    fun movieSimilarityUsesTheSameEvidenceRulesWithoutATitlePairOverride() {
        val anchor = media(
            id = 10,
            title = "The Godfather",
            genres = listOf("Crime", "Drama"),
            overview = "A mafia family transfers power between generations.",
        )
        val sequel = candidate(
            id = 11,
            title = "The Godfather Part II",
            genres = listOf("Crime", "Drama"),
            overview = "The mafia family expands while its earlier generation rises.",
            rating = 9.0,
            evidence = listOf(
                graph(
                    RecommendationEvidenceType.SAME_FRANCHISE,
                    1.0,
                    "CATALOGUE_COLLECTION",
                    "Belongs to the same canonical franchise as The Godfather",
                ),
            ),
        )
        val popularUnrelated = candidate(
            id = 12,
            title = "Highest Rated Space Adventure",
            genres = listOf("Science Fiction", "Adventure"),
            rating = 9.9,
            imdbVotes = 5_000_000,
        )
        val preferences = RecommendationPreferenceParser.parse(
            "Movie similar to The Godfather",
        ).preferences

        val ranked = RecommendationRanker.rank(
            preferences,
            listOf(popularUnrelated, sequel),
            similarityAnchor = anchor,
        )

        assertEquals(sequel.media.key, ranked.first().media.key)
        assertFalse(ranked.any { it.media.key == popularUnrelated.media.key })
    }

    @Test
    fun moodAndExclusionEvidenceBeatUnrelatedQuality() {
        val preferences = RecommendationPreferenceParser.parse(
            "A dark intense crime series, no animation",
        ).preferences
        val matching = candidate(
            id = 20,
            type = MediaType.TV,
            title = "Moral Nightfall",
            genres = listOf("Crime", "Drama"),
            overview = "A dark and intense crime investigation tests every moral boundary.",
            rating = 7.8,
        )
        val excluded = candidate(
            id = 21,
            type = MediaType.TV,
            title = "Animated Crime",
            genres = listOf("Animation", "Crime"),
            overview = "A dark intense crime saga.",
            rating = 9.8,
        )
        val unrelated = candidate(
            id = 22,
            type = MediaType.TV,
            title = "Sunny Baking",
            genres = listOf("Reality"),
            overview = "Cheerful bakers make cakes.",
            rating = 9.9,
        )

        val ranked = RecommendationRanker.rank(preferences, listOf(unrelated, excluded, matching))

        assertEquals(matching.media.key, ranked.first().media.key)
        assertFalse(ranked.any { it.media.key == excluded.media.key })
    }

    @Test
    fun hiddenGemIntentPrefersSupportingPopularityShapeNotRawPopularity() {
        val preferences = RecommendationPreferenceParser.parse(
            "A hidden gem drama movie",
        ).preferences
        val hiddenGem = candidate(
            id = 30,
            title = "Quiet Discovery",
            genres = listOf("Drama"),
            overview = "An intimate dramatic family story.",
            rating = 8.1,
            imdbVotes = 8_000,
            sourceCount = 2,
        )
        val blockbuster = candidate(
            id = 31,
            title = "The Global Blockbuster",
            genres = listOf("Drama"),
            overview = "A dramatic family story.",
            rating = 9.4,
            imdbVotes = 4_000_000,
            sourceCount = 4,
        )

        val ranked = RecommendationRanker.rank(preferences, listOf(blockbuster, hiddenGem))

        assertEquals(hiddenGem.media.key, ranked.first().media.key)
    }

    @Test
    fun hardOneHundredTwentyMinuteLimitRejectsUnknownAndLongRuntime() {
        val preferences = RecommendationPreferenceParser.parse(
            "Something scary under 120 minutes",
        ).preferences
        val valid = candidate(
            id = 40,
            title = "Short Fright",
            genres = listOf("Horror"),
            overview = "A scary haunted nightmare.",
            runtime = 119,
        )
        val unknown = candidate(
            id = 41,
            title = "Unknown Fright",
            genres = listOf("Horror"),
            overview = "A scary haunted nightmare.",
            runtime = null,
        )
        val exactLimit = candidate(
            id = 43,
            title = "Two-Hour Fright",
            genres = listOf("Horror"),
            overview = "A scary haunted nightmare.",
            runtime = 120,
        )
        val long = candidate(
            id = 42,
            title = "Long Fright",
            genres = listOf("Horror"),
            overview = "A scary haunted nightmare.",
            runtime = 121,
        )

        assertEquals(120, preferences.runtimeMaximumMinutes?.value)
        assertEquals(ConstraintStrength.HARD, preferences.runtimeMaximumMinutes?.strength)
        assertEquals(
            listOf(exactLimit.media.key, valid.media.key),
            RecommendationRanker.hardFilter(
                preferences,
                listOf(unknown, long, exactLimit, valid),
            )
                .map { it.media.key },
        )
    }

    @Test
    fun canonicalResolverUsesAliasesMediaTypeAndSurfacesAmbiguity() {
        val moneyHeist = CanonicalTitleAnchor(
            identity = CanonicalMediaIdentity(MediaType.TV, 50),
            canonicalTitle = "La casa de papel",
            alternativeTitles = setOf("Money Heist", "Haus des Geldes"),
            year = 2017,
        )
        val movieWithSameAlias = CanonicalTitleAnchor(
            identity = CanonicalMediaIdentity(MediaType.MOVIE, 51),
            canonicalTitle = "Money Heist: The Movie",
            alternativeTitles = setOf("Money Heist"),
            year = 2024,
        )
        val resolved = CanonicalTitleResolver.resolve(
            "Haus des Geldes",
            RecommendationContentType.TV,
            listOf(movieWithSameAlias, moneyHeist),
        )
        assertTrue(resolved is TitleAnchorResolution.Resolved)
        assertEquals(
            moneyHeist.identity,
            (resolved as TitleAnchorResolution.Resolved).anchor.identity,
        )

        val officeUk = CanonicalTitleAnchor(
            CanonicalMediaIdentity(MediaType.TV, 52),
            "The Office",
            alternativeTitles = setOf("The Office UK"),
            year = 2001,
        )
        val officeUs = CanonicalTitleAnchor(
            CanonicalMediaIdentity(MediaType.TV, 53),
            "The Office",
            alternativeTitles = setOf("The Office US"),
            year = 2005,
        )
        val ambiguous = CanonicalTitleResolver.resolve(
            "The Office",
            RecommendationContentType.TV,
            listOf(officeUs, officeUk),
        )
        assertTrue(ambiguous is TitleAnchorResolution.Ambiguous)
        assertEquals(2, (ambiguous as TitleAnchorResolution.Ambiguous).candidates.size)
    }

    @Test
    fun anchorMetadataSourceNeverMasqueradesAsADirectProviderRelation() {
        val anchor = media(
            id = 54,
            title = "Reference Point",
            genres = listOf("Crime", "Drama"),
            overview = "A crime family protects its power through a dangerous conspiracy.",
            cast = listOf("Avery Stone", "Morgan Vale"),
        )
        val providerRelated = candidate(
            id = 55,
            title = "Provider Relation",
            genres = listOf("Comedy"),
            overview = "An otherwise distant story.",
        ).copy(
            sources = setOf("ANCHOR_RELATED"),
            sourceRanks = mapOf("ANCHOR_RELATED" to 1),
            sourceCount = 1,
            sourcePosition = 1,
        )
        val metadataNeighborBase = candidate(
            id = 56,
            title = "Metadata Neighbor",
            genres = listOf("Crime", "Drama"),
            overview = "A crime family protects its power through a dangerous conspiracy.",
        )
        val metadataNeighbor = metadataNeighborBase.copy(
            media = metadataNeighborBase.media.copy(
                cast = listOf("Avery Stone", "Morgan Vale"),
            ),
            sources = setOf("ANCHOR_METADATA"),
            sourceRanks = mapOf("ANCHOR_METADATA" to 1),
            sourceCount = 1,
            sourcePosition = 1,
        )
        val preferences = RecommendationPreferenceParser.parse(
            "Movie similar to Reference Point",
        ).preferences

        val snapshot = RecommendationRanker.rankWithDiagnostics(
            preferences = preferences,
            candidates = listOf(metadataNeighbor, providerRelated),
            similarityAnchor = anchor,
        )
        val scored = snapshot.ranked + snapshot.rejectedLowConfidence
        val scoredProvider = scored.single { it.media.key == providerRelated.media.key }
        val scoredMetadata = scored.single { it.media.key == metadataNeighbor.media.key }

        assertTrue(
            scoredProvider.relevanceEvidence.any {
                it.type == RecommendationEvidenceType.DIRECT_RELATED_TITLE &&
                    it.source == "ANCHOR_RELATED"
            },
        )
        assertFalse(
            scoredMetadata.relevanceEvidence.any {
                it.type == RecommendationEvidenceType.DIRECT_RELATED_TITLE
            },
        )
        assertTrue(
            scoredMetadata.relevanceEvidence.any {
                it.type == RecommendationEvidenceType.SHARED_CAST ||
                    it.type == RecommendationEvidenceType.SHARED_GENRE
            },
        )
        assertEquals(providerRelated.media.key, snapshot.ranked.first().media.key)
    }

    @Test
    fun unresolvedTitleAnchorDoesNotSilentlyPublishGenericMatches() {
        val preferences = RecommendationPreferenceParser.parse(
            "Series similar to A Title That Does Not Exist",
        ).preferences
        val generic = candidate(
            id = 54,
            type = MediaType.TV,
            title = "Generic Highly Rated Series",
            rating = 9.9,
            imdbVotes = 5_000_000,
        )

        val snapshot = RecommendationRanker.rankWithDiagnostics(
            preferences = preferences,
            candidates = listOf(generic),
            similarityAnchor = null,
            precomputedSemanticScores = mapOf(generic.media.key to 1.0),
        )

        assertTrue(snapshot.ranked.isEmpty())
        assertEquals(listOf(generic.media.key), snapshot.rejectedLowConfidence.map { it.media.key })
        assertEquals(RecommendationConfidenceBand.LOW, snapshot.confidenceBand)
    }

    @Test
    fun precomputedSemanticScoresWinWithoutCallingLegacyScorer() {
        val preferences = RecommendationPreferenceParser.parse(
            "A thoughtful movie about identity and society",
        ).preferences
        val strong = candidate(60, title = "Inner Lives", overview = "A quiet story.")
        val weak = candidate(61, title = "Loud Distraction", overview = "A loud story.")
        val scorer = SemanticTextScorer { _, _ ->
            throw AssertionError("legacy scorer must not run when a snapshot score is present")
        }

        val ranked = RecommendationRanker.rank(
            preferences = preferences,
            candidates = listOf(weak, strong),
            semanticScorer = scorer,
            precomputedSemanticScores = mapOf(
                strong.media.key to 0.94,
                weak.media.key to 0.15,
            ),
        )

        assertEquals(strong.media.key, ranked.first().media.key)
        assertEquals(0.94, ranked.first().precomputedSemanticScore ?: 0.0, 0.001)
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
        sourceCount: Int = 2,
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
        sources = setOf("FIXTURE_CATALOGUE", "FIXTURE_EDITORIAL"),
        sourceRanks = mapOf("FIXTURE_CATALOGUE" to 2, "FIXTURE_EDITORIAL" to 3),
        sourceCount = sourceCount,
        sourcePosition = 2,
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
