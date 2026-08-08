package com.aliflix.app.recommendation.omdb

import com.aliflix.app.model.MediaType

enum class OmdbGenre(
    val canonicalName: String,
    val tmdbMovieGenreId: Int?,
    val tmdbTvGenreIds: List<Int> = emptyList(),
) {
    ACTION("Action", 28, listOf(10759)),
    ADVENTURE("Adventure", 12, listOf(10759)),
    ANIMATION("Animation", 16, listOf(16)),
    BIOGRAPHY("Biography", 36, listOf(36)),
    COMEDY("Comedy", 35, listOf(35)),
    CRIME("Crime", 80, listOf(80)),
    DOCUMENTARY("Documentary", 99, listOf(99)),
    DRAMA("Drama", 18, listOf(18)),
    FAMILY("Family", 10751, listOf(10751, 10762)),
    FANTASY("Fantasy", 14, listOf(10765)),
    FILM_NOIR("Film-Noir", 80, listOf(80)),
    GAME_SHOW("Game-Show", null, listOf(10764)),
    HISTORY("History", 36, listOf(36)),
    HORROR("Horror", 27, emptyList()),
    MUSIC("Music", 10402, emptyList()),
    MUSICAL("Musical", 10402, emptyList()),
    MYSTERY("Mystery", 9648, listOf(9648)),
    NEWS("News", null, listOf(10763)),
    REALITY_TV("Reality-TV", null, listOf(10764)),
    ROMANCE("Romance", 10749, emptyList()),
    SCI_FI("Sci-Fi", 878, listOf(10765)),
    SHORT("Short", null, emptyList()),
    SPORT("Sport", null, emptyList()),
    TALK_SHOW("Talk-Show", null, listOf(10767)),
    THRILLER("Thriller", 53, listOf(9648)),
    WAR("War", 10752, listOf(10768)),
    WESTERN("Western", 37, listOf(37));

    companion object {
        private val ALL_GENRES_MAP = entries.associateBy { it.canonicalName.lowercase() }

        fun parse(input: String): OmdbGenre? {
            val normalized = input.trim().lowercase()
            return when (normalized) {
                "science fiction", "science-fiction", "scifi", "sci fi", "sci-fi" -> SCI_FI
                "film noir", "film-noir" -> FILM_NOIR
                "reality tv", "reality-tv", "reality" -> REALITY_TV
                "talk show", "talk-show", "talk" -> TALK_SHOW
                "game show", "game-show" -> GAME_SHOW
                "action & adventure" -> ACTION
                "sci-fi & fantasy" -> SCI_FI
                "war & politics" -> WAR
                else -> ALL_GENRES_MAP[normalized]
            }
        }

        fun normalizeName(input: String): String {
            return parse(input)?.canonicalName ?: input.trim()
        }

        fun tmdbGenreIdsForSpec(genres: Set<String>, mediaType: MediaType): List<Int> {
            return genres.mapNotNull { parse(it) }
                .flatMap { genre ->
                    if (mediaType == MediaType.MOVIE) {
                        listOfNotNull(genre.tmdbMovieGenreId)
                    } else {
                        genre.tmdbTvGenreIds
                    }
                }
                .distinct()
        }
    }
}
