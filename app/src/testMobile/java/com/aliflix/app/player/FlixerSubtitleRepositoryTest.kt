package com.aliflix.app.player
import org.junit.Assert.*
import org.junit.Test
class FlixerSubtitleRepositoryTest {
    @Test fun originalVariantsPreserveLanguageAndHearingImpairedLabels() {
        val tracks = FlixerSubtitleRepository.parse("""[{"label":"Arabic2","file":"https://cache.vdrk.site/Arabic2.vtt"},{"label":"English Hi2","file":"https://cache.vdrk.site/English Hi2.vtt"}]""")
        assertEquals(listOf("AR", "EN"), tracks.map { it.languageCode })
        assertFalse(tracks.first().hearingImpaired)
        assertTrue(tracks.last().hearingImpaired)
        assertTrue(tracks.last().downloadToken.endsWith("English%20Hi2.vtt"))
    }
}
