package com.aliflix.app.talk

import android.content.Context
import com.aliflix.app.player.SileroSpeechSession

/** Exact 32 ms speech probabilities on the capture dispatcher, independent of
 * playback and without an asynchronous stale-probability queue. */
internal class TalkSpeechMonitor(context:Context,private val onFrame:(ByteArray,Float)->Unit):AutoCloseable {
    // Inference runs on the independent capture dispatcher, never the renderer.
    // No asynchronous probability can be applied to a different microphone frame.
    private val detector=SileroSpeechSession(context.assets.open("silero-vad-8k.onnx").use{it.readBytes()})
    private val frame=ByteArray(1024)
    private var count=0
    private var closed=false
    @Synchronized fun offer(bytes:ByteArray,generation:Long) {
        if(closed)return
        for(value in bytes) {
            frame[count++]=value
            if(count==frame.size){
                val samples=FloatArray(256){index->
                    fun sample(at:Int)=((frame[at].toInt() and 255) or (frame[at+1].toInt() shl 8)).toShort().toInt()
                    (sample(index*4)+sample(index*4+2))/65536f
                }
                onFrame(frame.copyOf(),detector.probability(samples));count=0
            }
        }
    }
    @Synchronized override fun close(){if(!closed){closed=true;detector.close();frame.fill(0);count=0}}
}
