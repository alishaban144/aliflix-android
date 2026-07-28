package com.aliflix.app.recommendation

import android.content.Context
import androidx.core.content.edit
import com.aliflix.app.model.Media
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class RecommendationStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "aliflix_ai_recommendations",
        Context.MODE_PRIVATE,
    )

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _taste = MutableStateFlow(loadTaste())
    val taste: StateFlow<TasteProfile> = _taste.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        _enabled.value = enabled
    }

    fun markSeen(media: Media) {
        val profile = _taste.value.copy(seenKeys = _taste.value.seenKeys + media.key)
        saveTaste(profile)
    }

    fun recordAccepted(media: Media) {
        var profile = _taste.value
        media.genres.forEach { genre ->
            profile = profile.withObservation("genre:${normalize(genre)}", positive = true)
        }
        val era = media.year.take(4).toIntOrNull()?.let { "${it / 10 * 10}s" }
        if (era != null) profile = profile.withObservation("era:$era", positive = true)
        profile = profile.withObservation(
            "type:${media.type.routeName}",
            positive = true,
        )
        saveTaste(profile)
    }

    fun recordRejected(media: Media, reason: String?) {
        var profile = _taste.value
        media.genres.forEach { genre ->
            profile = profile.withObservation("genre:${normalize(genre)}", positive = false)
        }
        reason?.takeIf(String::isNotBlank)?.let {
            profile = profile.withObservation("rejection:${normalize(it)}", positive = true)
        }
        saveTaste(profile)
    }

    fun resetTaste() {
        preferences.edit {
            remove(KEY_TASTE_SIGNALS)
            remove(KEY_SEEN_KEYS)
        }
        _taste.value = TasteProfile()
    }

    private fun TasteProfile.withObservation(
        key: String,
        positive: Boolean,
    ): TasteProfile {
        val current = signals[key] ?: TasteSignal(key)
        val updated = current.copy(
            positiveObservations = current.positiveObservations + if (positive) 1 else 0,
            negativeObservations = current.negativeObservations + if (positive) 0 else 1,
            updatedAtMillis = System.currentTimeMillis(),
        )
        return copy(signals = signals + (key to updated))
    }

    private fun loadTaste(): TasteProfile = runCatching {
        val signalsArray = JSONArray(preferences.getString(KEY_TASTE_SIGNALS, "[]"))
        val signals = (0 until signalsArray.length())
            .mapNotNull(signalsArray::optJSONObject)
            .mapNotNull { json ->
                val key = json.optString("key").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                TasteSignal(
                    key = key,
                    positiveObservations = json.optInt("positive"),
                    negativeObservations = json.optInt("negative"),
                    updatedAtMillis = json.optLong("updatedAt"),
                )
            }
            .associateBy(TasteSignal::key)
        val seenArray = JSONArray(preferences.getString(KEY_SEEN_KEYS, "[]"))
        val seen = (0 until seenArray.length())
            .mapNotNull { seenArray.optString(it).takeIf(String::isNotBlank) }
            .toSet()
        TasteProfile(signals = signals, seenKeys = seen)
    }.getOrDefault(TasteProfile())

    private fun saveTaste(profile: TasteProfile) {
        val signals = JSONArray().apply {
            profile.signals.values
                .sortedByDescending(TasteSignal::updatedAtMillis)
                .take(MAX_SIGNALS)
                .forEach { signal ->
                    put(
                        JSONObject()
                            .put("key", signal.key)
                            .put("positive", signal.positiveObservations)
                            .put("negative", signal.negativeObservations)
                            .put("updatedAt", signal.updatedAtMillis),
                    )
                }
        }
        val seen = JSONArray().apply {
            profile.seenKeys.take(MAX_SEEN_KEYS).forEach(::put)
        }
        preferences.edit {
            putString(KEY_TASTE_SIGNALS, signals.toString())
            putString(KEY_SEEN_KEYS, seen.toString())
        }
        _taste.value = profile.copy(
            signals = profile.signals.values
                .sortedByDescending(TasteSignal::updatedAtMillis)
                .take(MAX_SIGNALS)
                .associateBy(TasteSignal::key),
            seenKeys = profile.seenKeys.take(MAX_SEEN_KEYS).toSet(),
        )
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_TASTE_SIGNALS = "taste_signals"
        const val KEY_SEEN_KEYS = "seen_keys"
        const val MAX_SIGNALS = 80
        const val MAX_SEEN_KEYS = 250
    }
}
