package com.aliflix.app.talk

import com.aliflix.app.player.SubtitleCue
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

/** The viewer's whole episode transcript is available locally; the live model gets only the
 * question-relevant slice, always clipped at the current playhead. */
internal enum class TalkRecallMode { CONTEXT, PREVIOUS, TIMESTAMP, RECAP, SEARCH }

internal data class TalkRecallRequest(
    val mode: TalkRecallMode,
    val targetSeconds: Double? = null,
    val terms: List<String> = emptyList(),
    val targetBeyondAnchor: Boolean = false,
)

internal data class TalkCueScene(
    val startSeconds: Double,
    val endSeconds: Double,
    val cues: List<SubtitleCue>,
) {
    val text: String get() = cues.joinToString(" ") { it.text }
}

internal data class TalkRecallSelection(val role: String, val scene: TalkCueScene)

internal fun buildTalkCueScenes(cues: List<SubtitleCue>, throughSeconds: Double): List<TalkCueScene> {
    if (!throughSeconds.isFinite() || throughSeconds <= 0) return emptyList()
    val normalized = cues.asSequence()
        .filter { cue ->
            cue.startSeconds.isFinite() && cue.endSeconds.isFinite() &&
                cue.startSeconds >= 0 && cue.endSeconds >= cue.startSeconds &&
                cue.startSeconds < throughSeconds && cue.text.isNotBlank()
        }
        .map { cue ->
            val text = cue.text.trim().replace(Regex("\\s+"), " ").take(320)
            cue.copy(
                startSeconds = cue.startSeconds,
                endSeconds = minOf(cue.endSeconds.coerceAtLeast(cue.startSeconds), throughSeconds),
                text = text,
            )
        }
        .filter { it.text.isNotBlank() }
        .distinctBy { "${(it.startSeconds * 1000).toLong()}:${(it.endSeconds * 1000).toLong()}:${it.text}" }
        .sortedBy { it.startSeconds }
        .toList()
    if (normalized.isEmpty()) return emptyList()

    val scenes = mutableListOf<TalkCueScene>()
    var current = mutableListOf<SubtitleCue>()
    fun flush() {
        if (current.isEmpty()) return
        scenes += TalkCueScene(current.first().startSeconds, current.maxOf { it.endSeconds }, current.toList())
        current = mutableListOf()
    }
    for (cue in normalized) {
        val last = current.lastOrNull()
        if (last != null && (cue.startSeconds - last.endSeconds > 5.0 ||
                cue.startSeconds - current.first().startSeconds > 150.0)) flush()
        current += cue
    }
    flush()
    return scenes
}

private val recapWords = Regex(
    "(?i)(?:\\b(?:recap|catch me up|catch up|what happened so far|story so far|summari[sz]e|summary|from the beginning|since the beginning|whole episode|everything so far|bisher|zusammenfass\\w*)\\b|من البداية|ملخص|لخص)"
)
private val previousWords = Regex(
    "(?i)(?:\\b(?:previous scene|scene before|before this|preceding scene|last scene|earlier scene|just before|before that|what led (?:up )?to|how did we get here|what happened before|what happened earlier|vorherige szene|szene davor|davor)\\b|المشهد السابق|قبل هذا المشهد|قبل ذلك)"
)
private val searchWords = Regex(
    "(?i)(?:\\b(?:who|whose|what did|what does|what was|tell me about|remember|quote|exact line|said|say|mentioned|name|character|woman|man|boy|girl|child|father|mother|sister|brother|friend|grey|gray|hooded)\\b|(?:^|\\s)(?:هو|هي|من|قال|اسم|شخصية)(?:\\s|$)|ماذا قال)"
)

private val timestampPatterns = listOf(
    Regex("(?i)\\b(\\d{1,2}):(\\d{2}):(\\d{2})\\b"),
    Regex("(?i)\\b(\\d{1,3}):(\\d{2})\\b"),
    Regex("(?i)\\b(?:at|around|near|about|by)\\s+(\\d{1,3})[ .](\\d{2})\\b"),
    Regex("(?i)\\b(\\d{1,3})\\s*(?:minutes?|mins?|minuten)\\s+(?:(\\d{1,2})\\s*(?:seconds?|secs?|sekunden)\\s*)?(?:in|into|at|mark)?\\b"),
    Regex("(?i)\\b(?:at|around|near|about)\\s+(\\d{1,3})\\s*(?:minutes?|mins?|minuten)\\b"),
)

