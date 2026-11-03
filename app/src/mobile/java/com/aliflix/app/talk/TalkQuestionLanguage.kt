package com.aliflix.app.talk

import java.text.Normalizer
import java.util.Locale

/** The newest question owns the answer language. An explicit language request wins;
 * otherwise high-confidence detection may switch, and an uncertain token keeps the
 * language already established in this conversation. */
internal fun chooseQuestionResponseLanguage(current: String?, question: String): String? =
    explicitRequestedTalkLanguage(question) ?: detectTalkQuestionLanguage(question) ?: current

/** The language of the viewer's own question, not the film's audio or subtitle language.
 * Only high-confidence evidence may change the response language; a title, a name or a
 * single borrowed word leaves the established language intact. */
internal fun detectTalkQuestionLanguage(text: String): String? {
    val clean = text.trim()
    if (clean.isBlank()) return null
    scriptTalkLanguage(clean)?.let { return it }
    val normalized = Normalizer.normalize(clean.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    val tokens = normalized.split(Regex("[^a-z]+")).filter { it.length >= 2 }.toSet()
    if (tokens.isEmpty()) return null
    val scores = talkLatinHints.mapValues { (_, words) -> tokens.count { it in words } }
        .toMutableMap()
    for ((language, markers) in talkLanguageMarkers) {
        if (markers.any { clean.contains(it, ignoreCase = true) }) scores[language] = (scores[language] ?: 0) + 1
    }
    val ranked = scores.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    val best = ranked.firstOrNull() ?: return null
    val second = ranked.getOrNull(1)?.value ?: 0
    if (best.value >= 2 && best.value > second) return best.key
    if (best.value == 1 && second == 0) {
        val word = tokens.singleOrNull() ?: tokens.firstOrNull { it in talkStrongSingleWords[best.key].orEmpty() }
        if (word != null && word in talkStrongSingleWords[best.key].orEmpty() &&
            talkLatinHints.count { (_, words) -> word in words } == 1) return best.key
    }
    return null
}

private fun scriptTalkLanguage(value: String): String? {
    val letters = value.filter { it.isLetter() }
    if (letters.length < 2) return null
    fun count(predicate: (Char) -> Boolean) = letters.count(predicate)
    fun dominant(amount: Int) = amount * 100 >= letters.length * 55
    val arabic = count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' }
    if (dominant(arabic)) {
        val persian = letters.any { it in "پچژگکی" }
        return if (persian) "fa" else "ar"
    }
    if (dominant(count { it in '\u0590'..'\u05FF' })) return "he"
    if (dominant(count { it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF' })) return "el"
    if (dominant(count { it in '\u0400'..'\u04FF' })) {
        return if (letters.any { it in "іїєґІЇЄҐ" }) "uk" else "ru"
    }
    val hangul = count { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' }
    if (dominant(hangul)) return "ko"
    val kana = count { it in '\u3040'..'\u30FF' }
    val han = count { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }
    if (kana > 0 && (kana + han) * 100 >= letters.length * 55) return "ja"
    if (dominant(han)) return "zh"
    if (dominant(count { it in '\u0900'..'\u097F' })) return "hi"
    if (dominant(count { it in '\u0980'..'\u09FF' })) return "bn"
    if (dominant(count { it in '\u0B80'..'\u0BFF' })) return "ta"
    if (dominant(count { it in '\u0C00'..'\u0C7F' })) return "te"
    if (dominant(count { it in '\u0C80'..'\u0CFF' })) return "kn"
    if (dominant(count { it in '\u0D00'..'\u0D7F' })) return "ml"
    if (dominant(count { it in '\u0A80'..'\u0AFF' })) return "gu"
    if (dominant(count { it in '\u0A00'..'\u0A7F' })) return "pa"
    if (dominant(count { it in '\u0D80'..'\u0DFF' })) return "si"
    if (dominant(count { it in '\u0E00'..'\u0E7F' })) return "th"
    if (dominant(count { it in '\u1000'..'\u109F' })) return "my"
    if (dominant(count { it in '\u1780'..'\u17FF' })) return "km"
    if (dominant(count { it in '\u0E80'..'\u0EFF' })) return "lo"
    if (dominant(count { it in '\u10A0'..'\u10FF' })) return "ka"
    if (dominant(count { it in '\u0530'..'\u058F' })) return "hy"
    if (dominant(count { it in '\u1200'..'\u137F' })) return "am"
    return null
}

private fun words(value: String) = value.split(' ').filter { it.isNotBlank() }.toSet()

/** Function words are deliberately distinctive; generic nouns such as "scene" are omitted
 * where several languages share the spelling. Scores are token counts, not guesses. */
private val talkLatinHints: Map<String, Set<String>> = mapOf(
    "en" to words(
        "the are was were what when where which who whom whose why how happened happen happening did does do " +
            "this that these those and but or not you your we our they their he she it its about before after " +
            "previous earlier explain tell me please can could would should from with for into onto say said " +
            "mean just again because while during scene"
    ),
    "de" to words(
        "der die das den dem des ein eine einen einem einer und ist sind war waren wird wurden wurde was wer wie " +
            "wo wann warum passiert passierte geschehen geschehen szene vorher vorherige vorherigen davor bitte " +
            "erklare erklar mir ich du dich dir nicht kein keine mit von fur auf zu im am gerade jetzt noch auch " +
            "aber oder dass wenn dann dieser diese dieses letzte letzten vorhin erzahl"
    ),
    "es" to words(
        "el la los las un una unos unas de del al que es fue era fueron paso pasaron escena anterior antes " +
            "despues por para con en y o pero como quién quien donde cuándo cuando porque explica dime favor " +
            "esta estan hay habia habian justo ahora sucede sucedio"
    ),
    "fr" to words(
        "le la les un une des du de que qui quoi est etait etaient passe passee passees scene precedente " +
            "precedent avant apres pour avec dans sur et ou mais comment pourquoi ou quand explique dis moi je " +
            "tu il elle nous vous ils elles ne pas ce cette ces cest sest vient juste arrive arriver"
    ),
    "it" to words(
        "il lo la gli le un una di che cosa e era erano successo scena precedente prima dopo per con in su e " +
            "o ma come perche dove quando spiega dimmi mi io tu lui lei noi voi non questo questa si sono stato " +
            "stata appena adesso"
    ),
    "pt" to words(
        "o a os as um uma uns umas de do da dos das que e era eram aconteceu acontecer cena anterior antes " +
            "depois por para com em no na e ou mas como porque onde quando explica diga me eu voce ele ela nao " +
            "isso este esta muito tambem agora"
    ),
    "nl" to words(
        "de het een en van dat wat is was waren gebeurt gebeurde scène scene vorige vorig voor na met in op " +
            "maar hoe waarom waar wanneer leg uit mij ik jij hij zij niet geen dit deze nog ook"
    ),
    "tr" to words(
        "bir ve bu ne oldu olan sahne onceki once sonra icin ile de da mi mu nasil neden nerede zaman anlat " +
            "bana ben sen o degil evet hayir simdi"
    ),
    "pl" to words(
        "i w na z ze co sie jest bylo stalo scena poprzednia poprzedniej przed po dla jak dlaczego gdzie " +
            "kiedy wyjasnij mi ja ty on ona nie tak to ten ta teraz"
    ),
    "ro" to words(
        "si in cu pe un o care ce este era fost sa se scena anterioara inainte dupa pentru cum de ce unde cand " +
            "explica spune imi eu tu el ea nu da acum"
    ),
)

/** A single token is enough only when it is a question word that belongs to exactly one
 * language in the table above. This makes "Why?" English without making "Dark" anything. */
private val talkStrongSingleWords: Map<String, Set<String>> = mapOf(
    "en" to words("what why how when where who happened happen does did explain tell"),
    "de" to words("warum wer wie wo wann passiert geschehen erklare bitte nicht szene vorherige"),
    "es" to words("que como donde cuando paso porque explica escena anterior"),
    "fr" to words("pourquoi comment passe scene precedente explique"),
    "it" to words("perche cosa successo scena spiega dimmi"),
    "pt" to words("aconteceu porque explique onde"),
    "nl" to words("gebeurde waarom gebeurt vorige"),
    "tr" to words("oldu neden nasil anlat onceki"),
    "pl" to words("stalo dlaczego wyjasnij poprzednia"),
    "ro" to words("explica spune anterioara"),
)

/** Diacritics that carry a language signal after the base text has been folded. */
private val talkLanguageMarkers: Map<String, String> = mapOf(
    "es" to "¿¡ñ",
    "de" to "ß",
    "fr" to "œ",
    "pt" to "ãõ",
    "tr" to "ışğ",
    "pl" to "łąężźćńś",
    "ro" to "ăâîșț",
)
