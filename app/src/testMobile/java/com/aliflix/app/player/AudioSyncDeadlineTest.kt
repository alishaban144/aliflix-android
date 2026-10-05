package com.aliflix.app.player

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSyncDeadlineTest {
    @Test fun silenceOrUnfinishedNetworkCannotKeepTheUiWaitingPastTwentySeconds() = runTest {
        var expired = false
        var remaining = 20
        val result = async {
            AudioSyncDeadline.run(clock = { testScheduler.currentTime }, onRemaining = { remaining = it },
                onExpired = { expired = true }) { awaitCancellation() }
        }
        advanceTimeBy(18_999); runCurrent()
        assertFalse(expired)
        assertEquals(1, remaining)
        advanceTimeBy(1); runCurrent()
        assertTrue(expired)
        assertNull(result.await())
    }

    @Test fun nativeWorkIgnoringCancellationCannotReturnALateCorrection() = runTest {
        var expiredAt: Long? = null
        val result = async {
            AudioSyncDeadline.run(clock = { testScheduler.currentTime }, onRemaining = {},
                onExpired = { if (expiredAt == null) expiredAt = testScheduler.currentTime }) {
                withContext(NonCancellable) { delay(25_000) }
                "late correction"
            }
        }
        advanceTimeBy(19_000); runCurrent()
        assertEquals(19_000L, expiredAt)
        advanceUntilIdle()
        assertNull(result.await())
    }

    @Test fun verifiedResultFinishesImmediatelyAndCancelsItsCountdown() = runTest {
        var expired = false
        val result = AudioSyncDeadline.run(clock = { testScheduler.currentTime }, onRemaining = {},
            onExpired = { expired = true }) { delay(1_000); "verified correction" }
        assertEquals("verified correction", result)
        advanceUntilIdle()
        assertEquals(1_000L, testScheduler.currentTime)
        assertFalse(expired)
    }

    @Test fun userOrIdentityCancellationNeverBecomesATimeoutOrSuccess() = runTest {
        var expired = false
        var applied = false
        val job = launch {
            val result = AudioSyncDeadline.run(clock = { testScheduler.currentTime }, onRemaining = {},
                onExpired = { expired = true }) { delay(10_000); "correction" }
            applied = result != null
        }
        advanceTimeBy(1_000)
        job.cancelAndJoin()
        advanceUntilIdle()
        assertFalse(expired)
        assertFalse(applied)
    }
}
