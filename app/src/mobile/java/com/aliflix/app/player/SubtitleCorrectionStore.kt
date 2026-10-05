package com.aliflix.app.player

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.net.URI

// v4 includes all-known-scene and early-drift verification. Never replay a
// correction accepted by v3's weaker confidence gates after upgrading.
internal const val SUBTITLE_CORRECTION_SCHEMA = 4

private val transientSyncField = Regex("(?i)^(headers|token|.*token.*|auth|authorization|cookie|expires?|exp|signature|sig|policy|key-pair-id|hdnea|hdnts|x-amz-.*|x-goog-.*)$")

internal fun audioSyncFingerprint(format: androidx.media3.common.Format, mergedExternalText: Boolean = false): String = format.let { f ->
    // MergingMediaPeriod prefixes every child Format ID with the source index.
    // External captions add that wrapper to audible playback, whereas the
    // independent download scanner reads the original audio-only media item.
    val child = f.id.orEmpty().let { if (mergedExternalText) it.replaceFirst(Regex("^\\d+:"), "") else it }
    val id = child.let { if (it.startsWith("http://") || it.startsWith("https://")) stableSyncUrl(it) else it }
    "$id|${f.language}|${f.codecs}|${f.sampleRate}|${f.channelCount}|${f.label}"
}

/** Keep asset/rendition identifiers; discard authentication and URL expiry. */
internal fun stableSyncUrl(url: String): String = runCatching {
    val uri = URI(url)
    val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() && !transientSyncField.matches(
        java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8")) }.map { parameter ->
        val value = java.net.URLDecoder.decode(parameter.substringAfter('=', ""), "UTF-8")
        if (value.startsWith("http://") || value.startsWith("https://"))
            parameter.substringBefore('=') + "=" + java.net.URLEncoder.encode(stableSyncUrl(value), "UTF-8") else parameter
    }.sorted().joinToString("&")
    buildString {
        uri.scheme?.let { append(it.lowercase()); append(':') }
        uri.rawAuthority?.let { append("//"); append(it.lowercase()) }
        append(uri.rawPath.orEmpty())
        if (query.isNotEmpty()) { append('?'); append(query) }
    }
}.getOrDefault(url.substringBefore('?').substringBefore('#'))

internal fun stableSyncRules(rules: String): String = runCatching {
    val json = JSONObject(rules.ifBlank { "{}" })
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().filterNot(transientSyncField::matches).sorted()
            .joinToString("|", "{", "}") { key -> "$key=${canonical(value.opt(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> if (value.startsWith("http://") || value.startsWith("https://")) stableSyncUrl(value) else value
        else -> value.toString()
    }
    canonical(json)
}.getOrDefault("")

/** Only identity digests and timing transforms are account-synchronised. */
internal fun subtitleCorrectionKey(request: NativePlaybackRequest, contentKey: String, cues: String, audioTrack: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf("adaptive-speech-v$SUBTITLE_CORRECTION_SCHEMA", contentKey, stableSyncUrl(request.url), request.mimeType, stableSyncUrl(request.referer),
        stableSyncRules(request.streamUrlRules), request.preferredVideoWidth.toString(), request.preferredVideoHeight.toString(),
        runCatching { JSONObject(request.selectionJson).let { "${it.optString("provider")}|${stableSyncUrl(it.optString("baseUrl"))}" } }.getOrDefault(""),
        audioTrack, cues).forEach {
        val bytes = it.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal class SubtitleCorrectionStore(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("subtitle-audio-corrections", Context.MODE_PRIVATE)
    fun get(key: String): AudioSubtitleCorrection? = runCatching {
        val json = JSONObject(preferences.getString(key, null) ?: return null)
        if (json.optBoolean("reset")) return null
        val offset = json.getDouble("offset"); val rate = json.getDouble("rate"); val score = json.getDouble("confidence")
        val array = json.optJSONArray("regions") ?: JSONArray()
        val regions = (0 until array.length()).map { i -> array.getJSONArray(i).let { TimingRegion(it.getInt(0), it.getDouble(1)) } }
        if (json.optInt("schema") != SUBTITLE_CORRECTION_SCHEMA || !offset.isFinite() || offset !in -600.0..600.0 || rate !in .9..1.1 || score !in .46..1.00001 ||
            regions.size > 8 || regions.any { it.firstCue < 0 || !it.offset.isFinite() || it.offset !in -600.0..600.0 } ||
            regions.zipWithNext().any { it.first.firstCue >= it.second.firstCue }) null
        else AudioSubtitleCorrection(offset, rate, score, regions, json.optString("model", "offset"))
    }.getOrNull()
    fun put(key: String, correction: AudioSubtitleCorrection?) {
        val json = JSONObject().put("schema", SUBTITLE_CORRECTION_SCHEMA).put("reset", correction == null)
        correction?.let { json.put("offset", it.offset).put("rate", it.rate).put("confidence", it.confidence).put("model", it.model)
            .put("regions", JSONArray().apply { it.regions.forEach { put(JSONArray().put(it.firstCue).put(it.offset)) } }) }
        preferences.edit().putString(key, json.toString()).apply() // Reset is a synced tombstone.
    }
}
