package ch.snepilatch.app.logic.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Collapses a burst of requests into as few calls as the shape allows.
 *
 * Same contract as the Spfy web client's library invalidation, which is
 * `debounce(invalidate, 200, { leading: true, trailing: true, maxWait: 1000 })`:
 *
 * - the first request after a quiet period runs [onFire] straight away, so one change is not delayed
 * - requests arriving inside the window collapse into one further run, [waitMs] after the last of them
 * - a burst that never lets up still runs every [maxWaitMs], rather than being starved
 *
 * A lone request therefore fires once, not twice: the trailing run only happens when something
 * actually arrived during the window. A capped run does not re-open the window either — a steady
 * drip gets one call per [maxWaitMs], not two.
 *
 * Every [request] must arrive on [scope]'s dispatcher — callers on another thread (a socket
 * callback, say) have to hop to it first.
 */
class Debouncer(
    private val scope: CoroutineScope,
    private val waitMs: Long,
    private val maxWaitMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val onFire: () -> Unit,
) {
    private var window: Job? = null
    private var lastRequestAt = 0L
    private var lastFiredAt = 0L
    private var pending = false

    fun request() {
        lastRequestAt = now()
        pending = true
        if (window == null) {
            fire()
            openWindow()
        }
    }

    /**
     * Held open for as long as requests keep arriving, firing whenever [maxWaitMs] has passed since
     * the last run, and once more on the way out if anything is still unserved.
     */
    private fun openWindow() {
        window = scope.launch {
            while (now() - lastRequestAt < waitMs) {
                val untilQuiet = waitMs - (now() - lastRequestAt)
                val untilCap = maxWaitMs - (now() - lastFiredAt)
                delay(minOf(untilQuiet, untilCap).coerceAtLeast(1))
                if (pending && now() - lastFiredAt >= maxWaitMs) fire()
            }
            if (pending) fire()
            window = null
        }
    }

    private fun fire() {
        lastFiredAt = now()
        pending = false
        onFire()
    }
}
