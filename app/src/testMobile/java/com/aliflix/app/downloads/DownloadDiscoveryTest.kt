@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.aliflix.app.downloads

import androidx.media3.common.StreamKey
import com.aliflix.app.model.*
import com.aliflix.app.player.NativePlaybackRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class DownloadDiscoveryTest {
    private fun source(id: Int, vararg heights: Int): PreparedDownload {
        val selection = PlaybackSelection(Media(id, MediaType.MOVIE, "Fixture"))
        return PreparedDownload(selection, NativePlaybackRequest("https://source$id.test/master.m3u8",
            "application/x-mpegURL", "https://source$id.test", "fixture$id", "cookie$id", "Fixture", 0, false),
            heights.map { DownloadQuality("${it}p", it, it * 1000L, true, listOf(StreamKey(0, id)), "audio$id") },
            true, "server$id", listOf(DownloadAudioTrack("Audio$id", "en", "audio$id", id)))
    }
    @Test fun racesFourSourcesAndKeepsIndependentOwnersForDynamicResolutions() = runTest {
        val gate = CompletableDeferred<Unit>()
        var active = 0; var peak = 0; var cancelled = 0
        val updates = mutableListOf<DownloadDiscovery>()
        val job = async { discoverDownloadOptions((1..7).toList(), { id ->
            active++; peak = maxOf(peak, active)
            try { gate.await(); when (id) {
                1 -> source(id, 560)
                2 -> { delay(10); source(id, 320) }
                3 -> { delay(20); source(id, 930) }
                else -> { awaitCancellation() }
            } } finally { active--; if (id >= 4) cancelled++ }
        }, updates::add) }
        runCurrent(); assertEquals(4, active)
        gate.complete(Unit); val result = job.await()
        assertEquals(4, peak); assertTrue(cancelled > 0); assertEquals(0, active)
        assertEquals(listOf(930, 560, 320), result.choices.map { it.quality.height })
        assertEquals(560, result.default!!.quality.height)
        assertTrue(result.finished); assertTrue(updates.any { !it.finished && it.options.size == 1 })
        val aggregate = result.prepared()
        result.choices.forEach { option ->
            val owner = aggregate.owner(option.quality)
            assertSame(option.source, owner)
            assertEquals("cookie${owner.selection.media.id}", owner.playback.cookie)
            assertEquals(option.quality.keys, owner.qualities.single().keys)
            assertEquals("Audio${owner.selection.media.id}", aggregate.audioTracksFor(option.quality).single().label)
        }
    }
    @Test fun laterProvidersRetainAnExplicitPartialDiscoveryChoiceAndItsOwner() {
        val early = source(1, 560, 320)
        val later = source(2, 1080, 930, 720)
        val discovery = DownloadDiscovery((early.qualities.map { DownloadOption(it, early) } +
            later.qualities.map { DownloadOption(it, later) }), true)
        assertEquals(listOf(1080, 720, 320), discovery.choices.map { it.quality.height })
        val kept = discovery.choicesKeeping(560)
        assertEquals(listOf(1080, 560, 320), kept.map { it.quality.height })
        assertSame(early, kept[1].source)
        assertEquals(discovery.choices, discovery.choicesKeeping(null))
        assertEquals(discovery.choices, discovery.choicesKeeping(999))
    }
    @Test fun duplicatesAndUnknownsDoNotPrematurelyCompleteDiscovery() = runTest {
        var completed = 0
        val result = discoverDownloadOptions(listOf(1, 2, 3), { id -> completed++; source(id, if (id == 3) 0 else 560) })
        assertEquals(3, completed); assertEquals(listOf(560), result.choices.map { it.quality.height })
        assertEquals(3, result.options.size)
        val unknown = DownloadDiscovery(listOf(DownloadOption(source(1, 0).qualities.single(), source(1, 0))), true)
        assertEquals(0, unknown.choices.single().quality.height)
    }
    @Test fun deadlineKeepsPartialResultsAndCancelsBlockedProviders() = runTest {
        var closed = 0
        val result = discoverDownloadOptions(listOf(1, 2, 3), { id ->
            if (id == 1) source(id, 320) else try { awaitCancellation() } finally { closed++ }
        }, budgetMs = 500)
        assertEquals(500, testScheduler.currentTime); assertEquals(2, closed)
        assertTrue(result.finished); assertEquals(320, result.choices.single().quality.height)
    }
    @Test fun parentCancellationDoesNotBecomeSuccessfulDiscovery() = runTest {
        var completed = false
        val job = launch { discoverDownloadOptions(listOf(1, 2), { awaitCancellation() }); completed = true }
        runCurrent(); job.cancelAndJoin(); assertFalse(completed)
    }
    @Test fun wideManifestUsesExtremesAndNearestMidpointWithSmallerTieBreak() {
        val manifest = source(1, 320, 480, 560, 640, 930)
        val choices = relativeDownloadOptions(manifest.qualities.map { DownloadOption(it, manifest) })
        assertEquals(listOf(930, 640, 320), choices.map { it.quality.height })
        assertEquals(560, closestDownloadQuality(source(1, 560, 640).qualities, 600).height)
        val two = DownloadDiscovery(source(1, 930, 320).let { item -> item.qualities.map { DownloadOption(it, item) } }, true)
        assertEquals(320, two.default!!.quality.height)
    }
    @Test fun animeRacesBothDedicatedCataloguesEvenWhenStartingFromAGeneralSource() {
        val anime = Media(21, MediaType.TV, "One Piece", originalLanguage = "ja", genres = listOf("Animation"))
        val order = downloadProviderOrder(PlaybackSelection(anime, 1, 1, source = PlaybackSource(MobilePlaybackProvider.FLIXER)))
        assertEquals(listOf(PlaybackProviderId.MIRURO, PlaybackProviderId.ANIKURO), order.take(2))
        assertTrue(order.take(4).contains(MobilePlaybackProvider.FLIXER))
        assertEquals(order.size, order.distinct().size)
        assertFalse(downloadProviderOrder(PlaybackSelection(Media(218, MediaType.MOVIE, "The Terminator"))).any { it.isAnimeNative })
    }

}