private val recallStopWords = setOf(
    "the", "and", "that", "this", "with", "from", "what", "when", "where", "which", "who", "whom",
    "whose", "why", "how", "did", "does", "was", "were", "is", "are", "has", "have", "had", "happened",
    "happen", "scene", "before", "after", "previous", "earlier", "later", "about", "tell", "explain",
    "please", "again", "just", "then", "there", "their", "they", "them", "she", "her", "him", "his",
    "you", "your", "can", "could", "would", "should", "for", "into", "onto", "over", "under", "at",
    "in", "on", "of", "to", "a", "an", "it", "its", "as", "by", "or", "not", "no", "yes", "say",
    "said", "says", "quote", "exact", "line", "moment", "time", "something", "anything", "please",
    "bitte", "und", "der", "die", "das", "was", "wer", "wie", "wo", "wann", "warum", "szene", "vorher",
    "المشهد", "ماذا", "حدث", "قبل", "هذا", "هذه", "الذي", "التي", "من", "في", "على", "هل", "كان", "قال",
)

private fun timestampSeconds(question: String): Double? {
    for (pattern in timestampPatterns) {
        val match = pattern.find(question) ?: continue
        val groups = match.groupValues
        val seconds = when (groups.size) {
            4 -> groups[1].toIntOrNull()?.times(3600)?.plus((groups[2].toIntOrNull() ?: 0) * 60)
                ?.plus(groups[3].toIntOrNull() ?: 0)?.toDouble()
            3 -> {
                val first = groups[1].toIntOrNull()
                val second = groups[2].toIntOrNull()
                if (first == null || second == null || second !in 0..59) null else (first * 60 + second).toDouble()
            }
            else -> null
        }
        if (seconds != null && seconds >= 0) return seconds
    }
    return null
}

internal fun parseTalkRecallRequest(question: String, anchorSeconds: Double): TalkRecallRequest {
    val clean = question.trim()
    val target = timestampSeconds(clean)
    if (target != null) {
        return TalkRecallRequest(
            mode = TalkRecallMode.TIMESTAMP,
            targetSeconds = target,
            targetBeyondAnchor = target > anchorSeconds + .5,
        )
    }
    if (recapWords.containsMatchIn(clean)) return TalkRecallRequest(TalkRecallMode.RECAP)
    if (previousWords.containsMatchIn(clean)) return TalkRecallRequest(TalkRecallMode.PREVIOUS)
    val terms = clean.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 && it !in recallStopWords }
        .distinct()
        .take(8)
    return if ((searchWords.containsMatchIn(clean) || Regex("(?i)\\b(why|how|reason|cause|earlier|beginning)\\b").containsMatchIn(clean)) && terms.isNotEmpty()) {
        TalkRecallRequest(TalkRecallMode.SEARCH, terms = terms)
    } else TalkRecallRequest(TalkRecallMode.CONTEXT)
}

internal fun talkEpisodeRecallQuestion(question: String): Boolean {
    val clean = question.trim()
    if (clean.isBlank()) return false
    return timestampSeconds(clean) != null || recapWords.containsMatchIn(clean) ||
        previousWords.containsMatchIn(clean) || searchWords.containsMatchIn(clean) ||
        Regex("(?i)\\b(why|how|reason|cause|earlier|beginning)\\b").containsMatchIn(clean)
}

private fun sceneWatchedFraction(scene: TalkCueScene, ranges: List<ClosedFloatingPointRange<Double>>): Double {
    val length = scene.endSeconds - scene.startSeconds
    if (length <= 0) return 0.0
    var covered = 0.0
    for (range in ranges) {
        val start = max(scene.startSeconds, range.start)
        val end = minOf(scene.endSeconds, range.endInclusive)
        if (end > start) covered += end - start
    }
    return (covered / length).coerceIn(0.0, 1.0)
}

