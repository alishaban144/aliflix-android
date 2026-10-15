package com.aliflix.app.party

import com.aliflix.app.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PartyProtocolTest {
    @Test fun clockUsesMidpointAndRejectsLargeLatencyOutliers() {
        val clock = PartyClock()
        clock.sample(1000.0, 1100.0, 1000050.0)
        assertEquals(999000.0, clock.offset, .001)
        clock.sample(1000.0, 12000.0, 100000000.0)
        assertEquals(999000.0, clock.offset, .001)
        assertEquals(1000100.0, clock.serverNow(1100), .001)
        clock.reset(); assertFalse(clock.ready)
    }
    @Test fun exactEpisodeIdentityNeverDependsOnProvider() {
        val selected = PlaybackSelection(Media(70523, MediaType.TV, "Dark", year = "2017", originalLanguage = "de"), 1, 1)
        val content = PartyProtocol.content(selected)
        assertTrue(PartyProtocol.matches(content, selected.copy(source = PlaybackSource(MobilePlaybackProvider.FLIXER))))
        assertFalse(PartyProtocol.matches(content, selected.copy(episodeNumber = 2)))
        assertFalse(content.has("url")); assertFalse(content.has("provider")); assertFalse(content.has("subtitles"))
        assertEquals(1, PartyProtocol.selection(content, PlaybackPreferences()).episodeNumber)
    }
    @Test fun projectedClockKeepsPauseFixedAndAdvancesPlayingFromServerAnchor() {
        val state = JSONObject().put("position", 30000).put("at", 100000).put("playing", true)
        assertEquals(35000, PartyProtocol.target(state, 105000.0))
        state.put("playing", false)
        assertEquals(30000, PartyProtocol.target(state, 900000.0))
    }
    @Test fun correctionHasDeadbandGentleSpeedAndSeekCooldown() {
        assertEquals(1f, PartyProtocol.correction(200, 10000))
        assertEquals(1.03f, PartyProtocol.correction(900, 10000))
        assertEquals(.97f, PartyProtocol.correction(-900, 10000))
        assertEquals(0f, PartyProtocol.correction(3000, 10000))
        assertEquals(1.03f, PartyProtocol.correction(3000, 1000))
        assertEquals(.97f, PartyProtocol.correction(-3000, 1000))
    }
    @Test fun tenMinuteLatencySimulationConvergesWithoutRepeatedVisibleSeeking() {
        var position = 10000.0
        var seeks = 0
        var lastSeek = -10000L
        for (time in 0L..600000L step 500L) {
            val expected = 10000L + time
            val correction = PartyProtocol.correction(expected - position.toLong(), time - lastSeek)
            if (correction == 0f) { position = expected.toDouble(); seeks++; lastSeek = time }
            else position += 500 * correction
        }
        assertTrue(seeks <= 1)
        assertTrue(kotlin.math.abs(position - 610500) < 350)
    }
}
