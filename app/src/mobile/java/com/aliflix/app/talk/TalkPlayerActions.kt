package com.aliflix.app.talk

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aliflix.app.player.NativePlaybackService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The existing Media3 session owns playback. Talk issues the same commands as
 * native controls and reads real state; it never handles provider URLs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun talkPlayerAction(context:Context,args:JSONObject):JSONObject=withContext(Dispatchers.Main.immediate) {
    withTimeout(5000){
        if(NativePlaybackService.activeRequestId==null)return@withTimeout JSONObject().put("status","no_active_playback").put("instruction","No title is currently playing. Ask which title to play or open its native details.")
        val future=MediaController.Builder(context,SessionToken(context,ComponentName(context,NativePlaybackService::class.java))).buildAsync()
        val controller=suspendCancellableCoroutine<MediaController>{c->
            c.invokeOnCancellation{MediaController.releaseFuture(future)}
            future.addListener({try{val player=future.get();if(c.isActive)c.resume(player)else player.release()}catch(e:Exception){if(c.isActive)c.resumeWithException(e)}},Executor{it.run()})
        }
        try {
            if(controller.currentMediaItem==null)return@withTimeout JSONObject().put("status","no_active_playback").put("instruction","No title is playing. Ask which title to play, or open its native details.")
            val action=args.getString("action")
            when(action){
                "pause","resume"->{require(controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE));if(action=="pause")controller.pause()else controller.play();delay(120)}
                "seek"->{require(controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM));val seconds=args.getDouble("numberValue");require(seconds.isFinite()&&seconds>=0);controller.seekTo((seconds*1000).toLong().coerceAtMost(controller.duration.takeIf{it>0}?:Long.MAX_VALUE));delay(120)}
                "audio_language","subtitle_language"->{
                    require(controller.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS))
                    val language=args.getString("stringValue");require(language=="off"||language.matches(Regex("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")))
                    val parameters=controller.trackSelectionParameters.buildUpon()
                    if(action=="audio_language")parameters.setPreferredAudioLanguage(language)
                    else{parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,language=="off");if(language!="off")parameters.setPreferredTextLanguage(language)}
                    controller.trackSelectionParameters=parameters.build()
                }
                "player_state"->Unit
                else->error("Unknown playback action")
            }
            val tracks=controller.currentTracks.groups.flatMap{group->(0 until group.length).mapNotNull{index->group.getTrackFormat(index).language?.let{language->JSONObject().put("kind",if(group.type==C.TRACK_TYPE_AUDIO)"audio" else if(group.type==C.TRACK_TYPE_TEXT)"subtitle" else "video").put("language",language).put("selected",group.isTrackSelected(index))}}}
            JSONObject().put("status",if(action=="player_state")"current" else "command_sent").put("action",action)
                .put("playing",controller.isPlaying).put("playWhenReady",controller.playWhenReady).put("positionSeconds",controller.currentPosition/1000.0)
                .put("durationSeconds",controller.duration.takeIf{it>0}?.div(1000.0)).put("tracks",JSONArray(tracks))
                .put("instruction","Describe the actual returned player state. Language selection is a preference among the available source tracks; never claim that a missing track exists.")
        }finally{controller.release()}
    }
}
