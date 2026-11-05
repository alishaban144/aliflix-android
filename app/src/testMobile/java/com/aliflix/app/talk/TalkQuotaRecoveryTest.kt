package com.aliflix.app.talk
import org.junit.Assert.*
import org.junit.Test

class TalkQuotaRecoveryTest {
    @Test fun quotaRotationIsBoundedAndNeedsAnExistingSession() {
        assertTrue(talkCanRotateQuotaProject(true,0,true))
        assertTrue(talkCanRotateQuotaProject(true,1,true))
        assertFalse(talkCanRotateQuotaProject(true,2,true))
        assertFalse(talkCanRotateQuotaProject(false,0,true))
        assertFalse(talkCanRotateQuotaProject(true,0,false))
    }
}
