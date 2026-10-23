package com.aliflix.app.talk

/** Visual context belongs to the conversation, not the lifetime of a reply. */
internal data class TalkCardContext(
    val visible: List<TalkTitleCard> = emptyList(),
    val known: List<TalkTitleCard> = emptyList(),
    val group: List<TalkTitleCard> = emptyList(),
) {
    fun show(cards: List<TalkTitleCard>,replaceGroup:Boolean=false): TalkCardContext {
        if (cards.isEmpty()) return this
        return copy(visible = cards.distinctBy { it.key }.take(3),
            known = (cards + known).distinctBy { it.key }.take(24),
            group = if(cards.size>1&&(replaceGroup||group.isEmpty()))cards.take(3) else group)
    }
    fun resolve(name:String,kind:String?=null,year:Int=0,id:Int=0):List<TalkTitleCard> {
        val pool=(visible+group+known).distinctBy{it.key}.filter{kind==null||it.type.routeName==kind}
        if(id>0)return pool.filter{it.id==id}.take(1)
        val exactName=pool.any{it.key==name||listOfNotNull(it.title,it.originalTitle).any{title->cardName(title)==cardName(name)}}
        val statedYear=year.takeIf{it>0}?:Regex("\\(((?:18|19|20)\\d{2})\\)$").find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?:if(exactName)0 else Regex("(?:18|19|20)\\d{2}").find(name)?.value?.toIntOrNull()?:0
        val text=name.lowercase(java.util.Locale.ROOT).trim().trim('.','?','!')
        val ordinal=when(text){"first","first one","the first one","1st"->0;"second","second one","the second one","2nd"->1;"third","third one","the third one","last one","the last one","3rd"->2;else->-1}
        if(ordinal>=0)return group.getOrNull(ordinal)?.takeIf{kind==null||it.type.routeName==kind}?.let{listOf(it)}.orEmpty()
        if(text in setOf("","it","that","this","the movie","the film","the series","that title","this title","mentioned title","the mentioned title","the mentioned movie","that movie","that film","that series"))
            return visible.filter{kind==null||it.type.routeName==kind}
        val nameWithoutYear=if(exactName)name else name.replace(Regex("\\s*\\(?(?:18|19|20)\\d{2}\\)?$"),"")
        val matches=pool.filter{it.key==name||listOfNotNull(it.title,it.originalTitle).any{title->cardName(title)==cardName(nameWithoutYear)}}
            .filter{statedYear==0||it.year.toIntOrNull()==statedYear}
        // The active recommendation wins over an older same-name edition.
        return matches.take(1)
    }
    fun select(names: List<String>): List<TalkTitleCard> = names.flatMap{resolve(it)}.distinctBy { it.key }.take(3)
    fun snapshot()=org.json.JSONObject().put("focused",visible.singleOrNull()?.json())
        .put("visible",org.json.JSONArray(visible.map{it.json()})).put("recommendationGroup",org.json.JSONArray(group.map{it.json()}))
        .put("recent",org.json.JSONArray(known.take(24).map{it.json()}))
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
    fun topic(topic: String): TalkCardContext {
        val match = select(listOf(topic))
        return if (match.isNotEmpty()) show(match) else this
    }
    fun question(question: String): TalkCardContext {
        val text=question.lowercase(java.util.Locale.ROOT)
        val referenced=references(question)
        if(referenced.isNotEmpty())return show(referenced)
        if(Regex("\\b(?:change (?:the )?(?:subjects?|topics?)|different (?:subject|topic)|something (?:entirely )?else)\\b").containsMatchIn(text))return copy(visible=emptyList())
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
        if(named.isNotEmpty())return named.sortedBy{it.second.first}.map{it.first.title}.distinct().flatMap{resolve(it,year=explicitYear(question))}.distinctBy{it.key}.take(3)
        val ordinals=listOf("first|1st","second|2nd","third|3rd|last").flatMapIndexed{index,word->
            if(Regex("(?i)\\b(?:$word)(?: one| title| movie| film| series)?\\b").containsMatchIn(question))listOfNotNull(group.getOrNull(index))else emptyList()
        }
        if(ordinals.isNotEmpty())return ordinals
        if(explicitYear(question)==0&&Regex("(?i)\\b(?:it|its|this film|that film|this movie|that movie|this series|that series|that one)\\b").containsMatchIn(question))return listOfNotNull(visible.singleOrNull())
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
    known=lines.asReversed().flatMap{it.titles}.distinctBy{it.key}.take(24),
    group=(lines.lastOrNull{it.titles.size==3}?:lines.lastOrNull{it.titles.size>1})?.titles.orEmpty(),
).show(lines.lastOrNull{it.titles.isNotEmpty()}?.titles.orEmpty())

internal fun talkCanDismissCards(question:String,visible:List<TalkTitleCard>):Boolean {
    val text=question.lowercase(java.util.Locale.ROOT)
    if(Regex("\\b(?:hide|dismiss|remove|clear|stop showing|stop displaying|don't show|do not show)\\b.*\\bcards?\\b|إخفاء|اخف|verberge").containsMatchIn(text))return true
    if(Regex("\\b(?:show|display|bring|retrieve|get|open|see)\\b.*\\bcards?\\b|\\bcards? please\\b|(?:أظهر|اعرض|أرني).*بطاق|(?:zeig|zeige).*karte").containsMatchIn(text))return false
    if(Regex("\\b(?:change (?:the )?(?:subjects?|topics?)|different (?:subject|topic)|something (?:entirely )?else)\\b").containsMatchIn(text))return true
    if(visible.size==1&&(text.contains(visible.single().title.lowercase(java.util.Locale.ROOT))||Regex("\\b(?:it|its|this film|that film|this movie|that movie|this series|that series)\\b").containsMatchIn(text)))return false
    return true
}
