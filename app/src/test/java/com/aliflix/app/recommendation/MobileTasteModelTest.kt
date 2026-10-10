package com.aliflix.app.recommendation

import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MobileTasteModelTest {
    @Before fun reset() { MobileTasteModel.clear() }
    private fun film(id: Int, title: String, plot: String, genres: List<String>, vararg keywords: String) =
        Media(id, MediaType.MOVIE, title, overview = plot, genres = genres, posterPath = "/poster.jpg",
            keywords = keywords.mapIndexed { i, name -> MediaKeyword(1000 + i, name) })
    private val interstellar = film(157336, "Interstellar",
        "A team of astronauts travels through a wormhole to distant planets to ensure humanity's survival.",
        listOf("Adventure", "Drama", "Science Fiction"))
    private val martian = film(286217, "The Martian",
        "An astronaut is stranded on Mars after his crew leaves him behind. Alone on a distant planet, he must survive.",
        listOf("Adventure", "Drama", "Science Fiction"))
    private val shining = film(694, "The Shining",
        "A writer becomes the winter caretaker of a remote hotel. Isolated and snowbound, his family faces a supernatural presence.",
        listOf("Horror", "Drama"))
    private val others = film(1933, "The Others",
        "A mother and children in a secluded mansion confront a haunting and the possibility of ghosts.",
        listOf("Horror", "Mystery", "Thriller"))
    private val paddington = film(116149, "Paddington",
        "A young bear travels to London and is welcomed into the home of a kindly family.",
        listOf("Comedy", "Adventure", "Family"))
    private val gravity = film(49047, "Gravity",
        "Two astronauts are stranded after their spacecraft is destroyed. They struggle for survival in space.",
        listOf("Drama", "Science Fiction", "Thriller"))

    @Test fun storySynonymsConnectWithoutSharedPlotWords() {
        val anchor = film(100, "Cast Away", "A shipwreck leaves a castaway alone in a remote wilderness.", listOf("Drama"))
        val candidate = film(101, "Survival", "Stranded after an accident, a man must survive.", listOf("Adventure"))
        assertNotNull(MobileTasteModel.match(candidate, listOf(anchor)))
        assertTrue(StoryConcepts.extract(anchor.overview).intersect(StoryConcepts.extract(candidate.overview)).contains("survival"))
    }
    @Test fun explicitLikesKeepMultipleInterestsWithoutDilution() {
        val spaceOnly = MobileTasteModel.match(martian, listOf(interstellar))
        val horrorOnly = MobileTasteModel.match(others, listOf(shining))
        assertEquals(spaceOnly, MobileTasteModel.match(martian, listOf(interstellar, shining, paddington)))
        assertEquals(horrorOnly, MobileTasteModel.match(others, listOf(interstellar, shining, paddington)))
    }
    @Test fun genrePopularityCastAndGenericKeywordsCannotManufactureMatch() {
        val unrelated = paddington.copy(genres = interstellar.genres, rating = 10.0, tmdbVoteCount = 1000000,
            cast = listOf("Matthew McConaughey"), keywords = listOf(MediaKeyword(42, "based on novel or book")))
        val liked = interstellar.copy(cast = unrelated.cast, keywords = unrelated.keywords)
        assertNull(MobileTasteModel.match(unrelated, listOf(liked)))
        assertNull(MobileTasteModel.match(unrelated.copy(overview = ""), listOf(liked)))
    }
    @Test fun missingMetadataNeverIncreasesAffinityOrCreatesAHighTier() {
        val complete = MobileTasteModel.match(martian, listOf(interstellar))
        assertNotNull(complete)
        assertNull(MobileTasteModel.match(martian.copy(overview = "", genres = emptyList()), listOf(interstellar)))
        val sparse = martian.copy(overview = "", creators = listOf(MediaCreator(525, "Christopher Nolan", role = "Director")))
        val anchor = interstellar.copy(creators = sparse.creators)
        assertEquals(PersonalMatchTier.RELATED, MobileTasteModel.match(sparse, listOf(anchor))?.tier)
    }
    @Test fun viewingHistoryIsWeakerThanExplicitEnjoyment() {
        val like = MobileTasteModel.match(martian, listOf(interstellar))
        val watched = MobileTasteModel.match(martian, emptyList(), listOf(interstellar))
        assertTrue(like!!.score > watched!!.score)
        assertEquals(PersonalMatchTier.RELATED, watched.tier)
        assertNull(MobileTasteModel.match(paddington, emptyList(), listOf(interstellar)))
    }
    @Test fun tmdbEnglishOverviewRemainsComparableAcrossOriginalLanguages() {
        val candidate = martian.copy(originalLanguage = "de")
        assertEquals(MobileTasteModel.match(martian, listOf(interstellar)),
            MobileTasteModel.match(candidate, listOf(interstellar.copy(originalLanguage = "en"))))
    }
    @Test fun enrichedCacheChangesSparseCardsConsistentlyAcrossScreens() {
        val sparse = martian.copy(overview = "", keywords = emptyList(), creators = emptyList())
        assertNull(MobileTasteModel.match(sparse, listOf(interstellar)))
        MobileTasteModel.remember(listOf(martian, interstellar))
        val detail = PersonalizationEngine.match(martian, listOf(interstellar))
        val home = PersonalizationEngine.match(sparse, listOf(interstellar))
        assertEquals(detail, home)
        assertEquals(listOf(martian), TastePicks.rankMobile(listOf(martian), listOf(interstellar), emptySet()))
    }
    @Test fun heldOutPreferencesFavorIntendedStoriesAcrossDistinctTastes() {
        val likes = listOf(interstellar, shining)
        val ranked = TastePicks.rankMobile(listOf(paddington, others, gravity, martian), likes, emptySet())
        assertEquals(setOf(martian.key, gravity.key, others.key), ranked.map { it.key }.toSet())
        assertFalse(ranked.any { it.key == paddington.key })
    }
    @Test fun oldVersusNewFixturesRejectTheGenreOnlyFalsePositive() {
        val superficial = paddington.copy(genres = interstellar.genres, overview = "",
            rating = 9.0, tmdbVoteCount = 500000)
        val oldPositive = PersonalizationEngine.legacyMatch(martian, listOf(interstellar))!!.score
        val oldNegative = PersonalizationEngine.legacyMatch(superficial, listOf(interstellar))!!.score
        val newPositive = MobileTasteModel.match(martian, listOf(interstellar))
        val newNegative = MobileTasteModel.match(superficial, listOf(interstellar))
        assertTrue("Old genre-only baseline wrongly prefers missing story metadata: $oldNegative vs $oldPositive",
            oldNegative >= oldPositive)
        assertNotNull(newPositive)
        assertNull(newNegative)
    }
    @Test fun explicitLikesHaveMeaningfulLabelsAndNoPercentages() {
        assertEquals("Liked", MobileTasteModel.match(interstellar, listOf(interstellar))?.label)
        assertEquals("Good match", MobileTasteModel.match(martian, listOf(interstellar))?.label)
        assertNull(MobileTasteModel.match(martian, emptyList()))
        assertFalse(MobileTasteModel.match(martian, listOf(interstellar))!!.label.contains("%"))
    }
    @Test fun candidateSeedsIncludeOlderDistinctInterestsBeyondRecentLikes() {
        val manySpaceLikes = (1..20).map { interstellar.copy(id = it) }
        val seeds = MobileTasteModel.diverseSeeds(manySpaceLikes + shining + paddington, 4)
        assertTrue(shining in seeds)
        assertTrue(paddington in seeds)
        assertTrue(seeds.any { it.id in 1..20 })
    }
    @Test fun sparseLibraryRefreshCannotEraseCachedStoryEvidence() {
        MobileTasteModel.remember(listOf(martian, interstellar))
        val before = PersonalizationEngine.match(martian, listOf(interstellar))
        val sparse = martian.copy(overview = "", genres = emptyList())
        MobileTasteModel.remember(listOf(sparse))
        assertEquals(before, PersonalizationEngine.match(sparse, listOf(interstellar)))
    }

    @Test fun ambiguousStoryWordsDoNotConfuseFraudWithCreativeArts() {
        val conArtist = film(640, "Catch Me If You Can",
            "A young con artist masters forgery and impersonation while pursued by an FBI agent.", listOf("Drama", "Crime"))
        val musical = film(313369, "La La Land",
            "An actress and a jazz musician pursue their artistic ambitions in Los Angeles.", listOf("Drama", "Music"))
        assertTrue(StoryConcepts.extract(conArtist.overview).contains("heist"))
        assertFalse(StoryConcepts.extract(conArtist.overview).contains("art"))
        assertNull(MobileTasteModel.match(musical, listOf(conArtist)))
    }

}
