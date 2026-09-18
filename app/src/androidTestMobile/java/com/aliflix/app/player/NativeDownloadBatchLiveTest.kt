@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.exoplayer.offline.Download
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.AliflixApplication
import com.aliflix.app.data.PlaybackProviderRepository
import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.downloads.OfflineDownloads
import com.aliflix.app.downloads.PreparedDownload
import com.aliflix.app.downloads.downloadRequest
import com.aliflix.app.downloads.prepareDownload
import com.aliflix.app.downloads.prepareDownloadBatch
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real provider batch download test. Verifies one discovery serves every selected episode. */
class NativeDownloadBatchLiveTest {
    private val evidence = StringBuilder()

    @Test fun batchPreparesAllEpisodesOnOneProviderServerAndDownloadsRealBytes() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBatch") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNativeFixtureNetworkPermission()
        val media = Media(1396, MediaType.TV, "Breaking Bad")
        val selections = (1..3).map { episode ->
            "$episode" to PlaybackSelection(media, 1, episode, "Episode $episode",
                source = PlaybackProviderRepository(context).preferences.value.sourceFor(media))
        }
        val host = FrameLayout(context)
        var activity: ComponentActivity? = null
        var prepared = mutableMapOf<String, PreparedDownload>()
        val scenario = ActivityScenario.launch(com.aliflix.app.MainActivity::class.java)
        scenario.onActivity { current ->
            activity = current
            (current.findViewById(android.R.id.content) as? android.view.ViewGroup)
                ?.addView(host, android.view.ViewGroup.LayoutParams(1, 1))
        }
        try {
            val current = requireNotNull(activity) { "MainActivity did not attach" }
            val singleStart = SystemClock.elapsedRealtime()
            val single = withContext(Dispatchers.Main) { prepareDownload(current, host, selections.first().second, "en") }
            val singleElapsed = SystemClock.elapsedRealtime() - singleStart
            evidence.append("singleElapsedMs=$singleElapsed\n")
            prepared = mutableMapOf(selections.first().first to single)
            val errors = mutableMapOf<String, String>()
            val batchStart = SystemClock.elapsedRealtime()
            repeat(3) { attempt ->
                errors.clear()
                errors.putAll(buildMap {
                    withContext(Dispatchers.Main) {
                        prepareDownloadBatch(current, host, selections, "en", prepared.toMap(),
                            onPrepared = { key, value -> prepared[key] = value },
                            onError = { key, message -> put(key, message) })
                    }
                })
                errors.forEach { (key, message) -> evidence.append("attempt${attempt + 1} error[$key]=$message\n") }
                if (errors.isEmpty()) return@repeat
            }
            val batchElapsed = SystemClock.elapsedRealtime() - batchStart
            evidence.append("batchElapsedMs=$batchElapsed\n")
            assertTrue("Episodes failed preparation: $errors", errors.isEmpty())
            assertEquals(3, prepared.size)
            assertEquals("Every episode must resolve on the same provider",
                1, prepared.values.map { it.selection.source.provider }.distinct().size)
            assertEquals("Every episode must resolve on the same server",
                1, prepared.values.map { it.server }.distinct().size)
            assertTrue("The shared server must be identified", prepared.values.first().server.isNotBlank())
            assertEquals("Episodes must keep their own stream URLs",
                3, prepared.values.map { it.playback.url }.distinct().size)
            assertTrue("Batch preparation must stay within one discovery plus pinned retries",
                batchElapsed < 240_000)
            evidence.append("provider=${prepared.values.first().selection.source.provider}\n")
            evidence.append("server=${prepared.values.first().server}\n")
            val store = OfflineDownloads.get(context)
            val requests = prepared.values.map { item ->
                val lowest = item.qualities.minByOrNull { it.height } ?: item.qualities.first()
                evidence.append("quality=${lowest.label} estimate=${lowest.bytes}\n")
                item.downloadRequest(lowest, "", false)
            }
            instrumentation.runOnMainSync {
                runBlocking { withContext(Dispatchers.Main) { store.enqueue(requests) } }
            }
            await {
                requests.all { request ->
                    store.manager.downloadIndex.getDownload(request.id)
                        ?.let { it.bytesDownloaded > 0 } == true
                }
            }
            requests.forEach { request ->
                val download = store.manager.downloadIndex.getDownload(request.id)!!
                evidence.append("downloaded=${download.bytesDownloaded} state=${download.state}\n")
                assertTrue("Real bytes must be transferred", download.bytesDownloaded > 0)
            }
            android.util.Log.i("AliflixBatchTest", evidence.toString())
            val output = java.io.File(context.getExternalFilesDir(null), "batch-validation").apply { mkdirs() }
            java.io.File(output, "batch-evidence.txt").writeText(evidence.toString())
        } finally {
            scenario.close()
            context.stopService(Intent(context, NativePlaybackService::class.java))
            instrumentation.runOnMainSync {
                prepared.values.forEach { OfflineDownloads.get(context).remove(playbackProgressKey(it.selection)) }
            }
        }
    }

    private fun await(test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 300_000
        while (!test() && SystemClock.elapsedRealtime() < end) Thread.sleep(500)
        assertTrue("Real batch download failed to transfer bytes:\n$evidence", test())
    }
}
