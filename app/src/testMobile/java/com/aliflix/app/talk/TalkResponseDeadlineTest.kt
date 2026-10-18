package com.aliflix.app.talk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TalkResponseDeadlineTest {
    @Test fun unansweredTurnExpiresButRealAudioAndTeardownCancelIt()=runTest {
        var expirations=0
        val deadline=TalkResponseDeadline(this,1000){expirations++}
        deadline.awaiting();advanceTimeBy(1000);runCurrent();assertEquals(1,expirations)
        deadline.awaiting();advanceTimeBy(500);deadline.clear()
        advanceTimeBy(2000);runCurrent();assertEquals(1,expirations)
    }
    @Test fun oldTurnCannotExpireARearmedSpeechTurn()=runTest {
        var expirations=0
        val deadline=TalkResponseDeadline(this,1000){expirations++}
        deadline.awaiting();advanceTimeBy(700);deadline.awaiting()
        advanceTimeBy(400);runCurrent();assertEquals(0,expirations)
        advanceTimeBy(600);runCurrent();assertEquals(1,expirations)
        deadline.clear();advanceUntilIdle();assertEquals(1,expirations)
    }
}
