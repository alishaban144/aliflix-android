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

    fun updateRamoflixUrl(newUrl: String) {
        val normalized = RamoflixConfig.normalizeBaseUrl(newUrl) ?: return
        prefs.edit { putString(KEY_CUSTOM_RAMOFLIX_URL, normalized) }
        _preferences.value = _preferences.value.copy(
            ramoflixConfig = RamoflixConfig(normalized),
        )
    }

    fun resetRamoflixUrl() {
        prefs.edit { remove(KEY_CUSTOM_RAMOFLIX_URL) }
        _preferences.value = _preferences.value.copy(
            ramoflixConfig = RamoflixConfig(),
        )
    }

    fun updateMoviepireUrl(newUrl: String) {
        val normalized = RamoflixConfig.normalizeBaseUrl(newUrl) ?: return
        prefs.edit { putString(KEY_CUSTOM_MOVIEPIRE_URL, normalized) }
        _preferences.value = _preferences.value.copy(moviepireBaseUrl = normalized)
    }

    fun resetMoviepireUrl() {
        prefs.edit { remove(KEY_CUSTOM_MOVIEPIRE_URL) }
        _preferences.value = _preferences.value.copy(
            moviepireBaseUrl = PlaybackProviderId.MOVIEPIRE.defaultBaseUrl,
        )
    }

    fun updateDorabyUrl(newUrl: String) {
        val normalized = RamoflixConfig.normalizeBaseUrl(newUrl) ?: return
        prefs.edit { putString(KEY_CUSTOM_DORABY_URL, normalized) }
        _preferences.value = _preferences.value.copy(dorabyBaseUrl = normalized)
    }

    fun resetDorabyUrl() {
        prefs.edit { remove(KEY_CUSTOM_DORABY_URL) }
        _preferences.value = _preferences.value.copy(
            dorabyBaseUrl = PlaybackProviderId.DORABY.defaultBaseUrl,
        )
    }

    private fun loadPreferences(): PlaybackPreferences {
        val savedRamoflixUrl = prefs.getString(KEY_CUSTOM_RAMOFLIX_URL, null)
        val normalizedRamoflixUrl =
            savedRamoflixUrl?.let(RamoflixConfig::normalizeBaseUrl)
        if (savedRamoflixUrl != null && normalizedRamoflixUrl == null) {
            prefs.edit { remove(KEY_CUSTOM_RAMOFLIX_URL) }
        }
        val savedMoviepireUrl = prefs.getString(KEY_CUSTOM_MOVIEPIRE_URL, null)
        val normalizedMoviepireUrl =
            savedMoviepireUrl?.let(RamoflixConfig::normalizeBaseUrl)
        if (savedMoviepireUrl != null && normalizedMoviepireUrl == null) {
            prefs.edit { remove(KEY_CUSTOM_MOVIEPIRE_URL) }
        }
        if (prefs.contains(KEY_LEGACY_CUSTOM_BCINE_URL)) {
            prefs.edit { remove(KEY_LEGACY_CUSTOM_BCINE_URL) }
        }
        val savedDorabyUrl = prefs.getString(KEY_CUSTOM_DORABY_URL, null)
        val normalizedDorabyUrl =
            savedDorabyUrl?.let(RamoflixConfig::normalizeBaseUrl)
        if (savedDorabyUrl != null && normalizedDorabyUrl == null) {
            prefs.edit { remove(KEY_CUSTOM_DORABY_URL) }
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
            ramoflixConfig = RamoflixConfig(
                normalizedRamoflixUrl ?: RamoflixConfig.DEFAULT_URL,
            ),
            moviepireBaseUrl = normalizedMoviepireUrl
                ?: PlaybackProviderId.MOVIEPIRE.defaultBaseUrl,
            dorabyBaseUrl = normalizedDorabyUrl
                ?: PlaybackProviderId.DORABY.defaultBaseUrl,
        )
    }

    private companion object {
        const val PREFS_NAME = "aliflix_streaming_sources_prefs"
        const val KEY_CUSTOM_RAMOFLIX_URL = "custom_url_ramoflix"
        const val KEY_CUSTOM_MOVIEPIRE_URL = "custom_url_moviepire"
        const val KEY_LEGACY_CUSTOM_BCINE_URL = "custom_url_bcine"
        const val KEY_CUSTOM_DORABY_URL = "custom_url_doraby"
        const val KEY_GENERAL_PROVIDER_ID = "general_provider_id"
        const val KEY_LEGACY_ACTIVE_SOURCE_ID = "active_source_id"
    }
}
