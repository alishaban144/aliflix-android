package com.aliflix.app.model

import org.json.JSONObject

enum class MediaType(val routeName: String) {
    MOVIE("movie"),
    TV("tv");

    companion object {
        fun from(value: String?): MediaType =
            if (value == TV.routeName) TV else MOVIE
    }
}

enum class RatingSourceState {
    LOADING,
    VERIFIED,
    STALE,
    NOT_RATED,
    UNAVAILABLE,
}

data class MediaCreator(
    val tmdbId: Int,
    val name: String,
    val profilePath: String? = null,
) {
    val profileUrl: String?
        get() = profilePath?.let { path ->
            when {
                path.startsWith("https://") || path.startsWith("http://") -> path
                path.startsWith("/") -> "https://image.tmdb.org/t/p/w185$path"
                else -> null
            }
        }
}

data class Media(
    val id: Int,
    val type: MediaType,
    val title: String,
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val year: String = "",
    val rating: Double = 0.0,
    val imdbId: String? = null,
    val imdbRating: Double? = null,
    val imdbVoteCount: Int? = null,
    val imdbRatingState: RatingSourceState? = null,
    val rottenTomatoesRating: Int? = null,
    val rottenTomatoesState: RatingSourceState? = null,
    val tmdbVoteCount: Int? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val status: String = "",
    val originalLanguage: String = "",
    val creators: List<MediaCreator> = emptyList(),
    val runtime: String = "",
    val omdbGenres: List<String> = emptyList(),
    val omdbFullPlot: String? = null,
    val reviews: List<MediaReview> = emptyList(),
) {
    val key: String get() = "${type.routeName}:$id"
    val posterUrl: String?
        get() = imageUrl(posterPath, "w500")
    val backdropUrl: String?
        get() = imageUrl(backdropPath, "w1280")

    fun mergeWithOmdb(omdb: com.aliflix.app.data.omdb.OmdbTitleMetadata): Media {
        if (!omdb.found || !matchesOmdbIdentity(omdb)) return this

        val mergedImdbId = imdbId.takeIf { it?.matches(Regex("tt\\d+")) == true } ?: omdb.imdbId
        val mergedImdbRating = omdb.imdbRating ?: imdbRating
        val mergedImdbVotes = omdb.imdbVotes ?: imdbVoteCount
        val mergedImdbState = if (omdb.imdbRating != null) RatingSourceState.VERIFIED else imdbRatingState

        val mergedRtRating = omdb.rottenTomatoesRating ?: rottenTomatoesRating
        val mergedRtState = if (omdb.rottenTomatoesRating != null) RatingSourceState.VERIFIED else rottenTomatoesState

        val mergedRuntime = if (runtime.isBlank() && omdb.runtimeMinutes != null && omdb.runtimeMinutes > 0) {
            "${omdb.runtimeMinutes} min"
        } else {
            runtime
        }

        val mergedCast = if (cast.isEmpty() && omdb.actors.isNotEmpty()) omdb.actors else cast
        val mergedOverview = if ((overview.isBlank() || overview.length < 20) && !omdb.plot.isNull_or_blank()) {
            omdb.plot!!
        } else {
            overview
        }

        val mergedOmdbGenres = (omdbGenres + omdb.genres).distinct()
        val mergedFullPlot = omdb.plot ?: omdbFullPlot

        return copy(
            imdbId = mergedImdbId,
            imdbRating = mergedImdbRating,
            imdbVoteCount = mergedImdbVotes,
            imdbRatingState = mergedImdbState,
            rottenTomatoesRating = mergedRtRating,
            rottenTomatoesState = mergedRtState,
            runtime = mergedRuntime,
            cast = mergedCast,
            overview = mergedOverview,
            omdbGenres = mergedOmdbGenres,
            omdbFullPlot = mergedFullPlot,
        )
    }

    private fun matchesOmdbIdentity(
        omdb: com.aliflix.app.data.omdb.OmdbTitleMetadata,
    ): Boolean {
        val currentImdbId = imdbId?.takeIf { it.matches(Regex("tt\\d{5,12}")) }
        val returnedImdbId = omdb.imdbId?.takeIf { it.matches(Regex("tt\\d{5,12}")) }
        if (currentImdbId != null) return returnedImdbId == currentImdbId

        val returnedType = omdb.type?.lowercase().orEmpty()
        val typeMatches = when (type) {
            MediaType.MOVIE -> returnedType in setOf("movie", "tv movie")
            MediaType.TV -> returnedType in setOf("series", "tv series", "miniseries")
        }
        if (!typeMatches) return false

        fun normalized(value: String): String = java.text.Normalizer
            .normalize(value, java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        val returnedTitle = omdb.title?.let(::normalized).orEmpty()
        if (returnedTitle.isBlank() || returnedTitle != normalized(title)) return false

        val currentYear = year.take(4).toIntOrNull()
        return currentYear == null || omdb.year == null || kotlin.math.abs(currentYear - omdb.year) <= 2
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.isBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.routeName)
        put("title", title)
        put("overview", overview)
        put("posterPath", posterPath)
        put("backdropPath", backdropPath)
        put("year", year)
        put("rating", rating)
        imdbId?.let { put("imdbId", it) }
        imdbRating?.let { put("imdbRating", it) }
        imdbVoteCount?.let { put("imdbVoteCount", it) }
        imdbRatingState?.let { put("imdbRatingState", it.name) }
        rottenTomatoesRating?.let { put("rottenTomatoesRating", it) }
        rottenTomatoesState?.let { put("rottenTomatoesState", it.name) }
        tmdbVoteCount?.let { put("tmdbVoteCount", it) }
        put("genres", org.json.JSONArray(genres))
        put("cast", org.json.JSONArray(cast))
        put("status", status)
        put("originalLanguage", originalLanguage)
        put("creators", org.json.JSONArray().apply {
            creators.forEach { creator ->
                put(
                    JSONObject()
                        .put("tmdbId", creator.tmdbId)
                        .put("name", creator.name)
                        .put("profilePath", creator.profilePath),
                )
            }
        })
        put("runtime", runtime)
        put("omdbGenres", org.json.JSONArray(omdbGenres))
        omdbFullPlot?.let { put("omdbFullPlot", it) }
    }

    companion object {
        private fun imageUrl(value: String?, size: String): String? = value?.let { path ->
            when {
                path.startsWith("https://") || path.startsWith("http://") -> path
                path.startsWith("/") -> "https://image.tmdb.org/t/p/$size$path"
                else -> null
            }
        }

        fun fromJson(json: JSONObject): Media {
            return Media(
                id = json.getInt("id"),
                type = MediaType.from(json.optString("type")),
                title = json.optString("title", "Untitled"),
                overview = json.optString("overview"),
                posterPath = json.optString("posterPath")
                    .takeIf { it.isNotBlank() && it != "null" },
                backdropPath = json.optString("backdropPath")
                    .takeIf { it.isNotBlank() && it != "null" },
                year = json.optString("year"),
                rating = json.optDouble("rating", 0.0),
                imdbId = json.optString("imdbId")
                    .takeIf { it.matches(Regex("tt\\d+")) },
                imdbRating = json.optDouble("imdbRating").takeIf {
                    json.has("imdbRating") && it > 0.0
                },
                imdbVoteCount = json.optInt("imdbVoteCount").takeIf {
                    json.has("imdbVoteCount") && it >= 0
                },
                imdbRatingState = json.optString("imdbRatingState")
                    .takeIf(String::isNotBlank)
                    ?.let { value ->
                        RatingSourceState.entries.firstOrNull { it.name == value }
                    },
                rottenTomatoesRating = json.optInt("rottenTomatoesRating").takeIf {
                    json.has("rottenTomatoesRating") && it > 0
                },
                rottenTomatoesState = json.optString("rottenTomatoesState")
                    .takeIf(String::isNotBlank)
                    ?.let { value ->
                        RatingSourceState.entries.firstOrNull { it.name == value }
                    },
                tmdbVoteCount = json.optInt("tmdbVoteCount").takeIf {
                    json.has("tmdbVoteCount") && it >= 0
                },
                genres = json.optJSONArray("genres")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optString(index).takeIf(String::isNotBlank)
                    }
                }.orEmpty(),
                cast = json.optJSONArray("cast")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optString(index).takeIf(String::isNotBlank)
                    }
                }.orEmpty(),
                status = json.optString("status"),
                originalLanguage = json.optString("originalLanguage"),
                creators = json.optJSONArray("creators")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        val creator = array.optJSONObject(index) ?: return@mapNotNull null
                        val id = creator.optInt("tmdbId")
                        val name = creator.optString("name").trim()
                        if (id <= 0 || name.isBlank()) return@mapNotNull null
                        MediaCreator(
                            tmdbId = id,
                            name = name,
                            profilePath = creator.optString("profilePath")
                                .takeIf { it.isNotBlank() && it != "null" },
                        )
                    }
                }.orEmpty(),
                runtime = json.optString("runtime", ""),
                omdbGenres = json.optJSONArray("omdbGenres")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optString(index).takeIf(String::isNotBlank)
                    }
                }.orEmpty(),
                omdbFullPlot = json.optString("omdbFullPlot").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}

