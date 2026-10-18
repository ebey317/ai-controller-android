package com.ai.controller

import android.graphics.PointF
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Crew B — Robolectric tests for [CursorOverlay].
 *
 * The WindowManager is shadowed; the 60fps render loop runs on the main
 * looper, which the tests drive with [ShadowLooper.idleMainLooper].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CursorOverlayTest {

    private fun overlay() = CursorOverlay(RuntimeEnvironment.getApplication())

    private fun layoutParamsOf(o: CursorOverlay): WindowManager.LayoutParams? {
        val field = CursorOverlay::class.java.getDeclaredField("layoutParams")
        field.isAccessible = true
        return field.get(o) as WindowManager.LayoutParams?
    }

    private fun screenSize(): PointF {
        val dm = RuntimeEnvironment.getApplication().resources.displayMetrics
        return PointF(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
    }

    // ── show/hide state ──────────────────────────────────────────────────

    @Test
    fun `show attaches and hide detaches`() {
        val o = overlay()
        assertFalse(o.isShowing())
        o.show()
        assertTrue(o.isShowing())
        o.hide()
        assertFalse(o.isShowing())
    }

    @Test
    fun `show is idempotent`() {
        val o = overlay()
        o.show()
        val first = layoutParamsOf(o)
        o.show()
        assertTrue(o.isShowing())
        assertSame(first, layoutParamsOf(o))
    }

    @Test
    fun `hide before show and double hide are safe`() {
        val o = overlay()
        o.hide()
        o.show()
        o.hide()
        o.hide()
        assertFalse(o.isShowing())
    }

    // ── position logic ───────────────────────────────────────────────────

    @Test
    fun `cursor starts at screen center`() {
        val o = overlay()
        o.show()
        val screen = screenSize()
        val pos = o.getPosition()
        assertEquals(screen.x / 2f, pos.x, 0.001f)
        assertEquals(screen.y / 2f, pos.y, 0.001f)
        o.hide()
    }

    @Test
    fun `applyDelta before show is ignored`() {
        val o = overlay()
        o.applyDelta(500f, 500f) // must not throw; no target to move
        val pos = o.getPosition()
        assertEquals(0f, pos.x, 0.001f)
        assertEquals(0f, pos.y, 0.001f)
    }

    @Test
    fun `render loop lerps position toward clamped target`() {
        val o = overlay()
        o.show()
        val screen = screenSize()
        val startX = o.getPosition().x

        // A huge delta must clamp the target to the screen edge...
        o.applyDelta(100000f, 0f)
        // ...and the 60fps loop must move the visible position toward it.
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        val pos = o.getPosition()
        assertTrue("position moved toward target", pos.x > startX)
        assertTrue("position never leaves the screen", pos.x <= screen.x + 0.001f)
        o.hide()
    }

    @Test
    fun `negative delta moves cursor the other way`() {
        val o = overlay()
        o.show()
        val startX = o.getPosition().x
        o.applyDelta(-100000f, 0f)
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        val pos = o.getPosition()
        assertTrue("position moved left", pos.x < startX)
        assertTrue("position never leaves the screen", pos.x >= -0.001f)
        o.hide()
    }

    @Test
    fun `getPosition returns a copy`() {
        val o = overlay()
        o.show()
        val first = o.getPosition()
        first.x = 99999f // mutating the returned point must not move the cursor
        assertEquals(screenSize().x / 2f, o.getPosition().x, 0.001f)
        o.hide()
    }

    // ── touch transparency (bug A1 regression) ───────────────────────────

    @Test
    fun `cursor is touch transparent after show`() {
        val o = overlay()
        o.show()
        val params = layoutParamsOf(o)!!
        assertTrue(
            "FLAG_NOT_TOUCHABLE set",
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        )
        assertTrue(
            "FLAG_NOT_FOCUSABLE set",
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        )
        o.hide()
    }

    @Test
    fun `setTouchTransparent clears the flags`() {
        val o = overlay()
        o.show()
        o.setTouchTransparent(false)
        val params = layoutParamsOf(o)!!
        assertEquals(
            0,
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        assertEquals(
            0,
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        o.hide()
    }

    @Test
    fun `setTouchTransparent before show is safe`() {
        val o = overlay()
        o.setTouchTransparent(true) // layoutParams == null -> no crash
        assertFalse(o.isShowing())
    }

    @Test
    fun `raise restacks the view without crashing`() {
        val o = overlay()
        o.raise() // not attached -> no-op
        o.show()
        o.raise() // remove + re-add under the shadow WindowManager
        assertTrue(o.isShowing())
        o.hide()
    }
}
