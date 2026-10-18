package com.ai.controller

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Crew B — Robolectric tests for [PttController]'s edge-detection state machine.
 *
 * The debounce logic keys off `SystemClock.elapsedRealtime()`, which Robolectric
 * shadows: [ShadowSystemClock.advanceBy] moves it deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PttControllerTest {

    @Before
    fun setUp() {
        // Start the shadow clock well past the debounce window so the initial
        // lastEdgeMs=0 never accidentally debounces the first press of a test.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
    }

    private fun controller(
        onStart: () -> Unit = {},
        onStop: () -> Unit = {},
        onCancel: () -> Unit = {}
    ) = PttController(onStart, onStop, onCancel)

    @Test
    fun `press and release fires start then stop`() {
        var started = false
        var stopped = false
        val c = controller({ started = true }, { stopped = true })
        c.onButtonDown()
        assertTrue(c.isHeld)
        assertTrue(started)
        c.onButtonUp()
        assertFalse(c.isHeld)
        assertTrue(stopped)
    }

    @Test
    fun `up without down is ignored`() {
        var stopped = false
        val c = controller(onStop = { stopped = true })
        c.onButtonUp()
        assertFalse(stopped)
        assertFalse(c.isHeld)
    }

    @Test
    fun `rapid re-press within debounce window is dropped`() {
        val starts = AtomicInteger(0)
        val c = controller(onStart = { starts.incrementAndGet() })
        c.onButtonDown()
        c.onButtonUp()
        // Immediate re-press: now - lastEdgeMs < DEBOUNCE_MS(150) -> dropped.
        c.onButtonDown()
        assertEquals(1, starts.get())
        assertFalse(c.isHeld)
    }

    @Test
    fun `press after debounce window fires again`() {
        val starts = AtomicInteger(0)
        val c = controller(onStart = { starts.incrementAndGet() })
        c.onButtonDown()
        c.onButtonUp()
        ShadowSystemClock.advanceBy(Duration.ofMillis(200))
        c.onButtonDown()
        assertEquals(2, starts.get())
        assertTrue(c.isHeld)
    }

    @Test
    fun `second down while held cancels instead of restarting`() {
        var starts = 0
        var stops = 0
        var cancels = 0
        val c = controller({ starts++ }, { stops++ }, { cancels++ })
        c.onButtonDown()
        c.onButtonDown() // double-press gesture -> explicit cancel
        assertEquals(1, starts)
        assertEquals(0, stops)
        assertEquals(1, cancels)
        assertFalse(c.isHeld)
    }

    @Test
    fun `cancel edge refreshes the debounce window`() {
        // The cancel tap counts as the last edge: an immediate re-press is
        // debounced, even though the ORIGINAL down edge is long past. (This
        // pins the behavior the "refresh lastEdgeMs" comment describes.)
        val starts = AtomicInteger(0)
        val c = controller(onStart = { starts.incrementAndGet() })
        c.onButtonDown() // start edge
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        c.onButtonDown() // cancel edge at T+2s refreshes lastEdgeMs
        c.onButtonDown() // immediate re-press -> debounced off the cancel edge
        assertEquals(1, starts.get())
        assertFalse(c.isHeld)
        // ...but a press after the debounce window fires normally.
        ShadowSystemClock.advanceBy(Duration.ofMillis(200))
        c.onButtonDown()
        assertEquals(2, starts.get())
        assertTrue(c.isHeld)
    }


    @Test
    fun `up after cancel is ignored`() {
        var stops = 0
        var cancels = 0
        val c = controller(onStop = { stops++ }, onCancel = { cancels++ })
        c.onButtonDown()
        c.onButtonDown() // cancel
        c.onButtonUp()   // nothing held -> ignored
        assertEquals(1, cancels)
        assertEquals(0, stops)
    }

    @Test
    fun `throwing onStart callback does not corrupt state`() {
        val c = controller(onStart = { throw RuntimeException("boom") })
        c.onButtonDown() // exception swallowed by runCallback...
        assertTrue(c.isHeld) // ...but the transition was already committed
        c.onButtonUp()
        assertFalse(c.isHeld)
    }

    @Test
    fun `throwing onCancel callback still releases held state`() {
        val c = controller(onCancel = { throw RuntimeException("boom") })
        c.onButtonDown()
        c.onButtonDown() // cancel callback throws; state must still be released
        assertFalse(c.isHeld)
    }

    @Test
    fun `reset clears held state without firing callbacks`() {
        var starts = 0
        var stops = 0
        val c = controller({ starts++ }, { stops++ })
        c.onButtonDown()
        c.reset()
        assertFalse(c.isHeld)
        c.onButtonUp() // nothing held -> ignored, no stop callback
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test
    fun `concurrent presses from many threads fire exactly one start`() {
        // 8 threads race a single press. Under the lock they serialize:
        // the first wins the start edge, the next sees held=true and cancels,
        // the rest are debounced off the cancel edge (shadow clock is frozen
        // mid-race). No lost updates, no corrupt state, exactly one onStart.
        val starts = AtomicInteger(0)
        val cancels = AtomicInteger(0)
        val c = controller(
            onStart = { starts.incrementAndGet() },
            onCancel = { cancels.incrementAndGet() }
        )
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.submit {
                ready.await()
                c.onButtonDown()
                done.countDown()
            }
        }
        ready.countDown()
        assertTrue("threads did not finish", done.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(1, starts.get())
        assertEquals(1, cancels.get())
        assertFalse(c.isHeld)
    }

    @Test
    fun `concurrent down-up pairs never corrupt the state machine`() {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val cancels = AtomicInteger(0)
        val c = controller(
            onStart = { starts.incrementAndGet() },
            onStop = { stops.incrementAndGet() },
            onCancel = { cancels.incrementAndGet() }
        )
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.submit {
                repeat(25) {
                    c.onButtonDown()
                    c.onButtonUp()
                }
                done.countDown()
            }
        }
        assertTrue("threads did not finish", done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        // Every committed start edge is eventually closed by exactly one
        // stop-or-cancel edge; nothing is lost or double-counted. A press may
        // still be held when the threads finish, accounting for one open edge.
        val closed = stops.get() + cancels.get()
        if (c.isHeld) {
            assertEquals(starts.get(), closed + 1)
        } else {
            assertEquals(starts.get(), closed)
        }
    }
}
