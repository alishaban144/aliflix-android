package com.aliflix.app.talk

internal data class TalkOrbIntent(val x:Float,val y:Float,val scale:Float)

/** Layout attention comes from the current conversation and its visible cards.
 * The renderer supplies continuity; this policy never invents random waypoints. */
internal fun talkOrbIntent(phase:String,cardCount:Int,focusedCard:Int):TalkOrbIntent {
 if(phase=="Thinking")return TalkOrbIntent(.78f,.28f,if(cardCount>0).46f else .70f)
 if(cardCount>0){
  val x=if(focusedCard in 0 until cardCount)(focusedCard+.5f)/cardCount else .5f
  return if(phase=="Speaking")TalkOrbIntent(x,.67f,.60f) else TalkOrbIntent(.5f,.58f,.53f)
 }
 return when(phase){
  "Listening"->TalkOrbIntent(.5f,.64f,.92f)
  "Speaking"->TalkOrbIntent(.42f,.46f,.90f)
  else->TalkOrbIntent(.5f,.5f,1f)
 }
}

internal fun talkOrbMention(text:String,names:List<List<String>>):Int {
 val found=names.mapIndexedNotNull{index,variants->
  variants.filter{it.length>3}.maxOfOrNull{text.lastIndexOf(it,ignoreCase=true)}?.takeIf{it>=0}?.let{index to it}
 }
 return found.maxByOrNull{it.second}?.first?:-1
}
