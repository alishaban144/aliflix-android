package com.aliflix.app.talk

/** One local full-duplex AEC3 stream. JNI accepts exactly 10 ms, mono PCM16.
 * Capture keeps its real clock even while muted; generation fencing happens
 * after processing so no sample spanning a privacy boundary is transmitted. */
internal class TalkEchoProcessor private constructor(private var handle:Long):AutoCloseable {
    private val render=ShortArray(240)
    private val capture=ShortArray(160)
    private var renderCount=0
    private var captureCount=0
    private var generation=Long.MIN_VALUE
    private var tainted=true

    @Synchronized fun render(bytes:ByteArray,offset:Int,count:Int) {
        if(handle==0L)return
        require(offset>=0&&count>=0&&offset+count<=bytes.size&&count%2==0)
        var at=offset
        while(at<offset+count){
            render[renderCount++]=((bytes[at].toInt()and 255)or(bytes[at+1].toInt()shl 8)).toShort();at+=2
            if(renderCount==240){check(TalkApmNative.render(handle,render));renderCount=0}
        }
    }
    @Synchronized fun flushRenderTail(){
        if(handle!=0L&&renderCount>0){java.util.Arrays.fill(render,renderCount,render.size,0.toShort());check(TalkApmNative.render(handle,render));renderCount=0}
    }
    @Synchronized fun discardRenderTail(){renderCount=0;render.fill(0)}

    @Synchronized fun capture(samples:ShortArray,count:Int,delayMs:Int,ticket:Long):ByteArray {
        if(handle==0L)return ByteArray(0)
        require(count in 0..samples.size)
        if(generation!=ticket){generation=ticket;tainted=true}
        val out=ByteArray((captureCount+count)/160*320)
        var written=0
        for(i in 0 until count){
            capture[captureCount++]=samples[i]
            if(captureCount==160){
                check(TalkApmNative.capture(handle,capture,delayMs))
                if(!tainted)for(sample in capture){out[written++]=sample.toByte();out[written++]=(sample.toInt()shr 8).toByte()}
                captureCount=0;tainted=false
            }
        }
        return if(written==out.size)out else out.copyOf(written)
    }

    @Synchronized fun reset(){if(handle!=0L){check(TalkApmNative.reset(handle));renderCount=0;captureCount=0;generation=Long.MIN_VALUE;tainted=true;render.fill(0);capture.fill(0)}}
    @Synchronized fun statistics():DoubleArray=if(handle==0L)DoubleArray(0)else TalkApmNative.statistics(handle)
    @Synchronized override fun close(){if(handle!=0L){TalkApmNative.destroy(handle);handle=0;render.fill(0);capture.fill(0)}}
    companion object {
        fun create():TalkEchoProcessor?=runCatching{TalkApmNative.create().takeIf{it!=0L}?.let(::TalkEchoProcessor)}.getOrNull()
    }
}

internal object TalkApmNative {
    init {System.loadLibrary("aliflix_apm")}
    external fun create():Long
    external fun destroy(handle:Long)
    external fun reset(handle:Long):Boolean
    external fun render(handle:Long,pcm:ShortArray):Boolean
    external fun capture(handle:Long,pcm:ShortArray,delayMs:Int):Boolean
    external fun statistics(handle:Long):DoubleArray
}
