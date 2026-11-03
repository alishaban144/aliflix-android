package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitleArchiveSelectionTest {
    private fun archive(vararg files:Pair<String,String>):ByteArray=ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> files.forEach { (name,text) -> zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry() } }
    }.toByteArray()
    @Test fun recoveredArchiveKeepsTheSelectedExtendedMemberInsteadOfSimilarForcedCaptions() {
        val bytes=archive("folder/Movie.EXTENDED.forced.srt" to "foreign-only", "folder/Movie.EXTENDED.srt" to "complete dialogue")
        assertEquals("complete dialogue",String(extractSubtitleFromZip(bytes,"Movie.EXTENDED.srt",requireExactMember=true)))
    }
    @Test fun exactFileRecoveryRejectsAmbiguousAndForcedOnlyAlternatives() {
        assertThrows(SubtitleException::class.java) { extractSubtitleFromZip(archive("Movie.srt" to "first","Movie.HI.srt" to "second"),"Unknown.srt",requireExactMember=true) }
        assertThrows(SubtitleException::class.java) { extractSubtitleFromZip(archive("Movie.forced.srt" to "forced"),"Movie.srt",requireExactMember=true) }
        assertEquals("single",String(extractSubtitleFromZip(archive("renamed.srt" to "single"),"Original.srt",requireExactMember=true)))
    }
}
