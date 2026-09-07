package com.aliflix.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test

class ExactProgressTest {
    @Test fun durableProgressRejectsBootstrapAndStaleSamplesAndRetainsEpisodeIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PlaybackProgressStore(context)
        val show = PlaybackSelection(Media(2147483001, MediaType.TV, "Progress fixture"), 2, 3)
        val other = show.copy(episodeNumber = 4)
        val now = System.currentTimeMillis()
        store.savePlayerProgress(show, 980.125, 1000.0, true, now)
        store.savePlayerProgress(show, 0.0, 1000.0, true, now + 1)
        store.savePlayerProgress(show, Double.NaN, 1000.0, true, now + 2)
        store.savePlayerProgress(show, 120.0, 1000.0, true, now - 1)
        store.savePlayerProgress(other, 95.25, 100.0, true, now)
        val restored = PlaybackProgressStore(context)
        assertEquals(980.125, restored.progressFor(show)!!.positionSeconds, 0.0)
        assertEquals(95.25, restored.progressFor(other)!!.positionSeconds, 0.0)
        assertTrue(restored.progressFor(show)!!.resumeEligible)
        store.savePlayerProgress(show, 1000.0, 1000.0, true, now + 3)
        assertFalse(store.progressFor(show)!!.completed)
        store.savePlayerProgress(show, 1000.0, 1000.0, true, now + 4, ended = true)
        assertTrue(store.progressFor(show)!!.completed)
    }
}
