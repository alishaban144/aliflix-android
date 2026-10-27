package com.aliflix.app.talk

/** The sole microphone turn owner. Only confirmed near-end speech opens an
 * activity; transcripts and model/tool events never create microphone turns.
 * Each decision receives the exact 32 ms frame that Silero evaluated. */
internal class TalkInputTurnGate {
    data class Decision(val start:Boolean=false,val end:Boolean=false,val frames:List<ByteArray> = emptyList())
    private val prefix=ArrayDeque<ByteArray>()
    private var prefixBytes=0
    private var voicedMs=0
    private var quietMs=0
    var active=false; private set
    @Synchronized fun accept(pcm:ByteArray,probability:Float,echo:Boolean):Decision {
        val ms=pcm.size/32
        var sum=0.0
        for(at in pcm.indices step 2){val value=((pcm[at].toInt() and 255) or (pcm[at+1].toInt() shl 8)).toShort().toDouble();sum+=value*value}
        val level=kotlin.math.sqrt(sum/(pcm.size/2).coerceAtLeast(1))/32768.0
        val near=probability>=(if(active).35f else .65f)&&level>.0015
        if(!active) {
            prefix.addLast(pcm);prefixBytes+=pcm.size
            while(prefixBytes>25_600&&prefix.isNotEmpty())prefixBytes-=prefix.removeFirst().size
            voicedMs=if(near&&!echo)voicedMs+ms else 0
            if(voicedMs<64)return Decision()
            active=true;quietMs=0;voicedMs=0
            return Decision(start=true,frames=prefix.toList()).also{prefix.clear();prefixBytes=0}
        }
        quietMs=if(near&&!echo)0 else quietMs+ms
        if(quietMs>=704){active=false;quietMs=0;return Decision(end=true,frames=listOf(pcm))}
        return Decision(frames=listOf(pcm))
    }
    @Synchronized fun finish():Boolean {val was=active;reset();return was}
    @Synchronized fun reset(){active=false;voicedMs=0;quietMs=0;prefix.clear();prefixBytes=0}
}
