package com.aliflix.app.talk

import java.util.Locale

/** These boundaries stop a tool from changing the user's task. The live model
 * still interprets the conversation; this is not an extra classifier/model hop. */
internal enum class TalkIntent { DISCOVERY, DECISION, CAPABILITIES, STOP, CONVERSATION }

internal fun talkConversationIntent(question:String,choiceCount:Int=0):TalkIntent {
    val q=question.lowercase(Locale.ROOT).replace('’','\'').trim().replace(Regex("\\s+")," ")
    if(Regex("^(?:(?:ok|okay)[,.]? )?(?:please )?(?:stop(?: talking| speaking| answering)?(?: now)?|cancel(?: that| the search| the answer)?|never ?mind|be quiet|quiet|shut up|(?:that's |that is )?enough|hold on|wait(?: a (?:second|moment))?|خلاص|توقف|اسكت|لحظة|stopp|sei still|warte(?: kurz)?)(?:,? please)?[.!?]*$").matches(q))return TalkIntent.STOP
    if(Regex("\\byour(?: technical)? (?:limitations?|limits?|capabilities|access|restrictions?)\\b|\\b(?:can|can't|do) you (?:actually )?(?:access|see|control|read|use) (?:my|other apps|the (?:internet|web|microphone|camera))\\b|حدودك|قيودك|قدراتك|technischen (?:grenzen|einschränkungen)").containsMatchIn(q))return TalkIntent.CAPABILITIES
    // Excluded alternatives do not turn an open search into a closed decision.
    val task=q.replace(Regex("(?:do not|don't|never|avoid|exclude|excluding|without)\\s+(?:choos(?:e|ing)\\s+|pick\\s+|recommend\\s+|include\\s+)?[^,.!?;]*")," ")
    // An explicit second discovery task remains authorized alongside a comparison.
    if(Regex("(?:[.;,]|\\bthen\\b)\\s*(?:and |then |but )*(?:recommend|find|suggest|give me)\\s+(?:[2-9]|two|three|four|five|some|other|different|more)\\b").containsMatchIn(task))return TalkIntent.CONVERSATION
    val alternatives=Regex("\\b(?:or|versus|vs\\.?|between)\\b|أم|ولا|oder").containsMatchIn(task)
    if(Regex("\\bcompare\\b|قارن|vergleichen").containsMatchIn(task))return TalkIntent.DECISION
    val deciding=Regex("\\b(?:should|rather|better|prefer|choose|pick|decide|compare|comparison|recommend|recommendation|watch|worth|difference|which|soll|schauen|ansehen)\\b|أختار|اختار|أفضل|افضل|شاهد|قارن|welch|besser|vergleichen").containsMatchIn(task)
    val closedFollowup=choiceCount>1&&Regex("\\b(?:which (?:one|would|should|is)|pick (?:one|a winner)|choose (?:one|for me)|decide (?:for me|between)|compare (?:them|these|those|both)|of (?:these|those|the two)|(?:either|both|any) of)\\b|أي(?:هما|هم)|welches davon").containsMatchIn(task)
    // "Recommend A or B?" is a decision. "Recommend comedy or thriller movies"
    // is discovery: explicit plural genre/category requests remain open-ended.
    val genre="(?:comedy|comedies|thriller|horror|drama|documentary|documentaries|science fiction|sci-fi|fantasy|animated|animation|romance|western|crime|action)"
    val openCategories=Regex("\\b(?:movies|films|series|shows|titles) (?:about|with|like|similar|from|that|to watch)\\b|\\b(?:some|new|other|different|three|two|[2-9]) (?:movies|films|series|shows|titles)\\b|\\b$genre(?: or $genre)? (?:movies|films|series|shows|titles)\\b").containsMatchIn(task)
    if(closedFollowup||Regex("\\b(?:either|both) of (?:those|these|the)\\b").containsMatchIn(task)||alternatives&&deciding&&!openCategories)return TalkIntent.DECISION
    return TalkIntent.CONVERSATION
}

internal fun talkToolNeedsDiscoveryCorrection(tool:String,question:String,choiceCount:Int):Boolean =
    tool=="recommendations"&&talkConversationIntent(question,choiceCount) in setOf(TalkIntent.DECISION,TalkIntent.CAPABILITIES,TalkIntent.STOP)

/** UI completion is independent of AudioTrack draining and silent bookkeeping. */
internal fun talkShowsResponseProgress(sending:Boolean,answerComplete:Boolean)=sending&&!answerComplete

/** Only explicit two-title syntax is eligible for the optional UI fallback.
 * Ambiguous sentences stay with the model; never search arbitrary first words. */
