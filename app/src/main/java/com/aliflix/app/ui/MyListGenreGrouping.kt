package com.aliflix.app.ui

import com.aliflix.app.model.Media
import java.util.Locale

internal data class MyListGenreGroup(val key: String, val value: List<Media>)

internal val libraryGenreOrder = listOf(
    "Action & Adventure", "Sci-Fi & Fantasy", "Crime & Mystery", "Horror & Thriller",
    "Comedy", "Romance", "Drama & History", "Animation & Family", "Documentary & Reality", "Music",
)

/** Classify only this title's metadata. The collection never enters the decision. */
internal fun representativeLibraryGenre(item: Media): String {
    val genres = item.genres.ifEmpty { item.omdbGenres }.flatMap { it.split(',', '/', '|') }
        .map { it.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim() }.toSet()
    fun has(vararg names: String) = genres.any { it in names }
    return when {
        has("documentary", "reality", "news", "talk") -> "Documentary & Reality"
        has("animation", "family", "kids") -> "Animation & Family"
        has("horror") -> "Horror & Thriller"
        has("science fiction", "sci fi", "fantasy", "sci fi fantasy") -> "Sci-Fi & Fantasy"
        has("crime", "mystery") -> "Crime & Mystery"
        has("music", "musical") -> "Music"
        has("romance") -> "Romance"
        has("comedy") -> "Comedy"
        has("action", "adventure", "action adventure", "western") -> "Action & Adventure"
        has("thriller") -> "Horror & Thriller"
        has("drama", "history", "war", "war politics", "soap") -> "Drama & History"
        // Keep missing metadata visible while enrichment runs; never invent a genre.
        else -> ""
    }
}

internal fun groupMyListByMainGenre(items: List<Media>): List<MyListGenreGroup> {
    val groups = items.distinctBy(Media::key).groupBy(::representativeLibraryGenre)
    return (libraryGenreOrder + "").mapNotNull { genre ->
        groups[genre]?.takeIf { it.isNotEmpty() }?.let { MyListGenreGroup(genre, it) }
    }
}

internal fun libraryGenreSectionIndices(groups: List<MyListGenreGroup>): Map<String, Int> {
    var index = 0
    return groups.associate { group ->
        val section = group.key to index
        index += group.value.size + if (group.key.isNotEmpty()) 1 else 0
        section
    }
}
