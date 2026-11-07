package com.aliflix.app.talk

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Publish identities as they arrive and retain successful work when one title
 * times out. Catalogue queries are bounded independently of answer generation. */
internal suspend fun resolveTalkNamedCards(names:List<String>,budgetMillis:Long=7500,
    onResolved:(List<TalkTitleCard>)->Unit={},lookup:suspend(String)->List<TalkTitleCard>):List<TalkTitleCard> {
    val slots=arrayOfNulls<List<TalkTitleCard>>(names.size)
    val lock=Mutex();val gate=Semaphore(3)
    fun snapshot()=slots.filterNotNull().flatten().distinctBy{it.key}
    withTimeoutOrNull(budgetMillis){coroutineScope {
        names.mapIndexed { index,name -> async {
            val result=gate.withPermit {
                try{lookup(name)}catch(timeout:TimeoutCancellationException){currentCoroutineContext().ensureActive();emptyList()}
                catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){emptyList()}
            }
            lock.withLock { slots[index]=result;onResolved(snapshot()) }
        }}.awaitAll()
    }}
    return lock.withLock { snapshot() }
}

/** Keep title markup machine-readable in history while rendering normal elegant
 * emphasis, rather than exposing literal asterisks in the conversation. */
internal fun talkFormattedAnswer(text:String):androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    var end=0
    Regex("\\*{1,2}([^*\\n]+)\\*{1,2}").findAll(text).forEach { match ->
        append(text.substring(end,match.range.first))
        pushStyle(androidx.compose.ui.text.SpanStyle(fontStyle=androidx.compose.ui.text.font.FontStyle.Italic))
        append(match.groupValues[1]);pop();end=match.range.last+1
    }
    append(text.substring(end))
}

/** Candidates require a real catalogue identity before becoming a card. */
internal fun talkAnswerTitleNames(text:String,known:List<TalkTitleCard>):List<String> {
    val found=mutableListOf<Pair<Int,String>>()
    known.distinctBy{it.key}.forEach { card ->
        listOfNotNull(card.title,card.originalTitle).distinct().forEach { name ->
            Regex("(?<![\\p{L}\\p{N}])"+Regex.escape(name)+"(?![\\p{L}\\p{N}])",
                if(name.length<4)emptySet()else setOf(RegexOption.IGNORE_CASE)).find(text)?.let{found+=it.range.first to card.title}
        }
    }
    Regex("\\*{1,2}([^*\\n]{1,160})\\*{1,2}|[\"“]([^\"”\\n]{1,160})[\"”]").findAll(text).forEach { match ->
        val name=match.groupValues.drop(1).firstOrNull{it.isNotBlank()}.orEmpty().trim()
        if(name.any{it.isLetter()}&&!name.endsWith('?')&&!name.endsWith('!'))found+=match.range.first to name
    }
    Regex("(?:like|including|such as|try|watch|recommend) +((?:[A-Z][\\p{L}\\p{N}'’:-]*|(?:The|A|An))(?: +(?:[A-Z][\\p{L}\\p{N}'’:-]*|and|of|the|a|in|to)){1,8})").findAll(text).forEach{
        found+=it.groups[1]!!.range.first to it.groupValues[1].trim()
    }
    return found.sortedBy{it.first}.map{it.second}.distinctBy{it.lowercase(java.util.Locale.ROOT)}.take(24)
}

internal fun talkUsesGroundedCardReferences(names:List<String>,grounded:List<TalkTitleCard>,decision:Boolean):Boolean {
    if(grounded.isEmpty())return false
    if(decision)return names.size<=grounded.size
    fun normal(value:String)=value.lowercase(java.util.Locale.ROOT).filter{it.isLetterOrDigit()}
    val references=setOf("it","that","this","themovie","thefilm","theseries","thattitle","thistitle")
    return names.all { name -> normal(name) in references||grounded.any{card->
        name==card.key||listOfNotNull(card.title,card.originalTitle).any{normal(it)==normal(name)}
    } }
}
