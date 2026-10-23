package com.aliflix.app.talk

/** Visual context belongs to the conversation, not the lifetime of a reply. */
internal data class TalkCardContext(
    val visible: List<TalkTitleCard> = emptyList(),
    val known: List<TalkTitleCard> = emptyList(),
    val group: List<TalkTitleCard> = emptyList(),
) {
    fun show(cards: List<TalkTitleCard>): TalkCardContext {
        if (cards.isEmpty()) return this
        return copy(visible = cards.distinctBy { it.key }.take(3),
            known = (cards + known).distinctBy { it.key }.take(24),
            group = if(cards.size>1)cards.take(3) else group)
    }
    fun select(names: List<String>): List<TalkTitleCard> = names.mapNotNull { name ->
        known.firstOrNull { it.key == name || cardName(it.title) == cardName(name) }
    }.distinctBy { it.key }.take(3)
    fun topic(topic: String): TalkCardContext {
        val match = select(listOf(topic))
        return if (match.isNotEmpty()) show(match) else this
    }
    fun question(question: String): TalkCardContext {
        val text=question.lowercase(java.util.Locale.ROOT)
        val named=known.filter { card ->
            val title=card.title.lowercase(java.util.Locale.ROOT)
            title.length>=4&&Regex("(?<![\\p{L}\\p{N}])"+Regex.escape(title)+"(?![\\p{L}\\p{N}])").containsMatchIn(text)
        }
        if(named.isNotEmpty())return show(named)
        if(Regex("\\b(?:change (?:the )?(?:subjects?|topics?)|different (?:subject|topic)|something (?:entirely )?else)\\b").containsMatchIn(text))return copy(visible=emptyList())
        val ordinal=when {
            Regex("\\b(?:first|1st)(?: one| title| movie| film| series)?\\b").containsMatchIn(text)->0
            Regex("\\b(?:second|2nd)(?: one| title| movie| film| series)?\\b").containsMatchIn(text)->1
            Regex("\\b(?:third|3rd|last)(?: one| title| movie| film| series)\\b").containsMatchIn(text)->2
            else->null
        }
        return group.getOrNull(ordinal?:-1)?.let{show(listOf(it))}?:this
    }
}
private fun cardName(value: String) = value.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

internal fun talkCanDismissCards(question:String,visible:List<TalkTitleCard>):Boolean {
    val text=question.lowercase(java.util.Locale.ROOT)
    if(Regex("\\b(?:hide|dismiss|remove|clear|stop showing|stop displaying|don't show|do not show)\\b.*\\bcards?\\b|إخفاء|اخف|verberge").containsMatchIn(text))return true
    if(Regex("\\b(?:show|display|bring|retrieve|get|open|see)\\b.*\\bcards?\\b|\\bcards? please\\b|(?:أظهر|اعرض|أرني).*بطاق|(?:zeig|zeige).*karte").containsMatchIn(text))return false
    if(Regex("\\b(?:change (?:the )?(?:subjects?|topics?)|different (?:subject|topic)|something (?:entirely )?else)\\b").containsMatchIn(text))return true
    if(visible.size==1&&(text.contains(visible.single().title.lowercase(java.util.Locale.ROOT))||Regex("\\b(?:it|its|this film|that film|this movie|that movie|this series|that series)\\b").containsMatchIn(text)))return false
    return true
}
