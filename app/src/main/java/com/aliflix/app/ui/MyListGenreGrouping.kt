package com.aliflix.app.ui

import com.aliflix.app.model.Media
import java.util.Locale

internal data class MyListGenreGroup(val key: String, val value: List<Media>)

/**
 * Library-only, deterministic set-cover grouping. A title is assigned exactly once.
 * Prefer a well-supported, specific shared main genre instead of showing every TMDB tag.
 * Preserve a compact tab strip even when the collection spans many unrelated genres.
 */
internal fun groupMyListByMainGenre(items: List<Media>): List<MyListGenreGroup> {
    val remaining = items.distinctBy { it.key }.toMutableList()
    if (remaining.isEmpty()) return emptyList()

    val genresByKey = remaining.associate { item ->
        item.key to item.genres.ifEmpty { item.omdbGenres }
            .flatMap { it.split(',', '/', '|') }
            .map(::canonicalLibraryGenre)
            .filter(String::isNotBlank)
            .distinct()
            .ifEmpty { listOf("Other discoveries") }
    }
    val broadFamilies = setOf("Drama", "Action & Adventure", "Sci-Fi & Fantasy", "Family", "Other discoveries")
    val result = mutableListOf<MyListGenreGroup>()
    while (remaining.isNotEmpty() && result.size < 5) {
        val counts = remaining.flatMap { genresByKey.getValue(it.key) }
            .distinct()
            .associateWith { genre -> remaining.count { genre in genresByKey.getValue(it.key) } }
        val shared = counts.filterValues { it > 1 }
        val chosen = if (shared.isNotEmpty()) {
            shared.entries.sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key in broadFamilies }
                    .thenByDescending { entry ->
                        remaining.count { genresByKey.getValue(it.key).first() == entry.key }
                    }
                    .thenBy { it.key },
            ).first().key
        } else {
            // Unrelated singletons retain their original main-genre order.
            genresByKey.getValue(remaining.first().key).first()
        }
        val assigned = remaining.filter { chosen in genresByKey.getValue(it.key) }
        result += MyListGenreGroup(chosen, assigned)
        val assignedKeys = assigned.mapTo(hashSetOf()) { it.key }
        remaining.removeAll { it.key in assignedKeys }
    }
    if (remaining.isNotEmpty()) result += MyListGenreGroup("More", remaining.toList())
    return result
}

/** Treat TMDB's movie and TV genre variants as one display family, never subgenre tabs. */
private fun canonicalLibraryGenre(raw: String): String {
    val normalized = raw.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
    if (normalized.isEmpty()) return ""
    val words = normalized.split(' ').toSet()
    return when {
        "mystery" in words -> "Mystery"
        "scifi" in words || ("sci" in words && "fi" in words) ||
            ("science" in words && "fiction" in words) || "fantasy" in words -> "Sci-Fi & Fantasy"
        "documentary" in words || "documentaries" in words -> "Documentary"
        "animation" in words || "animated" in words -> "Animation"
        "crime" in words -> "Crime"
        "horror" in words -> "Horror"
        "thriller" in words || "thrillers" in words -> "Thriller"
        "romance" in words || "romantic" in words -> "Romance"
        "comedy" in words || "comedies" in words -> "Comedy"
        "action" in words || "adventure" in words -> "Action & Adventure"
        "family" in words || "kids" in words || "children" in words -> "Family"
        "drama" in words -> "Drama"
        "history" in words || "historical" in words -> "History"
        "war" in words -> "War"
        "western" in words -> "Western"
        "reality" in words -> "Reality"
        "news" in words -> "News"
        "music" in words || "musical" in words -> "Music"
        "soap" in words -> "Soap"
        "talk" in words -> "Talk"
        normalized == "tv movie" -> "TV Movie"
        else -> normalized.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }
}
