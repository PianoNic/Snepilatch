package ch.snepilatch.app.logic.shared

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shape the Spfy web client debounces its library invalidation with — leading edge, trailing
 * edge, and a maxWait ceiling. Pinned here because getting any of the three wrong is invisible:
 * the library would just refresh too eagerly or not at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebouncerTest {

    @Test fun `a lone request fires once, immediately`() = runTest {
        var fired = 0
        val clock = testScheduler
        val debouncer = Debouncer(this, 200, 1000, now = { clock.currentTime }) { fired++ }

        debouncer.request()
        assertEquals("leading edge is not delayed", 1, fired)

        advanceUntilIdle()
        // No trailing run: nothing arrived during the window to justify one.
        assertEquals(1, fired)
    }

    @Test fun `a burst collapses into a leading and a single trailing run`() = runTest {
        var fired = 0
        val clock = testScheduler
        val debouncer = Debouncer(this, 200, 1000, now = { clock.currentTime }) { fired++ }

        debouncer.request()
        repeat(4) {
            advanceTimeBy(50)
            debouncer.request()
        }
        assertEquals("still just the leading run while the burst is going", 1, fired)

        advanceUntilIdle()
        assertEquals(2, fired)
    }

    @Test fun `a burst that never stops still runs at maxWait`() = runTest {
        var fired = 0
        val clock = testScheduler
        val debouncer = Debouncer(this, 200, 1000, now = { clock.currentTime }) { fired++ }

        // A request every 100ms keeps resetting the 200ms wait, so only maxWait can let one through.
        repeat(30) {
            debouncer.request()
            advanceTimeBy(100)
        }
        advanceUntilIdle()

        // 3s of unbroken traffic: the leading run plus roughly one per second.
        assertEquals(4, fired)
    }

    @Test fun `a quiet period starts a fresh leading run`() = runTest {
        var fired = 0
        val clock = testScheduler
        val debouncer = Debouncer(this, 200, 1000, now = { clock.currentTime }) { fired++ }

        debouncer.request()
        advanceUntilIdle()
        debouncer.request()
        assertEquals("the second burst leads again rather than waiting", 2, fired)
    }
}
