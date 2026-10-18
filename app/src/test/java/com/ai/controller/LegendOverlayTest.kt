package com.ai.controller

import android.graphics.PointF
import android.view.View
import android.widget.TextView
import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
import com.ai.controller.models.SwipeDirection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Crew B — Robolectric tests for [LegendOverlay].
 *
 * WindowManager add/remove/update calls are absorbed by Robolectric's
 * ShadowWindowManager; the tests assert the overlay's own attach state,
 * view text/visibility, and the pure legend-text builder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegendOverlayTest {

    private fun overlay() = LegendOverlay(RuntimeEnvironment.getApplication())

    private fun textViewOf(o: LegendOverlay): TextView? {
        val field = LegendOverlay::class.java.getDeclaredField("view")
        field.isAccessible = true
        return field.get(o) as TextView?
    }

    private fun testProfile(): ControllerProfile = ControllerProfile(
        name = "Test",
        mappings = mutableMapOf(
            ControllerInput.BUTTON_A to ButtonAction.tap("Click"),
            ControllerInput.BUTTON_B to ButtonAction.back("Bksp"),
            ControllerInput.BUTTON_X to ButtonAction(ActionType.SWIPE, swipeDirection = SwipeDirection.UP),
            ControllerInput.BUTTON_START to ButtonAction.none()
        )
    )

    // ── show/hide state ──────────────────────────────────────────────────

    @Test
    fun `show attaches and isShowing becomes true`() {
        val o = overlay()
        assertFalse(o.isShowing())
        o.show()
        assertTrue(o.isShowing())
    }

    @Test
    fun `show is idempotent`() {
        val o = overlay()
        o.show()
        val first = textViewOf(o)
        o.show()
        assertTrue(o.isShowing())
        assertSame(first, textViewOf(o)) // no second view created
    }

    @Test
    fun `hide detaches and clears the view`() {
        val o = overlay()
        o.show()
        o.hide()
        assertFalse(o.isShowing())
        assertNull(textViewOf(o))
    }

    @Test
    fun `hide before show is a safe no-op`() {
        val o = overlay()
        o.hide() // must not throw
        assertFalse(o.isShowing())
    }

    @Test
    fun `hide is idempotent`() {
        val o = overlay()
        o.show()
        o.hide()
        o.hide()
        assertFalse(o.isShowing())
    }

    // ── update / setVisible ──────────────────────────────────────────────

    @Test
    fun `update before show is a safe no-op`() {
        val o = overlay()
        o.update(PointF(10f, 20f), "A:Click") // view == null -> returns
        assertFalse(o.isShowing())
    }

    @Test
    fun `update after show sets the legend text`() {
        val o = overlay()
        o.show()
        val legend = LegendOverlay.legendTextFor(testProfile())
        o.update(PointF(100f, 200f), legend)
        assertEquals(legend, textViewOf(o)!!.text.toString())
        assertTrue(o.isShowing())
    }

    @Test
    fun `setVisible toggles view visibility`() {
        val o = overlay()
        o.show()
        o.setVisible(false)
        assertEquals(View.GONE, textViewOf(o)!!.visibility)
        o.setVisible(true)
        assertEquals(View.VISIBLE, textViewOf(o)!!.visibility)
    }

    @Test
    fun `setVisible before show is a safe no-op`() {
        val o = overlay()
        o.setVisible(false) // view == null -> no crash
        assertFalse(o.isShowing())
    }

    // ── legendTextFor ────────────────────────────────────────────────────

    @Test
    fun `legendTextFor uses parity labels and falls back per slot`() {
        val text = LegendOverlay.legendTextFor(testProfile())
        assertTrue("A slot uses its label", text.contains("A:Click"))
        assertTrue("B slot uses its label", text.contains("B:Bksp"))
        assertTrue(
            "blank label falls back to lowercased type name",
            text.contains("X:swipe")
        )
        assertTrue("unmapped slot renders none", text.contains("Y:none"))
        assertTrue("NONE action with blank label renders none", text.contains("☰:none"))
    }

    @Test
    fun `legendTextFor groups four slots per line`() {
        val text = LegendOverlay.legendTextFor(testProfile())
        assertEquals(3, text.split("\n").size) // 12 slots, chunked(4)
        for (line in text.split("\n")) {
            assertEquals(4, line.split("   ").size)
        }
    }

    @Test
    fun `legendTextFor covers every slot in fixed order`() {
        val text = LegendOverlay.legendTextFor(ControllerProfile.default())
        val compact = text.replace("\n", " ")
        val order = listOf("A:", "B:", "X:", "Y:", "LB:", "RB:", "LT:", "RT:", "⧉:", "☰:", "LS:", "RS:")
        var lastIndex = -1
        for (slot in order) {
            val idx = compact.indexOf(slot)
            assertTrue("slot $slot present", idx >= 0)
            assertTrue("slot $slot in order", idx > lastIndex)
            lastIndex = idx
        }
    }
}
