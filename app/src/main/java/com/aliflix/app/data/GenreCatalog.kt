package com.aliflix.app.data

import com.aliflix.app.model.MediaType

data class GenreSpec(
    val id: String,
    val title: String,
    val type: MediaType,
    val genreExpression: String,
)

object GenreCatalog {
    val homeSpecs: List<GenreSpec> = listOf(
        movie("movie_action", "Action", "28"),
        movie("movie_adventure", "Adventure", "12"),
        movie("movie_animation", "Animation", "16"),
        movie("movie_comedy", "Comedy", "35"),
        movie("movie_crime", "Crime", "80"),
        movie("movie_drama", "Drama", "18"),
        movie("movie_horror", "Horror", "27"),
        movie("movie_romance", "Romance", "10749"),
        movie("movie_science_fiction", "Science Fiction", "878"),
        movie("movie_thriller", "Thriller", "53"),
        movie("movie_fantasy", "Fantasy", "14"),
        movie("movie_action_thrillers", "Action Thrillers", "28,53"),
        movie("movie_romantic_comedies", "Romantic Comedies", "35,10749"),
        movie("movie_crime_thrillers", "Crime Thrillers", "80,53"),
        movie("movie_historical_dramas", "Historical Dramas", "18,36"),
        movie(
            "movie_animated_family_adventures",
            "Animated Family Adventures",
            "16,10751",
        ),
        tv("tv_action_adventure", "Action & Adventure Series", "10759"),
        tv("tv_animation", "Animated Series", "16"),
        tv("tv_comedy", "Comedy Series", "35"),
        tv("tv_crime", "Crime Series", "80"),
        tv("tv_drama", "Drama Series", "18"),
        tv("tv_family_kids", "Family & Kids Series", "10751|10762"),
        tv("tv_mystery_scifi", "Mystery & Sci-Fi Series", "9648|10765"),
        tv("tv_documentary", "Documentary Series", "99"),
        tv("tv_reality", "Reality Series", "10764"),
        tv("tv_crime_dramas", "Crime Dramas", "18,80"),
        tv("tv_mystery_dramas", "Mystery Dramas", "18,9648"),
        tv("tv_comedy_dramas", "Comedy Dramas", "18,35"),
    )

    fun specFor(label: String, type: MediaType): GenreSpec? {
        val normalized = normalize(label)
        homeSpecs.firstOrNull { spec ->
            spec.type == type && normalize(spec.title) == normalized
        }?.let { return it }

        val ids = when (type) {
            MediaType.MOVIE -> movieGenreIds[normalized]
            MediaType.TV -> tvGenreIds[normalized]
        } ?: return null
        return GenreSpec(
            id = "detail_${type.routeName}_${normalized.replace(' ', '_')}",
            title = label.trim(),
            type = type,
            genreExpression = ids,
        )
    }

    private fun movie(id: String, title: String, genres: String) =
        GenreSpec(id, title, MediaType.MOVIE, genres)

    private fun tv(id: String, title: String, genres: String) =
        GenreSpec(id, title, MediaType.TV, genres)

    private fun normalize(value: String): String =
        value.lowercase()
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private val movieGenreIds = mapOf(
        "action" to "28",
        "adventure" to "12",
        "animation" to "16",
        "comedy" to "35",
        "crime" to "80",
        "documentary" to "99",
        "drama" to "18",
        "family" to "10751",
        "fantasy" to "14",
        "history" to "36",
        "horror" to "27",
        "music" to "10402",
        "mystery" to "9648",
        "romance" to "10749",
        "science fiction" to "878",
        "sci fi" to "878",
        "thriller" to "53",
        "war" to "10752",
        "western" to "37",
    )

    private val tvGenreIds = mapOf(
        "action and adventure" to "10759",
        "action adventure" to "10759",
        "animation" to "16",
        "comedy" to "35",
        "crime" to "80",
        "documentary" to "99",
        "drama" to "18",
        "family" to "10751",
        "kids" to "10762",
        "mystery" to "9648",
        "news" to "10763",
        "reality" to "10764",
        "sci fi and fantasy" to "10765",
        "sci fi fantasy" to "10765",
        "science fiction and fantasy" to "10765",
        "soap" to "10766",
        "talk" to "10767",
        "war and politics" to "10768",
        "western" to "37",
    )
}
