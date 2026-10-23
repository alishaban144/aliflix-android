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
        return if (match.isNotEmpty()) show(match) else copy(visible = emptyList())
    }
    fun question(question: String): TalkCardContext {
        val text=question.lowercase(java.util.Locale.ROOT)
        val named=known.filter { card ->
            val title=card.title.lowercase(java.util.Locale.ROOT)
            title.length>=4&&Regex("(?<![\\p{L}\\p{N}])"+Regex.escape(title)+"(?![\\p{L}\\p{N}])").containsMatchIn(text)
        }
        if(named.isNotEmpty())return show(named)
        if(Regex("\\b(?:change (?:the )?(?:subject|subjects|topic)|different (?:subject|topic)|something (?:entirely )?else)\\b").containsMatchIn(text))return copy(visible=emptyList())
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
