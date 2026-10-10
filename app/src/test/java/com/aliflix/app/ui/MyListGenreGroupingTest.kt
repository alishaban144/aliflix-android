package com.aliflix.app.ui

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class MyListGenreGroupingTest {
    private fun movie(id: Int, vararg genres: String) = Media(id, MediaType.MOVIE, "Movie $id", genres = genres.toList())
    @Test fun assignmentNeverDependsOnSavedGenrePopularity() {
        val a = movie(1, "Adventure", "Drama", "Science Fiction")
        val b = movie(2, "Mystery", "Crime", "Drama")
        val initial = groupMyListByMainGenre(listOf(a, b)).associate { group -> group.value.single().key to group.key }
        val larger = groupMyListByMainGenre(listOf(a, b) + (3..30).map { movie(it, "Drama", "Mystery") })
        assertEquals("Sci-Fi & Fantasy", initial[a.key])
        assertEquals("Crime & Mystery", initial[b.key])
        assertEquals(initial[a.key], larger.first { a in it.value }.key)
        assertEquals(initial[b.key], larger.first { b in it.value }.key)
    }
    @Test fun broadFamiliesAreCanonicalAndTitlesAppearExactlyOnce() {
        val inputs = listOf(movie(1, "Science Fiction"), movie(2, "Sci-Fi & Fantasy"),
            movie(3, "Fantasy"), movie(4, "Crime", "Mystery"), movie(5, "Action & Adventure"),
            movie(6, "Animation", "Family"), movie(7, "Horror", "Drama"))
        val groups = groupMyListByMainGenre(inputs + inputs.first())
        assertEquals(7, groups.sumOf { it.value.size })
        assertEquals(inputs.map { it.key }.toSet(), groups.flatMap { it.value }.map { it.key }.toSet())
        assertEquals(3, groups.single { it.key == "Sci-Fi & Fantasy" }.value.size)
        assertEquals("Horror & Thriller", representativeLibraryGenre(inputs.last()))
    }
    @Test fun onlyNonemptyNamedCategoriesAreShownInStableOrder() {
        val inputs = listOf("Comedy", "Mystery", "Animation", "Documentary", "Horror",
            "Music", "Reality", "Western", "Romance", "Drama").mapIndexed { i, genre -> movie(i, genre) }
        val groups = groupMyListByMainGenre(inputs)
        assertTrue(groups.size <= 10)
        assertFalse(groups.any { it.key == "More" || it.value.isEmpty() })
        assertEquals(groups.map { it.key }, libraryGenreOrder.filter { name -> groups.any { it.key == name } })
        assertEquals(groups.map { it.key }, groupMyListByMainGenre(inputs.reversed()).map { it.key })
    }
    @Test fun ownOmdbMetadataIsUsedAndUnknownGenresAreNeverInvented() {
        val fallback = movie(1).copy(omdbGenres = listOf("Crime, Mystery, Drama"))
        assertEquals("Crime & Mystery", representativeLibraryGenre(fallback))
        val missing = movie(2)
        assertEquals("", representativeLibraryGenre(missing))
        assertEquals(listOf(missing), groupMyListByMainGenre(listOf(missing)).single().value)
        assertTrue(groupMyListByMainGenre(emptyList()).isEmpty())
    }
    @Test fun removalPreservesRemainingAssignmentsAndNoTitlesAreLost() {
        val inputs = listOf(movie(1, "Comedy"), movie(2, "Family"), movie(3, "Thriller"))
        val before = groupMyListByMainGenre(inputs)
        val after = groupMyListByMainGenre(inputs.drop(1))
        inputs.drop(1).forEach { item ->
            assertEquals(before.first { item in it.value }.key, after.first { item in it.value }.key)
        }
        assertEquals(2, after.sumOf { it.value.size })
    }
    @Test fun shortcutTargetsFollowHeadersAndRemainValidAfterRemoval() {
        val titles = listOf(movie(1, "Action", "Thriller"), movie(2, "Action"),
            movie(3, "Comedy"), movie(4, "Drama", "Music"))
        val groups = groupMyListByMainGenre(titles)
        assertEquals("Action & Adventure", representativeLibraryGenre(titles.first()))
        assertEquals("Music", representativeLibraryGenre(titles.last()))
        assertEquals(mapOf("Action & Adventure" to 0, "Comedy" to 3, "Music" to 5), libraryGenreSectionIndices(groups))
        val updated = groupMyListByMainGenre(titles.drop(1))
        assertEquals(2, libraryGenreSectionIndices(updated)["Comedy"])
        assertEquals(4, libraryGenreSectionIndices(updated)["Music"])
    }

}
