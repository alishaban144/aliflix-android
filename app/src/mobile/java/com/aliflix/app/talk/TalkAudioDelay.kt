package com.aliflix.app.talk

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack

/** Delay from the reverse reference being queued to the echo arriving at APM.
 * Hardware timestamps include the HAL queues that playbackHeadPosition misses. */
internal class TalkAudioDelay {
    private val renderStamp=AudioTimestamp()
    private val captureStamp=AudioTimestamp()
    fun milliseconds(track:AudioTrack,record:AudioRecord,written:Long,read:Long,blockSamples:Int):Int {
        val now=System.nanoTime()
        val measured=if(track.getTimestamp(renderStamp)&&now>=renderStamp.nanoTime&&now-renderStamp.nanoTime<1_000_000_000L)
            renderStamp.framePosition+(now-renderStamp.nanoTime)*24000/1_000_000_000L
        else -1L
        // A timestamp from before pause/flush can refer to the previous frame
        // epoch. It cannot describe frames beyond this epoch's submitted PCM.
        val presented=if(measured in 0..written)measured else (track.playbackHeadPosition.toLong() and 0xffffffffL).coerceAtMost(written)
        val captureAge=if(record.getTimestamp(captureStamp,AudioTimestamp.TIMEBASE_MONOTONIC)==AudioRecord.SUCCESS){
            val readAt=captureStamp.nanoTime+(read-captureStamp.framePosition)*1_000_000_000L/16000
            ((now-readAt)/1_000_000).coerceIn(0,250)
        }else blockSamples*1000L/16000
        return (((written-presented).coerceAtLeast(0)*1000/24000)+captureAge).coerceIn(0,500).toInt()
    }
}
