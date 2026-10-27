package com.aliflix.app.talk

/** Uncommitted recommendation audio stays bounded and private. Approved
 * version-nine narration streams immediately after its identity validation. */
internal class TalkRecommendationDelivery {
    private val chunks = mutableListOf<ByteArray>()
    private var bytes = 0
    fun hold(pcm: ByteArray) {
        check(bytes + pcm.size <= 4_800_000) { "Recommendation audio exceeded its bound" }
        chunks.add(pcm)
        bytes += pcm.size
    }
    fun drain(): List<ByteArray> = chunks.toList().also { clear() }
    fun clear() { chunks.clear(); bytes = 0 }
}

internal fun talkSpeechMatchesScript(text:String,approved:String):Boolean {
    fun normalize(value:String)=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKD).lowercase(java.util.Locale.ROOT).filter{it.isLetterOrDigit()}
    return normalize(text)==normalize(approved)
}

internal fun talkAnswerNamesAllTitles(text: String, titles: List<TalkTitleCard>, otherTitles: List<TalkTitleCard> = emptyList()): Boolean {
    fun normalized(value: String) = " " + java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
        .lowercase(java.util.Locale.ROOT).replace(Regex("\\p{M}"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " "
    val answer = normalized(text)
    fun names(card: TalkTitleCard) = listOfNotNull(card.title, card.originalTitle).any { title ->
        val name = normalized(title)
        answer.contains(name) || answer.contains(normalized(title.replace(Regex("^(The|A|An) ", RegexOption.IGNORE_CASE), "")))
    }
    return titles.isNotEmpty() && titles.all(::names) && otherTitles
        .filter { other -> titles.none { it.key == other.key || normalized(it.title) == normalized(other.title) } }
        .none(::names)
}
