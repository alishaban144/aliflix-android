package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepRetrievalAndHardGenreTest {

    @Test
    fun hardFilterBeforeGeminiRejectsPureSciFiWhenHorrorRequested() {
        val sciFiMedia = Media(
            id = 101,
            type = MediaType.MOVIE,
            title = "Dark Alien War",
            overview = "A dark futuristic war against aliens",
            genres = listOf("Science Fiction", "Adventure"),
            year = "2022",
        )
        val horrorMedia = Media(
            id = 102,
            type = MediaType.MOVIE,
            title = "Dark House",
            overview = "A dark supernatural haunted house",
            genres = listOf("Horror", "Mystery"),
            year = "2022",
        )

        val pairs = listOf(
            VerificationCandidate(
                candidateId = sciFiMedia.key,
                tmdbId = 101,
                mediaType = "movie",
                title = sciFiMedia.title,
                originalTitle = sciFiMedia.title,
                overview = sciFiMedia.overview,
                genres = sciFiMedia.genres,
                keywords = listOf("space", "alien"),
                releaseYear = 2022,
                directorOrCreators = emptyList(),
                principalCast = emptyList(),
            ) to sciFiMedia,
            VerificationCandidate(
                candidateId = horrorMedia.key,
                tmdbId = 102,
                mediaType = "movie",
                title = horrorMedia.title,
                originalTitle = horrorMedia.title,
                overview = horrorMedia.overview,
                genres = horrorMedia.genres,
                keywords = listOf("ghost", "haunted"),
                releaseYear = 2022,
                directorOrCreators = emptyList(),
                principalCast = emptyList(),
            ) to horrorMedia,
        )

        val result = hardFilterBeforeGemini(
            candidates = pairs,
            wantedKind = MediaType.MOVIE,
            includedGenres = listOf("Horror"),
            excludedGenres = emptyList(),
            yearMin = null,
            yearMax = null,
            minRating = null,
            language = null,
            runtimeMax = null,
            recentKeys = emptySet(),
            seenKeys = emptySet(),
            rejectedKeys = emptySet(),
        )

        assertEquals(1, result.eligiblePairs.size)
        assertEquals(horrorMedia.key, result.eligiblePairs.first().second.key)
        assertEquals(1, result.hardGenreRejections)
    }

    @Test
    fun hardFilterRequiresAllSelectedGenresWhenMultipleChipsSelected() {
        val horrorOnly = Media(
            id = 201,
            type = MediaType.MOVIE,
            title = "Slasher Night",
            overview = "A simple slasher movie",
            genres = listOf("Horror"),
            year = "2021",
        )
        val horrorMystery = Media(
            id = 202,
            type = MediaType.MOVIE,
            title = "Ghost Mystery",
            overview = "A spooky mystery investigation",
            genres = listOf("Horror", "Mystery"),
            year = "2021",
        )

        val pairs = listOf(
            VerificationCandidate(
                candidateId = horrorOnly.key,
                tmdbId = 201,
                mediaType = "movie",
                title = horrorOnly.title,
                originalTitle = horrorOnly.title,
                overview = horrorOnly.overview,
                genres = horrorOnly.genres,
                keywords = emptyList(),
                releaseYear = 2021,
                directorOrCreators = emptyList(),
                principalCast = emptyList(),
            ) to horrorOnly,
            VerificationCandidate(
                candidateId = horrorMystery.key,
                tmdbId = 202,
                mediaType = "movie",
                title = horrorMystery.title,
                originalTitle = horrorMystery.title,
                overview = horrorMystery.overview,
                genres = horrorMystery.genres,
                keywords = emptyList(),
                releaseYear = 2021,
                directorOrCreators = emptyList(),
                principalCast = emptyList(),
            ) to horrorMystery,
        )

        val result = hardFilterBeforeGemini(
            candidates = pairs,
            wantedKind = MediaType.MOVIE,
            includedGenres = listOf("Horror", "Mystery"),
            excludedGenres = emptyList(),
            yearMin = null,
            yearMax = null,
            minRating = null,
            language = null,
            runtimeMax = null,
            recentKeys = emptySet(),
            seenKeys = emptySet(),
            rejectedKeys = emptySet(),
        )

        assertEquals(1, result.eligiblePairs.size)
        assertEquals(horrorMystery.key, result.eligiblePairs.first().second.key)
    }

    @Test
    fun canonicalGenreMatchingHandlesSciFiAliasesCorrectly() {
        assertTrue(isCanonicalGenreMatch("Science Fiction", "Sci-Fi", MediaType.MOVIE))
        assertTrue(isCanonicalGenreMatch("Science Fiction", "Sci-Fi & Fantasy", MediaType.TV))
        assertTrue(isCanonicalGenreMatch("Action", "Action & Adventure", MediaType.TV))
        assertFalse(isCanonicalGenreMatch("Horror", "Science Fiction", MediaType.MOVIE))
        assertFalse(isCanonicalGenreMatch("Horror", "Thriller", MediaType.MOVIE))
    }

    @Test
    fun titleWordAloneProvidesNoSemanticGenreEvidence() {
        val comedyWithDarkTitle = Media(
            id = 301,
            type = MediaType.MOVIE,
            title = "Dark Sea",
            overview = "A hilarious romance on a cruise ship",
            genres = listOf("Comedy", "Romance"),
            year = "2020",
        )
        val pairs = listOf(
            VerificationCandidate(
                candidateId = comedyWithDarkTitle.key,
                tmdbId = 301,
                mediaType = "movie",
                title = comedyWithDarkTitle.title,
                originalTitle = comedyWithDarkTitle.title,
                overview = comedyWithDarkTitle.overview,
                genres = comedyWithDarkTitle.genres,
                keywords = emptyList(),
                releaseYear = 2020,
                directorOrCreators = emptyList(),
                principalCast = emptyList(),
            ) to comedyWithDarkTitle,
        )

        val result = hardFilterBeforeGemini(
            candidates = pairs,
            wantedKind = MediaType.MOVIE,
            includedGenres = listOf("Horror"),
            excludedGenres = emptyList(),
            yearMin = null,
            yearMax = null,
            minRating = null,
            language = null,
            runtimeMax = null,
            recentKeys = emptySet(),
            seenKeys = emptySet(),
            rejectedKeys = emptySet(),
        )

        assertTrue("Dark in title should not turn Comedy into Horror", result.eligiblePairs.isEmpty())
        assertEquals(1, result.hardGenreRejections)
    }
}
