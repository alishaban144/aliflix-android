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

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    private companion object {
        const val PREFERENCES_NAME = "aliflix_ai_recommendations"
        const val KEY_ENABLED = "enabled"
    }
}
