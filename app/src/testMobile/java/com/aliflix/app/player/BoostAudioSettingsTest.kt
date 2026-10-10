package com.aliflix.app.player

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BoostAudioSettingsTest {
    @Test fun defaultsOffAndPersistsBothToggleValuesAcrossStoreRecreation() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aliflix_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val first = PlayerSettingsStore(context)
        assertFalse(first.settings.value.boostAudio)
        first.updateBoostAudio(true)
        assertTrue(first.settings.value.boostAudio)
        val recreated = PlayerSettingsStore(context)
        assertTrue(recreated.settings.value.boostAudio)
        recreated.updateBoostAudio(false)
        assertFalse(PlayerSettingsStore(context).settings.value.boostAudio)
    }
    @Test fun changingAutoPreferencesNeverClearsExplicitManualOffOrLanguage() {
        val context = RuntimeEnvironment.getApplication()
        val choices = context.getSharedPreferences("native-subtitle-choice", Context.MODE_PRIVATE)
        choices.edit().clear().putString("manual-owner", "tv:200").putBoolean("enabled", false)
            .putString("language", "AR").commit()
        val repository = com.aliflix.app.data.PlaybackProviderRepository(context)
        repository.selectPreferredSubtitleLanguage(com.aliflix.app.model.SubtitleLanguage.ENGLISH)
        repository.setAutoDisplaySubtitles(false)
        repository.setAutoDisplaySubtitles(true)
        repository.applySyncedPreferences(repository.preferences.value, 1234)
        assertEquals("tv:200", choices.getString("manual-owner", null))
        assertEquals("AR", choices.getString("language", null))
        assertTrue(choices.contains("enabled"))
        assertFalse(choices.getBoolean("enabled", true))
    }

}
