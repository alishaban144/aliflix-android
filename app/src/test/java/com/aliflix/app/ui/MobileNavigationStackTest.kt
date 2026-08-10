package com.aliflix.app.ui

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaCreator
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

    @Test
    fun creatorWorksAndNestedDetailsPopInChronologicalOrder() {
        val show = Media(id = 1396, type = MediaType.TV, title = "Breaking Bad")
        val work = Media(id = 60059, type = MediaType.TV, title = "Better Call Saul")
        val creator = MediaCreator(66633, "Vince Gilligan", "/vince.jpg")
        val creatorWorks = MobileDestination.Person(
            creator = creator,
            firstVisibleItemIndex = 18,
            firstVisibleItemScrollOffset = 24,
        )
        var stack = listOf<MobileDestination>(
            MobileDestination.Root(AppTab.HOME),
            MobileDestination.Detail(show),
            creatorWorks,
            MobileDestination.Detail(work),
        )

        stack = popMobileDestinationStack(stack)
        assertEquals(creatorWorks, stack.last())

        stack = popMobileDestinationStack(stack)
        assertEquals(MobileDestination.Detail(show), stack.last())
    }

    @Test
    fun destinationSaveKeysStayStableAfterOpeningAndClosingAChild() {
        val root = MobileDestination.Root(AppTab.HOME)
        val show = MobileDestination.Detail(
            Media(id = 1396, type = MediaType.TV, title = "Breaking Bad"),
        )
        val genre = MobileDestination.Genre("Crime", MediaType.TV)

        val rootStack = listOf<MobileDestination>(root)
        val detailStack = rootStack + show
        val genreStack = detailStack + genre
        val nestedStack = genreStack + MobileDestination.Detail(
            Media(id = 60059, type = MediaType.TV, title = "Better Call Saul"),
        )

        assertEquals(
            mobileDestinationSaveKey(genreStack),
            mobileDestinationSaveKey(popMobileDestinationStack(nestedStack)),
        )
        assertEquals(
            mobileDestinationSaveKey(detailStack),
            mobileDestinationSaveKey(popMobileDestinationStack(genreStack)),
        )
        assertEquals(
            mobileDestinationSaveKey(rootStack),
            mobileDestinationSaveKey(popMobileDestinationStack(detailStack)),
        )
    }
}
