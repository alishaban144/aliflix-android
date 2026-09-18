@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.aliflix.app.downloads

import androidx.media3.common.StreamKey
import com.aliflix.app.model.*
import com.aliflix.app.player.NativePlaybackRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class DownloadPreparationBatchTest {
    private val source = PlaybackSource(PlaybackProviderId.MOVIEPIRE, "https://selected.example")
    private fun selection(episode: Int, season: Int = 2, id: Int = 1396) =
        PlaybackSelection(Media(id, MediaType.TV, "Series"), season, episode, "Episode $episode")
    private fun items(count: Int) = (1..count).map { "$it" to selection(it) }
    private fun request(selection: PlaybackSelection, language: String = "en") = NativePlaybackRequest(
        "https://media.example/${selection.media.id}/${selection.seasonNumber}/${selection.episodeNumber}.m3u8",
        "application/x-mpegURL", "https://selected.example", "test", "", selection.episodeTitle.orEmpty(), 0, false,
        subtitleLanguage = language)
    private fun prepared(selection: PlaybackSelection, language: String = "en", server: String = "Vid") =
        PreparedDownload(selection, request(selection, language), listOf(DownloadQuality("720p", 720, 100, true,
            listOf(StreamKey(0, selection.episodeNumber ?: 0)))), false, server)
    private fun inspected(selection: PlaybackSelection, request: NativePlaybackRequest, language: String) =
        prepared(selection, language).copy(playback = request)

    @Test fun discoversOnceThenPinsExactSourceAndServerWithFourConcurrentEpisodeInspections() = runTest {
        var discoveries = 0
        var active = 0
        var peak = 0
        val calls = mutableListOf<PlaybackSelection>()
        val inspectedUrls = mutableListOf<String>()
        val results = mutableMapOf<String, PreparedDownload>()
        val gate = CompletableDeferred<Unit>()
        val job = async {
            prepareDownloadBatchInternal(items(10), "en", emptyMap(),
                discover = { discoveries++; prepared(it.copy(source = source)) },
                resolvePinned = { episode, server ->
                    assertEquals(source, episode.source)
                    assertEquals("Vid", server)
                    calls += episode
                    active++
                    peak = maxOf(peak, active)
                    request(episode)
                },
                inspect = { episode, request, language ->
                    inspectedUrls += request.url
                    gate.await()
                    active--
                    inspected(episode, request, language)
                },
                onPrepared = { key, value -> results[key] = value }, onError = { _, error -> fail(error) })
        }
        runCurrent()
        assertEquals(1, discoveries)
        assertEquals(4, calls.size)
        assertEquals(4, active)
        assertEquals(setOf("1"), results.keys)
        gate.complete(Unit)
        job.await()
        assertEquals(4, peak)
        assertEquals((2..10).toList(), calls.map { it.episodeNumber })
        assertEquals(9, inspectedUrls.distinct().size)
        assertEquals(10, results.size)
        assertEquals(10, results.values.map { it.playback.url }.distinct().size)
        assertEquals(10, results.values.map { it.qualities.single().keys }.distinct().size)
        assertTrue(results.values.all { it.server == "Vid" && it.selection.source == source })
    }

    @Test fun discoveryFailureIsReportedForEveryItemWithoutRepeatedDiscovery() = runTest {
        var discoveries = 0
        val errors = mutableMapOf<String, String>()
        prepareDownloadBatchInternal(items(7), "en", emptyMap(),
            discover = { discoveries++; error("No playable provider") },
            resolvePinned = { _, _ -> error("Must not resolve") }, inspect = ::inspected,
            onPrepared = { _, _ -> fail() }, onError = { key, error -> errors[key] = error })
        assertEquals(1, discoveries)
        assertEquals(7, errors.size)
        assertTrue(errors.values.all { it.contains("No playable provider") && it.contains("Retry") })
    }

    @Test fun individualResolutionInspectionAndTimeoutFailuresDoNotTriggerFallbackOrStopSiblings() = runTest {
        var discoveries = 0
        val resolved = mutableListOf<Int?>()
        val results = mutableMapOf<String, PreparedDownload>()
        val errors = mutableMapOf<String, String>()
        prepareDownloadBatchInternal(items(6), "en", emptyMap(),
            discover = { discoveries++; prepared(it.copy(source = source)) },
            resolvePinned = { episode, server ->
                assertEquals(source, episode.source)
                assertEquals("Vid", server)
                resolved += episode.episodeNumber
                when (episode.episodeNumber) {
                    2 -> error("Server missing")
                    4 -> withTimeout(10) { delay(20) }
                }
                request(episode)
            },
            inspect = { episode, request, language ->
                if (episode.episodeNumber == 3) error("Invalid manifest")
                inspected(episode, request, language)
            },
            onPrepared = { key, value -> results[key] = value }, onError = { key, error -> errors[key] = error })
        assertEquals(1, discoveries)
        assertEquals((2..6).toSet(), resolved.toSet())
        assertEquals(setOf("1", "5", "6"), results.keys)
        assertEquals(setOf("2", "3", "4"), errors.keys)
        assertTrue(errors.values.all { it.contains("Vid") && it.contains("Retry this episode") })
        assertTrue(errors.getValue("4").contains("timed out"))
    }

    @Test fun cachedUnselectedAnchorIsReusedForRetryWithoutDiscovery() = runTest {
        val anchor = prepared(selection(1).copy(source = source), server = "Mist - HD")
        val results = mutableListOf<PreparedDownload>()
        prepareDownloadBatchInternal(listOf("3" to selection(3)), "en", mapOf("1" to anchor),
            discover = { error("Must reuse anchor") },
            resolvePinned = { episode, server ->
                assertEquals(source, episode.source)
                assertEquals("Mist - HD", server)
                assertEquals(3, episode.episodeNumber)
                request(episode)
            }, inspect = ::inspected,
            onPrepared = { _, value -> results += value }, onError = { _, error -> fail(error) })
        assertEquals("Mist - HD", results.single().server)
        assertNotEquals(anchor.playback.url, results.single().playback.url)
    }

    @Test fun languageChangeReinspectsEachCachedRequestWithoutResolvingOrSharingStreamKeys() = runTest {
        val cached = items(3).associate { (key, episode) ->
            val item = prepared(episode.copy(source = source))
            key to item.copy(playback = item.playback.copy(subtitlesVtt = "old subtitles", preferEmbeddedSubtitles = true))
        }
        val results = mutableMapOf<String, PreparedDownload>()
        var inspections = 0
        prepareDownloadBatchInternal(items(3), "fr", cached,
            discover = { error("Must not discover") }, resolvePinned = { _, _ -> error("Must not resolve") },
            inspect = { episode, request, language ->
                inspections++
                val original = cached.getValue("${episode.episodeNumber}")
                assertEquals(original.playback.url, request.url)
                assertEquals(original.playback.cookie, request.cookie)
                assertEquals(source, episode.source)
                assertEquals("fr", request.subtitleLanguage)
                assertEquals("", request.subtitlesVtt)
                assertFalse(request.preferEmbeddedSubtitles)
                inspected(episode, request, language).copy(qualities = listOf(DownloadQuality("720p", 720, 100, true,
                    listOf(StreamKey(0, episode.episodeNumber!!), StreamKey(2, episode.episodeNumber!!)))))
            }, onPrepared = { key, value -> results[key] = value }, onError = { _, error -> fail(error) })
        assertEquals(3, inspections)
        assertEquals(3, results.values.map { it.qualities.single().keys }.distinct().size)
        assertTrue(results.values.all { it.server == "Vid" && it.playback.subtitleLanguage == "fr" })
        assertTrue(cached.values.all { it.playback.subtitleLanguage == "en" })
    }

    @Test fun unchangedLanguageReturnsCachedSuccessWithoutInspection() = runTest {
        val cached = prepared(selection(1).copy(source = source))
        var result: PreparedDownload? = null
        prepareDownloadBatchInternal(items(1), "EN", mapOf("1" to cached),
            discover = { error("Must not discover") }, resolvePinned = { _, _ -> error("Must not resolve") },
            inspect = { _, _, _ -> error("Must not inspect") },
            onPrepared = { _, value -> result = value }, onError = { _, error -> fail(error) })
        assertSame(cached, result)
    }

    @Test fun rejectsOtherMediaSeasonEpisodeAndStaleSourceCacheEntries() = runTest {
        val stale = source.copy(baseUrl = "https://old.example")
        val cached = mapOf(
            "1" to prepared(selection(9).copy(source = stale)),
            "other-season" to prepared(selection(1, season = 3).copy(source = source)),
            "other-media" to prepared(selection(1, id = 999).copy(source = source)),
            "old-source" to prepared(selection(1).copy(source = stale)))
        var discoveries = 0
        prepareDownloadBatchInternal(items(2), "en", cached,
            discover = { assertEquals(1, it.episodeNumber); discoveries++; prepared(it.copy(source = source)) },
            resolvePinned = { episode, _ -> assertEquals(source, episode.source); request(episode) },
            inspect = ::inspected, sourceIsCurrent = { it.source != stale },
            onPrepared = { _, _ -> }, onError = { _, error -> fail(error) })
        assertEquals(1, discoveries)
    }

    @Test fun mismatchedEpisodeCacheMayAnchorButCannotSupplyAnotherEpisodesRequest() = runTest {
        val cached = prepared(selection(9).copy(source = source))
        var resolved = 0
        prepareDownloadBatchInternal(items(1), "en", mapOf("1" to cached),
            discover = { error("Must reuse same-season anchor") },
            resolvePinned = { episode, _ -> resolved++; assertEquals(1, episode.episodeNumber); request(episode) },
            inspect = ::inspected,
            onPrepared = { _, value -> assertNotEquals(cached.playback.url, value.playback.url) },
            onError = { _, error -> fail(error) })
        assertEquals(1, resolved)
    }

    @Test fun languageInspectionFailureKeepsOtherCachedEpisodesAndDoesNotResolve() = runTest {
        val cached = items(2).associate { (key, episode) -> key to prepared(episode.copy(source = source)) }
        val errors = mutableListOf<String>()
        val successes = mutableListOf<String>()
        prepareDownloadBatchInternal(items(2), "fr", cached,
            discover = { error("Must not discover") }, resolvePinned = { _, _ -> error("Must not resolve") },
            inspect = { episode, request, language ->
                if (episode.episodeNumber == 1) error("Expired manifest")
                inspected(episode, request, language)
            }, onPrepared = { key, _ -> successes += key }, onError = { key, _ -> errors += key })
        assertEquals(listOf("1"), errors)
        assertEquals(listOf("2"), successes)
    }

    @Test fun parentCancellationStopsFourActiveWorkersAndQueuedItemsWithoutErrors() = runTest {
        var active = 0
        var released = 0
        var resolved = 0
        var errors = 0
        val job = launch {
            prepareDownloadBatchInternal(items(10), "en", emptyMap(),
                discover = { prepared(it.copy(source = source)) },
                resolvePinned = { _, _ ->
                    resolved++; active++
                    try { awaitCancellation() } finally { active--; released++ }
                }, inspect = ::inspected, onPrepared = { _, _ -> }, onError = { _, _ -> errors++ })
        }
        runCurrent()
        assertEquals(4, active)
        job.cancelAndJoin()
        assertEquals(4, resolved)
        assertEquals(4, released)
        assertEquals(0, active)
        assertEquals(0, errors)
    }

    @Test fun cancellationDuringDiscoveryPropagatesWithoutPerItemErrors() = runTest {
        var cancelled = false
        try {
            prepareDownloadBatchInternal(items(3), "en", emptyMap(),
                discover = { throw CancellationException("Stopped") },
                resolvePinned = { _, _ -> error("Must not resolve") }, inspect = ::inspected,
                onPrepared = { _, _ -> fail() }, onError = { _, _ -> fail() })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun workerCancellationPropagatesToBatchRatherThanBeingSilentlyJoined() = runTest {
        var cancelled = false
        try {
            prepareDownloadBatchInternal(items(3), "en", emptyMap(),
                discover = { prepared(it.copy(source = source)) },
                resolvePinned = { _, _ -> throw CancellationException("Stopped") }, inspect = ::inspected,
                onPrepared = { _, _ -> }, onError = { _, _ -> fail() })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun callerTimeoutRemainsCancellationRatherThanPerItemFailure() = runTest {
        var cancelled = false
        try {
            withTimeout(10) {
                prepareDownloadBatchInternal(items(3), "en", emptyMap(),
                    discover = { delay(20); prepared(it.copy(source = source)) },
                    resolvePinned = { _, _ -> error("Must not resolve") }, inspect = ::inspected,
                    onPrepared = { _, _ -> fail() }, onError = { _, _ -> fail() })
            }
        } catch (_: TimeoutCancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
