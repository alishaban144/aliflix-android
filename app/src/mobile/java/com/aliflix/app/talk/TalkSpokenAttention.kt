package com.aliflix.app.talk

/** Playback owns attention. Live transcription can run seconds ahead of the
 * loudspeaker, so its last title must never directly steer the face. The API
 * has no word timestamps: use ordered narration landmarks on the real playout
 * clock, refining duration once the complete PCM stream is known. */
internal class TalkSpokenAttention(private var text:String,private var cards:List<TalkTitleCard>,private val locked:Boolean=false) {
    private var start=-1L
    private var received=0L
    private var complete=false
    @Synchronized fun update(narration:String,visible:List<TalkTitleCard>){if(!locked){text=narration;cards=visible}}
    @Synchronized fun receive(samples:Int,firstSample:Long){if(start<0)start=firstSample;received+=samples}
    @Synchronized fun complete(){complete=true}
    @Synchronized fun cardAt(rendered:Long):String? {
        if(start<0||received<=0||rendered<start||rendered>=start+received)return null
        val duration=if(complete)received else maxOf(received,(text.split(Regex("\\s+")).size/2.6*24000).toLong())
        val fraction=((rendered-start).toDouble()/duration).coerceIn(0.0,1.0)
        val position=(fraction*text.length).toInt()
        val mentions=cards.flatMap { card ->
            listOfNotNull(card.title,card.originalTitle).distinct().flatMap { name ->
                Regex("(?<![\\p{L}\\p{N}])"+Regex.escape(name)+"(?![\\p{L}\\p{N}])",if(name.length<4)emptySet()else setOf(RegexOption.IGNORE_CASE)).findAll(text).map{it.range.first to card.key}.toList()
            }
        }.distinct().sortedBy{it.first}
        return mentions.lastOrNull{it.first<=position}?.second
    }
}
