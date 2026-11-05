package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkStoryMemoryTest {
    @Test fun openingLookupCannotReplaceAnalysisOfAnActualQuestion() {
        assertTrue(reusableTalkStoryMemory("research_ready",true,false))
        assertFalse(reusableTalkStoryMemory("research_ready",true,true))
        assertFalse(reusableTalkStoryMemory("preparing",false,true))
        assertFalse(reusableTalkStoryMemory("limited",false,true))
    }
    @Test fun followUpsReuseCompiledEvidenceWithoutAnotherModelRequest() {
        assertTrue(reusableTalkStoryMemory("grounded",false,true))
        assertTrue(reusableTalkStoryMemory("research_ready",false,true))
    }
}
