package com.aliflix.app.talk

/** Recommendation audio is a small atomic presentation. Validate its actual
 * output transcription before any PCM or transcript reaches the viewer. Ordinary
 * conversation and the search bridge continue to stream normally. */
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

internal fun talkResearchBridge(question:String,language:String?):String {
    val subject=question.replace(Regex("(?i)\\b(?:not|no|avoid|excluding|without)\\b[^.!?;]*")," ")
    return when(language?.substringBefore('-')) {
    "ar"->"أبحث لك عما يناسب ما تريده."
    "de"->"Ich suche etwas, das zu deiner Stimmung passt."
    "fr"->"Je cherche ce qui correspond à ton envie."
    "es"->"Estoy buscando algo que encaje con lo que quieres."
    else->when {
        Regex("(?i)sci.?fi|science[- ]fiction").containsMatchIn(subject)->"I'm looking for science fiction with ideas that stay with you."
        Regex("(?i)gentlem|elegan|stylish").containsMatchIn(subject)->"I'm looking for charm, style, and memorable characters."
        Regex("(?i)thrill|mystery|detective").containsMatchIn(subject)->"I'm looking for atmosphere, suspense, and a mystery worth following."
        Regex("(?i)comed(?:y|ies)|funny|laugh").containsMatchIn(subject)->"I'm looking for sharp comedy with a personality of its own."
        else->"I'm looking for a good fit for what you're in the mood for."
    }
}
}
internal fun talkIsApprovedBridge(text:String,approved:String):Boolean {
    fun normalize(value:String)=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKD).lowercase(java.util.Locale.ROOT).filter{it.isLetterOrDigit()}
    return normalize(text)==normalize(approved)
}
internal fun talkSpeechMatchesScript(text:String,script:String)=talkIsApprovedBridge(text,script)

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
