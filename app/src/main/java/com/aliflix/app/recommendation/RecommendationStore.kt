package com.aliflix.app.recommendation

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
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

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun setAiModel(model: RecommendationAiModel) {
        _aiModel.value = model
        preferences.edit {
            putString(KEY_AI_MODEL, model.workerValue)
            remove(LEGACY_KEY_GEMINI_MODEL)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "aliflix_ai_recommendations"
        const val KEY_ENABLED = "enabled"
        const val KEY_AI_MODEL = "ai_model"
        const val LEGACY_KEY_GEMINI_MODEL = "gemini_model"
    }
}
