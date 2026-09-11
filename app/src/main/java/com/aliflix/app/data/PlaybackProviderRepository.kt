package com.aliflix.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.PlaybackPreferences
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.SubtitleLanguage
import com.aliflix.app.model.defaultGeneralPlaybackProvider
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlaybackProviderRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val _preferences = MutableStateFlow(loadPreferences())
    val preferences: StateFlow<PlaybackPreferences> = _preferences.asStateFlow()
    private val _updatedAtMillis = MutableStateFlow(prefs.getLong(KEY_UPDATED_AT_MILLIS, 0L))
    val updatedAtMillis: StateFlow<Long> = _updatedAtMillis.asStateFlow()
    private val _mutations = MutableSharedFlow<Unit>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val mutations: SharedFlow<Unit> = _mutations

    val hasExplicitValues: Boolean
        get() = _updatedAtMillis.value > 0L || listOf(
            KEY_CUSTOM_RAMOFLIX_URL,
            KEY_CUSTOM_MOVIEPIRE_URL,
            KEY_CUSTOM_DORABY_URL,
            KEY_GENERAL_PROVIDER_ID,
            KEY_PREFERRED_SUBTITLE_LANGUAGE,
            KEY_AUTO_DISPLAY_SUBTITLES,
            KEY_LEGACY_ACTIVE_SOURCE_ID,
        ).any(prefs::contains)

    fun selectGeneralProvider(provider: PlaybackProviderId) {
        if (!provider.supportsGeneralPlayback) return
        prefs.edit { putString(KEY_GENERAL_PROVIDER_ID, provider.name) }
        _preferences.value = _preferences.value.copy(generalProvider = provider)
        recordLocalChange()
    }

    fun updateRamoflixUrl(newUrl: String) {
        val normalized = RamoflixConfig.normalizeBaseUrl(newUrl) ?: return
        prefs.edit { putString(KEY_CUSTOM_RAMOFLIX_URL, normalized) }
        _preferences.value = _preferences.value.copy(
            ramoflixConfig = RamoflixConfig(normalized),
        )
        recordLocalChange()
    }

    fun resetRamoflixUrl() {
        prefs.edit { remove(KEY_CUSTOM_RAMOFLIX_URL) }
        _preferences.value = _preferences.value.copy(
            ramoflixConfig = RamoflixConfig(),
        )
        recordLocalChange()
    }

    fun updateDorabyUrl(newUrl: String) {
        val normalized = RamoflixConfig.normalizeBaseUrl(newUrl) ?: return
        prefs.edit { putString(KEY_CUSTOM_DORABY_URL, normalized) }
        _preferences.value = _preferences.value.copy(dorabyBaseUrl = normalized)
        recordLocalChange()
    }

    fun resetDorabyUrl() {
        prefs.edit { remove(KEY_CUSTOM_DORABY_URL) }
        _preferences.value = _preferences.value.copy(
            dorabyBaseUrl = PlaybackProviderId.DORABY.defaultBaseUrl,
        )
        recordLocalChange()
    }

    fun updateMoviepireUrl(newUrl: String) {
        val normalized = RamoflixConfig.normalizeBaseUrl(newUrl) ?: return
        prefs.edit { putString(KEY_CUSTOM_MOVIEPIRE_URL, normalized) }
        _preferences.value = _preferences.value.copy(moviepireBaseUrl = normalized)
        recordLocalChange()
    }

    fun resetMoviepireUrl() {
        prefs.edit { remove(KEY_CUSTOM_MOVIEPIRE_URL) }
        _preferences.value = _preferences.value.copy(
            moviepireBaseUrl = PlaybackProviderId.MOVIEPIRE.defaultBaseUrl,
        )
        recordLocalChange()
    }

    fun selectPreferredSubtitleLanguage(language: SubtitleLanguage) {
        prefs.edit { putString(KEY_PREFERRED_SUBTITLE_LANGUAGE, language.code) }
        _preferences.value = _preferences.value.copy(preferredSubtitleLanguage = language)
        recordLocalChange()
    }

    fun setAutoDisplaySubtitles(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_DISPLAY_SUBTITLES, enabled) }
        _preferences.value = _preferences.value.copy(autoDisplaySubtitles = enabled)
        recordLocalChange()
    }

    /** Applies cloud/account-scope settings without creating a write-back loop. */
    fun applySyncedPreferences(value: PlaybackPreferences, updatedAtMillis: Long) {
        val provider = value.safeGeneralProvider
        _preferences.value = value.copy(generalProvider = provider)
        _updatedAtMillis.value = updatedAtMillis.coerceAtLeast(0L)
        prefs.edit {
            putString(KEY_GENERAL_PROVIDER_ID, provider.name)
            putString(KEY_CUSTOM_RAMOFLIX_URL, value.ramoflixConfig.baseUrl)
            putString(KEY_CUSTOM_MOVIEPIRE_URL, value.moviepireBaseUrl)
            putString(KEY_CUSTOM_DORABY_URL, value.dorabyBaseUrl)
            putString(KEY_PREFERRED_SUBTITLE_LANGUAGE, value.preferredSubtitleLanguage.code)
            putBoolean(KEY_AUTO_DISPLAY_SUBTITLES, value.autoDisplaySubtitles)
            putLong(KEY_UPDATED_AT_MILLIS, _updatedAtMillis.value)
            remove(KEY_LEGACY_ACTIVE_SOURCE_ID)
            remove(KEY_LEGACY_CUSTOM_BCINE_URL)
        }
    }

    private fun recordLocalChange() {
        val now = System.currentTimeMillis()
        _updatedAtMillis.value = now
        prefs.edit { putLong(KEY_UPDATED_AT_MILLIS, now) }
        _mutations.tryEmit(Unit)
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
            ?: defaultGeneralPlaybackProvider(isTv = BuildConfig.IS_TV)
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
            preferredSubtitleLanguage = SubtitleLanguage.fromCode(
                prefs.getString(KEY_PREFERRED_SUBTITLE_LANGUAGE, null),
            ),
            autoDisplaySubtitles = prefs.getBoolean(KEY_AUTO_DISPLAY_SUBTITLES, !BuildConfig.IS_TV),
        )
    }

    private companion object {
        const val PREFS_NAME = "aliflix_streaming_sources_prefs"
        const val KEY_CUSTOM_RAMOFLIX_URL = "custom_url_ramoflix"
        const val KEY_CUSTOM_MOVIEPIRE_URL = "custom_url_moviepire"
        const val KEY_LEGACY_CUSTOM_BCINE_URL = "custom_url_bcine"
        const val KEY_CUSTOM_DORABY_URL = "custom_url_doraby"
        const val KEY_GENERAL_PROVIDER_ID = "general_provider_id"
        const val KEY_PREFERRED_SUBTITLE_LANGUAGE = "preferred_subtitle_language"
        const val KEY_AUTO_DISPLAY_SUBTITLES = "auto_display_subtitles"
        const val KEY_LEGACY_ACTIVE_SOURCE_ID = "active_source_id"
        const val KEY_UPDATED_AT_MILLIS = "updated_at_millis"
    }
}
