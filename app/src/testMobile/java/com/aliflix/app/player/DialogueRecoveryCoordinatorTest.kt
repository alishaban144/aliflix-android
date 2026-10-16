package com.aliflix.app.player

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DialogueRecoveryCoordinatorTest {
    private fun clip(start: Double, count: Int = 200) = PlayedDialoguePcm(start, List(count) { ShortArray(320) })

    @Test fun onDeviceRecognizerRequestsNeverOverlap() = runTest {
        var active=0;var peak=0
        val job=launch { DialogueRecoveryCoordinator.run({ (0..5).map { clip(it*10.0,400) } }, {
            active++;peak=maxOf(peak,active)
            try { delay(500);emptyList() } finally { active-- }
        }, {}, clock={testScheduler.currentTime},parallelism=1) }
        job.join();assertEquals(1,peak);assertEquals(0,active)
    }

    @Test fun laterClipsReceiveTheirOwnDeadlineAfterAnActualSlotOpens() = runTest {
        val calls = mutableListOf<Pair<Double, Long>>()
        val completed = mutableListOf<Double>()
        var concurrent = 0
        var peak = 0
        val task = launch {
            DialogueRecoveryCoordinator.run({ (0..5).map { clip(it * 10.0) } }, { sample ->
                calls.add(sample.start to testScheduler.currentTime)
                concurrent++; peak = maxOf(peak, concurrent)
                try { delay(900); completed.add(sample.start); emptyList() }
                finally { concurrent-- }
            }, {}, requestTimeoutMs = 1_000, clock = { testScheduler.currentTime })
        }
        task.join()
        assertEquals(2, peak)
        assertEquals(6, completed.size)
        assertTrue(calls[2].second >= 900)
        assertTrue(calls[4].second >= 1_800)
    }

    @Test fun freshDialogueTakesAFreeSlotAndCancellationStopsEveryRequest() = runTest {
        var samples = listOf(clip(10.0), clip(0.0), clip(8.0))
        val calls = mutableListOf<Double>()
        var active = 0
        val task = launch {
            DialogueRecoveryCoordinator.run({ samples }, { sample ->
                calls.add(sample.start); active++
                try { delay(1_000); emptyList() } finally { active-- }
            }, {}, clock = { testScheduler.currentTime })
        }
        runCurrent()
        assertEquals(listOf(10.0, 0.0), calls)
        samples = listOf(clip(20.0), clip(8.0))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(20.0, calls[2], .001)
        task.cancelAndJoin()
        assertEquals(0, active)
        val count = calls.size
        advanceTimeBy(20_000); runCurrent()
        assertEquals(count, calls.size)
    }

    @Test fun duplicateSnapshotsAndGrowingFragmentsCannotSpendTheUploadBudget() = runTest {
        var count = 200
        val calls = mutableListOf<PlayedDialoguePcm>()
        val task = launch {
            DialogueRecoveryCoordinator.run({ listOf(clip(0.0, count), clip(0.0, count)) }, {
                calls.add(it); emptyList()
            }, {}, clock = { testScheduler.currentTime })
        }
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(1, calls.size)
        count = 300 // Just two new seconds: still the same fragment.
        advanceTimeBy(500); runCurrent()
        assertEquals(1, calls.size)
        count = 400
        advanceTimeBy(500); runCurrent()
        assertEquals(2, calls.size)
        task.cancelAndJoin()
        assertTrue(calls.sumOf { it.frames.size } <= 6_000)
    }

    @Test fun fastUnhelpfulCropsLeaveTwoRequestsForLaterDialogue() = runTest {
        var samples = listOf(clip(20.0), clip(0.0), clip(18.0), clip(16.0), clip(14.0), clip(12.0))
        val calls = mutableListOf<Double>()
        val task = launch { DialogueRecoveryCoordinator.run({ samples }, { calls.add(it.start); emptyList() }, {}, clock = { testScheduler.currentTime }) }
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(4, calls.size)
        samples = listOf(clip(30.0), clip(40.0))
        advanceTimeBy(500); runCurrent()
        task.join()
        assertEquals(listOf(30.0, 40.0), calls.takeLast(2))
    }

    @Test fun pausedPlaybackRetainsTheRemainingOriginalCropFallbacks() = runTest {
        val samples = listOf(clip(20.0), clip(0.0), clip(18.0), clip(16.0), clip(14.0), clip(12.0))
        val calls = mutableListOf<Double>()
        val task = launch { DialogueRecoveryCoordinator.run({ samples }, { calls.add(it.start); emptyList() }, {}, clock = { testScheduler.currentTime }) }
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(4, calls.size)
        task.join()
        assertEquals(samples.map { it.start }, calls)
        assertTrue(testScheduler.currentTime >= 2_000)
    }
}
