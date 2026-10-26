package com.aliflix.app.talk

import android.content.Context
import com.aliflix.app.player.NeuralSpeechWorker

/** Short sustained neural speech, after the platform AEC, can pause local
 * playback before a network round trip. Never gates or modifies sent audio. */
internal class TalkBargeInGate {
    private var started=-1L
    private var fired=false
    fun accept(probability:Float,atMillis:Long,assistantActive:Boolean,echoControlled:Boolean):Boolean {
        if(!assistantActive||!echoControlled||probability<.85f){started=-1;fired=false;return false}
        if(started<0)started=atMillis
        if(fired||atMillis-started<96)return false
        fired=true;return true
    }
    fun reset(){started=-1;fired=false}
}

/** The existing bundled Silero runs on its bounded background worker. Input
 * is reduced to 8kHz in 20ms frames only for local detection; the original
 * 16kHz stream is still sent to Live unchanged. No PCM is persisted. */
internal class TalkSpeechMonitor(context:Context,onProbability:(Double,Float)->Unit):AutoCloseable {
    private val worker=NeuralSpeechWorker(context){at,p,_,_->onProbability(at,p)}
    private val frame=ShortArray(160)
    private var frameCount=0
    private var firstSample:Int?=null
    private var samples=0L
    @Synchronized fun offer(bytes:ByteArray,generation:Long) {
        if(!worker.ready){frameCount=0;firstSample=null;samples=0;return}
        for(i in 0 until bytes.size-1 step 2) {
            val value=((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort().toInt()
            val first=firstSample
            if(first==null){firstSample=value;continue}
            firstSample=null;frame[frameCount++]=((first+value)/2).toShort()
            if(frameCount==frame.size){worker.offer(frame,samples/8000.0,generation,0);samples+=160;frameCount=0}
        }
    }
    override fun close()=worker.close()
}
