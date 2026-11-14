package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkOpeningSpeechTest {
    @Test fun keepsBoundariesAndAudioInOrderAcrossSetup() {
        val buffer = TalkOpeningSpeech()
        val start = TalkInputTurnGate.Decision(start=true, frames=listOf(byteArrayOf(1)))
        val middle = TalkInputTurnGate.Decision(frames=listOf(byteArrayOf(2)))
        val end = TalkInputTurnGate.Decision(end=true, frames=listOf(byteArrayOf(3)))
        buffer.offer(middle, 4)
        listOf(start, middle, end).forEach { buffer.offer(it, 5) }
        assertEquals(listOf(start to 5L, middle to 5L, end to 5L), buffer.drain())
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun clearedOrOversizedAudioCannotLeakIntoAnotherOpening() {
        val buffer = TalkOpeningSpeech()
        val start = TalkInputTurnGate.Decision(start=true, frames=listOf(byteArrayOf(1)))
        buffer.offer(start, 1)
        buffer.clear()
        assertTrue(buffer.drain().isEmpty())
        buffer.offer(start.copy(frames=listOf(ByteArray(480_001))), 2)
        buffer.offer(start, 2)
        assertTrue(buffer.drain().isEmpty())
        buffer.offer(start, 3)
        assertEquals(listOf(start to 3L), buffer.drain())
    }
}
