package com.aliflix.app.ui

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileNavigationStackTest {
    @Test
    fun nestedGenreDetailsPopInChronologicalOrderAndKeepScrollPosition() {
        val detailA = Media(
            id = 101,
            type = MediaType.MOVIE,
            title = "Origin",
        )
        val detailB = Media(
            id = 202,
            type = MediaType.MOVIE,
            title = "Nested title",
        )
        val genre = MobileDestination.Genre(
            name = "Thriller",
            mediaType = MediaType.MOVIE,
            firstVisibleItemIndex = 28,
            firstVisibleItemScrollOffset = 17,
        )
        var stack = listOf(
            MobileDestination.Root(AppTab.SEARCH),
            MobileDestination.Detail(detailA),
            genre,
            MobileDestination.Detail(detailB),
        )

        stack = popMobileDestinationStack(stack)
        assertEquals(genre, stack.last())

        stack = popMobileDestinationStack(stack)
        assertEquals(MobileDestination.Detail(detailA), stack.last())

        stack = popMobileDestinationStack(stack)
        assertEquals(MobileDestination.Root(AppTab.SEARCH), stack.last())
    }

    @Test
    fun rootDestinationCannotBePopped() {
        val root = listOf<MobileDestination>(
            MobileDestination.Root(AppTab.HOME),
        )

        assertEquals(root, popMobileDestinationStack(root))
    }
}
