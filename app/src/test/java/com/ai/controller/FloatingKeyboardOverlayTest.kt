package com.ai.controller

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for FloatingKeyboardOverlay: the show/hide/toggle state machine, the
 * remembered window position across hide/show, the drag-handle label/state, the
 * nudge clamping math, and the built key grid (full QWERTY + punctuation).
 *
 * Key clicks that would type into another app are safe here: the backing
 * service is a bare Robolectric-built ControllerAccessibilityService with no
 * focused node, so typeCharacter() is a no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingKeyboardOverlayTest {

    private lateinit var service: ControllerAccessibilityService
    private lateinit var overlay: FloatingKeyboardOverlay

    @Before
    fun setUp() {
        service = Robolectric.buildService(ControllerAccessibilityService::class.java).create().get()
        overlay = FloatingKeyboardOverlay(service)
    }

    private fun allButtons(): List<Button> {
        val root = overlay.contentRootForTest() ?: return emptyList()
        val out = mutableListOf<Button>()
        fun walk(v: View) {
            if (v is Button) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun buttonWithText(text: String): Button? =
        allButtons().firstOrNull { it.text.toString() == text }

    // ── state machine ─────────────────────────────────────────────────────

    @Test
    fun `starts hidden`() {
        assertFalse(overlay.isShowing())
        assertNull(overlay.contentRootForTest())
        assertNull(overlay.windowPositionForTest())
    }

    @Test
    fun `show then hide`() {
        overlay.show()
        assertTrue(overlay.isShowing())
        assertNotNull(overlay.contentRootForTest())
        overlay.hide()
        assertFalse(overlay.isShowing())
        assertNull(overlay.contentRootForTest())
    }

    @Test
    fun `toggle flips visibility`() {
        overlay.toggle()
        assertTrue(overlay.isShowing())
        overlay.toggle()
        assertFalse(overlay.isShowing())
    }

    @Test
    fun `show is idempotent`() {
        overlay.show()
        val root = overlay.contentRootForTest()
        overlay.show()
        assertTrue(overlay.isShowing())
        assertSame(root, overlay.contentRootForTest())
    }

    @Test
    fun `hide when hidden is a no-op`() {
        overlay.hide()
        assertFalse(overlay.isShowing())
    }

    @Test
    fun `handleTapAt returns false when hidden`() {
        assertFalse(overlay.handleTapAt(100f, 100f))
    }

    // ── position memory ───────────────────────────────────────────────────

    @Test
    fun `nudge moves the window and the position survives hide and show`() {
        overlay.show()
        val (x0, y0) = overlay.windowPositionForTest() ?: error("no window position")
        overlay.nudgePosition(100, 50)
        val (x1, y1) = overlay.windowPositionForTest() ?: error("no window position after nudge")
        assertEquals(x0 + 100, x1)
        assertEquals(y0 + 50, y1)
        overlay.hide()
        assertNull(overlay.windowPositionForTest())
        overlay.show()
        val (x2, y2) = overlay.windowPositionForTest() ?: error("no window position after re-show")
        assertEquals(x1, x2)
        assertEquals(y1, y2)
    }

    // ── clampNudgePosition math ────────────────────────────────────────────

    @Test
    fun `clampNudgePosition applies a plain move`() {
        assertEquals(150 to 130, clampNudgePosition(100, 100, 50, 30, 300, 200, 1080, 2400))
    }

    @Test
    fun `clampNudgePosition clamps negative moves at zero`() {
        assertEquals(0 to 0, clampNudgePosition(10, 10, -500, -500, 300, 200, 1080, 2400))
    }

    @Test
    fun `clampNudgePosition subtracts the panel size at the far edge`() {
        // the OCR-found bug: clamping to the raw screen edge without subtracting the
        // panel's own size let the panel drift mostly off-screen.
        assertEquals(780 to 2200, clampNudgePosition(700, 2100, 500, 500, 300, 200, 1080, 2400))
    }

    @Test
    fun `clampNudgePosition pins an oversized panel at the origin`() {
        assertEquals(0 to 0, clampNudgePosition(100, 100, -50, -50, 2000, 3000, 1080, 2400))
        assertEquals(0 to 0, clampNudgePosition(100, 100, 50, 50, 2000, 3000, 1080, 2400))
    }

    // ── drag handle ───────────────────────────────────────────────────────

    @Test
    fun `drag handle label and dragging state`() {
        overlay.show()
        assertFalse(overlay.isDragging())
        assertEquals("✥ Move", overlay.dragHandleLabel())
        buttonWithText("✥ Move")?.performClick() ?: error("drag handle not found")
        assertTrue(overlay.isDragging())
        assertEquals("✋ D-pad to move — click to drop", overlay.dragHandleLabel())
        buttonWithText("✋ D-pad to move — click to drop")?.performClick() ?: error("drag handle not found after arming")
        assertFalse(overlay.isDragging())
        assertEquals("✥ Move", overlay.dragHandleLabel())
    }

    // ── key grid ──────────────────────────────────────────────────────────

    @Test
    fun `key grid has the full alphabet plus punctuation`() {
        overlay.show()
        val texts = allButtons().map { it.text.toString() }.toSet()
        for (ch in 'a'..'z') assertTrue("missing key $ch", texts.contains(ch.toString()))
        for (sym in listOf(",", ".", "?", "!", "'", "-", "/", ":", ";", "@")) {
            assertTrue("missing punctuation $sym", texts.contains(sym))
        }
    }

    @Test
    fun `shift uppercases keys and clears after one keypress`() {
        overlay.show()
        assertNotNull(buttonWithText("q"))
        buttonWithText("⇧")?.performClick() ?: error("shift key not found")
        assertNotNull("expected uppercase Q after shift", buttonWithText("Q"))
        assertNull(buttonWithText("q"))
        // clicking a letter commits (no-op here) and auto-clears shift
        buttonWithText("Q")?.performClick() ?: error("Q key not found")
        assertNotNull(buttonWithText("q"))
        assertNull(buttonWithText("Q"))
    }

    @Test
    fun `font size buttons persist the new step`() {
        overlay.show()
        val before = KeyboardFontSizeStore.load(service)
        buttonWithText("A+")?.performClick() ?: error("A+ not found")
        assertEquals(KeyboardFontSizeStore.larger(before), KeyboardFontSizeStore.load(service), 0.001f)
        buttonWithText("A-")?.performClick() ?: error("A- not found")
        assertEquals(before, KeyboardFontSizeStore.load(service), 0.001f)
    }

    @Test
    fun `style row shows all five modes`() {
        overlay.show()
        for (mode in TextStyles.Mode.values()) {
            assertNotNull("missing style button for $mode", buttonWithText(styleLabel(mode)))
        }
    }
}
