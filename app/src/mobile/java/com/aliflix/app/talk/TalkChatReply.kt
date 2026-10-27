package com.aliflix.app.talk

/** One display reply belongs to a user turn, across provider/tool generations. */
internal class TalkChatReply {
    private var prefix=""
    private var segment=""
    private var titles=emptyList<TalkTitleCard>()
    private var sources=emptyList<TalkReference>()
    private var collectionCommitted=false
    fun reset(){prefix="";segment="";titles=emptyList();sources=emptyList();collectionCommitted=false}
    fun attach(cards:List<TalkTitleCard>,evidence:List<TalkReference>) {
        if(!collectionCommitted&&cards.isNotEmpty()){
            titles=cards.toList();sources=evidence.distinctBy{it.url}.take(12);collectionCommitted=true;return
        }
        // A competing late search cannot replace a collection or its evidence.
        if(cards.isEmpty()||titles.map{it.key}==cards.map{it.key})sources=(sources+evidence).distinctBy{it.url}.take(12)
    }
    fun narrate(text:String,newSegment:Boolean,cards:List<TalkTitleCard>,evidence:List<TalkReference>) {
        if(newSegment){prefix=join(prefix,segment).takeLast(6000);segment=""}
        segment=text
        if(collectionCommitted)attach(cards,evidence)else{
            titles=(titles+cards).distinctBy{it.key}.take(24)
            sources=(sources+evidence).distinctBy{it.url}.take(12)
        }
    }
    fun line()=TalkLine("assistant",join(prefix,segment).takeLast(6000),sources,titles=titles)
    fun upsert(lines:List<TalkLine>):List<TalkLine> {
        val boundary=lines.indexOfLast{it.role=="user"}
        val index=(boundary+1 until lines.size).firstOrNull{lines[it].role=="assistant"}
        val reply=line()
        if(reply.text.isBlank()&&reply.titles.isEmpty())return lines
        return if(index==null)(lines+reply).takeLast(64) else
            lines.mapIndexedNotNull{at,value->when{at==index->reply;at>boundary&&value.role=="assistant"->null;else->value}}.takeLast(64)
    }
    private fun join(first:String,next:String):String {
        if(first.isBlank())return next
        if(next.isBlank()||first.endsWith(next))return first
        if(next.startsWith(first))return next
        val overlap=(minOf(first.length,next.length) downTo 1).firstOrNull{first.regionMatches(first.length-it,next,0,it)}?:0
        return if(overlap>0)first+next.drop(overlap)else first.trimEnd()+" "+next.trimStart()
    }
}
