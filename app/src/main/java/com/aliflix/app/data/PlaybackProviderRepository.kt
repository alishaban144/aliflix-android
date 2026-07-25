package com.aliflix.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.aliflix.app.model.PlaybackPreferences
import com.aliflix.app.model.PlaybackProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlaybackProviderRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val _preferences = MutableStateFlow(loadPreferences())
    val preferences: StateFlow<PlaybackPreferences> = _preferences.asStateFlow()

    fun selectGeneralProvider(provider: PlaybackProviderId) {
        if (!provider.supportsGeneralPlayback) return
        prefs.edit { putString(KEY_GENERAL_PROVIDER_ID, provider.name) }
        _preferences.value = _preferences.value.copy(generalProvider = provider)
    }

    fun updateUrl(newUrl: String) {
        val normalized = RamoflixConfig.normalizeBaseUrl(newUrl) ?: return
        prefs.edit { putString(KEY_CUSTOM_URL, normalized) }
        _preferences.value = _preferences.value.copy(
            ramoflixConfig = RamoflixConfig(normalized),
        )
    }

    fun resetUrl() {
        prefs.edit { remove(KEY_CUSTOM_URL) }
        _preferences.value = _preferences.value.copy(
            ramoflixConfig = RamoflixConfig(),
        )
    }

    private fun loadPreferences(): PlaybackPreferences {
        val savedUrl = prefs.getString(KEY_CUSTOM_URL, null)
        val normalized = savedUrl?.let(RamoflixConfig::normalizeBaseUrl)
        if (savedUrl != null && normalized == null) {
            prefs.edit { remove(KEY_CUSTOM_URL) }
        }
        val storedProvider = prefs.getString(KEY_GENERAL_PROVIDER_ID, null)
            ?: prefs.getString(KEY_LEGACY_ACTIVE_SOURCE_ID, null)
        val generalProvider = PlaybackProviderId.fromStoredValue(storedProvider)
            ?.takeIf(PlaybackProviderId::supportsGeneralPlayback)
            ?: PlaybackProviderId.RAMOFLIX
        if (storedProvider != null) {
            prefs.edit {
                putString(KEY_GENERAL_PROVIDER_ID, generalProvider.name)
                remove(KEY_LEGACY_ACTIVE_SOURCE_ID)
            }
        }
        return PlaybackPreferences(
            generalProvider = generalProvider,
            ramoflixConfig = RamoflixConfig(normalized ?: RamoflixConfig.DEFAULT_URL),
        )
    }

    private companion object {
        const val PREFS_NAME = "aliflix_streaming_sources_prefs"
        const val KEY_CUSTOM_URL = "custom_url_ramoflix"
        const val KEY_GENERAL_PROVIDER_ID = "general_provider_id"
        const val KEY_LEGACY_ACTIVE_SOURCE_ID = "active_source_id"
    }
}