private fun selectScenes(
    scenes: List<TalkCueScene>,
    request: TalkRecallRequest,
    anchorSeconds: Double,
): List<TalkRecallSelection> {
    if (scenes.isEmpty() || request.targetBeyondAnchor) return emptyList()
    val currentIndex = scenes.indexOfLast { it.startSeconds <= anchorSeconds }
    fun current(): TalkCueScene? = scenes.getOrNull(currentIndex)
    return when (request.mode) {
        TalkRecallMode.TIMESTAMP -> {
            val target = request.targetSeconds ?: return emptyList()
            val exact = scenes.indexOfFirst { target >= it.startSeconds - .25 && target <= it.endSeconds + .25 }
                .takeIf { it >= 0 } ?: scenes.indexOfLast { it.endSeconds <= target + .25 }
            if (exact < 0) emptyList()
            else buildList {
                scenes.getOrNull(exact - 1)?.let { add(TalkRecallSelection("setup", it)) }
                add(TalkRecallSelection("target", scenes[exact]))
            }
        }
        TalkRecallMode.PREVIOUS -> {
            val at = currentIndex
            if (at < 0) emptyList() else buildList {
                scenes.getOrNull(at - 2)?.let { add(TalkRecallSelection("earlier", it)) }
                scenes.getOrNull(at - 1)?.let { add(TalkRecallSelection("previous_candidate", it)) }
                // A pause may already show the next, silent shot. The latest dialogue
                // must remain a candidate rather than being mislabeled as the current scene.
                add(TalkRecallSelection("latest_exchange", scenes[at]))
            }
        }
        TalkRecallMode.RECAP -> recapTimeline(scenes).map { TalkRecallSelection("timeline", it) }
        TalkRecallMode.SEARCH -> {
            val scored = scenes.map { scene ->
                val text = scene.text.lowercase(Locale.ROOT)
                val score = request.terms.sumOf { term -> if (text.contains(term)) 1 else 0 }
                scene to score
            }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<TalkCueScene, Int>> { it.second }
                .thenBy { it.first.startSeconds }).take(5).sortedBy { it.first.startSeconds }
            if (scored.isEmpty()) listOfNotNull(current()?.let { TalkRecallSelection("current", it) })
            else scored.map { TalkRecallSelection("match", it.first) }
        }
        TalkRecallMode.CONTEXT -> buildList {
            current()?.let { add(TalkRecallSelection("current", it)) }
            val at = currentIndex
            scenes.getOrNull(at - 1)?.let { add(TalkRecallSelection("previous", it)) }
            scenes.getOrNull(at - 2)?.let { add(TalkRecallSelection("earlier", it)) }
        }
    }
}

/** A recap keeps broad episode coverage without flooding the live context. */
private fun recapTimeline(scenes: List<TalkCueScene>): List<TalkCueScene> {
    if (scenes.size <= 32) return scenes
    val step = scenes.size / 32.0
    return (0 until 32).map { scenes[(it * step).toInt().coerceAtMost(scenes.lastIndex)] }.distinct()
}

private fun excerpt(text: String, limit: Int = 700): String =
    if (text.length <= limit) text else text.take(limit / 2).trimEnd() + " … " + text.takeLast(limit / 2).trimStart()

