package com.aliflix.app.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SubtitleCorrectionMigrationTest {
    @Test fun weakerLegacyCorrectionsCannotReturnThroughAccountRestoration() {
        val store = SubtitleCorrectionStore(RuntimeEnvironment.getApplication())
        store.preferences.edit().putString("restored", """{"schema":3,"offset":10.24,"rate":1,"confidence":0.929}""").commit()
        assertNull(store.get("restored"))
    }
    @Test fun newlyVerifiedCorrectionsAndResetKeepTheExistingSyncStore() {
        val store = SubtitleCorrectionStore(RuntimeEnvironment.getApplication())
        val correction = AudioSubtitleCorrection(-47.0, 1.0, .88)
        store.put("verified", correction)
        assertEquals(correction, store.get("verified"))
        assertEquals(4, JSONObject(store.preferences.getString("verified", null)!!).getInt("schema"))
        store.put("verified", null)
        assertNull(store.get("verified"))
        assertTrue(JSONObject(store.preferences.getString("verified", null)!!).getBoolean("reset"))
    }
}
