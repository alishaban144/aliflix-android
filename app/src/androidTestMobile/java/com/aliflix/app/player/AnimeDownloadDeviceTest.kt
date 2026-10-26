@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.downloads.*
import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AnimeDownloadDeviceTest {
    @Test fun japaneseEpisodesDiscoverNativeOptionsAndDownloadThroughPinnedRoute() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAnimeDownload") == "true")
        grantNativeFixtureNetworkPermission()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val report = File(context.getExternalFilesDir(null), "anime-download-device.txt").apply { writeText("") }
        ActivityScenario.launch(com.aliflix.app.MainActivity::class.java).use { scenario ->
            lateinit var activity: androidx.activity.ComponentActivity
            lateinit var host: FrameLayout
            scenario.onActivity { activity = it; host = FrameLayout(it); (it.findViewById<android.view.ViewGroup>(android.R.id.content)).addView(host, android.view.ViewGroup.LayoutParams(1, 1)) }
            for ((index, media) in listOf(
                Media(21, MediaType.TV, "One Piece", year = "1999", genres = listOf("Animation"), originalLanguage = "ja"),
                Media(1429, MediaType.TV, "Attack on Titan", year = "2013", genres = listOf("Animation"), originalLanguage = "ja")
            ).withIndex()) {
                val selection = PlaybackSelection(media, 1, 1, source = PlaybackSource(PlaybackProviderId.MIRURO))
                val began = android.os.SystemClock.elapsedRealtime()
                val found = withContext(Dispatchers.Main) { prepareDownload(activity, host, selection, "en") }
                val elapsed = android.os.SystemClock.elapsedRealtime() - began
                assertTrue("Anime discovery exceeded deadline: $elapsed", elapsed < 33_000)
                assertTrue(found.qualities.isNotEmpty())
                val native = found.options.filter { it.source.selection.source.identity.isAnimeNative }
                assertTrue("No dedicated anime option: ${found.qualities.map { it.height }}", native.isNotEmpty())
                val option = relativeDownloadOptions(native).last()
                val prepared = mutableMapOf("1" to option.source)
                withContext(Dispatchers.Main) { prepareDownloadBatch(activity, host, listOf("1" to selection, "2" to selection.copy(episodeNumber = 2)), "en", prepared,
                    { key, value -> prepared[key] = value }, { _, message -> fail(message) }, pinnedAnchor = option) }
                assertEquals(2, prepared.size)
                val requests = prepared.values.map { item ->
                    val quality = closestDownloadQuality(item.qualities, option.quality.height)
                    val owner = item.owner(quality)
                    val fixture = owner.selection.copy(media = owner.selection.media.copy(id = 2147482980 + index))
                    owner.copy(selection = fixture, playback = owner.playback.copy(selectionJson = fixture.nativeJson()), options = emptyList())
                        .downloadRequest(quality, "", false)
                }
                val store = withContext(Dispatchers.Main) { OfflineDownloads.get(context) }
                try {
                    withContext(Dispatchers.Main) { store.enqueue(requests) }
                    val end = android.os.SystemClock.elapsedRealtime() + 90_000
                    while (requests.any { (store.manager.downloadIndex.getDownload(it.id)?.bytesDownloaded ?: 0L) <= 0L } && android.os.SystemClock.elapsedRealtime() < end) delay(200)
                    requests.forEach { request -> assertTrue("Anime download transferred no bytes", (store.manager.downloadIndex.getDownload(request.id)?.bytesDownloaded ?: 0) > 0) }
                    report.appendText("${media.title}: elapsedMs=$elapsed,heights=${found.qualities.map { it.height }},provider=${option.source.selection.source.identity.name},server=${option.source.server},episodes=${prepared.size},bytes=${requests.map { store.manager.downloadIndex.getDownload(it.id)?.bytesDownloaded }},pinned=${prepared.values.all { it.selection.source == option.source.selection.source && it.server == option.source.server }}\n")
                } finally { withContext(Dispatchers.Main) { requests.forEach { store.remove(it.id) } } }
            }
            scenario.onActivity { (host.parent as android.view.ViewGroup).removeView(host) }
        }
        context.stopService(Intent(context, NativePlaybackService::class.java))
        Unit
    }
}
