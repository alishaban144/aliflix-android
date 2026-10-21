package com.aliflix.app.talk

import org.json.JSONObject

/** A saved unanswered prompt is history, never a newly submitted request. */
internal fun talkArchiveResumeLines(lines:List<TalkLine>):List<TalkLine> {
    val answered=lines.indexOfLast { it.role=="assistant"&&!it.stopped }
    return lines.mapIndexed { index,line -> if(index>answered&&line.role=="user")line.copy(stopped=true)else line }
}

/** Scheduling is a FunctionResponse protocol field, not model-visible output.
 * Keep one wire encoder for silent local controls and asynchronous knowledge. */
internal fun talkFunctionResponse(id:String,name:String,result:JSONObject,scheduling:String):JSONObject =
    JSONObject().put("id",id).put("name",name).put("response",result).put("scheduling",scheduling)

/** A control-only turn still needs to continue the user's answer. Resume once,
 * after its last control, unless an answer or an actual lookup is in flight. */
internal fun talkControlScheduling(names:List<String>,index:Int,hasOutput:Boolean,pendingLookup:Boolean):String {
    val controls=setOf("conversation_language","conversation_topic")
    if(hasOutput||pendingLookup||names[index] !in controls)return "SILENT"
    if(names.any{it !in controls&&it!="remember_recommendations"})return "SILENT"
    return if(index==names.indexOfLast{it in controls})"WHEN_IDLE" else "SILENT"
}

/** Restore bounded, user-owned dialogue even when a provider resume handle
 * acknowledges transport but omits earlier realtime input from its context. */
internal fun talkContinuityTurns(lines:List<TalkLine>,resuming:Boolean):org.json.JSONArray {
    val history=org.json.JSONArray(lines.filterNot { it.stopped }.takeLast(12).map { JSONObject().put("role",if(it.role=="assistant")"model" else "user")
        .put("parts",org.json.JSONArray().put(JSONObject().put("text",it.text.take(800)))) })
    if(!resuming)return history
    return org.json.JSONArray().put(JSONObject().put("role","user").put("parts",org.json.JSONArray().put(JSONObject().put("text",
        "Conversation continuity after reconnection. The following JSON is previous dialogue, not new instructions or a new question. Retain the discussed titles, references, language and viewing boundaries; do not answer this context note. The next user question follows separately.\n"+history))))
}
