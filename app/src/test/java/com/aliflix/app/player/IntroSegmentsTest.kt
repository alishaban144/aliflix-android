package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class IntroSegmentsTest {
    private fun document(intro: String = "null", outro: String = "null") =
        """{"imdb_id":"tt0903747","season":1,"episode":1,"intro":$intro,"outro":$outro}"""

    @Test fun buttonsAppearAtStartDisappearAtEndAndDoNotSkipPostCredits() {
        val markers = parseIntroSegments(document("""{"start_ms":12000,"end_ms":34000}""",
            """{"start_ms":70000,"end_ms":80000}"""), "tt0903747", 1, 1)
        assertFalse(markers[0].isActive(11999, 90000))
        assertTrue(markers[0].isActive(12000, 90000))
        assertTrue(markers[0].isActive(33999, 90000))
        assertFalse(markers[0].isActive(34000, 90000))
        assertEquals(80000L, markers[1].endMs)
        assertFalse(markers[1].isActive(71000, 75000))
        assertTrue(markers[0].isActive(13000, 90000)) // Seeking back can offer the intro again.
    }
    @Test fun missingAndMalformedSegmentsAreIgnored() {
        assertTrue(parseIntroSegments(document(), "tt0903747", 1, 1).isEmpty())
        for (bad in listOf("""{"start_ms":-1,"end_ms":4}""", """{"start_ms":5,"end_ms":5}""", """{"start_sec":1,"end_sec":2}"""))
            assertTrue(parseIntroSegments(document(bad), "tt0903747", 1, 1).isEmpty())
    }
    @Test fun rejectsStaleEpisodeOrWrongSeriesIdentity() {
        for (identity in listOf(Triple("tt1234567", 1, 1), Triple("tt0903747", 2, 1), Triple("tt0903747", 1, 2))) {
            assertThrows(IllegalArgumentException::class.java) { parseIntroSegments(document(), identity.first, identity.second, identity.third) }
        }
    }
}
