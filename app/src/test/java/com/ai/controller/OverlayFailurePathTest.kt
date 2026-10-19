package com.ai.controller

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PointF
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Final-push coverage: the WindowManager failure branches on the three
 * overlays — addView/removeView/updateViewLayout throwing
 * BadTokenException / IllegalArgumentException / IllegalStateException.
 *
 * The overlays grab WindowManager from the Context in their constructors,
 * so a ContextWrapper swaps in a throwing delegating fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayFailurePathTest {

    private class FakeWindowManager(private val delegate: WindowManager) : WindowManager by delegate {
        var addThrows: Throwable? = null
        var removeThrows: Throwable? = null
        var updateThrows: Throwable? = null

        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            addThrows?.let { throw it }
            delegate.addView(view, params)
        }

        override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
            updateThrows?.let { throw it }
            delegate.updateViewLayout(view, params)
        }

        override fun removeView(view: View) {
            removeThrows?.let { throw it }
            delegate.removeView(view)
        }
    }

    private fun fakeContext(): Pair<Context, FakeWindowManager> {
        val app = RuntimeEnvironment.getApplication()
        val real = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fake = FakeWindowManager(real)
        val ctx = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) fake else super.getSystemService(name)
        }
        return ctx to fake
    }

    // ── LegendOverlay ────────────────────────────────────────────────────

    @Test
    fun `legend show absorbs BadTokenException`() {
        val (ctx, fake) = fakeContext()
        val overlay = LegendOverlay(ctx)
        fake.addThrows = WindowManager.BadTokenException("no token")
        overlay.show() // must not throw
        assertFalse(overlay.isShowing())
    }

    @Test
    fun `legend hide absorbs removeView failure`() {
        val (ctx, fake) = fakeContext()
        val overlay = LegendOverlay(ctx)
        overlay.show()
        assertTrue(overlay.isShowing())
        fake.removeThrows = IllegalArgumentException("not attached")
        overlay.hide() // must not throw
        assertFalse(overlay.isShowing())
    }

    @Test
    fun `legend update absorbs updateViewLayout failure`() {
        val (ctx, fake) = fakeContext()
        val overlay = LegendOverlay(ctx)
        overlay.show()
        fake.updateThrows = IllegalArgumentException("not attached")
        overlay.update(PointF(10f, 20f), "A: tap") // must not throw
        assertTrue(overlay.isShowing())
    }

    // ── DebugOverlay ─────────────────────────────────────────────────────

    @Test
    fun `debug show absorbs BadTokenException`() {
        val (ctx, fake) = fakeContext()
        val overlay = DebugOverlay(ctx)
        fake.addThrows = WindowManager.BadTokenException("no token")
        overlay.show() // must not throw
        assertFalse(overlay.isShowing())
    }

    @Test
    fun `debug hide absorbs removeView failure`() {
        val (ctx, fake) = fakeContext()
        val overlay = DebugOverlay(ctx)
        overlay.show()
        assertTrue(overlay.isShowing())
        fake.removeThrows = IllegalArgumentException("not attached")
        overlay.hide() // must not throw
        assertFalse(overlay.isShowing())
    }

    // ── CursorOverlay ────────────────────────────────────────────────────

    @Test
    fun `cursor show absorbs BadTokenException`() {
        val (ctx, fake) = fakeContext()
        val overlay = CursorOverlay(ctx)
        fake.addThrows = WindowManager.BadTokenException("no token")
        overlay.show() // must not throw
        assertFalse(overlay.isShowing())
    }

    @Test
    fun `cursor setTouchTransparent absorbs updateViewLayout failure`() {
        val (ctx, fake) = fakeContext()
        val overlay = CursorOverlay(ctx)
        // show() itself calls setTouchTransparent(true), exercising the catch.
        fake.updateThrows = IllegalArgumentException("not attached")
        overlay.show() // must not throw
        assertTrue(overlay.isShowing())
    }

    @Test
    fun `cursor hide absorbs removeView failure`() {
        val (ctx, fake) = fakeContext()
        val overlay = CursorOverlay(ctx)
        overlay.show()
        fake.removeThrows = IllegalArgumentException("not attached")
        overlay.hide() // must not throw
        assertFalse(overlay.isShowing())
    }

    @Test
    fun `cursor raise recovers when removeView throws`() {
        val (ctx, fake) = fakeContext()
        val overlay = CursorOverlay(ctx)
        overlay.show()
        fake.removeThrows = IllegalArgumentException("not attached")
        overlay.raise() // catch -> hide() -> show(); must not throw
        assertTrue(overlay.isShowing())
    }

    @Test
    fun `cursor interpolate absorbs updateViewLayout failure`() {
        val (ctx, fake) = fakeContext()
        val overlay = CursorOverlay(ctx)
        overlay.show()
        fake.updateThrows = IllegalArgumentException("not attached")
        val m = CursorOverlay::class.java.getDeclaredMethod("interpolate").apply {
            isAccessible = true
        }
        m.invoke(overlay) // must not throw
    }
}
