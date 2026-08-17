package com.aliflix.app

import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaCreator
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.V3CatalogMedia
import com.aliflix.app.recommendation.V3HomeFeed
import com.aliflix.app.recommendation.V3TitleDetails

internal data class MobileHomeSnapshot(
    val content: HomeContent,
    val editorialPicks: List<Media>,
)

internal fun V3CatalogMedia.toMobileMedia(
    fallback: Media? = null,
    preserveArtwork: Boolean = false,
): Media {
    val type = MediaType.from(mediaType)
    val mappedPoster = if (preserveArtwork) fallback?.posterPath ?: posterPath else posterPath ?: fallback?.posterPath
    val mappedBackdrop = if (preserveArtwork) fallback?.backdropPath ?: backdropPath else backdropPath ?: fallback?.backdropPath
    return (fallback ?: Media(id = tmdbId, type = type, title = title)).copy(
        id = tmdbId,
        type = type,
        title = title,
        overview = overview ?: fallback?.overview.orEmpty(),
        posterPath = mappedPoster,
        backdropPath = mappedBackdrop,
        year = releaseDate?.take(4) ?: fallback?.year.orEmpty(),
        rating = tmdbRating ?: fallback?.rating ?: 0.0,
        tmdbVoteCount = tmdbVoteCount ?: fallback?.tmdbVoteCount,
        genres = genres.ifEmpty { fallback?.genres.orEmpty() },
        originalLanguage = originalLanguage ?: fallback?.originalLanguage.orEmpty(),
        runtime = runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" }
            ?: fallback?.runtime.orEmpty(),
    )
}

internal fun V3TitleDetails.toStableMobileMedia(fallback: Media): Media {
    val mapped = media.toMobileMedia(fallback = fallback, preserveArtwork = true)
    return mapped.copy(
        imdbId = imdbId ?: fallback.imdbId,
        status = status?.takeIf(String::isNotBlank) ?: fallback.status,
        creators = creators.map { creator ->
            MediaCreator(
                tmdbId = creator.tmdbId,
                name = creator.name,
                profilePath = creator.profilePath,
            )
        }.ifEmpty { fallback.creators },
        cast = cast.map { it.name }.ifEmpty { fallback.cast },
    )
}

internal fun Media.mergeStableMobileDetailUpdate(update: Media): Media {
    if (key != update.key) return this
    return update.copy(
        id = id,
        type = type,
        title = title,
        overview = update.overview.ifBlank { overview },
        posterPath = posterPath ?: update.posterPath,
        backdropPath = backdropPath ?: update.backdropPath,
        year = year.ifBlank { update.year },
        rating = rating.takeIf { it > 0.0 } ?: update.rating,
        imdbId = update.imdbId ?: imdbId,
        imdbRating = update.imdbRating ?: imdbRating,
        imdbRatingState = update.imdbRatingState ?: imdbRatingState,
        imdbVoteCount = update.imdbVoteCount ?: imdbVoteCount,
        rottenTomatoesRating = update.rottenTomatoesRating ?: rottenTomatoesRating,
        rottenTomatoesState = update.rottenTomatoesState ?: rottenTomatoesState,
        tmdbVoteCount = tmdbVoteCount ?: update.tmdbVoteCount,
        genres = genres.ifEmpty { update.genres },
        cast = cast.ifEmpty { update.cast },
        status = status.ifBlank { update.status },
        originalLanguage = originalLanguage.ifBlank { update.originalLanguage },
        creators = creators.ifEmpty { update.creators },
        runtime = runtime.ifBlank { update.runtime },
    )
}

internal fun V3HomeFeed.toStableMobileHome(
    previousContent: HomeContent?,
    previousEditorialPicks: List<Media>,
): MobileHomeSnapshot {
    val previousByKey = buildMap {
        previousContent?.let { content ->
            put(content.hero.key, content.hero)
            content.rails.flatMap(ContentRail::items).forEach { item -> put(item.key, item) }
        }
        previousEditorialPicks.forEach { item -> put(item.key, item) }
    }
    fun stable(item: V3CatalogMedia): Media {
        val type = MediaType.from(item.mediaType)
        return item.toMobileMedia(
            fallback = previousByKey["${type.routeName}:${item.tmdbId}"],
            preserveArtwork = true,
        )
    }

    return MobileHomeSnapshot(
        content = HomeContent(
            hero = stable(hero),
            rails = rails.mapNotNull { rail ->
                rail.title.takeIf(String::isNotBlank)?.let { title ->
                    ContentRail(
                        title = title,
                        items = rail.items.map(::stable).distinctBy(Media::key),
                    )
                }?.takeIf { it.items.isNotEmpty() }
            },
        ),
        editorialPicks = editorialPicks.map(::stable).distinctBy(Media::key),
    )
}
