package com.aliflix.app.player

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import kotlinx.coroutines.*

/** Opt-in physical playback proof for the reported identities, including short-form content. */
class ReliabilityAcceptanceDeviceTest {
    @Test fun auditEveryProviderWithoutAutomaticSubstitution() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("providerAudit")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val receipt=JSONArray()
        ActivityScenario.launch<androidx.activity.ComponentActivity>(Intent(context,androidx.activity.ComponentActivity::class.java)).use { scenario ->
            lateinit var activity:androidx.activity.ComponentActivity
            lateinit var host:android.widget.FrameLayout
            scenario.onActivity { activity=it;host=android.widget.FrameLayout(it);it.setContentView(host) }
            for(provider in MobilePlaybackProvider.entries+PlaybackProviderId.entries) {
                val media=when {
                    provider.isAnimeNative -> Media(73223,MediaType.TV,"Black Clover",year="2017",originalLanguage="ja",genres=listOf("Animation"))
                    provider==MobilePlaybackProvider.SERIENSTREAM -> Media(70523,MediaType.TV,"Dark",year="2017",imdbId="tt5753856",originalLanguage="de")
                    else -> Media(180,MediaType.MOVIE,"Minority Report",year="2002",imdbId="tt0181689",originalLanguage="en")
                }
                val selection=PlaybackSelection(media,if(media.type==MediaType.TV)1 else null,if(media.type==MediaType.TV)1 else null,source=PlaybackSource(provider))
                val began=SystemClock.elapsedRealtime();val item=JSONObject().put("provider",provider.name)
                runBlocking { withContext(Dispatchers.Main) {
                    val adapter=NativeStreamResolver(activity,com.aliflix.app.data.PlaybackProgressStore(activity),host)
                    try {
                        withTimeout(22_000) { adapter.resolve(selection,0,emptySet(),allowVerification=false) { item.put("server",it) } }
                        item.put("playableNativeVideo",true)
                    } catch(error:Exception) {
                        item.put("playableNativeVideo",false).put("failure",error.javaClass.simpleName)
                    } finally { adapter.close() }
                } }
                item.put("elapsedMs",SystemClock.elapsedRealtime()-began);receipt.put(item)
                File(context.getExternalFilesDir(null),"providers-177.json").writeText(receipt.toString(2))
            }
        }
        assertEquals(12,receipt.length())
    }
    @Test fun requestedTitlesPlayAndSeek() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveReliability")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val output=File(context.getExternalFilesDir(null),"reliability-177").apply { mkdirs() }
        val cases=listOf(
            PlaybackSelection(Media(180,MediaType.MOVIE,"Minority Report",year="2002",imdbId="tt0181689",originalLanguage="en"),source=PlaybackSource(MobilePlaybackProvider.FILMO)),
            PlaybackSelection(Media(67415,MediaType.MOVIE,"Incoherence",year="2025",imdbId="tt1997399",originalLanguage="ko"),source=PlaybackSource(MobilePlaybackProvider.FILMO)),
            PlaybackSelection(Media(86328,MediaType.TV,"Don't Hug Me I'm Scared",year="2011",originalLanguage="en"),1,1,"Creativity",source=PlaybackSource(MobilePlaybackProvider.SERIENSTREAM)),
        )
        val receipts=JSONArray();val failures=mutableListOf<String>()
        for((index,selection) in cases.withIndex()) {
            context.stopService(Intent(context,NativePlaybackService::class.java))
            val began=SystemClock.elapsedRealtime()
            val receipt=JSONObject().put("title",selection.media.title).put("key",selection.key)
            ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java)
                .putExtra("selection",selection.nativeJson()).putExtra("autoSubtitles",false).putExtra("playTapElapsedMs",began),nativePhoneLaunchOptions()).use { scenario ->
                var ready=false;var failed=false;var snapshot=NativePlayerUi()
                val deadline=began+65_000
                while(!ready&&!failed&&SystemClock.elapsedRealtime()<deadline) {
                    scenario.onActivity { activity ->
                        snapshot=activity.playbackUiState
                        ready=snapshot.ready&&snapshot.stage==null&&activity.playbackController?.isPlaying==true&&NativePlaybackService.renderedStreamUrl==NativePlaybackService.activeStreamUrl
                        failed=snapshot.error!=null
                    }
                    Thread.sleep(100)
                }
                receipt.put("playToFrameMs",SystemClock.elapsedRealtime()-began).put("ready",ready).put("server",snapshot.server).put("error",snapshot.error)
                if(ready) {
                    var position=0L
                    scenario.onActivity { activity ->
                        val player=requireNotNull(activity.playbackController)
                        val target=minOf(60_000L,(player.duration/3).coerceAtLeast(0))
                        player.seekTo(target);position=target
                        receipt.put("provider",nativeSelection(requireNotNull(NativePlaybackService.activeRequest).selectionJson).source.identity.name)
                    }
                    var advanced=false;val seekDeadline=SystemClock.elapsedRealtime()+15_000
                    while(!advanced&&SystemClock.elapsedRealtime()<seekDeadline) {
                        scenario.onActivity { activity ->advanced=activity.playbackController?.let { it.isPlaying&&it.currentPosition>position+800&&it.playerError==null }==true }
                        Thread.sleep(200)
                    }
                    receipt.put("seekAndPlayback",advanced)
                    if(!advanced)failures+="${selection.media.title}: seek did not resume"
                } else failures+="${selection.media.title}: ${snapshot.error?:"no first frame within 65 seconds"}"
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->File(output,"case-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()}
            }
            receipts.put(receipt);File(output,"playback.json").writeText(receipts.toString(2))
        }
        context.stopService(Intent(context,NativePlaybackService::class.java))
        assertTrue(failures.joinToString("; "),failures.isEmpty())
    }
}
