package com.aliflix.app.talk

import org.json.JSONArray
import org.json.JSONObject

/** Keep the selected timed dialogue once. The complete local index is retained
 * by the player; it need not be replayed into every model turn. */
internal fun compactTalkRecall(original:JSONObject):JSONObject {
    val wire=JSONObject(original.toString())
    val prefix=wire.optJSONObject("storyThroughAnchor")?:return wire
    val transcript=prefix.optString("timedTranscript")
    if(transcript.isBlank())return wire
    val entries=linkedSetOf<String>()
    fun retain(text:String){
        Regex("(?m)(?=^\\d+(?:\\.\\d+)?\\t)").split(text).map(String::trim).filter(String::isNotBlank).forEach(entries::add)
    }
    retain(transcript)
    val windows=wire.optJSONArray("scenes")?:JSONArray()
    for(index in 0 until windows.length())windows.optJSONObject(index)?.let { window->
        retain(window.optString("timedTranscript"));window.remove("timedTranscript")
    }
    prefix.put("timedTranscript",entries.sortedBy{it.substringBefore('\t').toDoubleOrNull()?:Double.MAX_VALUE}.joinToString("\n","","\n"))
    prefix.put("includedCues",entries.size)
    prefix.remove("dialogue") // Same selected lines are already timestamped above.
    wire.remove("timelineIndex")
    return wire
}

internal fun compactTalkSceneMetadata(original:JSONObject)=JSONObject(original.toString()).apply{
    optJSONObject("episodeRecall")?.let{put("episodeRecall",compactTalkRecall(it))}
}
