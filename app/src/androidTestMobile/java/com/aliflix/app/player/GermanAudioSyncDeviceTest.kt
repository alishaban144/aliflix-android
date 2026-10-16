@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.json.JSONObject
import java.io.File

/** Opt-in: real German episode audio and the normally selected English/Arabic caption edition. */
class GermanAudioSyncDeviceTest {
    private val saved = mutableListOf<Pair<android.content.SharedPreferences,Map<String,*>>>()
    @Before fun preservePreferences() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        for(name in listOf(com.aliflix.app.data.PlaybackProviderRepository.PREFS_NAME,"serienstream-audio","native-subtitle-choice")) {
            val prefs=context.getSharedPreferences(name,0);saved.add(prefs to prefs.all.toMap())
        }
    }
    @After fun restorePreferences() {
        BufferedDialogueAnalysis.debugObserveSpeech(null)
        for((prefs,values) in saved) {
            val editor=prefs.edit().clear()
            for((key,value) in values) when(value) {
                is String -> editor.putString(key,value)
                is Boolean -> editor.putBoolean(key,value)
                is Int -> editor.putInt(key,value)
                is Long -> editor.putLong(key,value)
                is Float -> editor.putFloat(key,value)
            }
            editor.commit()
        }
    }
    @Test fun episodeAudioEditionsKeepTheLatestChoicePositionAndCaptions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveGermanSync") == "true")
        grantNativeFixtureNetworkPermission()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val selection=PlaybackSelection(Media(1418,MediaType.TV,"The Big Bang Theory",year="2007",
            imdbId="tt0898266",originalLanguage="en"),1,1,"Pilot",source=PlaybackSource(MobilePlaybackProvider.SERIENSTREAM))
        context.stopService(Intent(context,NativePlaybackService::class.java))
        context.getSharedPreferences("serienstream-audio",0).edit().putString(selection.media.key,"de").commit()
        context.getSharedPreferences("native-subtitle-choice",0).edit().clear().putString("language","en").putBoolean("enabled",true).commit()
        ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java)
            .putExtra("selection",selection.nativeJson()).putExtra("autoSubtitles",true)
            .putExtra("subtitleLanguage","en"),nativePhoneLaunchOptions()).use { scenario ->
            await("German episode with two editions",150000) { var ready=false;scenario.onActivity {
                it.playbackUiState.error?.let(::fail)
                ready=it.playbackController?.isPlaying==true && NativePlaybackService.activeRequest?.sourceAudioLanguage=="de" &&
                    it.playbackUiState.sourceAudioOptions.any { option -> option.language=="en" } && NativePlaybackService.originalSubtitles()!=null
            };ready }
            scenario.onActivity { it.playbackController!!.pause();it.playbackController!!.seekTo(125000) }
            await("Paused position",10000) { var ready=false;scenario.onActivity { ready=it.playbackController!!.currentPosition>=124900 && it.playbackController!!.playbackState==Player.STATE_READY };ready }
            val captions=NativePlaybackService.originalSubtitles()
            scenario.onActivity {
                it.selectSourceAudio("en");assertEquals("en",it.playbackUiState.pendingSourceAudioLanguage)
                it.selectSourceAudio("de");it.selectSourceAudio("en")
            }
            await("Latest English edition",65000) { var ready=false;scenario.onActivity {
                ready=it.playbackUiState.pendingSourceAudioLanguage==null && it.playbackUiState.sourceAudioLanguage=="en" &&
                    NativePlaybackService.activeRequest?.sourceAudioLanguage=="en" && it.playbackController!!.playbackState==Player.STATE_READY
            };ready }
            scenario.onActivity {
                assertEquals("en",NativePlaybackService.selectedAudioLanguage())
                assertFalse(it.playbackController!!.playWhenReady)
                assertEquals(125000L,it.playbackController!!.currentPosition)
                assertEquals(captions,NativePlaybackService.originalSubtitles())
                it.selectSourceAudio("de")
            }
            await("German edition restored",65000) { var ready=false;scenario.onActivity {
                ready=it.playbackUiState.pendingSourceAudioLanguage==null && it.playbackUiState.sourceAudioLanguage=="de" &&
                    NativePlaybackService.activeRequest?.sourceAudioLanguage=="de" && it.playbackController!!.playbackState==Player.STATE_READY
            };ready }
            scenario.onActivity {
                assertFalse(it.playbackController!!.playWhenReady)
                assertEquals(125000L,it.playbackController!!.currentPosition)
                assertEquals(captions,NativePlaybackService.originalSubtitles())
            }
        }
        context.stopService(Intent(context,NativePlaybackService::class.java))
    }
    @Test fun germanEpisodeSynchronizesTranslatedCaptionsWithoutMovingPlayback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveGermanSync") == "true")
        grantNativeFixtureNetworkPermission()
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val language = InstrumentationRegistry.getArguments().getString("captionLanguage") ?: "en"
        val episode = InstrumentationRegistry.getArguments().getString("episode")?.toIntOrNull() ?: 1
        val playedSeconds=InstrumentationRegistry.getArguments().getString("playedSeconds")?.toIntOrNull()?.coerceIn(8,60) ?: 8
        val selection = PlaybackSelection(Media(1418, MediaType.TV, "The Big Bang Theory", year = "2007",
            imdbId = "tt0898266", originalLanguage = "en"), 1, episode, if (episode == 1) "Pilot" else "The Big Bran Hypothesis",
            source = PlaybackSource(MobilePlaybackProvider.SERIENSTREAM))
        context.stopService(Intent(context, NativePlaybackService::class.java))
        context.getSharedPreferences(com.aliflix.app.data.PlaybackProviderRepository.PREFS_NAME, 0).edit()
            .putString(com.aliflix.app.data.PlaybackProviderRepository.KEY_PREFERRED_SUBTITLE_LANGUAGE, language.uppercase())
            .putBoolean(com.aliflix.app.data.PlaybackProviderRepository.KEY_AUTO_DISPLAY_SUBTITLES, true).commit()
        context.getSharedPreferences("serienstream-audio", 0).edit().putString(selection.media.key, "de").commit()
        context.getSharedPreferences("native-subtitle-choice", 0).edit().clear().putString("language", language).putBoolean("enabled", true).commit()
        val output = File(context.getExternalFilesDir(null), "german-sync-$language").apply { mkdirs() }
        val observed = java.util.concurrent.atomic.AtomicReference<List<SpeechWindow>>(emptyList())
        if (InstrumentationRegistry.getArguments().getString("captureSyncEvidence") == "true")
            BufferedDialogueAnalysis.debugObserveSpeech { observed.set(it) }
        ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
            .putExtra("selection", selection.nativeJson()).putExtra("autoSubtitles", true)
            .putExtra("subtitleLanguage", language), nativePhoneLaunchOptions()).use { scenario ->
            await("German episode and translated captions", 150000) {
                var ready = false
                scenario.onActivity {
                    it.playbackUiState.error?.let { error -> fail(error) }
                    ready = it.playbackUiState.audioSyncAvailable && NativePlaybackService.activeRequest?.sourceAudioLanguage == "de" &&
                        it.playbackController?.isPlaying == true && NativePlaybackService.originalSubtitles() != null
                }
                ready
            }
            scenario.onActivity { assertEquals("de", NativePlaybackService.selectedAudioLanguage()); it.playbackController!!.seekTo(125000) }
            await("German dialogue", playedSeconds*1000L+45000) { var ready = false; scenario.onActivity { ready = it.playbackController!!.currentPosition >= 125000+playedSeconds*1000L }; ready }
            val original = requireNotNull(NativePlaybackService.originalSubtitles())
            File(output,"original-cues.json").writeText(original)
            if (InstrumentationRegistry.getArguments().getString("collectReferences") == "true") {
                kotlinx.coroutines.runBlocking {
                    val repo = SubdlSubtitleRepository()
                    val tracks = mobileSubtitleCandidates(normalizeMobileSubtitleTracks(repo.search(selection,"de").getOrDefault(emptyList())), "de", 1, 1, selection.media.title)
                    for ((index, track) in tracks.take(6).withIndex()) {
                        val cues = repo.download(track,selection).getOrDefault(emptyList())
                        File(output,"reference-$index.json").writeText(subtitleCuesJson(cues))
                    }
                }
            }
            var began = 0L; var position = 0L; var discontinuities = 0
            val listener = object : Player.Listener {
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { discontinuities++ }
            }
            scenario.onActivity {
                it.playbackController!!.pause()
            }
            Thread.sleep(300)
            scenario.onActivity {
                position = it.playbackController!!.currentPosition
                it.playbackController!!.addListener(listener)
                it.resetSubtitleSync(); began = SystemClock.elapsedRealtime(); it.syncWithAudio()
            }
            await("Bounded German sync", 20000) { var done = false; scenario.onActivity { done = it.playbackUiState.audioSyncState != "Syncing…" }; done }
            scenario.onActivity {
                val request = requireNotNull(NativePlaybackService.activeRequest)
                val correction = SubtitleCorrectionStore(context).get(subtitleCorrectionKey(request, selection.key, original, NativePlaybackService.selectedAudioFingerprint()))
                val receipt = JSONObject().put("title", selection.media.title).put("season", 1).put("episode", episode)
                    .put("audio", NativePlaybackService.selectedAudioLanguage()).put("captions", language)
                    .put("state", it.playbackUiState.audioSyncState).put("elapsedMs", SystemClock.elapsedRealtime() - began)
                    .put("offset", correction?.offset).put("rate", correction?.rate).put("model", correction?.model)
                    .put("confidence", correction?.confidence).put("videoFps",NativePlaybackService.selectedVideoFrameRate())
                    .put("references", it.preparedSyncReferences.size)
                    .put("positionDeltaMs", it.playbackController!!.currentPosition-position).put("discontinuities", discontinuities)
                    .put("diagnostics", NativePlaybackService.speechDiagnostics())
                File(output, "receipt.json").writeText(receipt.toString())
                File(output,"observed-speech.json").writeText(org.json.JSONArray().apply {
                    observed.get().forEach { window -> put(JSONObject().put("start",window.start).put("bits",window.speech.joinToString("") { bit -> if(bit>=.5) "1" else "0" })) }
                }.toString())
                assertEquals(receipt.toString(), "Synced", it.playbackUiState.audioSyncState)
                assertNotNull(correction)
                assertEquals(original, NativePlaybackService.originalSubtitles())
                assertEquals(position, it.playbackController!!.currentPosition)
                assertEquals(0, discontinuities)
                assertFalse(it.playbackController!!.playWhenReady)
                it.resetSubtitleSync(); assertFalse(it.playbackUiState.audioSyncApplied)
                it.playbackController!!.removeListener(listener)
            }
        }
        context.stopService(Intent(context, NativePlaybackService::class.java))
    }
    private fun await(label: String, timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(100) }
        fail("$label; ${NativePlaybackService.speechDiagnostics()}")
    }
}
