package com.aliflix.app.talk

/** Visual context belongs to the conversation, not the lifetime of a reply. */
internal data class TalkCardContext(
    val visible: List<TalkTitleCard> = emptyList(),
    val known: List<TalkTitleCard> = emptyList(),
    val group: List<TalkTitleCard> = emptyList(),
    val choiceGroups:List<List<TalkTitleCard>> = emptyList(),
) {
    fun preparedComparison(cards:List<TalkTitleCard>,keepPresented:Boolean):TalkCardContext {
        val prepared=show(cards,replaceGroup=true).compared(cards)
        return if(keepPresented)prepared.copy(visible=visible,group=group)else prepared
    }
    /** Reuses catalogue identities already present in this session. No dialogue
     * text, timestamps, user notes or extra provider/destination is recorded. */
    fun compared(cards:List<TalkTitleCard>):TalkCardContext {
        if(cards.size<2)return this
        val keys=cards.map{it.key}
        if(choiceGroups.lastOrNull()?.map{it.key}==keys)return this
        val updated=choiceGroups+listOf(cards.take(24))
        return copy(choiceGroups=if(updated.size<=8)updated else listOf(updated.first())+updated.takeLast(7))
    }
    fun show(cards: List<TalkTitleCard>,replaceGroup:Boolean=false,limit:Int=8): TalkCardContext {
        if (cards.isEmpty()) return this
        val next=copy(visible = cards.distinctBy { it.key }.take(limit.coerceIn(1,24)),
            known = (cards + known).distinctBy { it.key }.take(96),
            group = if(cards.size>1&&(replaceGroup||group.isEmpty()))cards.take(limit.coerceIn(1,24)) else group)
        return if(replaceGroup&&cards.size>1)next.compared(next.visible)else next
    }
    fun resolve(name:String,kind:String?=null,year:Int=0,id:Int=0):List<TalkTitleCard> {
        val pool=(visible+group+known).distinctBy{it.key}.filter{kind==null||it.type.routeName==kind}
        if(id>0)return pool.filter{it.id==id}.take(1)
        val exactName=pool.any{it.key==name||listOfNotNull(it.title,it.originalTitle).any{title->cardName(title)==cardName(name)}}
        val statedYear=year.takeIf{it>0}?:Regex("\\(((?:18|19|20)\\d{2})\\)$").find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?:if(exactName)0 else Regex("(?:18|19|20)\\d{2}").find(name)?.value?.toIntOrNull()?:0
        val text=name.lowercase(java.util.Locale.ROOT).trim().trim('.','?','!')
        val ordinal=talkCardOrdinal(text,group.size)
        if(ordinal>=0)return group.getOrNull(ordinal)?.takeIf{kind==null||it.type.routeName==kind}?.let{listOf(it)}.orEmpty()
        if(text in setOf("","it","that","this","the movie","the film","the series","that title","this title","mentioned title","the mentioned title","the mentioned movie","that movie","that film","that series"))
            return visible.filter{kind==null||it.type.routeName==kind}
        val nameWithoutYear=if(exactName)name else name.replace(Regex("\\s*\\(?(?:18|19|20)\\d{2}\\)?$"),"")
        val matches=pool.filter{it.key==name||listOfNotNull(it.title,it.originalTitle).any{title->cardName(title)==cardName(nameWithoutYear)}}
            .filter{statedYear==0||it.year.toIntOrNull()==statedYear}
        // The active recommendation wins over an older same-name edition.
        return matches.take(1)
    }
    fun select(names: List<String>): List<TalkTitleCard> = names.flatMap{resolve(it)}.distinctBy { it.key }.take(8)
    fun snapshot()=org.json.JSONObject().put("focused",visible.singleOrNull()?.json())
        .put("visible",org.json.JSONArray(visible.map{it.json()})).put("recommendationGroup",org.json.JSONArray(group.map{it.json()}))
        .put("recent",org.json.JSONArray(known.take(24).map{it.json()}))
        .put("choiceGroups",org.json.JSONArray(choiceGroups.map{cards->org.json.JSONArray(cards.map{it.key})}))
        .put("instruction","These are established catalogue identities, not new requests. Reuse id, type and year for follow-ups. A repeated title refers to this edition unless the user explicitly names another year. First/second/third refer to recommendationGroup order; it/that refers to focused. Never ask which edition of a title you just recommended.")
    fun ground(args:org.json.JSONObject,question:String):org.json.JSONObject {
        val year=explicitYear(question)
        val established=references(question).singleOrNull()?:resolve(args.optString("title"),year=year).singleOrNull()
            ?:return if(year>0)org.json.JSONObject(args.toString()).put("year",year).apply{remove("tmdbId")}else args
        return org.json.JSONObject(args.toString()).put("title",established.title).put("kind",established.type.routeName)
            .put("year",established.year.toIntOrNull()).put("tmdbId",established.id)
    }
    fun hydrate(cards:List<TalkTitleCard>):TalkCardContext {
        fun List<TalkTitleCard>.updated()=map{old->cards.firstOrNull{it.key==old.key}?:old}
        return copy(visible=visible.updated(),known=known.updated(),group=group.updated())
    }
    fun topic(@Suppress("UNUSED_PARAMETER") topic: String): TalkCardContext = this
    fun question(question: String): TalkCardContext {
        val text=question.lowercase(java.util.Locale.ROOT)
        if(choiceGroups.isNotEmpty()&&Regex("\\b(?:first|initial|earliest)\\s+(?:question|comparison|pair)|\\boriginal (?:two|pair|comparison|choices|options)\\b").containsMatchIn(text))return show(choiceGroups.first())
        val referenced=references(question)
        if(referenced.isNotEmpty()) {
            val comparison=referenced.size>1&&talkConversationIntent(question,group.size)==TalkIntent.DECISION
            return show(referenced,replaceGroup=comparison).let{if(comparison)it.compared(referenced)else it}
        }
        if(Regex("\\b(?:change (?:the )?(?:subjects?|topics?)|different (?:subject|topic)|something (?:entirely )?else)\\b").containsMatchIn(text))return copy(visible=emptyList())
        val intent=talkConversationIntent(question,group.size)
        if(intent==TalkIntent.CAPABILITIES||talkDiscoveryQuestion(question,group.isNotEmpty())||talkExplicitComparisonTitles(question).isNotEmpty())return copy(visible=emptyList())
        val movieFollowup=Regex("\\b(?:plot|story|ending|character|cast|director|scene|tone|runtime|soundtrack|violence|violent|spoiler|subtitles?|film|movie|series|watch|save|list|favorite|favourite)\\b").containsMatchIn(text)
        val standalone=Regex("^(?:what (?:is|are)|who (?:is|are)|where|when|why (?:is|does)|how (?:does|do)|can you|translate|tell me about|explain)\\b").containsMatchIn(text)
        val refersBack=Regex("\\b(?:it|its|this|that|these|those|they|them|their)\\b").containsMatchIn(text)
        val continuation=text.trim().removePrefix("can you ").removePrefix("could you ").removePrefix("would you ").removePrefix("please ").trimEnd('.','!','?')
        if(Regex("^(?:go on|continue|keep going|elaborate|tell me (?:a little )?more|say more|explain (?:more|further)|more details?)(?: please)?$").matches(continuation))return this
        if(standalone&&!movieFollowup&&!refersBack)return copy(visible=emptyList())
        return this
    }
    /** Resolve the human's reference before considering a model's guessed tool
     * title/year. Numeric titles (2049, 1917, 2001) are identities, not editions. */
    fun references(question:String):List<TalkTitleCard> {
        val pool=(visible+group+known).distinctBy{it.key}
        val matches=pool.flatMap{card->listOfNotNull(card.title,card.originalTitle).distinct().flatMap{title->
            if(title.length<4)emptyList()else titlePattern(title).findAll(question).map{card to it.range}.toList()
        }}
        val named=matches.filter{(_,range)->matches.none{(_,other)->other!=range&&other.first<=range.first&&other.last>=range.last}}
        if(named.isNotEmpty())return named.sortedBy{it.second.first}.map{it.first.title}.distinct().flatMap{resolve(it,year=explicitYear(question))}.distinctBy{it.key}.take(8)
        val ordinals=Regex("(?i)\\b(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|last|[1-9][0-9]?(?:st|nd|rd|th))(?:(?: one| title| movie| film| series| option| choice| recommendation)\\b|[.!?]*$)").findAll(question)
            .mapNotNull{group.getOrNull(talkCardOrdinal(it.groupValues[1],group.size))}.distinctBy{it.key}.toList()
        if(ordinals.isNotEmpty())return ordinals
        if(explicitYear(question)==0&&Regex("(?i)\\b(?:it|its|this film|that film|this movie|that movie|this series|that series|that one|this one)\\b").containsMatchIn(question))return listOfNotNull(visible.singleOrNull())
        return emptyList()
    }
    private fun explicitYear(question:String):Int {
        val withoutTitles=(visible+group+known).flatMap{listOfNotNull(it.title,it.originalTitle)}.distinct().sortedByDescending{it.length}
            .fold(question){text,title->titlePattern(title).replace(text," ")}
        return Regex("\\b(?:18|19|20)\\d{2}\\b").find(withoutTitles)?.value?.toIntOrNull()?:0
    }
}
private fun titlePattern(title:String)=Regex("(?<![\\p{L}\\p{N}])"+Regex.escape(title)+"(?![\\p{L}\\p{N}])",RegexOption.IGNORE_CASE)
private fun cardName(value: String) = java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKD).lowercase(java.util.Locale.ROOT)
    .replace(Regex("\\s*\\((?:18|19|20)\\d{2}\\)$"),"").replace(Regex("^(the|a|an)\\s+"),"").filter { it.isLetterOrDigit() }

