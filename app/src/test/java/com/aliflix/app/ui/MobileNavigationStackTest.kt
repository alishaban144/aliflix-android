package com.aliflix.app.ui

import com.aliflix.app.DetailUiState
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaCreator
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileNavigationStackTest {
    @Test
    fun systemBackReturnsEveryNonHomeDestinationToHome() {
        val home = listOf<MobileDestination>(MobileDestination.Root(AppTab.HOME))
        val discover = listOf<MobileDestination>(MobileDestination.Root(AppTab.SEARCH))
        val mySpace = listOf<MobileDestination>(MobileDestination.Root(AppTab.MY_SPACE))
        val detail = discover + MobileDestination.Detail(
            Media(id = 1396, type = MediaType.TV, title = "Breaking Bad"),
        )

        assertFalse(shouldReturnHomeOnSystemBack(home))
        assertTrue(shouldReturnHomeOnSystemBack(discover))
        assertTrue(shouldReturnHomeOnSystemBack(mySpace))
        assertTrue(shouldReturnHomeOnSystemBack(detail))
    }

    @Test
    fun stackDepthAlwaysControlsPushAndPopMotion() {
        assertEquals(
            MobileNavigationMotion.PUSH,
            mobileNavigationMotion(
                initialDepth = 2,
                targetDepth = 3,
                initialTab = AppTab.HOME,
                targetTab = AppTab.HOME,
            ),
        )
        assertEquals(
            MobileNavigationMotion.POP,
            mobileNavigationMotion(
                initialDepth = 3,
                targetDepth = 2,
                initialTab = AppTab.HOME,
                targetTab = AppTab.HOME,
            ),
        )
    }

    @Test
    fun rootTabsAnimateInTheirVisualOrder() {
        assertEquals(
            MobileNavigationMotion.TAB_FORWARD,
            mobileNavigationMotion(
                initialDepth = 1,
                targetDepth = 1,
                initialTab = AppTab.HOME,
                targetTab = AppTab.MY_SPACE,
            ),
        )
        assertEquals(
            MobileNavigationMotion.TAB_BACKWARD,
            mobileNavigationMotion(
                initialDepth = 1,
                targetDepth = 1,
                initialTab = AppTab.MY_SPACE,
                targetTab = AppTab.SEARCH,
            ),
        )
    }

    @Test
    fun outgoingDetailKeepsItsOwnSnapshotWhileNestedDetailOpens() {
        val original = Media(id = 101, type = MediaType.MOVIE, title = "Origin")
        val nested = Media(id = 202, type = MediaType.MOVIE, title = "Nested title")
        val originalState = DetailUiState(
            item = original,
            recommendations = listOf(nested),
        )
        val nestedState = DetailUiState(item = nested)

        assertEquals(
            originalState,
            mobileAnimatedDetailState(
                targetItem = original,
                targetSaveKey = "2:detail:${original.key}",
                liveState = nestedState,
                snapshots = mapOf("2:detail:${original.key}" to originalState),
            ),
        )
        assertEquals(
            nestedState,
            mobileAnimatedDetailState(
                targetItem = nested,
                targetSaveKey = "3:detail:${nested.key}",
                liveState = nestedState,
                snapshots = emptyMap(),
            ),
        )
        assertEquals(
            originalState,
            mobileAnimatedDetailState(
                targetItem = original,
                targetSaveKey = "2:detail:${original.key}",
                liveState = DetailUiState(loading = true, item = original),
                snapshots = mapOf("2:detail:${original.key}" to originalState),
            ),
        )
    }

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
        val creator = MobileDestination.Person(
            MediaCreator(66633, "Vince Gilligan", "/vince.jpg"),
        )

        val rootStack = listOf<MobileDestination>(root)
        val detailStack = rootStack + show
        val genreStack = detailStack + genre
        val creatorStack = detailStack + creator
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
            mobileDestinationSaveKey(detailStack),
            mobileDestinationSaveKey(popMobileDestinationStack(creatorStack)),
        )
        assertEquals(
            mobileDestinationSaveKey(rootStack),
            mobileDestinationSaveKey(popMobileDestinationStack(detailStack)),
        )
    }

    @Test
    fun deepNavigationUnwindsStepByStepToOriginalTab() {
        val rootTab = MobileDestination.Root(AppTab.SEARCH)
        val movie1 = Media(id = 10, type = MediaType.MOVIE, title = "Interstellar")
        val movie2 = Media(id = 20, type = MediaType.MOVIE, title = "Inception")
        val genre = MobileDestination.Genre("Sci-Fi", MediaType.MOVIE, firstVisibleItemIndex = 14, firstVisibleItemScrollOffset = 50)
        val creator = MobileDestination.Person(MediaCreator(525, "Christopher Nolan", "/nolan.jpg"), firstVisibleItemIndex = 8, firstVisibleItemScrollOffset = 20)

        // Simulate navigation: Discover -> Movie1 -> Genre -> Movie2 -> Creator
        var stack = listOf<MobileDestination>(
            rootTab,
            MobileDestination.Detail(movie1),
            genre,
            MobileDestination.Detail(movie2),
            creator,
        )

        // 1. Back from Creator -> Movie2
        stack = popMobileDestinationStack(stack)
        assertEquals(MobileDestination.Detail(movie2), stack.last())
        assertEquals(4, stack.size)

        // 2. Back from Movie2 -> Genre (with scroll position preserved)
        stack = popMobileDestinationStack(stack)
        val currentGenre = stack.last() as MobileDestination.Genre
        assertEquals("Sci-Fi", currentGenre.name)
        assertEquals(14, currentGenre.firstVisibleItemIndex)
        assertEquals(50, currentGenre.firstVisibleItemScrollOffset)
        assertEquals(3, stack.size)

        // 3. Back from Genre -> Movie1
        stack = popMobileDestinationStack(stack)
        assertEquals(MobileDestination.Detail(movie1), stack.last())
        assertEquals(2, stack.size)

        // 4. Back from Movie1 -> Discover Root
        stack = popMobileDestinationStack(stack)
        assertEquals(rootTab, stack.last())
        assertEquals(1, stack.size)

        // At root Discover, system back returns to Home
        assertTrue(shouldReturnHomeOnSystemBack(stack))
    }
}
