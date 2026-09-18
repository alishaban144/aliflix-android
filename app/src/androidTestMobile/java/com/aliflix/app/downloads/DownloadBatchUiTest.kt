@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.downloads

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        var heightsForPrepared: (String) -> List<Int> = { listOf(1080, 720, 480) }
        val enqueued = mutableListOf<List<Pair<String, String>>>()
        val paused = mutableListOf<String>()
        val resumed = mutableListOf<String>()
        var preparations = 0
        override suspend fun seasons(media: Media) = listOf(Season(1, "Season 1"))
        override suspend fun episodes(media: Media, season: Int) = (1..3).map { Episode(1, it, "Episode $it") }
        override suspend fun prepare(activity: ComponentActivity, host: android.widget.FrameLayout,
            selections: List<Pair<String, PlaybackSelection>>, language: String, cached: Map<String, PreparedDownload>,
            onPrepared: (String, PreparedDownload) -> Unit, onError: (String, String) -> Unit) {
            preparations++
            selections.forEach { (key, selection) ->
                onPrepared(key, PreparedDownload(selection, request(selection),
                    heightsForPrepared(key).map { height -> DownloadQuality("${height}p", height, 1_000_000, true, emptyList()) },
                    true, "Vid"))
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
        compose.setContent { DownloadButton(Media(1396, MediaType.TV, "Series"), episode, dependencies = fake) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Download, Series", true)
            .fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Download, Series").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Select all").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun selectAll() {
        compose.waitUntil(10_000) { runCatching {
            compose.onNodeWithText("Select all").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        }.getOrDefault(false) }
        compose.onNodeWithText("Select all").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Quality for all 3 episodes", true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Available qualities differ", true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun sharedQualityMenuReplacesPerEpisodeMenusAndAppliesToAllEpisodes() {
        val fake = FakeUi()
        openPicker(fake, null)
        selectAll()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Quality for all 3 episodes", true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Quality for all 3 episodes", true).assertExists()
        assertEquals(0, compose.onAllNodes(hasContentDescription("Quality for E", true)).fetchSemanticsNodes().size)
        compose.onNodeWithText("720p · ≈ 3 MB total ▾").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1080p · ≈ 3 MB total").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1080p · ≈ 3 MB total").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1080p · ≈ 3 MB total ▾").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Download 3 episodes").performClick()
        compose.waitUntil(10_000) { fake.enqueued.isNotEmpty() }
        val requests = fake.enqueued.single()
        assertEquals(listOf("tv:1396:s1:e1", "tv:1396:s1:e2", "tv:1396:s1:e3"), requests.map { it.first })
        assertTrue(requests.all { it.second == "1080p" })
        assertTrue(fake.paused.isEmpty())
    }

    @Test fun perEpisodeQualityMenusAppearWhenQualitiesDiffer() {
        val fake = FakeUi()
        fake.heightsForPrepared = { key -> if (key == "1:1") listOf(1080, 720) else listOf(720, 480) }
        openPicker(fake, null)
        selectAll()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Available qualities differ", true).fetchSemanticsNodes().isNotEmpty() }
        (1..3).forEach { episode ->
            assertEquals(1, compose.onAllNodes(hasContentDescription("Quality for E$episode", true)).fetchSemanticsNodes().size)
        }
        assertEquals(0, compose.onAllNodesWithText("Quality for all 3 episodes", true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Download 3 episodes").performClick()
        compose.waitUntil(10_000) { fake.enqueued.isNotEmpty() }
        assertEquals(listOf("tv:1396:s1:e1", "tv:1396:s1:e2", "tv:1396:s1:e3"), fake.enqueued.single().map { it.first })
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
