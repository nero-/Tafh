package com.jossephus.chuchu.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual-time harness: [scheduleDelayed] only records the block, so the test
 * decides exactly when the deferred frame fires.
 */
private class SchedulerHarness {
    var now = 0L
    var emits = 0
    val emitTimes = mutableListOf<Long>()
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()

    private val scheduler =
        SnapshotScheduler(
            intervalMs = SNAPSHOT_MIN_INTERVAL_MS,
            now = { now },
            scheduleDelayed = { delayMs, block -> pending += (now + delayMs) to block },
            emit = {
                emits += 1
                emitTimes += now
            },
        )

    fun request(force: Boolean = false) = scheduler.request(force)

    /** Advances virtual time to [timeMs], running every deferred frame that is due. */
    fun advanceTo(timeMs: Long) {
        now = timeMs
        while (true) {
            val due = pending.filter { it.first <= now }.minByOrNull { it.first } ?: break
            pending.remove(due)
            due.second()
        }
    }

    val pendingCount: Int get() = pending.size
}

class SnapshotSchedulerTest {
    @Test
    fun hundredRequestsAtSameTimestampEmitOnce() {
        val harness = SchedulerHarness()
        repeat(100) { harness.request() }
        assertEquals(1, harness.emits)
        assertEquals(1, harness.pendingCount)
    }

    @Test
    fun requestInsideWindowDefersExactlyOneEmitToBoundary() {
        val harness = SchedulerHarness()
        harness.request()
        assertEquals(1, harness.emits)

        harness.advanceTo(5L)
        repeat(10) { harness.request() }
        assertEquals(1, harness.emits)
        assertEquals(1, harness.pendingCount)

        harness.advanceTo(SNAPSHOT_MIN_INTERVAL_MS)
        assertEquals(2, harness.emits)
        assertEquals(0, harness.pendingCount)
    }

    @Test
    fun forceInsideWindowDoesNotBypass() {
        val harness = SchedulerHarness()
        harness.request()
        assertEquals(1, harness.emits)

        harness.advanceTo(5L)
        harness.request(force = true)
        assertEquals(1, harness.emits)
        assertEquals(1, harness.pendingCount)

        harness.advanceTo(SNAPSHOT_MIN_INTERVAL_MS)
        assertEquals(2, harness.emits)
        assertEquals(listOf(0L, SNAPSHOT_MIN_INTERVAL_MS), harness.emitTimes)
    }

    @Test
    fun forceAtBoundaryEmitsImmediately() {
        val harness = SchedulerHarness()
        harness.request()
        assertEquals(1, harness.emits)

        harness.advanceTo(SNAPSHOT_MIN_INTERVAL_MS)
        harness.request(force = true)
        assertEquals(2, harness.emits)
        assertEquals(0, harness.pendingCount)
    }

    @Test
    fun failedEmitDoesNotConsumeTheFrameBudget() {
        var scheduler: SnapshotScheduler? = null
        var nowMs = 0L
        var successfulEmits = 0
        var failuresLeft = 1
        scheduler =
            SnapshotScheduler(
                intervalMs = SNAPSHOT_MIN_INTERVAL_MS,
                now = { nowMs },
                scheduleDelayed = { _, _ -> },
                emit = {
                    if (failuresLeft > 0) {
                        failuresLeft -= 1
                        scheduler?.markEmitFailed()
                    } else {
                        successfulEmits += 1
                    }
                },
            )
        val live = checkNotNull(scheduler)

        nowMs = 0L
        live.request()
        // The failed attempt gave the frame budget back, so the retry is
        // allowed to run at the same instant instead of waiting a frame.
        live.request()
        assertEquals(1, successfulEmits)
    }

    @Test
    fun interleavedBurstsNeverExceedOneEmitPerWindow() {
        val harness = SchedulerHarness()
        for (t in 0L..1_000L) {
            harness.advanceTo(t)
            harness.request(force = t % 7L == 0L)
            if (t % 3L == 0L) harness.request()
        }
        harness.advanceTo(2_000L)

        // One frame per 16 ms window across the 0..1000 ms stream, plus the
        // trailing coalesced frame.
        assertEquals((1_000L / SNAPSHOT_MIN_INTERVAL_MS + 2L).toInt(), harness.emits)
        val sorted = harness.emitTimes.sorted()
        for (i in 1 until sorted.size) {
            assertTrue(
                "emits at ${sorted[i - 1]} and ${sorted[i]} are closer than the frame budget",
                sorted[i] - sorted[i - 1] >= SNAPSHOT_MIN_INTERVAL_MS,
            )
        }
    }
}

class TerminalReadDelayTest {
    @Test
    fun pollsAtMinWhileInputEchoIsExpected() {
        assertEquals(MIN_READ_DELAY_MS, terminalReadDelayMs(idleForMs = 0L, sinceLastInputMs = 0L))
        assertEquals(
            MIN_READ_DELAY_MS,
            terminalReadDelayMs(idleForMs = 10_000L, sinceLastInputMs = 249L),
        )
    }

    @Test
    fun idleCeilingIsHalved() {
        assertEquals(32L, MAX_READ_DELAY_MS)
        assertEquals(
            MAX_READ_DELAY_MS,
            terminalReadDelayMs(idleForMs = 10_000L, sinceLastInputMs = INPUT_ECHO_WINDOW_MS),
        )
    }

    @Test
    fun idleTiersStillRamp() {
        assertEquals(MIN_READ_DELAY_MS, terminalReadDelayMs(idleForMs = 0L, sinceLastInputMs = 10_000L))
        assertEquals(
            NEAR_IDLE_READ_DELAY_MS,
            terminalReadDelayMs(idleForMs = 100L, sinceLastInputMs = 10_000L),
        )
        assertEquals(
            IDLE_READ_DELAY_MS,
            terminalReadDelayMs(idleForMs = 1_000L, sinceLastInputMs = 10_000L),
        )
        assertEquals(
            MAX_READ_DELAY_MS,
            terminalReadDelayMs(idleForMs = 5_000L, sinceLastInputMs = 10_000L),
        )
    }
}
