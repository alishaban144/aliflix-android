package com.aliflix.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RamoflixRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<RamoflixConfig> = _config.asStateFlow()

    init {
        // Older builds stored a selected provider. Ramoflix is now always used.
        prefs.edit { remove(KEY_LEGACY_ACTIVE_SOURCE_ID) }
    }

    fun updateUrl(newUrl: String) {
        val normalized = RamoflixConfig.normalizeBaseUrl(newUrl) ?: return
        prefs.edit { putString(KEY_CUSTOM_URL, normalized) }
        _config.value = RamoflixConfig(normalized)
    }

    fun resetUrl() {
        prefs.edit { remove(KEY_CUSTOM_URL) }
        _config.value = RamoflixConfig()
    }

    private fun loadConfig(): RamoflixConfig {
        val savedUrl = prefs.getString(KEY_CUSTOM_URL, null)
        val normalized = savedUrl?.let(RamoflixConfig::normalizeBaseUrl)
        if (savedUrl != null && normalized == null) {
            prefs.edit { remove(KEY_CUSTOM_URL) }
        }
        return RamoflixConfig(normalized ?: RamoflixConfig.DEFAULT_URL)
    }

    private companion object {
        const val PREFS_NAME = "aliflix_streaming_sources_prefs"
        const val KEY_CUSTOM_URL = "custom_url_ramoflix"
        const val KEY_LEGACY_ACTIVE_SOURCE_ID = "active_source_id"
    }
}
