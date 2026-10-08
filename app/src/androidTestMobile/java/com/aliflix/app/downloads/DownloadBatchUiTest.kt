@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.downloads

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onLast
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.model.Episode
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.Season
import com.aliflix.app.player.NativePlaybackRequest
import com.aliflix.app.player.nativeJson
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DownloadBatchUiTest {
    @get:Rule val compose = createComposeRule()

    private class FakeUi : DownloadUiDependencies {
        override val entries = MutableStateFlow<List<SavedDownload>>(emptyList())
        override val preferredHeight = 720
        override val requestNotifications = false
        var audioForPrepared: (String) -> List<DownloadAudioTrack> = { emptyList() }
        var heightsForPrepared: (String) -> List<Int> = { listOf(1080, 720, 480) }
        val enqueued = mutableListOf<List<Pair<String, String>>>()
        val paused = mutableListOf<String>()
        val resumed = mutableListOf<String>()
        var discoveryGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var progressiveGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var preparations = 0
        override suspend fun discover(activity: ComponentActivity, host: android.widget.FrameLayout,
            selection: PlaybackSelection, language: String, onUpdate: (DownloadDiscovery) -> Unit): PreparedDownload {
            val gate = progressiveGate ?: return super<DownloadUiDependencies>.discover(activity, host, selection, language, onUpdate)
            fun source(vararg heights: Int) = PreparedDownload(selection, request(selection),
                heights.map { DownloadQuality("${it}p", it, 1_000_000, true, emptyList()) }, true, "Fixture")
            val early = source(560, 320)
            val options = early.qualities.map { DownloadOption(it, early) }.toMutableList()
            onUpdate(DownloadDiscovery(options.toList()))
            gate.await()
            val later = source(1080, 930, 720)
            options += later.qualities.map { DownloadOption(it, later) }
            return DownloadDiscovery(options, true).also(onUpdate).prepared()
        }
        override suspend fun seasons(media: Media) = listOf(Season(1, "Season 1"))
        override suspend fun episodes(media: Media, season: Int) = (1..3).map { Episode(1, it, "Episode $it") }
        override suspend fun prepare(activity: ComponentActivity, host: android.widget.FrameLayout,
            selections: List<Pair<String, PlaybackSelection>>, language: String, cached: Map<String, PreparedDownload>,
            onPrepared: (String, PreparedDownload) -> Unit, onError: (String, String) -> Unit) {
            preparations++
            if (cached.isEmpty()) discoveryGate?.await()
            selections.forEach { (key, selection) ->
                onPrepared(key, cached[key] ?: PreparedDownload(selection.copy(source = com.aliflix.app.model.PlaybackSource(com.aliflix.app.model.PlaybackProviderId.RAMOFLIX)), request(selection),
                    heightsForPrepared(key).map { height -> DownloadQuality("${height}p", height, 1_000_000, true, emptyList(), "audio") },
                    true, "Vid", audioTracks = audioForPrepared(key)))
            }
        }
        override fun blockedIds() = emptySet<String>()
        override suspend fun enqueue(requests: List<DownloadRequest>) {
            enqueued.add(requests.map { it.id to JSONObject(String(it.data, Charsets.UTF_8)).getString("quality") })
        }
        override fun pause(id: String) { paused.add(id) }
        override fun resume(saved: SavedDownload) { resumed.add(saved.id) }
        private fun request(selection: PlaybackSelection) = NativePlaybackRequest(
            "https://fixture.example/${selection.media.id}/${selection.seasonNumber}/${selection.episodeNumber}.m3u8",
            "application/x-mpegURL", "https://fixture.example/", "test", "", selection.episodeTitle.orEmpty(), 0, false,
            selectionJson = selection.nativeJson())
    }

    private fun saved(id: String, selection: PlaybackSelection, state: Int, percent: Int, contentLength: Long = 10_000_000): SavedDownload {
        val request = DownloadRequest.Builder(id, android.net.Uri.parse("https://fixture.example/v.m3u8"))
            .setMimeType("application/x-mpegURL").build()
        val progress = DownloadProgress().apply {
            bytesDownloaded = contentLength * percent / 100
            percentDownloaded = percent.toFloat()
        }
        val reason = if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE
        val download = Download(request, state, 0, 0, contentLength, 0, reason, progress)
        val playback = NativePlaybackRequest("https://fixture.example/v.m3u8", "application/x-mpegURL", "", "test", "",
            "Episode 1", 0, false, selectionJson = selection.nativeJson())
        return SavedDownload(download, playback, "720p", contentLength)
    }

    private fun openPicker(fake: FakeUi, episode: Episode?) {
        compose.setContent { com.aliflix.app.ui.theme.AliflixMobileTheme { DownloadButton(Media(1396, MediaType.TV, "Series"), episode, dependencies = fake) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Download, Series", true)
            .fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Download, Series").performClick()
        if (episode == null) compose.onNodeWithText("Episodes").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(if (episode == null) "Clear" else "Balanced").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun selectAll() {
        // Opening the batch picker selects all eligible episodes automatically.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Balanced").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun largeTextKeepsDownloadVisibleAndEpisodesReachable() {
        val fake = FakeUi()
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, 1.6f)) {
                com.aliflix.app.ui.theme.AliflixMobileTheme {
                    DownloadButton(Media(1396, MediaType.TV, "Series"), dependencies = fake)
                }
            }
        }
        compose.onNodeWithContentDescription("Download, Series").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Balanced").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Download 3 episodes").assertIsDisplayed()
        compose.onNodeWithText("Episodes").performClick()
        compose.onNodeWithText("Clear").performScrollTo()
        // Index zero is Select all/Clear. Reveal the nested list's bottom in
        // the outer sheet before checking its third episode at large text.
        compose.onAllNodes(androidx.compose.ui.test.hasScrollAction()).onLast().performScrollToIndex(3)
        compose.onNode(hasText("Subtitles:", substring = true)).performScrollTo()
        compose.onNodeWithText("E3 · Episode 3").assertIsDisplayed()
        compose.onNodeWithText("Download 3 episodes").assertIsDisplayed()
    }

    @Test fun initialSkeletonDisablesDownloadUntilDiscoveryFinishes() {
        val fake = FakeUi()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.discoveryGate = gate
        compose.setContent { com.aliflix.app.ui.theme.AliflixMobileTheme {
            DownloadButton(Media(2147482001, MediaType.MOVIE, "Loading fixture"), dependencies = fake)
        } }
        compose.onNodeWithContentDescription("Download, Loading fixture").performClick()
        compose.onNodeWithText("Finding download options…").assertIsDisplayed()
        compose.onAllNodesWithText("Download").onLast().assertIsNotEnabled()
        gate.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Balanced").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Balanced").assertIsDisplayed()
        compose.onNodeWithText("Finding download options…").assertDoesNotExist()
    }

    @Test fun aQualityChosenDuringDiscoverySurvivesLaterProviderResults() {
        val fake = FakeUi()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.progressiveGate = gate
        compose.setContent { com.aliflix.app.ui.theme.AliflixMobileTheme {
            DownloadButton(Media(2147482002, MediaType.MOVIE, "Progressive fixture"), dependencies = fake)
        } }
        compose.onNodeWithContentDescription("Download, Progressive fixture").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("560p").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("560p").performClick()
        compose.onAllNodesWithText("Download").onLast().assertIsNotEnabled()
        gate.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Balanced").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("560p").assertIsDisplayed()
        compose.waitUntil(5_000) { !compose.onAllNodesWithText("Download").onLast().fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        compose.onAllNodesWithText("Download").onLast().performClick()
        compose.waitUntil(5_000) { fake.enqueued.isNotEmpty() }
        assertEquals("560p", fake.enqueued.single().single().second)
    }
    @Test fun sharedQualityMenuReplacesPerEpisodeMenusAndAppliesToAllEpisodes() {
        val fake = FakeUi()
        openPicker(fake, null)
        selectAll()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Balanced").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Balanced").assertExists()
        assertEquals(0, compose.onAllNodes(hasContentDescription("Quality for E", true)).fetchSemanticsNodes().size)
        compose.onNodeWithText("Best").performClick()
        compose.waitUntil(5_000) { !compose.onNodeWithText("Download 3 episodes").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        compose.onNodeWithText("Download 3 episodes").performClick()
        compose.waitUntil(10_000) { fake.enqueued.isNotEmpty() }
        val requests = fake.enqueued.single()
        assertEquals(listOf("tv:1396:s1:e1", "tv:1396:s1:e2", "tv:1396:s1:e3"), requests.map { it.first })
        assertTrue(requests.all { it.second == "1080p" })
        assertTrue(fake.paused.isEmpty())
    }

    @Test fun differingEpisodeResolutionsStillHaveOneSeasonTierAndNoPerEpisodeMenus() {
        val fake = FakeUi()
        fake.heightsForPrepared = { key -> if (key.contains(":e1:")) listOf(930, 560, 320) else listOf(640, 480, 320) }
        openPicker(fake, null)
        selectAll()
        assertEquals(0, compose.onAllNodes(hasContentDescription("Quality for E", true)).fetchSemanticsNodes().size)
        compose.waitUntil(10_000) { !compose.onNodeWithText("Download 3 episodes").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        compose.onNodeWithText("Download 3 episodes").performClick()
        compose.waitUntil(10_000) { fake.enqueued.isNotEmpty() }
        assertEquals(listOf("560p", "480p", "480p"), fake.enqueued.single().map { it.second })
    }

    @Test fun identicalEpisodeAudioAppearsOnceBesideSharedQuality() {
        val fake = FakeUi()
        fake.audioForPrepared = { listOf(DownloadAudioTrack("Track 1", null, "audio", 0)) }
        openPicker(fake, null)
        selectAll()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Audio: Track 1").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, compose.onAllNodesWithText("Audio: Track 1").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription("Quality for E", true)).fetchSemanticsNodes().size)
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val output = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "flixer-validation").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(output, "download-episodes.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        }
    }

    @Test fun downloadingRingShowsDeterminateProgressAndClickPauses() {
        val fake = FakeUi()
        val selection = PlaybackSelection(Media(1396, MediaType.TV, "Series"), 1, 1, "Episode 1")
        fake.entries.value = listOf(saved(playbackProgressKey(selection), selection, Download.STATE_DOWNLOADING, 40))
        compose.setContent { DownloadButton(Media(1396, MediaType.TV, "Series"), Episode(1, 1, "Episode 1"), dependencies = fake) }
        compose.onNodeWithContentDescription("Pause download, Series, season 1, episode 1").assertExists()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.4f, 0f..1f))).assertExists()
        compose.onNodeWithContentDescription("Pause download, Series, season 1, episode 1").performClick()
        compose.waitUntil(5_000) { fake.paused.isNotEmpty() }
        assertEquals(listOf(playbackProgressKey(selection)), fake.paused)
    }

    @Test fun queuedStateShowsIndeterminateRing() {
        val fake = FakeUi()
        val selection = PlaybackSelection(Media(1396, MediaType.TV, "Series"), 1, 1, "Episode 1")
        fake.entries.value = listOf(saved(playbackProgressKey(selection), selection, Download.STATE_QUEUED, -1))
        compose.setContent { DownloadButton(Media(1396, MediaType.TV, "Series"), Episode(1, 1, "Episode 1"), dependencies = fake) }
        compose.onNodeWithContentDescription("Pause download, Series, season 1, episode 1").assertExists()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
    }

    @Test fun pausedStateResumesAndCompletedShowsDoneWithoutOpeningPicker() {
        val fake = FakeUi()
        val selection = PlaybackSelection(Media(1396, MediaType.TV, "Series"), 1, 1, "Episode 1")
        fake.entries.value = listOf(saved(playbackProgressKey(selection), selection, Download.STATE_STOPPED, 40))
        compose.setContent { DownloadButton(Media(1396, MediaType.TV, "Series"), Episode(1, 1, "Episode 1"), dependencies = fake) }
        compose.onNodeWithContentDescription("Resume download, Series, season 1, episode 1").performClick()
        compose.waitUntil(5_000) { fake.resumed.isNotEmpty() }
        fake.entries.value = listOf(saved(playbackProgressKey(selection), selection, Download.STATE_COMPLETED, 100))
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Downloaded, Series, season 1, episode 1")
            .fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Downloaded, Series, season 1, episode 1").performClick()
        assertTrue(fake.paused.isEmpty() && fake.resumed.size == 1 && fake.preparations == 0)
    }

    @Test fun failedStateOffersRetryThroughPicker() {
        val fake = FakeUi()
        val selection = PlaybackSelection(Media(1396, MediaType.TV, "Series"), 1, 1, "Episode 1")
        fake.entries.value = listOf(saved(playbackProgressKey(selection), selection, Download.STATE_FAILED, 10))
        compose.setContent { DownloadButton(Media(1396, MediaType.TV, "Series"), Episode(1, 1, "Episode 1"), dependencies = fake) }
        compose.onNodeWithContentDescription("Retry download, Series, season 1, episode 1").performClick()
        compose.waitUntil(10_000) { fake.preparations >= 1 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Download", true).fetchSemanticsNodes().isNotEmpty() }
    }
}
