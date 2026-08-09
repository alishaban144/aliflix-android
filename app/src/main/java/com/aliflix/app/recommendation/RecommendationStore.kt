package com.aliflix.app.recommendation

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.aliflix.app.model.Media
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * An in-memory taste reducer with asynchronous persistence.
 *
 * Production supplies a main-owned scope. User actions publish their tiny
 * immutable snapshot immediately; JSON work and SharedPreferences access are
 * then queued away from the click handler.
 */
class RecommendationStore(
    context: Context,
    private val scope: CoroutineScope? = null,
    private val dispatchers: RecommendationDispatchers =
        RecommendationDispatchers.Default,
) {
    private val appContext = runCatching { context.applicationContext }
        .getOrNull()
        ?: context
    private val preferences: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val writeMutex = Mutex()
    private val tasteStateLock = Any()
    private val enabledVersion = AtomicLong()
    private val tasteVersion = AtomicLong()
    private var tasteHydrated = scope == null
    private var discardStoredTaste = false
    private val pendingTasteMutations =
        mutableListOf<(TasteProfile) -> TasteProfile>()

    // Fail closed until the persisted opt-in state is known. This prevents a
    // stored opt-out from being bypassed during the first few frames.
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _taste = MutableStateFlow(TasteProfile())
    val taste: StateFlow<TasteProfile> = _taste.asStateFlow()

    init {
        if (scope == null) {
            _enabled.value = preferences.getBoolean(KEY_ENABLED, true)
            _taste.value = loadTasteFromPreferences()
        } else {
            val initialEnabledVersion = enabledVersion.get()
            scope.launch {
                val stored = withContext(dispatchers.io) {
                    StoredTasteStrings(
                        enabled = preferences.getBoolean(KEY_ENABLED, true),
                        signals = preferences.getString(KEY_TASTE_SIGNALS, "[]") ?: "[]",
                        seenKeys = preferences.getString(KEY_SEEN_KEYS, "[]") ?: "[]",
                    )
                }
                val loadedTaste = withContext(dispatchers.computation) {
                    parseTaste(stored.signals, stored.seenKeys)
                }
                if (enabledVersion.get() == initialEnabledVersion) {
                    _enabled.value = stored.enabled
                }
                val pendingPersistence = synchronized(tasteStateLock) {
                    val pending = pendingTasteMutations.toList()
                    val base = if (discardStoredTaste) TasteProfile() else loadedTaste
                    val merged = boundTaste(
                        pending.fold(base) { profile, mutation -> mutation(profile) },
                    )
                    pendingTasteMutations.clear()
                    discardStoredTaste = false
                    tasteHydrated = true
                    _taste.value = merged
                    if (pending.isEmpty()) {
                        null
                    } else {
                        tasteVersion.incrementAndGet() to merged
                    }
                }
                pendingPersistence?.let { (version, merged) ->
                    persistAsync(version, taste = merged)
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        val version = enabledVersion.incrementAndGet()
        persistAsync(version, enabled = enabled)
    }

    fun markSeen(media: Media) {
        mutateTaste { profile ->
            profile.copy(seenKeys = profile.seenKeys + media.key)
        }
    }

    fun recordAccepted(media: Media) {
        mutateTaste { initial ->
            var profile = initial
            media.genres.forEach { genre ->
                profile = profile.withObservation(
                    "genre:${normalize(genre)}",
                    positive = true,
                )
            }
            val era = media.year.take(4).toIntOrNull()?.let { "${it / 10 * 10}s" }
            if (era != null) {
                profile = profile.withObservation("era:$era", positive = true)
            }
            profile.withObservation(
                "type:${media.type.routeName}",
                positive = true,
            )
        }
    }

    fun recordRejected(media: Media, reason: String?) {
        mutateTaste { initial ->
            var profile = initial
            media.genres.forEach { genre ->
                profile = profile.withObservation(
                    "genre:${normalize(genre)}",
                    positive = false,
                )
            }
            reason?.takeIf(String::isNotBlank)?.let {
                profile = profile.withObservation(
                    "rejection:${normalize(it)}",
                    positive = true,
                )
            }
            profile
        }
    }

    fun resetTaste() {
        val version = synchronized(tasteStateLock) {
            pendingTasteMutations.clear()
            if (!tasteHydrated) discardStoredTaste = true
            _taste.value = TasteProfile()
            tasteVersion.incrementAndGet()
        }
        val activeScope = scope
        if (activeScope == null) {
            preferences.edit {
                remove(KEY_TASTE_SIGNALS)
                remove(KEY_SEEN_KEYS)
            }
            return
        }
        activeScope.launch(dispatchers.io) {
            writeMutex.withLock {
                if (tasteVersion.get() != version) return@withLock
                preferences.edit {
                    remove(KEY_TASTE_SIGNALS)
                    remove(KEY_SEEN_KEYS)
                }
            }
        }
    }

    private fun mutateTaste(mutation: (TasteProfile) -> TasteProfile) {
        val pendingPersistence = synchronized(tasteStateLock) {
            val bounded = boundTaste(mutation(_taste.value))
            _taste.value = bounded
            val version = tasteVersion.incrementAndGet()
            if (tasteHydrated) {
                version to bounded
            } else {
                pendingTasteMutations += mutation
                null
            }
        }
        pendingPersistence?.let { (version, bounded) ->
            persistAsync(version, taste = bounded)
        }
    }

    private fun boundTaste(profile: TasteProfile): TasteProfile =
        profile.copy(
            signals = profile.signals.values
                .sortedByDescending(TasteSignal::updatedAtMillis)
                .take(MAX_SIGNALS)
                .associateBy(TasteSignal::key),
            // Sets preserve insertion order here; retain the newest feedback
            // instead of discarding every new key once the cap is reached.
            seenKeys = profile.seenKeys.toList()
                .takeLast(MAX_SEEN_KEYS)
                .toCollection(linkedSetOf()),
        )

    private fun persistAsync(
        version: Long,
        enabled: Boolean? = null,
        taste: TasteProfile? = null,
    ) {
        val activeScope = scope
        if (activeScope == null) {
            enabled?.let { preferences.edit { putBoolean(KEY_ENABLED, it) } }
            taste?.let(::persistTaste)
            return
        }
        activeScope.launch {
            val encodedTaste = taste?.let { profile ->
                withContext(dispatchers.computation) { encodeTaste(profile) }
            }
            withContext(dispatchers.io) {
                writeMutex.withLock {
                    if (enabled != null && enabledVersion.get() == version) {
                        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
                    }
                    if (encodedTaste != null && tasteVersion.get() == version) {
                        preferences.edit {
                            putString(KEY_TASTE_SIGNALS, encodedTaste.signals)
                            putString(KEY_SEEN_KEYS, encodedTaste.seenKeys)
                        }
                    }
                }
            }
        }
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

    private fun loadTasteFromPreferences(): TasteProfile = parseTaste(
        signals = preferences.getString(KEY_TASTE_SIGNALS, "[]") ?: "[]",
        seenKeys = preferences.getString(KEY_SEEN_KEYS, "[]") ?: "[]",
    )

    private fun parseTaste(signals: String, seenKeys: String): TasteProfile = runCatching {
        val signalsArray = JSONArray(signals)
        val parsedSignals = (0 until signalsArray.length())
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
        val seenArray = JSONArray(seenKeys)
        val parsedSeen = (0 until seenArray.length())
            .mapNotNull { seenArray.optString(it).takeIf(String::isNotBlank) }
            .toSet()
        TasteProfile(signals = parsedSignals, seenKeys = parsedSeen)
    }.getOrDefault(TasteProfile())

    private fun encodeTaste(profile: TasteProfile): EncodedTaste {
        val signals = JSONArray().apply {
            profile.signals.values.forEach { signal ->
                put(
                    JSONObject()
                        .put("key", signal.key)
                        .put("positive", signal.positiveObservations)
                        .put("negative", signal.negativeObservations)
                        .put("updatedAt", signal.updatedAtMillis),
                )
            }
        }
        val seen = JSONArray().apply { profile.seenKeys.forEach(::put) }
        return EncodedTaste(signals.toString(), seen.toString())
    }

    private fun persistTaste(profile: TasteProfile) {
        val encoded = encodeTaste(profile)
        preferences.edit {
            putString(KEY_TASTE_SIGNALS, encoded.signals)
            putString(KEY_SEEN_KEYS, encoded.seenKeys)
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private data class StoredTasteStrings(
        val enabled: Boolean,
        val signals: String,
        val seenKeys: String,
    )

    private data class EncodedTaste(
        val signals: String,
        val seenKeys: String,
    )

    private companion object {
        const val PREFERENCES_NAME = "aliflix_ai_recommendations"
        const val KEY_ENABLED = "enabled"
        const val KEY_TASTE_SIGNALS = "taste_signals"
        const val KEY_SEEN_KEYS = "seen_keys"
        const val MAX_SIGNALS = 80
        const val MAX_SEEN_KEYS = 250
    }
}
