@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.aliflix.app.model.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Hardware decoder proof: each independently encoded AAC track has a different measured frequency. */
class NativeAudioSelectionDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun languageRowChangesActuallyDecodedAudioAndRapidChoicesKeepTheLastTrack() {
        grantNativeFixtureNetworkPermission()
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        context.stopService(Intent(context, NativePlaybackService::class.java))
        val frames = CopyOnWriteArrayList<ShortArray>()
        NativeBackgroundPlaybackTest.FixtureServer(instrument.context.assets.open("audio-language-fixture.mp4").use { it.readBytes() }).use { server ->
            val selection = PlaybackSelection(Media(2147482912, MediaType.MOVIE, "Audio selection validation"), source=PlaybackSource(PlaybackProviderId.RAMOFLIX))
            val request = NativePlaybackRequest(server.url,"video/mp4","https://fixture.aliflix.test/","Aliflix","",selection.media.title,0,true,selectionJson=selection.nativeJson())
            val file = File(context.cacheDir,"native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
            try {
                ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java).putExtra("requestFile",file.name),nativePhoneLaunchOptions()).use { scenario ->
                    await("Audio ready") { var ready=false;scenario.onActivity { ready=it.playbackController?.isPlaying==true };ready }
                    NativePlaybackService.debugObserveSpeech { _, pcm -> frames.add(pcm) }
                    compose.onNodeWithContentDescription("More").performClick()
                    compose.onNodeWithText("Audio & subtitles").performClick()
                    compose.onNodeWithText("German",substring=true).performClick()
                    await("German PCM") { frequency(frames.takeLast(25)) in 820.0..940.0 && NativePlaybackService.audioSelectionState?.value?.pending == null }
                    var position=0L
                    scenario.onActivity { position=it.playbackController!!.currentPosition;assertEquals("de",NativePlaybackService.selectedAudioLanguage()) }
                    compose.onNodeWithText("English",substring=true).performClick()
                    compose.onNodeWithText("German",substring=true).performClick()
                    compose.onNodeWithText("English",substring=true).performClick()
                    frames.clear()
                    await("Latest English PCM") { frequency(frames.takeLast(25)) in 400.0..480.0 && NativePlaybackService.audioSelectionState?.value?.pending == null }
                    scenario.onActivity { assertEquals("en",NativePlaybackService.selectedAudioLanguage());assertTrue(it.playbackController!!.currentPosition>=position);assertNull(it.playbackController!!.playerError) }
                    compose.onNodeWithText("English",substring=true).assertIsSelected()
                    compose.onNodeWithText("German",substring=true).assertIsNotSelected()
                    val image=instrument.uiAutomation.takeScreenshot()
                    File(context.getExternalFilesDir(null),"audio-selection-native.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };image.recycle()
                }
            } finally { NativePlaybackService.debugObserveSpeech(null);context.stopService(Intent(context,NativePlaybackService::class.java));file.delete() }
        }
    }
    private fun frequency(frames:List<ShortArray>):Double {
        if(frames.size<20)return 0.0
        val samples=frames.flatMap { it.toList() };val crossings=samples.zipWithNext().count { (a,b)->a<=0&&b>0 }
        return crossings*8000.0/samples.size
    }
    private fun await(label:String,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+18000
        while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(100)}
        fail("$label; ${NativePlaybackService.speechDiagnostics()}")
    }
}