internal fun buildTalkRecallPacket(
    query: String,
    request: TalkRecallRequest,
    anchorSeconds: Double,
    cues: List<SubtitleCue>,
    captionSource: String,
    captionEdition: String,
    watchedRanges: List<ClosedFloatingPointRange<Double>>,
    visualBeatSeconds: List<Double>,
    responseLanguage: String? = null,
    includeWholeStory: Boolean = false,
): JSONObject {
    val scenes = buildTalkCueScenes(cues, anchorSeconds)
    val selected = selectScenes(scenes, request, anchorSeconds)
    val storyCeiling = if (request.mode == TalkRecallMode.TIMESTAMP && !Regex("(?i)\\b(why|how|cause|reason)\\b").containsMatchIn(query))
        minOf(anchorSeconds, (request.targetSeconds ?: anchorSeconds) + 8.0) else anchorSeconds
    val focusedPrevious = !includeWholeStory && request.mode == TalkRecallMode.PREVIOUS && anchorSeconds > 600 &&
        Regex("(?i)previous scene|scene before|immediately before|just before|preceding scene").containsMatchIn(query)
    val focusedQuestion = !includeWholeStory && anchorSeconds > 600 &&
        request.mode in setOf(TalkRecallMode.SEARCH, TalkRecallMode.CONTEXT) &&
        scenes.sumOf { it.text.length } > 12_000 &&
        !Regex("(?i)\\b(opening|beginning|start of|first scene)\\b").containsMatchIn(query)
    val availableCues=scenes.flatMap { it.cues }
    val weightedTerms=request.terms.map { term -> term to (1.0+kotlin.math.ln((availableCues.size+1.0)/(availableCues.count { it.text.contains(term,true) }+1.0))) }
    val hits=if(focusedQuestion)availableCues.map { cue -> cue to weightedTerms.sumOf { (term,weight) -> if(cue.text.contains(term,true))weight else 0.0 } }
        .filter { it.second>0 }.sortedByDescending { it.second }.take(8).map { it.first } else emptyList()
    val storyScenes = if (focusedQuestion) buildTalkCueScenes(cues.filter { cue ->
        cue.endSeconds >= anchorSeconds - 300 || hits.any { hit ->
            cue.startSeconds <= hit.endSeconds + 25 && cue.endSeconds >= hit.startSeconds - 25
        }
    }, anchorSeconds)
        else if (focusedPrevious) buildTalkCueScenes(cues.filter { it.endSeconds >= anchorSeconds - 600 }, anchorSeconds)
        else if (storyCeiling == anchorSeconds) scenes else buildTalkCueScenes(cues, storyCeiling)
    val story = talkStoryTranscript(storyScenes)
        .put("requestedThroughSeconds", storyCeiling).put("availableThroughSeconds", anchorSeconds)
        .put("coverage", if (focusedQuestion) "question_context" else if (focusedPrevious) "recent_context" else "chronological_prefix")
        .put("fullPrefixAvailable", captionSource !in setOf("none", "observed_captions"))
        .put("fromSeconds", storyScenes.firstOrNull()?.startSeconds ?: 0.0)
    val suppliedCues=storyScenes.flatMap { it.cues }.take(story.getInt("includedCues")).toHashSet()
    fun supplied(scene:TalkCueScene)=request.mode!=TalkRecallMode.TIMESTAMP && (focusedQuestion || scene.cues.all(suppliedCues::contains))
    val latestDialogue = scenes.lastOrNull()
    val currentScene = latestDialogue?.takeIf { anchorSeconds <= it.endSeconds + 1.5 }
    val gaps = mutableListOf<String>()
    if (request.targetBeyondAnchor) gaps += "The requested timestamp is beyond the current playhead. Do not reveal it or answer as if it happened."
    if (scenes.isEmpty()) gaps += "No timed dialogue was found before the current playhead."
    if (selected.isEmpty() && scenes.isNotEmpty() && !request.targetBeyondAnchor)
        gaps += "No earlier or matching scene was found before the playhead; the current scene may be the first."
    if (captionSource == "observed_captions") gaps += "Only captions already shown during playback are available, not a complete episode transcript."
    if (captionSource == "none") gaps += "No timed transcript is available. Use only the current scene evidence."
    if (!story.getBoolean("complete")) gaps += "The story transcript exceeds this context page. Retrieve omitted earlier dialogue with scene_recall and its timestamp; do not treat it as absent from the film."
    val selectedWithNoVisual = selected.filter { choice ->
        visualBeatSeconds.none { it >= choice.scene.startSeconds - .5 && it <= choice.scene.endSeconds + .5 }
    }
    if (selectedWithNoVisual.isNotEmpty()) gaps += "No video frame was observed for earlier scenes; describe transcript-supported events without claiming visual detail."
    val selectedUnwatched = selected.filter { sceneWatchedFraction(it.scene, watchedRanges) < .35 }
    if (selectedUnwatched.isNotEmpty()) gaps += "Some requested scenes were skipped in this session. Evidence retrieved now can describe them; never claim the viewer watched them. Attached earlier video frames support visual facts and the transcript supports dialogue."

    val packet = JSONObject()
        .put("status", "recalled")
        .put("mode", request.mode.name.lowercase(Locale.ROOT))
        .put("query", query.take(1600))
        .put("anchorSeconds", anchorSeconds)
        .put("viewedThroughSeconds", anchorSeconds)
        .put("targetSeconds", request.targetSeconds ?: JSONObject.NULL)
        .put("targetBeyondAnchor", request.targetBeyondAnchor)
        .put("captionEdition", captionEdition)
        .put("captionSource", captionSource)
        .put("storyThroughAnchor", story)
        .put("timelineIndex",JSONArray(recapTimeline(scenes).map { scene -> JSONObject().put("startSeconds",scene.startSeconds).put("endSeconds",scene.endSeconds).put("dialogueHint",excerpt(scene.text,180)) }))
        .put("transcript", JSONObject()
            .put("cueCount", cues.size)
            .put("firstSeconds", cues.minOfOrNull { it.startSeconds } ?: JSONObject.NULL)
            .put("lastSeconds", cues.maxOfOrNull { it.endSeconds }?.let { minOf(it, anchorSeconds) } ?: JSONObject.NULL)
            .put("throughSeconds", anchorSeconds))
        .put("watchedRanges", JSONArray(watchedRanges.map { range ->
            JSONArray().put(range.start.coerceAtLeast(0.0)).put(range.endInclusive.coerceAtLeast(range.start))
        }))
        .put("currentScene", currentScene?.let { sceneJson(it, "current", request, watchedRanges, visualBeatSeconds, summaryOnly = supplied(it)) })
        .put("latestDialogue", latestDialogue?.let { sceneJson(it, "latest_exchange", request, watchedRanges, visualBeatSeconds, summaryOnly = supplied(it)) })
        .put("dialogueWindowsAreSceneBoundaries", false)
        .put("participantIdentities", "Unverified: these captions do not attribute speakers or bind character names to faces. Use roles/descriptions unless exact dialogue or separately grounded evidence identifies the participant. Never assign a name or spouse relationship from remembered cast/plot familiarity.")
        .put("scenes", JSONArray(selected.map { sceneJson(it.scene, it.role, request, watchedRanges, visualBeatSeconds, summaryOnly = supplied(it.scene)) }))
        .put("evidenceGaps", JSONArray(gaps))
        .put("responseLanguage", responseLanguage ?: JSONObject.NULL)
        .put("scope", scopeInstruction)
    return packet
}

