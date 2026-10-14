@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.MainActivity
import com.aliflix.app.downloads.*
import com.aliflix.app.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in physical proof: real provider requests, decoded frames, audio switches and offline transfer. */
class SerienstreamLiveTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output get() = File(context.getExternalFilesDir(null), "serienstream-proof").apply { mkdirs() }
    private fun enabled() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSerienstream") == "true")
        grantNativeFixtureNetworkPermission()
    }
    private fun await(seconds: Int = 45, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Timed out waiting for native playback", condition())
    }
    private fun selection(title: String, id: Int, year: String, imdb: String, language: String) =
        PlaybackSelection(Media(id, MediaType.TV, title, year = year, imdbId = imdb, originalLanguage = language),1,1,
            source = PlaybackSource(MobilePlaybackProvider.SERIENSTREAM))

    private fun <T> withProviderActivity(block: suspend (MainActivity, android.widget.FrameLayout) -> T): T =
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            lateinit var owner: MainActivity
            lateinit var host: android.widget.FrameLayout
            scenario.onActivity { activity ->
                owner = activity
                host = DownloadSession.get(activity).host
            }
            try { runBlocking { withContext(Dispatchers.Main.immediate) { block(owner, host) } } }
            finally { scenario.onActivity { (host.parent as? android.view.ViewGroup)?.removeView(host) } }
        }

    private suspend fun resolveInApp(activity: MainActivity, host: android.widget.FrameLayout,
        selected: PlaybackSelection, audio: String? = null): NativePlaybackRequest =
        NativeStreamResolver(activity, (activity.application as com.aliflix.app.AliflixApplication).playbackProgressStore, host).use {
            it.resolve(selected, 0, emptySet(), audioLanguage = audio, onServer = {})
        }

    @Test fun viewerVerificationReturnsToNativePlayback() {
        enabled()
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualVerification") == "true")
        val selected = if (InstrumentationRegistry.getArguments().getString("verificationTitle") == "Dark")
            selection("Dark",70523,"2017","tt5753856","de") else selection("Pantheon",195339,"2022","tt11680642","en")
        context.stopService(Intent(context,NativePlaybackService::class.java))
        instrumentation.runOnMainSync {
            File(context.noBackupFilesDir,"playback-routes/${com.aliflix.app.data.playbackProgressKey(selected).replace(':','_')}.json").delete()
            if (InstrumentationRegistry.getArguments().getString("coldProvider") == "true")
                context.getSharedPreferences("serienstream-verified-frames",0).edit().clear().commit()
        }
        ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java)
            .putExtra("selection",selected.nativeJson()).putExtra("autoSubtitles",false)).use { scenario ->
            var verification = false; var ready = false
            await { scenario.onActivity { verification = it.playbackUiState.verificationRequired; ready = it.playbackUiState.ready }; verification || ready }
            if (verification) scenario.onActivity { it.openProviderVerification() }
            await(300) { scenario.onActivity { ready = it.playbackUiState.ready && it.playbackUiState.stage == null }; ready }
            assertEquals(selected.media.originalLanguage,NativePlaybackService.activeRequest?.sourceAudioLanguage)
            assertNotNull(NativePlaybackService.renderedStreamUrl)
            assertTrue(NativePlaybackService.hasSelectedAudio)
            if (InstrumentationRegistry.getArguments().getString("automaticVerification") == "true")
                assertFalse("The site's automatic check should not need a visible verification window", verification)
            output.resolve("verification.txt").writeText("viewerVerificationToNativePlayback=passed\naudio=${selected.media.originalLanguage}\nvisibleCheckRequired=$verification\n")
        }
        context.stopService(Intent(context,NativePlaybackService::class.java))
    }

    @Test fun directCatalogueExposesTheSelectedEpisodeAndItsLanguageEditions() {
        enabled()
        val selected = selection("Dark",70523,"2017","tt5753856","de")
        val started = SystemClock.elapsedRealtime()
        val request = withProviderActivity { activity, host -> resolveInApp(activity, host, selected) }
        assertEquals("de",request.sourceAudioLanguage)
        output.resolve("direct-dark.txt").writeText("resolveMs=${SystemClock.elapsedRealtime() - started}\naudio=${request.sourceAudioLanguage}\n")
    }

    @Test fun originalAudioThreeTitlesAndLanguageSwitchesPreserveTheEpisodeAndPosition() {
        enabled()
        for (selected in listOf(selection("Dark",70523,"2017","tt5753856","de"),
            selection("Pantheon",195339,"2022","tt11680642","en"), selection("1899",90669,"2022","tt9319668","de"))) {
            context.stopService(Intent(context,NativePlaybackService::class.java))
            instrumentation.runOnMainSync {
                val app = context.applicationContext as com.aliflix.app.AliflixApplication
                app.playbackProgressStore.removeMedia(selected.media)
                context.getSharedPreferences("serienstream-audio",0).edit().remove(selected.media.key).commit()
                context.getSharedPreferences("native-resolver-performance",0).edit()
                    .remove("series:${selected.media.key}:provider").remove("series:${selected.media.key}:server").commit()
                File(context.noBackupFilesDir,"playback-routes/${com.aliflix.app.data.playbackProgressKey(selected).replace(':','_')}.json").delete()
            }
            val started = SystemClock.elapsedRealtime()
            ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java)
                .putExtra("selection",selected.nativeJson()).putExtra("autoSubtitles",false)).use { scenario ->
                var ready = false
                await { scenario.onActivity { ready = it.playbackUiState.ready && it.playbackUiState.stage == null }; ready }
                val elapsed = SystemClock.elapsedRealtime() - started
                var actual = checkNotNull(NativePlaybackService.activeRequest)
                assertEquals(MobilePlaybackProvider.SERIENSTREAM,nativeSelection(actual.selectionJson).source.identity)
                assertEquals(selected.media.originalLanguage,actual.sourceAudioLanguage)
                assertNotNull(NativePlaybackService.renderedStreamUrl)
                assertTrue(NativePlaybackService.hasSelectedAudio)
                output.resolve("${selected.media.title}-startup.txt").writeText("firstFrameMs=$elapsed\nlanguage=${actual.sourceAudioLanguage}\noptions=${actual.sourceAudioOptions}\n")
                scenario.onActivity { it.playbackController!!.seekTo(120_000) }
                await { var p = 0L; scenario.onActivity { p = it.playbackController!!.currentPosition }; p >= 120_000 }
                Thread.sleep(3000)
                scenario.onActivity { assertTrue(it.playbackController!!.isPlaying); assertNull(it.playbackController!!.playerError) }
                if (actual.sourceAudioChoices().size > 1) {
                    for (language in listOf("de","en")) {
                        if (language == actual.sourceAudioLanguage) continue
                        val before = actual.url
                        var position = 0L
                        scenario.onActivity { position = it.playbackController!!.currentPosition; it.selectSourceAudio(language) }
                        await { NativePlaybackService.activeRequest?.let { it.url != before && it.sourceAudioLanguage == language } == true && NativePlaybackService.playbackReady }
                        actual = checkNotNull(NativePlaybackService.activeRequest)
                        assertEquals(selected.media.id,nativeSelection(actual.selectionJson).media.id)
                        assertEquals(1,nativeSelection(actual.selectionJson).episodeNumber)
                        scenario.onActivity { assertTrue("Audio switches retain position",it.playbackController!!.currentPosition >= position - 1000) }
                    }
                }
                output.resolve("${selected.media.title}-checks.txt").writeText("decodedVideo=true\naudio=true\nseekPassed=true\nlanguageSwitchesPassed=true\n")
            }
        }
        context.stopService(Intent(context,NativePlaybackService::class.java))
    }

    @Test fun englishDownloadFromGermanEditionCompletesAndPlaysOffline() {
        enabled()
        val selected = selection("Pantheon",195339,"2022","tt11680642","en")
        var initialUrl = ""
        val download = withProviderActivity { activity, host ->
            val request = resolveInApp(activity, host, selected, "de")
            initialUrl = request.url
            val prepared = inspectDownload(selected,request,"")
            assertEquals(setOf("de","en"),prepared.audioTracks.map { it.language }.toSet())
            assertEquals("English",prepared.audioTracks.single { it.original }.label)
            val english = prepared.audioTracks.single { it.language == "en" }
            prepared.downloadRequest(prepared.qualities.first(),"",false,english.index) { gate ->
                SerienstreamBrowserSession.approve(activity, host, gate)
            }
        }
        val saved = NativePlaybackRequest.fromJson(org.json.JSONObject(String(download.data)).getString("playback"))
        assertEquals("en",saved.sourceAudioLanguage)
        assertNotEquals(initialUrl,download.uri.toString())
        lateinit var store: OfflineDownloads
        instrumentation.runOnMainSync { store = OfflineDownloads.get(context) }
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("openDownloads",true)).use {
            runBlocking { withContext(Dispatchers.Main) { store.enqueue(listOf(download)) } }
            await(300) { store.manager.downloadIndex.getDownload(download.id)?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }
        }
        val completed = checkNotNull(store.manager.downloadIndex.getDownload(download.id))
        assertTrue(completed.bytesDownloaded > 1_000_000)
        output.resolve("download.txt").writeText("state=completed\nbytes=${completed.bytesDownloaded}\naudio=${saved.sourceAudioLanguage}\n")
        // OfflineDownloads.offlineFactory has no upstream; every byte must come from the owned cache.
        val offline = saved.copy(playing = true, positionMs = 0)
        ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java).putExtra("request",offline.toJson())).use { scenario ->
            await { NativePlaybackService.playbackReady && NativePlaybackService.renderedStreamUrl != null }
            scenario.onActivity { assertEquals(listOf("en"), it.playbackUiState.sourceAudioOptions.map { option -> option.language }) }
            scenario.onActivity { it.playbackController!!.seekTo(90_000) }
            Thread.sleep(4000)
            scenario.onActivity { assertNull(it.playbackController!!.playerError); assertTrue(it.playbackController!!.currentPosition >= 90_000) }
            assertTrue(NativePlaybackService.hasSelectedAudio)
        }
        instrumentation.runOnMainSync { store.manager.removeDownload(download.id) }
        context.stopService(Intent(context,NativePlaybackService::class.java))
    }
}
