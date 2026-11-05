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
    fun clear(){jobs.values.forEach{it.cancel()};jobs.clear();ready.clear();retryAfter.clear()}
    fun attach(scene:TalkSceneContext){
        ready[scene.key]?.let{scene.metadata.put(if(it.optString("status")=="research_ready")"storyResearch" else "storyUnderstanding",it)}
        scene.metadata.put("storyState",if(ready[scene.key]?.optString("status") in setOf("grounded","research_ready"))ready[scene.key]!!.optString("status") else if(jobs.any{(key,job)->key.startsWith(scene.key+":")&&job.isActive})"preparing" else "limited")
    }
    fun prepare(scene:TalkSceneContext,host:TalkPlayerHost,question:String="",refresh:Boolean=false):Deferred<JSONObject?> {
        if(!refresh)ready[scene.key]?.takeIf{it.optString("status") in setOf("grounded","research_ready")}?.let{return CompletableDeferred(it)}
        if(SystemClock.elapsedRealtime()<(retryAfter[scene.key]?:0L))return CompletableDeferred(ready[scene.key])
        val recall=host.talkStoryEvidence()?:return CompletableDeferred(null)
        val prefix=recall.optJSONObject("storyThroughAnchor")?:return CompletableDeferred(null)
        val rows=prefix.optJSONArray("dialogue")?:JSONArray()
        val key=scene.key+":"+rows.toString().hashCode()+":"+if(refresh)question else ""
        jobs[key]?.takeIf{it.isActive}?.let{return it}
        jobs.remove(key)
        val anchor=scene.anchorSeconds
        val chosen=listOfNotNull(scene.frames.firstOrNull(),scene.frames.getOrNull(scene.frames.size/3),
            scene.frames.getOrNull(scene.frames.size*2/3),scene.frames.lastOrNull()).distinctBy{it.seconds}.filter{it.seconds<=anchor}
        val packet=JSONObject().put("id",scene.metadata.optInt("id")).put("kind",scene.metadata.optString("type"))
            .put("title",scene.metadata.optString("title").take(180)).put("sourceKey",scene.key.take(600))
            .put("anchorSeconds",anchor).put("mode","sources").put("question",question.take(1600)).put("dialogue",JSONArray((0 until minOf(rows.length(),5000)).map{rows.getJSONArray(it)}))
            .put("transcriptComplete",prefix.optBoolean("complete")&&rows.length()<=5000)
            .put("captionAlignment",recall.optJSONObject("playbackEvidence")?.optString("captionAlignment").orEmpty().take(80))
            .put("frames",JSONArray(chosen.map{JSONObject().put("seconds",it.seconds).put("data",Base64.encodeToString(it.jpeg,Base64.NO_WRAP))}))
        if(scene.metadata.optString("type")=="tv")packet.put("season",scene.metadata.optInt("season",1)).put("episode",scene.metadata.optInt("episode",1))
        scene.metadata.optJSONObject("groundedObservation")?.let{packet.put("observation",it.toString().take(8000))}
        val began=SystemClock.elapsedRealtime()
        return scope.async {
            val result=try{post(JSONObject().put("story",packet))}
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){issue=error.message.orEmpty().take(180);retryAfter[scene.key]=SystemClock.elapsedRealtime()+45000;null}
            elapsedMs=SystemClock.elapsedRealtime()-began
            if(result?.optString("status") in setOf("grounded","research_ready"))issue=""
            if(TalkPlayerConnection.current()?.talkScene()?.key==scene.key&&result!=null){
                ready[scene.key]=result
                while(ready.size>4)ready.remove(ready.keys.first())
                if(result.optString("status") !in setOf("grounded","research_ready"))retryAfter[scene.key]=SystemClock.elapsedRealtime()+45000
                while(retryAfter.size>8)retryAfter.remove(retryAfter.keys.first())
                attach(scene)
            }
            result
        }.also{deferred->jobs[key]=deferred;while(jobs.size>4){jobs.remove(jobs.keys.first())?.cancel()}}
    }
    suspend fun await(scene:TalkSceneContext,host:TalkPlayerHost,question:String="",refresh:Boolean=false):JSONObject? {
        val work=prepare(scene,host,question,refresh)
        val result=withTimeoutOrNull(if(refresh)20000 else 1500){work.await()}
        attach(scene);return result?:ready[scene.key]
    }
}