/** Keep the chronological story, not only literal word matches. The conversational model
 * can resolve paraphrases, motives and cross-language questions against all earlier lines.
 * Long transcripts expose an explicit coverage limit; no fabricated summary fills a gap. */
internal fun talkStoryTranscript(scenes: List<TalkCueScene>, characterBudget: Int = 12_000): JSONObject {
    val cues = scenes.flatMap { it.cues }
    val rows = JSONArray()
    var characters = 0
    // Keep the opening when unusually long content needs another page. Detailed timestamp
    // recall remains independent of this budget and can retrieve any earlier window.
    for (cue in cues) {
        if (characters + cue.text.length > characterBudget) break
        rows.put(JSONArray().put(cue.startSeconds).put(cue.endSeconds).put(cue.text))
        characters += cue.text.length
    }
    return JSONObject().put("format", "[startSeconds,endSeconds,dialogue]")
        .put("dialogue", rows).put("includedCues", rows.length()).put("eligibleCues", cues.size)
        .put("complete", rows.length() == cues.size)
        .put("throughSeconds", if (rows.length() == 0) 0.0 else rows.getJSONArray(rows.length() - 1).getDouble(1))
}

private fun sceneJson(
    scene: TalkCueScene,
    role: String,
    request: TalkRecallRequest,
    watchedRanges: List<ClosedFloatingPointRange<Double>>,
    visualBeatSeconds: List<Double>,
    summaryOnly: Boolean = false,
): JSONObject {
    val beats = visualBeatSeconds.filter { it >= scene.startSeconds - .5 && it <= scene.endSeconds + .5 }
    val cues = JSONArray().apply {
        scene.cues.take(80).forEach { cue ->
            put(JSONObject().put("startSeconds", cue.startSeconds)
                .put("endSeconds", minOf(cue.endSeconds, scene.endSeconds))
                .put("text", cue.text.take(320)))
        }
    }
    return JSONObject()
        .put("role", role)
        .put("startSeconds", scene.startSeconds)
        .put("endSeconds", scene.endSeconds)
        .put("watchedFraction", sceneWatchedFraction(scene, watchedRanges))
        .put("visualFrameSeconds", JSONArray(beats.take(4)))
        .apply {
            if (summaryOnly || request.mode == TalkRecallMode.RECAP) put("excerpt", excerpt(scene.text))
            else put("dialogue", cues)
        }
}

private const val scopeInstruction =
    "Evidence is limited to this episode/movie through anchorSeconds. Use only these exact captions and observed frames. " +
        "Never use later scenes, endings, hidden identities, future episodes or remembered plot. Earlier scenes from the beginning through this playhead are allowed, including scenes skipped by seeking. " +
        "storyThroughAnchor supplies chronological earlier dialogue even when the question uses different words or another language. Selected scenes are retrieval hints, not the limit of the available story. Connect supported earlier causes to current actions, and distinguish evidence from interpretation. " +
        "For a previous-scene or timestamp question, explain the supported action, who was involved, and its immediate significance. " +
        "Do not list objects or give a generic synopsis. If a visual detail was not observed, say the answer is based on the transcript. " +
        "Answer in the viewer's latest question language and do not switch because the film audio or captions use another language."
