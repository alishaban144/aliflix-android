package com.aliflix.app.player

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Exact source identity includes signed URL, rendition rules and track identity. Conservative
 * misses are safer than reusing another encode's timing. Only digests leave the device.
 */
internal fun subtitleCorrectionKey(request: NativePlaybackRequest, contentKey: String, cues: String, audioTrack: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf("webrtc-fft-v2", contentKey, request.url, request.mimeType, request.referer, request.cookie,
        request.streamUrlRules, request.offlineDownloadId, audioTrack, cues).forEach {
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
        if (!offset.isFinite() || offset !in -120.0..120.0 || rate !in .95..1.05 || score !in .48..1.00001) null
        else AudioSubtitleCorrection(offset, rate, score)
    }.getOrNull()
    fun put(key: String, correction: AudioSubtitleCorrection?) {
        val json = JSONObject().put("reset", correction == null)
        correction?.let { json.put("offset", it.offset).put("rate", it.rate).put("confidence", it.confidence) }
        preferences.edit().putString(key, json.toString()).apply() // Reset is a synced tombstone.
    }
}
