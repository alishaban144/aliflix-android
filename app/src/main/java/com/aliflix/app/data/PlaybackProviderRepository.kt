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

    fun updateRivestreamUrl(newUrl: String) {
        val normalized = RamoflixConfig.normalizeBaseUrl(newUrl) ?: return
        prefs.edit { putString(KEY_CUSTOM_RIVESTREAM_URL, normalized) }
        _preferences.value = _preferences.value.copy(rivestreamBaseUrl = normalized)
    }

    fun resetRivestreamUrl() {
        prefs.edit { remove(KEY_CUSTOM_RIVESTREAM_URL) }
        _preferences.value = _preferences.value.copy(
            rivestreamBaseUrl = PlaybackProviderId.RIVESTREAM.defaultBaseUrl,
        )
    }

    fun updateMovies67Url(newUrl: String) {
        val normalized = RamoflixConfig.normalizeBaseUrl(newUrl) ?: return
        prefs.edit { putString(KEY_CUSTOM_MOVIES_67_URL, normalized) }
        _preferences.value = _preferences.value.copy(movies67BaseUrl = normalized)
    }

    fun resetMovies67Url() {
        prefs.edit { remove(KEY_CUSTOM_MOVIES_67_URL) }
        _preferences.value = _preferences.value.copy(
            movies67BaseUrl = PlaybackProviderId.MOVIES_67.defaultBaseUrl,
        )
    }

    private fun loadPreferences(): PlaybackPreferences {
        val savedRamoflixUrl = prefs.getString(KEY_CUSTOM_RAMOFLIX_URL, null)
        val normalizedRamoflixUrl =
            savedRamoflixUrl?.let(RamoflixConfig::normalizeBaseUrl)
        if (savedRamoflixUrl != null && normalizedRamoflixUrl == null) {
            prefs.edit { remove(KEY_CUSTOM_RAMOFLIX_URL) }
        }
        val savedRivestreamUrl = prefs.getString(KEY_CUSTOM_RIVESTREAM_URL, null)
        val normalizedRivestreamUrl =
            savedRivestreamUrl?.let(RamoflixConfig::normalizeBaseUrl)
        if (savedRivestreamUrl != null && normalizedRivestreamUrl == null) {
            prefs.edit { remove(KEY_CUSTOM_RIVESTREAM_URL) }
        }
        val savedMovies67Url = prefs.getString(KEY_CUSTOM_MOVIES_67_URL, null)
        val normalizedMovies67Url =
            savedMovies67Url?.let(RamoflixConfig::normalizeBaseUrl)
        if (savedMovies67Url != null && normalizedMovies67Url == null) {
            prefs.edit { remove(KEY_CUSTOM_MOVIES_67_URL) }
        }
        val storedProvider = prefs.getString(KEY_GENERAL_PROVIDER_ID, null)
            ?: prefs.getString(KEY_LEGACY_ACTIVE_SOURCE_ID, null)
        val generalProvider = PlaybackProviderId.fromStoredValue(storedProvider)
            ?.takeIf(PlaybackProviderId::supportsGeneralPlayback)
            ?: PlaybackProviderId.RIVESTREAM
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
            rivestreamBaseUrl = normalizedRivestreamUrl
                ?: PlaybackProviderId.RIVESTREAM.defaultBaseUrl,
            movies67BaseUrl = normalizedMovies67Url
                ?: PlaybackProviderId.MOVIES_67.defaultBaseUrl,
        )
    }

    private companion object {
        const val PREFS_NAME = "aliflix_streaming_sources_prefs"
        const val KEY_CUSTOM_RAMOFLIX_URL = "custom_url_ramoflix"
        const val KEY_CUSTOM_RIVESTREAM_URL = "custom_url_rivestream"
        const val KEY_CUSTOM_MOVIES_67_URL = "custom_url_movies_67"
        const val KEY_GENERAL_PROVIDER_ID = "general_provider_id"
        const val KEY_LEGACY_ACTIVE_SOURCE_ID = "active_source_id"
    }
}
