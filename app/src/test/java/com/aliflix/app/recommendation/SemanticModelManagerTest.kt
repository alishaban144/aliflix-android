package com.aliflix.app.recommendation

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticModelManagerTest {
    @Test
    fun staleGenerationCannotPublishAfterANewerLifecycleAction() {
        val gate = SemanticLifecycleGate()
        val initial = gate.snapshot()
        var published = "none"

        assertTrue(gate.commit(initial) { published = "initial" })
        val replacement = requireNotNull(gate.nextGenerationIfOpen())

        assertFalse(gate.commit(initial) { published = "stale" })
        assertTrue(gate.commit(replacement) { published = "replacement" })
        assertEquals("replacement", published)
    }

    @Test
    fun closedLifecycleRejectsNewWorkAndLatePublication() {
        val gate = SemanticLifecycleGate()
        val initial = gate.snapshot()
        var publications = 0

        assertTrue(gate.close { publications += 1 } != null)
        assertEquals(null, gate.nextGenerationIfOpen())
        assertFalse(gate.commit(initial) { publications += 1 })
        assertEquals(1, publications)
    }

    @Test
    fun lifecycleOperationsAreSerialized() = runTest {
        val gate = SemanticLifecycleGate()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = launch {
            gate.serialized {
                order += "first-start"
                firstStarted.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstStarted.await()
        val second = launch {
            gate.serialized { order += "second" }
        }
        runCurrent()

        assertEquals(listOf("first-start"), order)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    @Test
    fun queryAndStableCandidateRepresentationsAreCalculatedOnce() = runTest {
        val backend = RecordingBackend()
        val session = CachedSemanticEmbeddingSession(backend)
        val documents = listOf(
            document("series:1", "metadata-v1", "crime family drama"),
            document("series:2", "metadata-v1", "lawyer crime drama"),
        )

        session.similarities("Series similar to Breaking Bad", documents)
        session.similarities("Series similar to Breaking Bad", documents)

        assertEquals(1, backend.count("Series similar to Breaking Bad"))
        assertEquals(1, backend.count("crime family drama"))
        assertEquals(1, backend.count("lawyer crime drama"))

        session.similarities(
            "Series similar to Breaking Bad",
            listOf(document("series:1", "metadata-v2", "crime family drama")),
        )
        assertEquals(2, backend.count("crime family drama"))
    }

    @Test
    fun canceledBatchStopsBeforeEmbeddingRemainingCandidates() = runTest {
        lateinit var scoringJob: Job
        val calls = AtomicInteger(0)
        val backend = object : SemanticEmbeddingBackend {
            override fun embed(text: String): List<Float> {
                if (calls.incrementAndGet() == 5) scoringJob.cancel()
                return listOf(1f, 0.5f)
            }

            override fun close() = Unit
        }
        val session = CachedSemanticEmbeddingSession(backend)
        val execution = TestSemanticExecutionContext(
            StandardTestDispatcher(testScheduler),
        )
        val scorer = DedicatedSemanticBatchScorer(session, execution)
        val documents = (1..80).map { index ->
            document("series:$index", "v1", "candidate number $index")
        }

        scoringJob = launch(start = CoroutineStart.LAZY) {
            scorer.similarities("slow subjective query", documents)
        }
        scoringJob.start()
        advanceUntilIdle()

        assertTrue(scoringJob.isCancelled)
        assertTrue(
            "Cancellation should stop well before the bounded batch is exhausted",
            calls.get() < 10,
        )
        scorer.retireAndClose()
    }

    @Test
    fun retainedScorerCannotUseSessionAfterRetirementOrClose() = runTest {
        val session = BlockingSession()
        val execution = TestSemanticExecutionContext(
            StandardTestDispatcher(testScheduler),
        )
        val scorer = DedicatedSemanticBatchScorer(session, execution)
        val inFlight = launch {
            scorer.similarities("query", listOf(document("movie:1", "v1", "one")))
        }
        runCurrent()
        session.started.await()

        scorer.retire()
        assertTrue(scorer.similarities("late", listOf(document("movie:2", "v1", "two"))).isEmpty())
        val closeJob = launch { scorer.retireAndClose() }
        runCurrent()
        assertFalse("An in-flight inference must not be closed underneath", session.closed)

        session.release.complete(Unit)
        inFlight.join()
        closeJob.join()
        assertTrue(session.closed)
        assertEquals(1, session.calls)
        assertTrue(scorer.similarities("after-close", listOf(document("movie:3", "v1", "three"))).isEmpty())
        assertEquals(1, session.calls)
    }

    private fun document(
        key: String,
        identity: String,
        text: String,
    ) = SemanticCandidateDocument(
        mediaKey = key,
        metadataIdentity = identity,
        text = text,
    )

    private class RecordingBackend : SemanticEmbeddingBackend {
        private val calls = mutableMapOf<String, Int>()

        override fun embed(text: String): List<Float> {
            calls[text] = calls.getOrDefault(text, 0) + 1
            return listOf(text.length.toFloat(), 1f)
        }

        override fun close() = Unit

        fun count(text: String): Int = calls.getOrDefault(text, 0)
    }

    private class BlockingSession : SemanticEmbeddingSession {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var closed = false

        override suspend fun similarities(
            query: String,
            documents: List<SemanticCandidateDocument>,
        ): Map<String, Double> {
            check(!closed)
            calls += 1
            started.complete(Unit)
            release.await()
            check(!closed)
            return documents.associate { it.mediaKey to 1.0 }
        }

        override fun close() {
            check(!closed)
            closed = true
        }
    }

    private class TestSemanticExecutionContext(
        override val dispatcher: CoroutineDispatcher,
    ) : SemanticExecutionContext {
        override fun close() = Unit
    }
}
