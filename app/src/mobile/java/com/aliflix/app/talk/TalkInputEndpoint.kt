package com.aliflix.app.talk

/** A bounded end hint for short utterances that remote automatic VAD can leave
 * open. Audio always streams unchanged; this never gates words or starts turns. */
internal class TalkInputEndpoint {
    private var voicedMs=0
    private var lastVoice=0L
    private var floor=.002
    fun accept(level:Double,frameMs:Int,now:Long,assistantSpeaking:Boolean):Boolean {
        if(assistantSpeaking){reset();return false}
        val voiced=level>maxOf(.009,floor*3)
        if(voiced){voicedMs+=frameMs;lastVoice=now}
        else if(voicedMs==0)floor=floor*.98+level.coerceAtMost(.02)*.02
        if(lastVoice>0&&now-lastVoice>=1000){
            val ended=voicedMs>=80;reset();return ended
        }
        return false
    }
    fun reset(){voicedMs=0;lastVoice=0}
}
