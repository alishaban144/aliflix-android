package com.aliflix.app.talk

import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** A separate, source-bound story/character memory; full dialogue is analysed
 * outside Live, whose own context remains bounded and latency-sensitive. */
internal class TalkStoryContext(private val scope:CoroutineScope,
    private val post:suspend(JSONObject)->JSONObject) {
    private val jobs=linkedMapOf<String,Deferred<JSONObject?>>()
    private val ready=linkedMapOf<String,JSONObject>()
    private val retryAfter=linkedMapOf<String,Long>()
    internal var issue="";private set
    internal var elapsedMs=0L;private set
    private fun memoryKey(scene:TalkSceneContext)="${scene.identity}:${scene.revision}:${(scene.anchorSeconds*10).toLong()}:${scene.metadata.optString("subtitleEdition")}:${scene.metadata.optJSONArray("dialogue")?.toString()?.hashCode()}"
    fun clear(){jobs.values.forEach{it.cancel()};jobs.clear();ready.clear();retryAfter.clear()}
    fun attach(scene:TalkSceneContext){
        ready[memoryKey(scene)]?.let{scene.metadata.put(if(it.optString("status")=="research_ready")"storyResearch" else "storyUnderstanding",it)}
        scene.metadata.put("storyState",if(ready[memoryKey(scene)]?.optString("status") in setOf("grounded","research_ready"))ready[memoryKey(scene)]!!.optString("status") else if(jobs.any{(key,job)->key.startsWith(memoryKey(scene)+":")&&job.isActive})"preparing" else "limited")
    }
    fun recallQuery(scene:TalkSceneContext,question:String):String = expandedTalkStoryQuery(question,
        scene.metadata.optJSONArray("dialogue")?.toString().orEmpty(),ready[memoryKey(scene)])
    fun prepare(scene:TalkSceneContext,host:TalkPlayerHost,question:String="",refresh:Boolean=false):Deferred<JSONObject?> {
        if(!refresh)ready[memoryKey(scene)]?.takeIf{reusableTalkStoryMemory(it.optString("status"),it.optBoolean("deferred"),question.isNotBlank())}?.let{return CompletableDeferred(it)}
        if(SystemClock.elapsedRealtime()<(retryAfter[memoryKey(scene)]?:0L))return CompletableDeferred(ready[memoryKey(scene)])
        val recall=host.talkStoryEvidence()?:return CompletableDeferred(null)
        val prefix=recall.optJSONObject("storyThroughAnchor")?:return CompletableDeferred(null)
        val rows=prefix.optJSONArray("dialogue")?:JSONArray()
        val key=memoryKey(scene)+":"+rows.toString().hashCode()+":"+question
        jobs[key]?.takeIf{it.isActive}?.let{return it}
        jobs.remove(key)
        val anchor=scene.anchorSeconds
        val chosen=listOfNotNull(scene.frames.firstOrNull(),scene.frames.getOrNull(scene.frames.size/3),
            scene.frames.getOrNull(scene.frames.size*2/3),scene.frames.lastOrNull()).distinctBy{it.seconds}.filter{it.seconds<=anchor}
        val packet=JSONObject().put("id",scene.metadata.optInt("id")).put("kind",scene.metadata.optString("type"))
            .put("title",scene.metadata.optString("title").take(180)).put("sourceKey",scene.key.take(600))
            .put("anchorSeconds",anchor).put("mode","analysis").put("question",question.take(1600)).put("dialogue",JSONArray((0 until minOf(rows.length(),5000)).map{rows.getJSONArray(it)}))
            .put("transcriptComplete",prefix.optBoolean("complete")&&rows.length()<=5000)
            .put("captionAlignment",recall.optJSONObject("playbackEvidence")?.optString("captionAlignment").orEmpty().take(80))
            .put("frames",JSONArray(chosen.map{JSONObject().put("seconds",it.seconds).put("data",Base64.encodeToString(it.jpeg,Base64.NO_WRAP))}))
        if(scene.metadata.optString("type")=="tv")packet.put("season",scene.metadata.optInt("season",1)).put("episode",scene.metadata.optInt("episode",1))
        scene.metadata.optJSONObject("groundedObservation")?.let{packet.put("observation",it.toString().take(8000))}
        val began=SystemClock.elapsedRealtime()
        return scope.async {
            val result=try{post(JSONObject().put("story",packet))}
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){issue=error.message.orEmpty().take(180);retryAfter[memoryKey(scene)]=SystemClock.elapsedRealtime()+45000;null}
            elapsedMs=SystemClock.elapsedRealtime()-began
            if(result?.optString("status") in setOf("grounded","research_ready"))issue=""
            if(TalkPlayerConnection.current()?.talkScene()?.let{memoryKey(it)}==memoryKey(scene)&&result!=null){
                ready[memoryKey(scene)]=result
                while(ready.size>4)ready.remove(ready.keys.first())
                if(result.optString("status") !in setOf("grounded","research_ready"))retryAfter[memoryKey(scene)]=SystemClock.elapsedRealtime()+45000
                while(retryAfter.size>8)retryAfter.remove(retryAfter.keys.first())
                attach(scene)
            }
            result
        }.also{deferred->jobs[key]=deferred;while(jobs.size>4){jobs.remove(jobs.keys.first())?.cancel()}}
    }
    suspend fun await(scene:TalkSceneContext,host:TalkPlayerHost,question:String="",refresh:Boolean=false):JSONObject? {
        val work=prepare(scene,host,question,refresh)
        val result=withTimeoutOrNull(if(refresh||question.isNotBlank())20000 else 9500){work.await()}
        attach(scene);return result?:ready[memoryKey(scene)]
    }
}

/** A cheap opening lookup is not an analysis of the viewer's actual question. */
internal fun reusableTalkStoryMemory(status:String,deferred:Boolean,hasQuestion:Boolean)=
    status=="grounded" || status=="research_ready" && (!deferred || !hasQuestion)

/** Research expands retrieval terms, not factual assertions. The full earlier
 * transcript remains local and searchable rather than filling each Live turn. */
internal fun expandedTalkStoryQuery(question:String,currentDialogue:String,research:JSONObject?):String {
    val names=research?.optJSONArray("mentionedCharacters")?:JSONArray()
    val current=buildList { for(i in 0 until names.length()) {
        val name=if(names.opt(i) is String)names.getString(i)else names.optJSONObject(i)?.optString("name").orEmpty()
        val terms=name.split(Regex("[^\\p{L}]+" )).filter{it.length>=3}
        if(terms.any{Regex("(?iu)\\b"+Regex.escape(it)+"\\b").containsMatchIn(currentDialogue)})addAll(terms)
    } }
    val safe=research?.optJSONArray("establishedSearchTerms")?:JSONArray()
    val entities=(0 until minOf(safe.length(),6)).map{safe.getString(it)}
    return (question+" "+(current+entities).distinct().joinToString(" ")).trim().take(1600)
}