data class Season(
    val number: Int,
    val title: String,
    val episodeCount: Int = 0,
    val posterPath: String? = null,
) {
    val posterUrl: String?
        get() = posterPath?.let { path ->
            when {
                path.startsWith("https://") || path.startsWith("http://") -> path
                path.startsWith("/") -> "https://image.tmdb.org/t/p/w500$path"
                else -> null
            }
        }
}

data class Episode(
    val seasonNumber: Int,
    val number: Int,
    val title: String,
    val overview: String = "",
    val stillPath: String? = null,
    val runtime: String = "",
    val imdbRating: Double? = null,
    val rottenTomatoesRating: Int? = null,
) {
    val stillUrl: String?
        get() = stillPath?.let { path ->
            when {
                path.startsWith("https://") || path.startsWith("http://") -> path
                path.startsWith("/") -> "https://image.tmdb.org/t/p/w500$path"
                else -> null
            }
    }
}

data class PlaybackSelection(
    val media: Media,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val source: PlaybackSource = PlaybackSource.ramoflix(),
) {
    val key: String
        get() = buildString {
            append(media.key)
            if (media.type == MediaType.TV) {
                append(":s")
                append(seasonNumber ?: 1)
                append(":e")
                append(episodeNumber ?: 1)
            }
            append(":via:")
            append(source.provider.name.lowercase())
            append("@")
            append(source.cleanDomain.lowercase())
        }

    val entryUrl: String?
        get() = source.buildEntryUrl(
            media = media,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
}

data class ContentRail(
    val title: String,
    val items: List<Media>,
)

data class HomeContent(
    val hero: Media,
    val rails: List<ContentRail>,
)
