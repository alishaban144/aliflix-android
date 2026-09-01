package com.aliflix.app.recommendation

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists the live Ask Aliflix visibility preference. */
class RecommendationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _aiModel = MutableStateFlow(
        RecommendationAiModel.fromWorkerValue(
            preferences.getString(KEY_AI_MODEL, null)
                ?: preferences.getString(LEGACY_KEY_GEMINI_MODEL, null),
        ),
    )
    val aiModel: StateFlow<RecommendationAiModel> = _aiModel.asStateFlow()
    private val _updatedAtMillis = MutableStateFlow(
        preferences.getLong(KEY_UPDATED_AT_MILLIS, 0L),
    )
    val updatedAtMillis: StateFlow<Long> = _updatedAtMillis.asStateFlow()
    private val _mutations = MutableSharedFlow<Unit>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val mutations: SharedFlow<Unit> = _mutations

    val hasExplicitValues: Boolean
        get() = _updatedAtMillis.value > 0L ||
            preferences.contains(KEY_ENABLED) ||
            preferences.contains(KEY_AI_MODEL) ||
            preferences.contains(LEGACY_KEY_GEMINI_MODEL)

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        recordLocalChange()
    }

    fun setAiModel(model: RecommendationAiModel) {
        _aiModel.value = model
        preferences.edit {
            putString(KEY_AI_MODEL, model.workerValue)
            remove(LEGACY_KEY_GEMINI_MODEL)
        }
        recordLocalChange()
    }

    /** Applies cloud/account-scope settings without creating a write-back loop. */
    fun applySyncedSettings(
        enabled: Boolean,
        model: RecommendationAiModel,
        updatedAtMillis: Long,
    ) {
        _enabled.value = enabled
        _aiModel.value = model
        _updatedAtMillis.value = updatedAtMillis.coerceAtLeast(0L)
        preferences.edit {
            putBoolean(KEY_ENABLED, enabled)
            putString(KEY_AI_MODEL, model.workerValue)
            putLong(KEY_UPDATED_AT_MILLIS, _updatedAtMillis.value)
            remove(LEGACY_KEY_GEMINI_MODEL)
        }
    }

    private fun recordLocalChange() {
        val now = System.currentTimeMillis()
        _updatedAtMillis.value = now
        preferences.edit { putLong(KEY_UPDATED_AT_MILLIS, now) }
        _mutations.tryEmit(Unit)
    }

    private companion object {
        const val PREFERENCES_NAME = "aliflix_ai_recommendations"
        const val KEY_ENABLED = "enabled"
        const val KEY_AI_MODEL = "ai_model"
        const val LEGACY_KEY_GEMINI_MODEL = "gemini_model"
        const val KEY_UPDATED_AT_MILLIS = "updated_at_millis"
    }
}
