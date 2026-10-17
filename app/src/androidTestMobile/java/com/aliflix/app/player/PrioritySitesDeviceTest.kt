@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import com.aliflix.app.downloads.downloadRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.*

/** Opt-in network integration: no substituted source, cached stream URL or mocked decoder. */
class PrioritySitesDeviceTest {
    @Test fun darkDownloadsKeepSerienstreamAndExposeTheActualGermanEdition() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePriorityDownloads")=="true")
        grantNativeFixtureNetworkPermission()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val selection=PlaybackSelection(Media(70523,MediaType.TV,"Dark",year="2017",imdbId="tt5753856",originalLanguage="de"),1,1,
            source=PlaybackSource(MobilePlaybackProvider.SERIENSTREAM))
        context.stopService(Intent(context,NativePlaybackService::class.java))
        ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java)
            .putExtra("selection",selection.nativeJson()).putExtra("autoSubtitles",false),nativePhoneLaunchOptions()).use { scenario ->
            var owner:NativePlayerActivity?=null
            await("Dark native playback",90000) { var ready=false;scenario.onActivity { owner=it;it.playbackUiState.error?.let(::fail);ready=it.playbackUiState.ready };ready }
            val activity=requireNotNull(owner)
            runBlocking { withContext(Dispatchers.Main.immediate) {
                val host=android.widget.FrameLayout(activity).apply { alpha=0f }
                val parent=activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                parent.addView(host,0)
                try {
                    val source=withTimeout(65000) { com.aliflix.app.downloads.prepareDownload(activity,host,selection,"") }
                    assertEquals(MobilePlaybackProvider.SERIENSTREAM,source.selection.source.identity)
                    val quality=source.qualities.first()
                    val german=source.audioTracksFor(quality).first { it.language=="de" }
                    val download=source.downloadRequest(quality,"",false,german.index)
                    val saved=NativePlaybackRequest.fromJson(JSONObject(String(download.data,Charsets.UTF_8)).getString("playback"))
                    assertEquals("de",saved.sourceAudioLanguage)
                    File(context.getExternalFilesDir(null),"dark-download-receipt.json").writeText(JSONObject()
                        .put("provider",source.selection.source.identity.displayName).put("qualities",JSONArray(source.qualities.map { it.label }))
                        .put("audio",JSONArray(source.audioTracksFor(quality).map { it.language })).put("savedAudio",saved.sourceAudioLanguage)
                        .put("passed",true).toString())
                } finally { parent.removeView(host) }
            } }
        }
        context.stopService(Intent(context,NativePlaybackService::class.java))
    }
    @Test fun catalogueAudioAndNativePlaybackAcrossNineDifferentTitles() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("livePrioritySites")=="true")
        grantNativeFixtureNetworkPermission()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cases=listOf(
            Media(694,MediaType.MOVIE,"The Shining",year="1980",imdbId="tt0081505",originalLanguage="en"),
            Media(157336,MediaType.MOVIE,"Interstellar",year="2014",imdbId="tt0816692",originalLanguage="en"),
            Media(490132,MediaType.MOVIE,"Green Book",year="2018",imdbId="tt6966692",originalLanguage="en"),
            Media(109445,MediaType.MOVIE,"Frozen",year="2013",imdbId="tt2294629",originalLanguage="en"),
            Media(70523,MediaType.TV,"Dark",year="2017",imdbId="tt5753856",originalLanguage="de"),
            Media(1418,MediaType.TV,"The Big Bang Theory",year="2007",imdbId="tt0898266",originalLanguage="en"),
            Media(63351,MediaType.TV,"Narcos",year="2015",imdbId="tt2707408",originalLanguage="es"),
            Media(73223,MediaType.TV,"Black Clover",year="2017",imdbId="tt7441658",originalLanguage="ja",genres=listOf("Animation")),
            Media(13916,MediaType.TV,"Death Note",year="2006",imdbId="tt0877057",originalLanguage="ja",genres=listOf("Animation")),
            Media(30991,MediaType.TV,"Cowboy Bebop",year="1998",imdbId="tt0213338",originalLanguage="ja",genres=listOf("Animation")),
        ).filter { args.getString("titleId")==null || it.id.toString()==args.getString("titleId") }
        val receipts=JSONArray()
        for(media in cases) {
            val provider=PlaybackPreferences().sourceFor(media)
            val selection=PlaybackSelection(media,if(media.type==MediaType.TV) 1 else null,
                if(media.type==MediaType.TV) 1 else null,source=provider)
            context.stopService(Intent(context,NativePlaybackService::class.java))
            Thread.sleep(350)
            val started=SystemClock.elapsedRealtime()
            val receipt=JSONObject().put("title",media.title).put("provider",provider.identity.displayName)
            try {
                ActivityScenario.launch<NativePlayerActivity>(Intent(context,NativePlayerActivity::class.java)
                    .putExtra("selection",selection.nativeJson()).putExtra("autoSubtitles",true)
                    .putExtra("subtitleLanguage","en"),nativePhoneLaunchOptions()).use { scenario ->
                    await("${media.title}: native playback",150000) {
                        var ready=false
                        scenario.onActivity {
                            it.playbackUiState.error?.let { error -> fail(error) }
                            ready=it.playbackController?.isPlaying==true &&
                                it.playbackController!!.currentPosition>3000 && NativePlaybackService.speechDiagnostics().contains("frames=")
                        };ready
                    }
                    scenario.onActivity {
                        val request=requireNotNull(NativePlaybackService.activeRequest)
                        assertEquals("No cross-provider fallback",provider.identity,nativeSelection(request.selectionJson).source.identity)
                        assertTrue("Decoded video dimensions",it.playbackController!!.videoSize.width>0)
                        assertNull(it.playbackController!!.playerError)
                        receipt.put("startupMs",SystemClock.elapsedRealtime()-started)
                            .put("audio",NativePlaybackService.selectedAudioLanguage())
                            .put("audioEditions",JSONArray(it.playbackUiState.sourceAudioOptions.map { option->option.language }))
                            .put("captionsLoaded",NativePlaybackService.originalSubtitles()!=null)
                            .put("diagnostics",NativePlaybackService.speechDiagnostics())
                    }
                    args.getString("server")?.let { server ->
                        scenario.onActivity { it.selectServer(server) }
                        await("Selected website mirror",65000) { var ready=false;scenario.onActivity {
                            it.playbackUiState.error?.let(::fail)
                            ready=it.playbackUiState.ready && it.playbackUiState.server==server && it.playbackController?.isPlaying==true
                        };ready }
                        scenario.onActivity { assertEquals(provider.identity,nativeSelection(NativePlaybackService.activeRequest!!.selectionJson).source.identity) }
                        receipt.put("selectedServer",server)
                    }
                    if(media.id==694 && args.getString("requireOriginalAudio")=="true") {
                        await("Verified original English alternative",65000){var found=false;scenario.onActivity {found=it.playbackUiState.sourceAudioOptions.any{o->o.language=="en"}};found}
                        scenario.onActivity{it.selectSourceAudio("en")}
                        await("English native source ready",30000){var ready=false;scenario.onActivity{ready=it.playbackUiState.pendingSourceAudioLanguage==null&&it.playbackUiState.sourceAudioLanguage=="en"&&it.playbackController?.isPlaying==true};ready}
                        scenario.onActivity{assertNull(it.playbackController!!.playerError);receipt.put("englishAlternative",it.playbackUiState.playbackSelection?.source?.identity?.displayName).put("englishVerified",true)}
                    }
                    if(media.id==157336) {
                        var nativeGerman=false
                        scenario.onActivity { activity ->
                            val german=activity.playbackController!!.currentTracks.groups.filter { it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO }
                                .flatMap { group -> (0 until group.length).map { group to it } }
                                .firstOrNull { (group,index)->group.getTrackFormat(index).language in setOf("de","deu","ger") }
                            if(german!=null) {
                                nativeGerman=true;NativePlaybackService.selectAudio(german.first.mediaTrackGroup,german.second)
                            } else activity.selectSourceAudio("de")
                        }
                        await("German edition is confirmed",65000) {
                            var done=false;scenario.onActivity {
                                done=it.playbackUiState.pendingSourceAudioLanguage==null &&
                                    (if(nativeGerman) NativePlaybackService.selectedAudioLanguage() in setOf("de","deu","ger") && NativePlaybackService.audioSelectionState?.value?.pending==null
                                     else NativePlaybackService.activeRequest?.sourceAudioLanguage=="de") && it.playbackController?.isPlaying==true
                            };done
                        }
                        scenario.onActivity { assertEquals("de",NativePlaybackService.selectedAudioLanguage());receipt.put("germanSelectionConfirmed",true) }
                    }
                }
                receipt.put("passed",true)
            } catch(error:Throwable) {
                receipt.put("passed",false).put("error",error.message)
                throw error
            } finally {
                receipts.put(receipt)
                File(context.getExternalFilesDir(null),"priority-sites-receipts.json").writeText(receipts.toString())
                context.stopService(Intent(context,NativePlaybackService::class.java))
            }
        }
    }
    private fun await(label:String,timeout:Long,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){ if(condition())return;Thread.sleep(100) }
        fail("$label; ${NativePlaybackService.speechDiagnostics()}")
    }
}
