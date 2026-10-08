@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import androidx.media3.exoplayer.offline.Download
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.downloads.*
import com.aliflix.app.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineDownloadTest {
    @Test fun mp4PlaysAndSeeksWithSourceGone() = verify(false)
    @Test fun hlsQualityAndOriginalSubtitlesPlayWithSourceGone() = verify(true)
    @Test fun alternateAudioAndSubtitlesSurviveOwnedManifestAndRestart() = verify(true, true)
    private fun verify(hls: Boolean, alternate: Boolean = false) {
        grantNativeFixtureNetworkPermission()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bytes = instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() }
        val files = if (hls) instrumentation.context.assets.list("offline-hls")!!.associateWith { name -> instrumentation.context.assets.open("offline-hls/$name").use { it.readBytes() } } else mapOf("video.mp4" to bytes)
        val fixture = if (alternate) files + ("master.m3u8" to ("#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"low.m3u8\"\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"French\",LANGUAGE=\"fr\",DEFAULT=NO,AUTOSELECT=YES,URI=\"high.m3u8\"\n" +
            String(files.getValue("master.m3u8"), Charsets.UTF_8).substringAfter("#EXTM3U\n").replace("SUBTITLES=\"subs\"", "SUBTITLES=\"subs\",AUDIO=\"audio\""))
            .toByteArray()) else files
        val server = OfflineFixtureServer(fixture, if (hls) "master.m3u8" else "video.mp4", if (hls) 0 else 8)
        val selection = PlaybackSelection(Media(2147482997, MediaType.MOVIE, "Offline fixture"))
        val id = playbackProgressKey(selection)
        lateinit var store: OfflineDownloads
        instrumentation.runOnMainSync { store = OfflineDownloads.get(context) }
        var scenario: ActivityScenario<NativePlayerActivity>? = null
        try {
            instrumentation.runOnMainSync { store.manager.removeDownload(id) }
            await { store.manager.downloadIndex.getDownload(id) == null }
            val request = NativePlaybackRequest(server.url, if (hls) "application/x-mpegURL" else "video/mp4", "https://fixture.aliflix.test/", "Aliflix test", "",
                "Offline fixture", 0, true, subtitlesVtt = if (hls) "" else "WEBVTT\n\n00:00:00.000 --> 00:00:20.000\nOffline subtitle\n",
                selectionJson = selection.nativeJson())
            val prepared = runBlocking { inspectDownload(selection, request, "EN") }
            if (hls) {
                assertEquals(listOf(180, 90), prepared.qualities.map { it.height })
                assertTrue(prepared.hasOriginalSubtitles)
            } else assertEquals(bytes.size.toLong(), prepared.qualities.single().bytes)
            val quality = prepared.qualities.first()
            val foreign = prepared.copy(playback = prepared.playback.copy(url = "https://wrong-source.example/master.m3u8", cookie = "wrong-cookie"), qualities = listOf(quality.copy(height = 560)))
            val aggregate = DownloadDiscovery(listOf(DownloadOption(foreign.qualities.first(), foreign), DownloadOption(quality, prepared)), true).prepared()
            val download = runBlocking { aggregate.downloadRequest(quality, "EN", true, if (alternate) -1 else null) }
            assertEquals(prepared.playback.url, download.uri.toString())
            if (alternate) assertEquals(2, download.streamKeys.count { it.groupIndex == 1 })
            // Manager and service use the same durable index/cache; foreground transfer is exercised below.
            ActivityScenario.launch<com.aliflix.app.MainActivity>(Intent(context, com.aliflix.app.MainActivity::class.java).putExtra("openDownloads", true)).use {
                runBlocking { withContext(Dispatchers.Main) { store.enqueue(listOf(download)) } }
                if (!hls) {
                    await { store.entries.value.any { item -> item.id == id && item.download.state == Download.STATE_DOWNLOADING && item.downloadedBytes > 0 } }
                    instrumentation.runOnMainSync { store.pause(id) }
                    await { store.entries.value.any { item -> item.id == id && item.download.state == Download.STATE_STOPPED } }
                    Thread.sleep(300)
                    val paused = store.entries.value.first { item -> item.id == id }
                    assertEquals(Download.STATE_STOPPED, paused.download.state)
                    instrumentation.runOnMainSync { store.resume(paused) }
                }
                await { store.manager.downloadIndex.getDownload(id)?.state == Download.STATE_COMPLETED }
            }
            if (!hls) assertEquals(bytes.size.toLong(), store.manager.downloadIndex.getDownload(id)!!.bytesDownloaded)
            server.close()
            // Read through a new index instance, with the source server gone.
            val persisted = androidx.media3.exoplayer.offline.DefaultDownloadIndex(androidx.media3.database.StandaloneDatabaseProvider(context)).getDownload(id)!!
            val saved = NativePlaybackRequest.fromJson(org.json.JSONObject(String(persisted.request.data, Charsets.UTF_8)).getString("playback"))
            val file = java.io.File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json")
            file.writeText(saved.copy(positionMs = 2_000).toJson())
            scenario = ActivityScenario.launch(Intent(context, NativePlayerActivity::class.java).putExtra("requestFile", file.name))
            await { NativePlaybackService.playbackReady && NativePlaybackService.renderedStreamUrl == saved.url }
            await {
                var visible = false
                instrumentation.runOnMainSync {
                    visible = NativePlaybackService.currentCaptionCues().any { it.text.toString() == "Offline subtitle" }
                }
                visible
            }
            scenario.recreate()
            await { NativePlaybackService.playbackReady }
            if (alternate) {
                await { var found = false; scenario.onActivity { found = it.playbackController!!.currentTracks.groups.filter { group -> group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }.sumOf { group -> group.length } >= 2 }; found }
                scenario.onActivity { activity ->
                    val controller = activity.playbackController!!
                    val tracks = controller.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                    val group = tracks.last()
                    controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon()
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, group.length - 1)).build()
                }
                await { NativePlaybackService.playbackReady && NativePlaybackService.playbackFailure == null }
            }
            val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java)
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
            try {
                val controller = manager.getActiveSessions(null).first { it.packageName == context.packageName }
                controller.transportControls.seekTo(7_000)
                await { (controller.playbackState?.position ?: 0) >= 7_000 }
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            assertNull(NativePlaybackService.playbackFailure)
        } finally {
            scenario?.close(); server.close()
            context.stopService(Intent(context, NativePlaybackService::class.java))
            instrumentation.runOnMainSync { store.manager.removeDownload(id) }
        }
    }
    private fun await(test: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 30_000
        while (!test() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        assertTrue("Download/offline playback timed out", test())
    }
}

