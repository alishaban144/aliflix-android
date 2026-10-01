package com.aliflix.app.ui

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyListGenreGroupingTest {
    private fun movie(id: Int, vararg genres: String) =
        Media(id = id, type = MediaType.MOVIE, title = "Movie $id", genres = genres.toList())

    @Test fun sharedMysteryConsolidatesDifferentPrimaryLabelsWithoutDuplicates() {
        val a = movie(1, "Mystery", "Science Fiction", "Drama", "Action")
        val b = movie(2, "Fantasy", "sci-fi", "mystery")
        val groups = groupMyListByMainGenre(listOf(a, b))
        assertEquals(listOf("Mystery"), groups.map { it.key })
        assertEquals(listOf(a.key, b.key), groups.single().value.map { it.key })
    }

    @Test fun scienceFictionFantasyAndScifiAreOneCanonicalFamily() {
        val groups = groupMyListByMainGenre(listOf(
            movie(1, "Science Fiction"), movie(2, "Sci-Fi & Fantasy"), movie(3, "Fantasy"),
        ))
        assertEquals(listOf("Sci-Fi & Fantasy"), groups.map { it.key })
        assertEquals(3, groups.single().value.size)
    }

    @Test fun manyUnrelatedGenresFitInFiveNamedTabsPlusMoreWithNoLostTitles() {
        val inputs = listOf("Mystery", "Comedy", "Animation", "Documentary", "Horror",
            "Music", "Reality", "Western", "Romance").mapIndexed { index, label -> movie(index + 1, label) }
        val groups = groupMyListByMainGenre(inputs)
        assertEquals(6, groups.size)
        assertEquals("More", groups.last().key)
        assertEquals(inputs.map { it.key }.toSet(), groups.flatMap { it.value }.map { it.key }.toSet())
        assertEquals(inputs.size, groups.sumOf { it.value.size })
    }

    @Test fun unrelatedTitlesKeepMainGenreAndMissingMetadataIsStillVisible() {
        val groups = groupMyListByMainGenre(listOf(movie(1, "Drama"), movie(2), movie(3, "Comedy")))
        assertEquals(listOf("Drama", "Other discoveries", "Comedy"), groups.map { it.key })
        assertTrue(groupMyListByMainGenre(emptyList()).isEmpty())
    }
}
