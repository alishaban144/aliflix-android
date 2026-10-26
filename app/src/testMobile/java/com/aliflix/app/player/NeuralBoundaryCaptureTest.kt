package com.aliflix.app.player
import org.junit.Assert.*
import org.junit.Test
class NeuralBoundaryCaptureTest {
    @Suppress("UNCHECKED_CAST") private fun known(capture:PlaybackSpeechBuffer):Int {
        val field=PlaybackSpeechBuffer::class.java.getDeclaredField("neuralEvidence").apply { isAccessible=true }
        return (field.get(capture) as java.util.TreeMap<Int,ByteArray>).values.sumOf { rows -> rows.count { it>=0 } }
    }
    @Test fun independentScanRetainsValidLateResultsFromAnEarlierSegment() {
        val scan=PlaybackSpeechBuffer(retainDecodedNeuralHistory=true)
        val token=scan.generation;val boundary=scan.continuityEpoch()
        scan.discontinuity();scan.acceptNeural(100.0,.9f,token,boundary)
        assertTrue(known(scan)>0);scan.close()
    }
    @Test fun visiblePlayerStillRejectsEarlierContinuityAndEveryScanRejectsOldSoundtrackGenerations() {
        val live=PlaybackSpeechBuffer();val token=live.generation;val boundary=live.continuityEpoch()
        live.discontinuity();live.acceptNeural(100.0,.9f,token,boundary)
        assertEquals(0,known(live));live.close()
        val scan=PlaybackSpeechBuffer(retainDecodedNeuralHistory=true)
        val old=scan.generation;scan.reset();scan.acceptNeural(100.0,.9f,old,0)
        assertEquals(0,known(scan));scan.close()
    }
}