internal class OfflineFixtureServer(private val files: Map<String, ByteArray>, entry: String, private val throttleMs: Long = 0) : AutoCloseable {
    private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}/$entry"
    init { kotlin.concurrent.thread(isDaemon = true) { while (!socket.isClosed) {
        val client = runCatching { socket.accept() }.getOrNull() ?: break
        kotlin.concurrent.thread(isDaemon = true) { runCatching { client.use { connection ->
            val input = connection.getInputStream().bufferedReader()
            val first = input.readLine() ?: return@use
            val path = first.split(" ")[1].substringAfterLast('/')
            val data = files[path] ?: return@use
            var range: String? = null
            while (true) { val line = input.readLine() ?: return@use; if (line.isEmpty()) break; if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim() }
            val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val end = (range?.substringAfter('-')?.toIntOrNull() ?: data.lastIndex).coerceAtMost(data.lastIndex)
            val type = when { path.endsWith("m3u8") -> "application/x-mpegURL"; path.endsWith("ts") -> "video/mp2t"; path.endsWith("vtt") -> "text/vtt"; else -> "video/mp4" }
            val header = buildString {
                append("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Type: $type\r\nContent-Length: ${end - start + 1}\r\n")
                if (range != null) append("Content-Range: bytes $start-$end/${data.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            connection.getOutputStream().apply { write(header.toByteArray()); if (!first.startsWith("HEAD")) {
                var position = start
                while (position <= end) { val size = minOf(4096, end - position + 1); write(data, position, size); flush(); position += size; if (throttleMs > 0) Thread.sleep(throttleMs) }
            }; flush() }
        } } }
    } } }
    override fun close() { socket.close() }
}
