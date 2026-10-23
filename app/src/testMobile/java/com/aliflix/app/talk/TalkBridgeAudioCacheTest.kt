package com.aliflix.app.talk

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TalkBridgeAudioCacheTest {
    @get:Rule val folder=TemporaryFolder()
    @Test fun approvedAudioSurvivesSessionsWithoutCrossingVoiceOrSentenceIdentity()=runBlocking {
        val directory=folder.newFolder();val cache=TalkBridgeAudioCache(directory)
        val sentence=talkResearchBridge("movies about gentlemen","en")
        assertNull(cache.load("Aoede",sentence))
        cache.store("Aoede",sentence,listOf(byteArrayOf(1,2),byteArrayOf(3,4)))
        assertArrayEquals(byteArrayOf(1,2,3,4),TalkBridgeAudioCache(directory).load("Aoede",sentence))
        assertNull(cache.load("Kore",sentence))
        assertNull(cache.load("Aoede",talkResearchBridge("science fiction","en")))
        directory.listFiles()!!.single().writeBytes(byteArrayOf(1))
        assertNull(cache.load("Aoede",sentence))
    }
    @Test fun clipsAndDiskUseStayBoundedAndMalformedPcmCannotBeReplayed()=runBlocking {
        val directory=folder.newFolder();val cache=TalkBridgeAudioCache(directory)
        cache.store("Aoede","odd",listOf(byteArrayOf(1)))
        cache.store("Aoede","too long",listOf(ByteArray(480002)))
        assertNull(cache.load("Aoede","odd"));assertNull(cache.load("Aoede","too long"))
        repeat(14){cache.store("Aoede","approved sentence $it",listOf(ByteArray(640)))}
        assertEquals(10,directory.listFiles()!!.count{it.extension=="pcm"})
        assertEquals(6400L,directory.walkTopDown().filter(File::isFile).sumOf{it.length()})
    }
}
