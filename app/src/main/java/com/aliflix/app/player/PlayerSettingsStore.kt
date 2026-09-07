package com.aliflix.app.player

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerSettings(
    val subtitleFontSizeSp: Float = DEFAULT_SUBTITLE_FONT_SIZE_SP,
    val subtitleBackgroundOpacity: Float = DEFAULT_SUBTITLE_BACKGROUND_OPACITY,
    val subtitleDelayTenths: Int = 0,
    val subtitleVerticalOffsetDp: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val resizeModeZoom: Boolean = false,
) {
    val subtitleDelaySeconds: Double get() = subtitleDelayTenths / 10.0

    companion object {
        const val DEFAULT_SUBTITLE_FONT_SIZE_SP = 16f
        const val DEFAULT_SUBTITLE_BACKGROUND_OPACITY = 0.5f
    }
}

class PlayerSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<PlayerSettings> = _settings.asStateFlow()

    fun updateSubtitleFontSize(sizeSp: Float) {
        val clamped = sizeSp.coerceIn(11f, 30f)
        update { it.copy(subtitleFontSizeSp = clamped) }
        preferences.edit { putFloat(KEY_SUBTITLE_FONT_SIZE, clamped) }
    }

    fun updateSubtitleBackgroundOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0f, 1f)
        update { it.copy(subtitleBackgroundOpacity = clamped) }
        preferences.edit { putFloat(KEY_SUBTITLE_BG_OPACITY, clamped) }
    }

    fun updateSubtitleDelayTenths(tenths: Int) {
        val clamped = tenths.coerceIn(-600, 600)
        update { it.copy(subtitleDelayTenths = clamped) }
        preferences.edit { putInt(KEY_SUBTITLE_DELAY_TENTHS, clamped) }
    }

    fun updateSubtitleVerticalOffsetDp(offsetDp: Int) {
        val clamped = offsetDp.coerceIn(-200, 200)
        update { it.copy(subtitleVerticalOffsetDp = clamped) }
        preferences.edit { putInt(KEY_SUBTITLE_VERTICAL_OFFSET, clamped) }
    }

    fun updatePlaybackSpeed(speed: Float) {
        val safeSpeed = if (speed in listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)) speed else 1.0f
        update { it.copy(playbackSpeed = safeSpeed) }
        preferences.edit { putFloat(KEY_PLAYBACK_SPEED, safeSpeed) }
    }

    fun updateResizeModeZoom(zoom: Boolean) {
        update { it.copy(resizeModeZoom = zoom) }
        preferences.edit { putBoolean(KEY_RESIZE_MODE_ZOOM, zoom) }
    }

    private inline fun update(transform: (PlayerSettings) -> PlayerSettings) {
        _settings.value = transform(_settings.value)
    }

    private fun readSettings(): PlayerSettings = PlayerSettings(
        subtitleFontSizeSp = preferences.getFloat(KEY_SUBTITLE_FONT_SIZE, PlayerSettings.DEFAULT_SUBTITLE_FONT_SIZE_SP)
            .coerceIn(11f, 30f),
        subtitleBackgroundOpacity = preferences.getFloat(KEY_SUBTITLE_BG_OPACITY, PlayerSettings.DEFAULT_SUBTITLE_BACKGROUND_OPACITY)
            .coerceIn(0f, 1f),
        subtitleDelayTenths = preferences.getInt(KEY_SUBTITLE_DELAY_TENTHS, 0)
            .coerceIn(-600, 600),
        subtitleVerticalOffsetDp = preferences.getInt(KEY_SUBTITLE_VERTICAL_OFFSET, 0)
            .coerceIn(-200, 200),
        playbackSpeed = preferences.getFloat(KEY_PLAYBACK_SPEED, 1.0f).let {
            if (it in listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)) it else 1.0f
        },
        resizeModeZoom = preferences.getBoolean(KEY_RESIZE_MODE_ZOOM, false),
    )

    companion object {
        private const val PREFERENCES_NAME = "aliflix_player_settings"
        private const val KEY_SUBTITLE_FONT_SIZE = "subtitle_font_size_sp"
        private const val KEY_SUBTITLE_BG_OPACITY = "subtitle_bg_opacity"
        private const val KEY_SUBTITLE_DELAY_TENTHS = "subtitle_delay_tenths"
        private const val KEY_SUBTITLE_VERTICAL_OFFSET = "subtitle_vertical_offset_dp"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_RESIZE_MODE_ZOOM = "resize_mode_zoom"
    }
}
