package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackAudioTransactionTest {
    @Test fun failedChoiceNeverOverwritesConfirmedAudio() {
        val state = PlaybackAudioTransaction<String>()
        state.confirm("Track 1")
        state.select("Track 2")
        assertFalse(state.confirm("Track 1"))
        assertEquals("Track 1", state.confirmed)
        assertTrue(state.retry())
        assertFalse(state.retry())
        assertEquals("Track 1", state.rollback())
        assertNull(state.pending)
    }

    @Test fun rapidChoicesOnlyCommitTheLastRequestedTrack() {
        val state = PlaybackAudioTransaction<String>()
        state.confirm("Track 1")
        state.select("Track 2")
        state.select("Track 3")
        assertFalse(state.confirm("Track 2"))
        assertTrue(state.confirm("Track 3"))
        assertEquals("Track 3", state.confirmed)
        assertFalse(state.confirm("Track 3"))
    }

    @Test fun seekGrantsOneNewAttemptWithoutChangingPendingAudio() {
        val state = PlaybackAudioTransaction<String>()
        state.confirm("Track 1")
        state.select("Track 4")
        assertTrue(state.retry())
        assertFalse(state.retry())
        state.seek()
        assertEquals("Track 4", state.pending)
        assertTrue(state.retry())
        assertFalse(state.retry())
        state.reset()
        assertNull(state.pending)
        assertNull(state.confirmed)
    }
}