internal fun talkExplicitComparisonTitles(question:String):List<String> {
    val comparison=question.split(Regex("(?i)(?:[,;.!]\\s*(?:and\\s+)?(?:then\\s+)?|\\bthen\\s+)(?:recommend|suggest|find|give me)\\b"),limit=2).first()
    if(talkConversationIntent(comparison)!=TalkIntent.DECISION)return emptyList()
    val patterns=listOf(
        "(?i)\\bcompare\\s+(.+?)\\s+(?:with|to|versus|vs\\.?|and)\\s+(.+?)(?:[?.!,;]|$)",
        "(?i)\\b(?:choose|pick|decide)\\s+between\\s+(.+?)\\s+and\\s+(.+?)(?:[?.!,;]|$)",
        "(?i)\\b(?:watch|prefer|recommend)\\s+(.+?)\\s+(?:or|versus|vs\\.?)\\s+(.+?)(?:[?.!,;]|$)"
    )
    val match=patterns.firstNotNullOfOrNull{Regex(it).find(comparison)}?:return emptyList()
    val names=(1..2).map{index->
        var name=match.groupValues[index].trim().trim('"','“','”','\'')
            .replace(Regex("(?i)\\s+(?:tonight|this evening|for (?:a|my|the)|in one sentence)\\b.*$"),"")
            .replace(Regex("(?i)^(?:the movie|the film|the series)\\s+"),"").trim()
        Regex("(?i)^(?:the\\s+)?((?:18|19|20)\\d{2})\\s+(.+)$").matchEntire(name)?.let{name=it.groupValues[2]+" ("+it.groupValues[1]+")"}
        name
    }
    return names.takeIf{it.distinct().size==2&&it.all{v->v.length in 2..100&&!Regex("(?i)\\b(?:or|between|which|those|these|one hundred|minutes)\\b").containsMatchIn(v)}}.orEmpty()
}

/** High-confidence speech-act boundaries, not an extra permission dialog.
 * Model-proposed actions cannot turn a quote, hypothetical or negation into a write. */
internal fun talkActionRequested(action:String,question:String):Boolean {
    if(action in setOf("show_card","title_state","player_state","downloads_state","library_state","download_work"))return true
    val q=question.lowercase(Locale.ROOT).replace('’','\'').trim()
    if(Regex("^(?:what if|what would|how (?:do|can|would)|if i (?:ask|asked|say|said|were|wanted|told)|suppose|hypothetically|imagine that)\\b").containsMatchIn(q))return false
    if(Regex("\\b(?:just a quote|only quoting|just an example|as an example|quoted phrase)\\b").containsMatchIn(q))return false
    if(Regex("^(?:what (?:can|could|does)|do you (?:like|love|prefer|need|know|have)|can you explain)\\b").containsMatchIn(q))return false
    val clauses=q.split(Regex("[.;!?]|,\\s*(?:then|and|but)\\b|\\b(?:and then|then please)\\b")).map{it.trim()}.filter{it.isNotEmpty()}
    val verb=when(action) {
        "save"->"save|add|put";"unsave"->"unsave|remove|delete"
        "favorite"->"favorite|favourite|like|love|mark";"unfavorite"->"unfavorite|unfavourite|unlike|remove"
        "dislike"->"dislike|hide|reject";"undo_dislike","undo"->"undo|restore|bring back"
        "play","resume"->"play|resume|start";"pause"->"pause|stop"
        "cancel_download_work"->"cancel|stop|remove";"download"->"download";"set_setting","set_voice","set_history"->"change|set|turn|switch|enable|disable"
        "open_title","open_screen","similar","search"->"open|show|go|search|find|navigate"
        "agent_create"->"create|build|plan|monitor|watch|research|prepare|schedule|make|start|keep"
        "agent_update"->"update|change|revise|replace|adjust|modify"
        "agent_remember"->"remember|store|save|keep|retain"
        "agent_forget"->"forget|erase|delete|remove|store|remember"
        "agent_pause"->"pause|stop|suspend";"agent_cancel"->"cancel|stop|delete|remove"
        "agent_resume"->"resume|continue|restart|start"
        "agent_refresh"->"refresh|recheck|check|run|retry"
        "batch_library"->"save|unsave|add|remove|favorite|favourite|unfavorite|dislike|hide|reject|put|mark"
        else->action.replace('_',' ')
    }
    if(Regex("^(?:what|why|who|when|where|explain|tell me about)\\b").containsMatchIn(q)&&
        clauses.drop(1).none{Regex("^(?:(?:then|and|please) )*(?:$verb)\\b").containsMatchIn(it)})return false
    if(Regex("(?:don't|do not|never|not to)\\s+[^,.;!?]{0,20}\\b(?:$verb)\\b").containsMatchIn(q))return false
    if(Regex("^(?:(?:please|can you|could you|would you) )?(?:recommend|suggest|find me|give me)\\b").containsMatchIn(q)&&
        !Regex("\\b(?:$verb)\\b").containsMatchIn(q))return false
    if(Regex("^i (?:like|love|enjoy|hate|dislike)\\b").containsMatchIn(q)&&!Regex("\\b(?:please|can you|could you|save|add|mark|put|hide|reject|favorite|favourite|remove|unlike|download|play|open|set|turn|switch|enable|disable|undo)\\b").containsMatchIn(q))return false
    return true
}