internal fun talkRestoredCards(lines:List<TalkLine>):TalkCardContext = TalkCardContext(
    known=lines.asReversed().flatMap{it.titles}.distinctBy{it.key}.take(96),
    group=(lines.lastOrNull{it.titles.size>1})?.titles.orEmpty(),
    choiceGroups=lines.filter{it.titles.size>1}.map{it.titles}.distinctBy{it.map{c->c.key}}.let{if(it.size<=8)it else listOf(it.first())+it.takeLast(7)},
).show(lines.lastOrNull{it.titles.isNotEmpty()}?.titles.orEmpty(),limit=24)

internal fun talkCanDismissCards(question:String,visible:List<TalkTitleCard>):Boolean {
    val text=question.lowercase(java.util.Locale.ROOT)
    if(Regex("\\b(?:hide|dismiss|remove|clear|stop showing|stop displaying|don't show|do not show)\\b.*\\bcards?\\b|إخفاء|اخف|verberge").containsMatchIn(text))return true
    if(Regex("\\b(?:show|display|bring|retrieve|get|open|see)\\b.*\\bcards?\\b|\\bcards? please\\b|(?:أظهر|اعرض|أرني).*بطاق|(?:zeig|zeige).*karte").containsMatchIn(text))return false
    if(Regex("\\b(?:change (?:the )?(?:subjects?|topics?)|different (?:subject|topic)|something (?:entirely )?else)\\b").containsMatchIn(text))return true
    if(visible.size==1&&(text.contains(visible.single().title.lowercase(java.util.Locale.ROOT))||Regex("\\b(?:it|its|this film|that film|this movie|that movie|this series|that series)\\b").containsMatchIn(text)))return false
    return TalkCardContext(visible=visible,known=visible,group=visible).question(question).visible.isEmpty()
}

internal fun talkCardOrdinal(value:String,size:Int):Int {
    val text=value.lowercase(java.util.Locale.ROOT).removePrefix("the ").replace(Regex(" (?:one|title|movie|film|series|option|choice|recommendation)$"),"")
    if(text=="last")return size-1
    val words=listOf("first","second","third","fourth","fifth","sixth","seventh","eighth","ninth","tenth","eleventh","twelfth")
    val named=words.indexOf(text);if(named>=0)return named
    return Regex("^([1-9][0-9]?)(?:st|nd|rd|th)$").matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?:-1
}
