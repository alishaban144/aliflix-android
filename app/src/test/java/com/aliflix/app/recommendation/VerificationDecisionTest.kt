package com.aliflix.app.recommendation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationDecisionTest {

    @Test
    fun parseReturnsCorrectEnumValues() {
        assertEquals(VerificationDecision.DEFINITE_MATCH, VerificationDecision.parse("DEFINITE_MATCH"))
        assertEquals(VerificationDecision.PROBABLE_MATCH, VerificationDecision.parse("PROBABLE_MATCH"))
        assertEquals(VerificationDecision.INSUFFICIENT_EVIDENCE, VerificationDecision.parse("INSUFFICIENT_EVIDENCE"))
        assertEquals(VerificationDecision.REJECT, VerificationDecision.parse("REJECT"))
    }

    @Test
    fun parseFallsBackToRejectForUnknownValue() {
        assertEquals(VerificationDecision.REJECT, VerificationDecision.parse("ACCEPT"))
        assertEquals(VerificationDecision.REJECT, VerificationDecision.parse(""))
        assertEquals(VerificationDecision.REJECT, VerificationDecision.parse("unknown"))
    }

    @Test
    fun parseIsCaseInsensitive() {
        assertEquals(VerificationDecision.DEFINITE_MATCH, VerificationDecision.parse("definite_match"))
        assertEquals(VerificationDecision.PROBABLE_MATCH, VerificationDecision.parse("Probable_Match"))
    }

    @Test
    fun definiteMatchIsAlwaysAccepted() {
        assertTrue(VerificationDecision.DEFINITE_MATCH.isAccepted(0.0))
        assertTrue(VerificationDecision.DEFINITE_MATCH.isAccepted(0.5))
        assertTrue(VerificationDecision.DEFINITE_MATCH.isAccepted(1.0))
    }

    @Test
    fun probableMatchIsAcceptedAtOrAboveThreshold() {
        assertTrue(VerificationDecision.PROBABLE_MATCH.isAccepted(0.70))
        assertTrue(VerificationDecision.PROBABLE_MATCH.isAccepted(0.85))
        assertTrue(VerificationDecision.PROBABLE_MATCH.isAccepted(1.0))
    }

    @Test
    fun probableMatchIsRejectedBelowThreshold() {
        assertFalse(VerificationDecision.PROBABLE_MATCH.isAccepted(0.69))
        assertFalse(VerificationDecision.PROBABLE_MATCH.isAccepted(0.5))
        assertFalse(VerificationDecision.PROBABLE_MATCH.isAccepted(0.0))
    }

    @Test
    fun insufficientEvidenceIsNeverAccepted() {
        assertFalse(VerificationDecision.INSUFFICIENT_EVIDENCE.isAccepted(1.0))
        assertFalse(VerificationDecision.INSUFFICIENT_EVIDENCE.isAccepted(0.5))
    }

    @Test
    fun rejectIsNeverAccepted() {
        assertFalse(VerificationDecision.REJECT.isAccepted(1.0))
        assertFalse(VerificationDecision.REJECT.isAccepted(0.0))
    }

    @Test
    fun oldAcceptStringNeverMatchesAnything() {
        // This test documents that "ACCEPT" is not a valid decision
        // and falls back to REJECT via parse()
        val decision = VerificationDecision.parse("ACCEPT")
        assertEquals(VerificationDecision.REJECT, decision)
        assertFalse(decision.isAccepted(1.0))
    }
}
