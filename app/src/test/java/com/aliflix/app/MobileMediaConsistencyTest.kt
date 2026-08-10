package com.aliflix.app

import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaCreator
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.V3CatalogMedia
import com.aliflix.app.recommendation.V3CatalogPerson
import com.aliflix.app.recommendation.V3HomeFeed
import com.aliflix.app.recommendation.V3HomeRail
import com.aliflix.app.recommendation.V3TitleDetails
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileMediaConsistencyTest {
    private fun catalogMedia(
        id: Int = 1396,
        type: String = "tv",
        title: String = "Breaking Bad",
        poster: String? = "/tmdb-poster.jpg",
        backdrop: String? = "/tmdb-backdrop.jpg",
    ) = V3CatalogMedia(
        tmdbId = id,
        mediaType = type,
        title = title,
        originalTitle = title,
        overview = "TMDB overview",
        posterPath = poster,
        backdropPath = backdrop,
        releaseDate = "2008-01-20",
        genres = listOf("Drama", "Crime"),
        originalLanguage = "en",
        originCountries = listOf("US"),
        runtimeMinutes = 47,
        tmdbRating = 8.9,
        tmdbVoteCount = 15_000,
    )

    @Test
    fun titleEnrichmentKeepsTappedArtworkAndTmdbCreator() {
        val tapped = Media(
            id = 1396,
            type = MediaType.TV,
            title = "Breaking Bad",
            posterPath = "/tapped-poster.jpg",
            backdropPath = "/tapped-backdrop.jpg",
        )
        val details = V3TitleDetails(
            media = catalogMedia(),
            status = "Ended",
            creators = listOf(V3CatalogPerson(66633, "Vince Gilligan", "/vince.jpg")),
            cast = listOf(V3CatalogPerson(17419, "Bryan Cranston", null)),
        ).toStableMobileMedia(tapped)

        assertEquals("/tapped-poster.jpg", details.posterPath)
        assertEquals("/tapped-backdrop.jpg", details.backdropPath)
        assertEquals("Ended", details.status)
        assertEquals("Vince Gilligan", details.creators.single().name)
        assertEquals(listOf("Drama", "Crime"), details.genres)
    }

    @Test
    fun progressiveRatingsUpdateCannotReplaceArtworkOrCreators() {
        val creator = MediaCreator(66633, "Vince Gilligan", "/vince.jpg")
        val stable = Media(
            id = 1396,
            type = MediaType.TV,
            title = "Breaking Bad",
            posterPath = "/stable.jpg",
            backdropPath = "/stable-wide.jpg",
            rating = 8.9,
            status = "Ended",
            originalLanguage = "en",
            creators = listOf(creator),
        )
        val progressive = stable.copy(
            posterPath = "/legacy.jpg",
            backdropPath = "/legacy-wide.jpg",
            status = "",
            originalLanguage = "",
            creators = emptyList(),
            imdbRating = 9.5,
            rottenTomatoesRating = 96,
        )

        val merged = stable.mergeStableMobileDetailUpdate(progressive)
        assertEquals("/stable.jpg", merged.posterPath)
        assertEquals("/stable-wide.jpg", merged.backdropPath)
        assertEquals(listOf(creator), merged.creators)
        assertEquals("Ended", merged.status)
        assertEquals(9.5, merged.imdbRating)
        assertEquals(96, merged.rottenTomatoesRating)
    }

    @Test
    fun refreshedHomeSnapshotKeepsExistingArtworkForMatchingTmdbIdentity() {
        val old = Media(
            id = 10,
            type = MediaType.MOVIE,
            title = "Hero",
            posterPath = "/already-visible.jpg",
            backdropPath = "/already-visible-wide.jpg",
        )
        val previous = HomeContent(old, listOf(ContentRail("Trending", listOf(old))))
        val snapshot = V3HomeFeed(
            hero = catalogMedia(10, "movie", "Hero", "/new.jpg", "/new-wide.jpg"),
            rails = listOf(V3HomeRail("Trending", listOf(catalogMedia(10, "movie", "Hero", "/new.jpg", "/new-wide.jpg")))),
            editorialPicks = emptyList(),
        ).toStableMobileHome(previous, emptyList())

        assertEquals("/already-visible.jpg", snapshot.content.hero.posterPath)
        assertEquals("/already-visible-wide.jpg", snapshot.content.rails.single().items.single().backdropPath)
    }
}
