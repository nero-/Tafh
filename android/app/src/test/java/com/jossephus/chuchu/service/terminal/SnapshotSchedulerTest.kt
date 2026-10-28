package com.jossephus.chuchu.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual-time harness: [scheduleDelayed] only records the block, so the test decides exactly when
 * the deferred frame fires.
 */
private class SchedulerHarness {
    private var now = 0L
    private var emits = 0
    private val emitTimes = mutableListOf<Long>()
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

    fun emitCount(): Int = emits

    fun pendingCount(): Int = pending.size

    fun emittedAt(): List<Long> = emitTimes.toList()

    /** Moves the clock without running deferred frames. */
    fun setTime(timeMs: Long) {
        now = timeMs
    }

    /** Advances virtual time to [timeMs], running every deferred frame that is due. */
    fun advanceTo(timeMs: Long) {
        while (true) {
            val due = pending.filter { it.first <= timeMs }.minByOrNull { it.first } ?: break
            pending.remove(due)
            now = due.first
            due.second()
        }
        now = timeMs
    }
}

class SnapshotSchedulerTest {
    @Test
    fun hundredRequestsAtSameTimestampEmitOnce() {
        val harness = SchedulerHarness()
        repeat(100) { harness.request() }

        assertEquals(1, harness.emitCount())
        assertEquals(1, harness.pendingCount())
    }

    @Test
    fun requestAfterFiveMsEmitsOnlyAtTheBoundary() {
        val harness = SchedulerHarness()
        harness.request()
        assertEquals(1, harness.emitCount())

        harness.advanceTo(5L)
        repeat(10) { harness.request() }
        assertEquals(1, harness.emitCount())

        harness.advanceTo(SNAPSHOT_MIN_INTERVAL_MS)
        assertEquals(2, harness.emitCount())
        assertEquals(0, harness.pendingCount())
    }

    @Test
    fun forceAfterFiveMsDoesNotBypassTheBudget() {
        val harness = SchedulerHarness()
        harness.request()

        harness.advanceTo(5L)
        harness.request(force = true)
        assertEquals(1, harness.emitCount())

        harness.advanceTo(SNAPSHOT_MIN_INTERVAL_MS)
        assertEquals(2, harness.emitCount())
        assertEquals(listOf(0L, SNAPSHOT_MIN_INTERVAL_MS), harness.emittedAt())
    }

    @Test
    fun forceAtTheBoundaryEmitsImmediately() {
        val harness = SchedulerHarness()
        harness.request()
        assertEquals(1, harness.emitCount())

        harness.setTime(SNAPSHOT_MIN_INTERVAL_MS)
        harness.request(force = true)
        assertEquals(2, harness.emitCount())

        // The frame that was already deferred for this instant is superseded:
        // running the clock on must not add a third emit.
        harness.advanceTo(SNAPSHOT_MIN_INTERVAL_MS * 4)
        assertEquals(2, harness.emitCount())
    }

    @Test
    fun failedEmitGivesTheFrameBudgetBack() {
        var nowMs = 0L
        var successfulEmits = 0
        var failuresLeft = 1
        var scheduler: SnapshotScheduler? = null
        val live =
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
        scheduler = live

        live.request()
        // The failed attempt did not consume the budget, so the retry runs
        // immediately instead of being pushed a frame out.
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

        // Frames at 0, 16, 32, ... 1000 ms, plus the coalesced trailing frame.
        assertEquals((1_000L / SNAPSHOT_MIN_INTERVAL_MS + 2L).toInt(), harness.emitCount())
        val times = harness.emittedAt()
        for (i in 1 until times.size) {
            assertTrue(
                "emits at ${times[i - 1]} and ${times[i]} are closer than the frame budget",
                times[i] - times[i - 1] >= SNAPSHOT_MIN_INTERVAL_MS,
            )
        }
    }
}

class TerminalReadDelayTest {
    @Test
    fun pollsAtMinDuringThePostInputEchoWindow() {
        assertEquals(MIN_READ_DELAY_MS, terminalReadDelayMs(idleForMs = 0L, sinceLastInputMs = 0L))
        assertEquals(
            MIN_READ_DELAY_MS,
            terminalReadDelayMs(idleForMs = 10_000L, sinceLastInputMs = 249L),
        )
    }

    @Test
    fun idleCeilingIsHalvedTo32ms() {
        assertEquals(32L, MAX_READ_DELAY_MS)
        assertEquals(
            MAX_READ_DELAY_MS,
            terminalReadDelayMs(idleForMs = 10_000L, sinceLastInputMs = INPUT_ECHO_WINDOW_MS),
        )
    }

    @Test
    fun idleTiersStillRamp() {
        assertEquals(
            MIN_READ_DELAY_MS,
            terminalReadDelayMs(idleForMs = 0L, sinceLastInputMs = 10_000L),
        )
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
