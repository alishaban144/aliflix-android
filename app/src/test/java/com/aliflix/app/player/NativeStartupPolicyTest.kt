package com.aliflix.app.player

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeStartupPolicyTest {
    @Test fun fastestSuccessfulCandidateWinsAndLosersAreDestroyedBeforeReturn() = runBlocking {
        var active = 0; var peak = 0; var cleaned = 0
        val winner = firstSuccessful((0..3).map { index -> suspend {
            active++; peak = maxOf(peak, active)
            try { delay(if (index == 0) 200 else 10); if (index == 1) error("Broken server"); index }
            finally { active--; cleaned++ }
        } })
        assertEquals(2, winner)
        assertEquals(2, peak)
        assertEquals(0, active)
        assertTrue(cleaned >= 3)
    }

    @Test fun leavingPreparationCancelsEveryActiveCandidate() = runBlocking {
        var active = 0
        val started = CompletableDeferred<Unit>()
        val job = launch {
            firstSuccessful((0..3).map { suspend {
                active++; started.complete(Unit)
                try { awaitCancellation() } finally { active-- }
            } })
        }
        started.await(); job.cancelAndJoin()
        assertEquals(0, active)
    }

    @Test fun englishAliasesFormOneGroupWithoutLosingReleaseChoices() {
        fun track(id: String, code: String, name: String) = SubtitleTrack(id, code, name, id, "$id.srt", false, "srt", null, id)
        val result = normalizeMobileSubtitleTracks(listOf(track("one", "en", "en"), track("two", "ENG", "English"),
            track("three", "English", "english"), track("one", "EN", "English")))
        assertEquals(3, result.size)
        assertEquals(setOf("EN"), result.map { it.languageCode }.toSet())
        assertEquals(setOf("English"), result.map { it.languageName }.toSet())
    }

    @Test fun archiveSelectsRequestedEpisodeInsteadOfFirstSimilarFilename() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for (episode in listOf(1, 2, 10)) {
                zip.putNextEntry(ZipEntry("Show.S01E${episode.toString().padStart(2, '0')}.srt"))
                zip.write("episode $episode".toByteArray()); zip.closeEntry()
            }
        }
        assertEquals("episode 10", String(extractSubtitleFromZip(output.toByteArray(), "Show.Season.1.zip", 1, 10)))
        assertThrows(SubtitleException::class.java) { extractSubtitleFromZip(output.toByteArray(), "Show.Season.1.zip", 1, 5) }
    }

    @Test fun hashUsesFileLengthAndBothLittleEndianBlocksWithUnsignedOverflow() {
        val first = ByteArray(65536); val last = ByteArray(65536)
        first[0] = 1; last[0] = 2
        assertEquals("0000000000020003", subtitleVideoHash(131072, first, last))
        assertEquals("000000000001c000", subtitleVideoHash(131072, ByteArray(65536) { -1 }, ByteArray(65536) { -1 }))
    }
}
