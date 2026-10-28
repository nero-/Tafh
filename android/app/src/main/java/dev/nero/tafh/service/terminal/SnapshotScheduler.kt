package dev.nero.tafh.service.terminal

/**
 * Coalescing scheduler for terminal snapshot emissions.
 *
 * The engine publishes at most one snapshot per [intervalMs] frame budget: a burst of read-loop
 * chunks, key writes and scroll requests collapses into a single native snapshot instead of one per
 * event.
 *
 * Rules:
 * - [request] emits immediately when at least [intervalMs] has elapsed since the last emit (or when
 *   nothing has been emitted yet).
 * - a request inside the budget schedules exactly one deferred emit at `intervalMs - elapsed` and
 *   drops further requests until it runs.
 * - [force] never bypasses the budget: it only means "flush now if the budget already allows it",
 *   otherwise it coalesces into the pending slot like any other request.
 *
 * Time and deferral are injected so the scheduler stays pure JVM code: the engine passes a
 * coroutine-backed [scheduleDelayed], tests pass virtual time.
 */
class SnapshotScheduler(
    private val intervalMs: Long = SNAPSHOT_MIN_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val scheduleDelayed: (delayMs: Long, block: () -> Unit) -> Unit,
    private val emit: () -> Unit,
) {
    private val lock = Any()
    private var lastEmitAtMs: Long? = null
    private var previousEmitAtMs: Long? = null
    private var pending = false

    // Invalidates already-scheduled blocks when an emit happens first, so a
    // late timer callback can never add a second emit inside one window.
    private var scheduledToken = 0

    /**
     * Requests a snapshot emission, coalescing to at most one emit per [intervalMs] window. [force]
     * never bypasses that cap.
     *
     * Callable from the read lane and the session lane concurrently; the monitor also publishes the
     * state updates to the deferred block.
     */
    fun request(force: Boolean = false) {
        synchronized(lock) {
            val nowMs = now()
            val elapsed = elapsedSinceLastEmit(nowMs)
            if (force && elapsed < intervalMs) {
                // A forced flush inside the budget must still coalesce: fall
                // through to the pending slot instead of emitting.
                schedulePending(elapsed)
                return
            }
            if (elapsed >= intervalMs) {
                pending = false
                scheduledToken += 1
                emitNow(nowMs)
                return
            }
            schedulePending(elapsed)
        }
    }

    /**
     * The last [emit] failed, so it must not consume the frame budget: restore the timestamp from
     * before the failed attempt.
     */
    fun markEmitFailed() {
        synchronized(lock) { lastEmitAtMs = previousEmitAtMs }
    }

    /** Forgets the frame budget (session teardown). */
    fun reset() {
        synchronized(lock) {
            lastEmitAtMs = null
            previousEmitAtMs = null
            pending = false
            scheduledToken += 1
        }
    }

    private fun schedulePending(elapsed: Long) {
        if (pending) return
        pending = true
        val token = ++scheduledToken
        val waitMs = (intervalMs - elapsed).coerceAtLeast(1L)
        scheduleDelayed(waitMs) {
            synchronized(lock) {
                // Superseded by an emit that already happened.
                if (token != scheduledToken) return@synchronized
                pending = false
                emitNow(now())
            }
        }
    }

    private fun emitNow(nowMs: Long) {
        previousEmitAtMs = lastEmitAtMs
        lastEmitAtMs = nowMs
        emit()
    }

    private fun elapsedSinceLastEmit(nowMs: Long): Long {
        val last = lastEmitAtMs ?: return Long.MAX_VALUE
        return nowMs - last
    }
}

/** Minimum spacing between two snapshot emissions (one 60 Hz frame). */
const val SNAPSHOT_MIN_INTERVAL_MS: Long = 16L

/**
 * How long a requested geometry must stay unchanged before it is applied.
 *
 * The IME slide-in animation produces a new canvas size on every frame, and each applied size costs
 * a native grid rebuild plus a remote SIGWINCH (which makes TUIs like vim/htop redraw everything).
 * Coalescing on a short settle window turns that burst into the single resize the session should
 * actually see.
 */
const val RESIZE_SETTLE_MS: Long = 120L

/** Failed snapshot emissions retried before the frame budget is left alone. */
const val MAX_SNAPSHOT_RETRIES: Int = 3
