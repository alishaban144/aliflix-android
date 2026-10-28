package com.aliflix.app.talk

import org.json.JSONArray
import org.json.JSONObject

/** Discovery belongs to the catalogue presentation path even if Live picks its
 * general web tool. Facts, plot discussion, greetings and card controls stay conversational. */
internal fun talkDiscoveryQuestion(question:String,hasRecommendations:Boolean=false):Boolean {
    val text=question.lowercase(java.util.Locale.ROOT).trim()
    if(talkConversationIntent(question,if(hasRecommendations)3 else 0)!=TalkIntent.CONVERSATION)return false
    if(Regex("^(?:(?:i|we) (?:recommend|suggest)|(?:don't|do not|stop) (?:recommend|suggest))\\b").containsMatchIn(text))return false
    if(Regex("\\b(?:recommend|suggest) the (?:movie|film|series|show)\\s+\\p{L}").containsMatchIn(text))return false
    if(hasRecommendations&&(talkCatalogueRefinementQuestion(text)||Regex("^(?:more|other|different|alternative|another)(?:[.!?]*$|\\s+(?:different\\s+)?(?:titles?|movies?|films?|series|shows?|choices?))").containsMatchIn(text)))return true
    val media=Regex("\\b(?:movies?|films?|series|shows?|titles?|recommendations?|sci.?fi|science fiction)\\b|أفلام|افلام|مسلسلات|filme|serien").containsMatchIn(text)
    if(media&&Regex("\\b(?:recommend|suggest|empfiehl|empfehle|recomienda|sugiere)\\b|اقترح|رشح").containsMatchIn(text))return true
    if(Regex("\\b(?:cards?|posters?|restore|bring back|release dates?|news|reviews?|cast|trailers?|box office|budget|awards?|who|director|explain|ending|episode|season|called|named)\\b|بطاق|karte").containsMatchIn(text))return false
    if(Regex("\\b(?:show|display|retrieve)\\b.*\\bagain\\b").containsMatchIn(text))return false
    if(media&&Regex("\\b(?:find|give me|give us|show me|looking for|recommendations for)\\b").containsMatchIn(text))return true
    if(media&&Regex("\\b(?:search|look up)\\b").containsMatchIn(text)&&Regex("\\b(?:movies|films|shows|titles|one|two|three|some|[1-9])\\b").containsMatchIn(text))return true
    if(Regex("^(?:(?:some|good|great|best|recent|thoughtful|science fiction|sci.?fi)\\s+)*(?:movies|films|series|shows)\\b").containsMatchIn(text))return true
    if(Regex("\\bwhat (?:should|can|could) (?:i|we) watch(?:[?.!]|$| tonight)").containsMatchIn(text))return true
    return hasRecommendations&&Regex("^(?:something|anything|more like those)\\b.*\\b(?:less|more|lighter|darker|similar|like)\\b").containsMatchIn(text)
}

internal fun talkRecommendationArgs(question:String)=JSONObject().put("query",question.take(1600))
    .put("mediaType",if(Regex("(?i)\\b(?:series|tv|television|shows)\\b|مسلسلات|serien").containsMatchIn(question))"tv" else "movie")
    .put("candidates",JSONArray())

/** The caller retains the original tool name/id for the FunctionResponse wire envelope. */
internal fun talkCanonicalLookup(call:JSONObject,question:String,hasRecommendations:Boolean=false):JSONObject =
    if(call.optString("name")=="web_research"&&talkDiscoveryQuestion(question,hasRecommendations))
        JSONObject(call.toString()).put("name","recommendations").put("args",talkRecommendationArgs(question).apply{
            call.optJSONObject("args")?.optString("bridge")?.takeIf{it.isNotBlank()}?.let{put("bridge",it)}
        })
    else call

internal fun talkCatalogueRefinementQuestion(question:String):Boolean {
    val q=question.lowercase(java.util.Locale.ROOT).replace(Regex("\\.\\s*(?:keep|preserve|retain)\\s+(?:all\\s+)?(?:the\\s+)?(?:other|remaining|same)\\s+constraints(?:\\s+(?:from|of)\\s+(?:this|the|that|previous)\\s+search)?[.!?\\s]*$"),"").trim().trimEnd('.','!','?')
    return Regex("^(?:(?:only|just|show(?: me)?|give(?: me)?)\\s+)?(?:(?:(?:films?|movies?|titles?|series|shows?)\\s+)?(?:(?:from|since|after|before|until|through)\\s+(?:18|19|20)\\d{2}(?:\\s+(?:onwards?|and later|and newer))?|(?:more\\s+)?(?:recent|newer|latest)|(?:under|less than|at most)\\s+\\d{1,3}\\s+(?:minutes?|mins?))|(?:ended|finished|completed|running|ongoing|returning)\\s+(?:series|shows?))$").matches(q)
}
internal fun talkDiscoveryExclusions(question:String,cards:TalkCardContext):List<String> =
    if(talkCatalogueRefinementQuestion(question))emptyList()else cards.known.map{it.title}.distinct().take(24)
