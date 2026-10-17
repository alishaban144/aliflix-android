@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.*

/** Track inspection only: no video decoder, playback, audio focus or PCM upload. */
internal suspend fun hasVerifiedAudioLanguage(context:Context,request:NativePlaybackRequest,language:String):Boolean = withContext(Dispatchers.Main.immediate) {
    withTimeout(9000) {
        val player=ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(StartupStreamCache.mediaFactory(context,request))).build()
        try {
            player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO,true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).setPreferredAudioLanguage(language).build()
            player.setMediaItem(MediaItem.Builder().setUri(request.url).setMimeType(request.mimeType).build());player.prepare()
            while(player.currentTracks.groups.none{it.type==C.TRACK_TYPE_AUDIO}){player.playerError?.let{throw it};delay(30)}
            if(com.aliflix.app.BuildConfig.DEBUG)android.util.Log.i("AliflixOriginalAudio", "Tracks: "+player.currentTracks.groups.filter{it.type==C.TRACK_TYPE_AUDIO}.flatMap{group->(0 until group.length).map{group.getTrackFormat(it).language}}.joinToString())
            player.currentTracks.groups.filter{it.type==C.TRACK_TYPE_AUDIO}.any{group->(0 until group.length).any { index->
                group.getTrackFormat(index).language?.substringBefore('-')?.equals(language,true)==true
            }}
        }finally{player.release()}
    }
}
