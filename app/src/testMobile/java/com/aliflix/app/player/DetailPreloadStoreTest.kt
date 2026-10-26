package com.aliflix.app.player

import com.aliflix.app.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DetailPreloadStoreTest {
    private val selection = PlaybackSelection(Media(86340, MediaType.TV, "Undone"), 1, 1,
        availableEpisodes = listOf(Episode(1, 1, "First"), Episode(1, 2, "Next")),
        source = PlaybackSource(MobilePlaybackProvider.FLIXER))
    private fun session() = DetailPreloadStore.Session(selection, 0, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        .also { it.result = CompletableDeferred(); DetailPreloadStore.current = it }
    @After fun clear() {
        DetailPreloadStore.current?.cancel(); DetailPreloadStore.claimed?.cancel()
        DetailPreloadStore.current = null; DetailPreloadStore.claimed = null
    }
    @Test fun playTransfersUnfinishedWorkAndRetainsTheWinningProvidersMetadata() = runBlocking {
        val session = session()
        claimDetailPreload(selection)
        assertTrue(session.transferred)
        assertNull(DetailPreloadStore.current)
        val winner = selection.copy(source = PlaybackSource(PlaybackProviderId.CINEJOY))
        val request = NativePlaybackRequest("https://fixture.test/video.m3u8", "application/x-mpegURL",
            "https://fixture.test/", "fixture-agent", "session=winner", "Undone", 0, false,
            selectionJson = winner.nativeJson())
        val awaiting = async(start = CoroutineStart.UNDISPATCHED) { DetailPreloadStore.take(selection, 0) }
        assertFalse(awaiting.isCompleted)
        (session.result as CompletableDeferred).complete(DetailPreloadStore.Entry(winner, "Pinned", request))
        val result = requireNotNull(awaiting.await())
        assertEquals(winner.source, result.first.source)
        assertEquals("Pinned", result.second)
        assertEquals(request.cookie, result.third.cookie)
        assertEquals(request.referer, result.third.referer)
        assertEquals(selection.availableEpisodes, result.first.availableEpisodes)
        assertFalse(session.scope.isActive)
        assertNull(DetailPreloadStore.claimed)
    }
    @Test fun anotherEpisodeCannotClaimOrRetainItsRequests() {
        val session = session()
        claimDetailPreload(selection.copy(episodeNumber = 2))
        assertNull(DetailPreloadStore.claimed)
        assertFalse(session.scope.isActive)
    }
    @Test fun anotherProviderOrResumePositionCannotReuseStalePreparation() = runBlocking {
        val first = session()
        claimDetailPreload(selection.copy(source = PlaybackSource(PlaybackProviderId.CINEJOY)))
        assertFalse(first.scope.isActive)
        val second = session()
        claimDetailPreload(selection)
        assertNull(DetailPreloadStore.take(selection, 10_000))
        assertFalse(second.scope.isActive)
    }
    @Test fun cancellingForegroundPreparationReleasesTransferredResources() = runBlocking {
        val session = session()
        claimDetailPreload(selection)
        val awaiting = launch(start = CoroutineStart.UNDISPATCHED) { DetailPreloadStore.take(selection, 0) }
        awaiting.cancelAndJoin()
        assertFalse(session.scope.isActive)
        assertNull(DetailPreloadStore.claimed)
    }
}
