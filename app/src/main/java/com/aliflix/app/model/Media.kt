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

data class Media(
    val id: Int,
    val type: MediaType,
    val title: String,
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val year: String = "",
    val rating: Double = 0.0,
    val imdbRating: Double? = null,
    val rottenTomatoesRating: Int? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val isJapaneseAnime: Boolean = false,
) {
    val key: String get() = "${type.routeName}:$id"
    val posterUrl: String?
        get() = imageUrl(posterPath, "w500")
    val backdropUrl: String?
        get() = imageUrl(backdropPath, "w1280")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.routeName)
        put("title", title)
        put("overview", overview)
        put("posterPath", posterPath)
        put("backdropPath", backdropPath)
        put("year", year)
        put("rating", rating)
        imdbRating?.let { put("imdbRating", it) }
        rottenTomatoesRating?.let { put("rottenTomatoesRating", it) }
        put("genres", org.json.JSONArray(genres))
        put("cast", org.json.JSONArray(cast))
        put("isJapaneseAnime", isJapaneseAnime)
    }

    companion object {
        private fun imageUrl(value: String?, size: String): String? = value?.let { path ->
            when {
                path.startsWith("https://") || path.startsWith("http://") -> path
                path.startsWith("/") -> "https://image.tmdb.org/t/p/$size$path"
                else -> null
            }
        }

        fun fromJson(json: JSONObject): Media = Media(
            id = json.getInt("id"),
            type = MediaType.from(json.optString("type")),
            title = json.optString("title", "Untitled"),
            overview = json.optString("overview"),
            posterPath = json.optString("posterPath").takeIf { it.isNotBlank() && it != "null" },
            backdropPath = json.optString("backdropPath").takeIf { it.isNotBlank() && it != "null" },
            year = json.optString("year"),
            rating = json.optDouble("rating", 0.0),
            imdbRating = json.optDouble("imdbRating").takeIf {
                json.has("imdbRating") && it > 0.0
            },
            rottenTomatoesRating = json.optInt("rottenTomatoesRating").takeIf {
                json.has("rottenTomatoesRating") && it > 0
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
            isJapaneseAnime = json.optBoolean("isJapaneseAnime", false),
        )
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
