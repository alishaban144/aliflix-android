package com.aliflix.app.talk

import org.json.JSONArray
import org.json.JSONObject

/** Place the task's epistemic distinction next to the question, after evidence. */
internal fun talkSceneQuestionFocus(question:String):String =
    (if(Regex("(?i)\\b(?:nervous|worried|afraid|scared|guilty|anxious|panicked|worry|fear)\\b").containsMatchIn(question))
        "This asks about a character's worry or guilt. Lead with what that named character BELIEVES happened or FEARS may be discovered, then connect the specific earlier action to the present risk in two or three clear sentences. A character's account alone does not prove that event actually occurred. Do not assert that the feared event was objectively fatal, or that evidence will prove it. Do not invent records, documents or other evidence items absent from the supplied scene. Preserve this belief-versus-fact distinction explicitly in the answer."
    else "")+(if(Regex("(?i)\\b(?:but|instead|whereas|while)\\b").containsMatchIn(question))
        " Explain BOTH sides of the requested contrast, including what remains possible and what does not, before giving the reason. Do not answer just one side." else "")+
    (if(question.count{it=='?'}>1)" Answer every question separately in the order asked. Resolve the helper or visitor against the specific current exchange and earlier dialogue. If more than one person helped at that location, distinguish who helped with each action rather than silently choosing a generic occupational role." else "")

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
    val research=optJSONObject("storyResearch")
    val prefix=optJSONObject("episodeRecall")?.optJSONObject("storyThroughAnchor")
    if(research?.optString("mode")=="direct_player_evidence"&&prefix!=null){
        val supportingQuotes=linkedSetOf<String>()
        val supporting=research.optJSONArray("supportingDialogue")?:JSONArray()
        for(index in 0 until supporting.length()){
            val row=supporting.optJSONArray(index)?:continue
            supportingQuotes.add(String.format(java.util.Locale.ROOT,"%.2f",row.getDouble(0))+"\t"+row.getString(2).replace('\n',' '))
        }
        val quotes=prefix.optString("timedTranscript").lineSequence().filter{it.isNotBlank()&&it !in supportingQuotes}.toList()
        prefix.put("timedTranscript",quotes.joinToString("\n","","\n")).put("includedCues",quotes.size)
            .put("additionalQuestionEvidence","storyResearch.supportingDialogue")
        research.put("evidenceKind","Actual selected-cut character utterances, not independently certified actions. These passages were selected for this question; read them before the broader recall index.")
        // These invariants already live in the versioned system/scene contract.
        research.remove("instruction");research.remove("scope")
        research.optJSONArray("mentionedCharacters")?.let{names->for(index in 0 until names.length())names.optJSONObject(index)?.remove("scope")}
        remove("captureDiagnostic")
    }
}
