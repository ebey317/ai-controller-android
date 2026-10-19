package com.ai.controller

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text editing against a fake editable field: typeCharacter/typeText,
 * backspaceOnce, deleteNextOnce, commitTextOnce, injectText, pressEnter,
 * moveCaret, deleteToLineStart, clearOnFreshFocus, and fieldIdentity —
 * plus the no-target early-return paths with the keyboard closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextMutationTest {

    private lateinit var service: ControllerAccessibilityService

    /** Opens the floating keyboard and points the typing target at [field]. */
    private fun openKeyboardWithField(field: FakeField): FakeField {
        service.startCustomKeyboard()
        assertTrue(service.keyboardOverlay.isShowing())
        service.keyboardTypingTarget = field.node
        return field
    }

    @Before
    fun setUp() {
        service = connectTestService()
    }

    @After
    fun tearDown() {
        service.onDestroy()
    }

    @Test
    fun `typeCharacter appends at the caret`() {
        val field = openKeyboardWithField(FakeField("hi"))
        service.typeCharacter("!")
        assertEquals("hi!", field.text)
        assertEquals(listOf("hi!"), field.setTextCalls)
    }

    @Test
    fun `typeCharacter replaces the active selection`() {
        val field = FakeField("hello")
        field.node.setTextSelection(1, 4)
        openKeyboardWithField(field)
        service.typeCharacter("X")
        assertEquals("hXo", field.text)
    }

    @Test
    fun `typeText inserts a whole string at the caret`() {
        val field = FakeField("ab")
        field.node.setTextSelection(1, 1)
        openKeyboardWithField(field)
        service.typeText("XY")
        assertEquals("aXYb", field.text)
    }

    @Test
    fun `backspaceOnce deletes the char before the caret`() {
        val field = openKeyboardWithField(FakeField("ab"))
        service.backspaceOnce()
        assertEquals("a", field.text)
    }

    @Test
    fun `backspaceOnce removes the selection when one is active`() {
        val field = FakeField("hello")
        field.node.setTextSelection(1, 4)
        openKeyboardWithField(field)
        service.backspaceOnce()
        assertEquals("ho", field.text)
    }

    @Test
    fun `backspaceOnce at the start of the field is a no-op`() {
        val field = FakeField("ab")
        field.node.setTextSelection(0, 0)
        openKeyboardWithField(field)
        service.backspaceOnce()
        assertEquals("ab", field.text)
        assertTrue(field.setTextCalls.isEmpty())
    }

    @Test
    fun `deleteNextOnce deletes the char after the caret`() {
        val field = FakeField("ab")
        field.node.setTextSelection(0, 0)
        openKeyboardWithField(field)
        service.deleteNextOnce()
        assertEquals("b", field.text)
    }

    @Test
    fun `deleteNextOnce at the end of the field is a no-op`() {
        val field = openKeyboardWithField(FakeField("ab"))
        service.deleteNextOnce()
        assertEquals("ab", field.text)
        assertTrue(field.setTextCalls.isEmpty())
    }

    @Test
    fun `deleteNextOnce on an empty field is a no-op`() {
        val field = openKeyboardWithField(FakeField(""))
        service.deleteNextOnce()
        assertTrue(field.setTextCalls.isEmpty())
    }

    @Test
    fun `deleteNextOnce removes the selection when one is active`() {
        val field = FakeField("hello")
        field.node.setTextSelection(1, 4)
        openKeyboardWithField(field)
        service.deleteNextOnce()
        assertEquals("ho", field.text)
    }

    @Test
    fun `commitTextOnce inserts at the caret`() {
        val field = FakeField("ab")
        field.node.setTextSelection(1, 1)
        openKeyboardWithField(field)
        service.commitTextOnce("Z")
        assertEquals("aZb", field.text)
    }

    @Test
    fun `injectText appends the utterance at the caret`() {
        val field = openKeyboardWithField(FakeField("ab"))
        service.injectText("CD")
        assertEquals("abCD", field.text)
    }

    @Test
    fun `injectText with no target logs and does nothing`() {
        // keyboard closed, no windows -> no focused node
        service.injectText("CD")
    }

    @Test
    fun `pressEnter falls back to newline without IME_ENTER support`() {
        val field = openKeyboardWithField(FakeField("ab"))
        service.pressEnter()
        assertEquals("ab\n", field.text)
    }

    @Test
    fun `pressEnter uses IME_ENTER when the field advertises it`() {
        val field = FakeField("ab")
        field.node.addAction(AccessibilityNodeInfo.AccessibilityAction(4096, "ime"))
        val performed = mutableListOf<Int>()
        val shadow = org.robolectric.Shadows.shadowOf(field.node)
        shadow.setOnPerformActionListener(
            org.robolectric.shadows.ShadowAccessibilityNodeInfo.OnPerformActionListener { action, _ ->
                performed.add(action)
                true
            }
        )
        openKeyboardWithField(field)
        service.pressEnter()
        assertTrue(performed.contains(4096))
        assertTrue(field.setTextCalls.isEmpty()) // no newline fallback
    }

    @Test
    fun `moveCaret moves the selection by delta and clamps`() {
        val field = FakeField("hello")
        field.node.setTextSelection(3, 3)
        openKeyboardWithField(field)
        service.moveCaret(-1)
        assertEquals(2 to 2, field.caret())
        service.moveCaret(10)
        assertEquals(5 to 5, field.caret())
        service.moveCaret(-10)
        assertEquals(0 to 0, field.caret())
    }

    @Test
    fun `deleteToLineStart clears to the start of the current line`() {
        val field = FakeField("hello\nworld")
        field.node.setTextSelection(8, 8)
        openKeyboardWithField(field)
        service.deleteToLineStart()
        assertEquals("hello\nrld", field.text)
    }

    @Test
    fun `deleteToLineStart on a single line clears to the field start`() {
        val field = openKeyboardWithField(FakeField("abc"))
        service.deleteToLineStart()
        assertEquals("", field.text)
    }

    @Test
    fun `deleteToLineStart at position zero is a no-op`() {
        val field = FakeField("abc")
        field.node.setTextSelection(0, 0)
        openKeyboardWithField(field)
        service.deleteToLineStart()
        assertEquals("abc", field.text)
    }

    @Test
    fun `clearOnFreshFocus wipes a non-empty field`() {
        val field = FakeField("hi")
        service.clearOnFreshFocus(field.node)
        assertEquals("", field.text)
    }

    @Test
    fun `clearOnFreshFocus leaves an empty field alone`() {
        val field = FakeField("")
        service.clearOnFreshFocus(field.node)
        assertTrue(field.setTextCalls.isEmpty())
    }

    @Test
    fun `clearOnFreshFocus tolerates null`() {
        service.clearOnFreshFocus(null)
    }

    @Test
    fun `fieldIdentity fingerprints the node structurally`() {
        val node = AccessibilityNodeInfo.obtain()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val a = service.fieldIdentity(node)
        val b = service.fieldIdentity(node)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `text actions with the keyboard closed hit the no-target path`() {
        // keyboard never opened: typingTarget() falls back to findEditableTarget(),
        // which finds nothing under Robolectric (no windows).
        val field = FakeField("ab")
        service.keyboardTypingTarget = field.node // ignored while keyboard is closed
        service.typeCharacter("X")
        service.typeText("XY")
        service.backspaceOnce()
        service.deleteNextOnce()
        service.commitTextOnce("Z")
        service.pressEnter()
        service.moveCaret(1)
        service.deleteToLineStart()
        assertTrue(field.setTextCalls.isEmpty())
    }

    @Test
    fun `injectFocusedText warns when the view rejects set-text`() {
        val plain = AccessibilityNodeInfo.obtain() // no listener -> performAction false
        service.injectFocusedText(plain, "x")
    }

    @Test
    fun `moveCaret warns when selection is not supported`() {
        // Plain node with no action listener: performAction returns false.
        val plain = AccessibilityNodeInfo.obtain()
        service.startCustomKeyboard()
        service.keyboardTypingTarget = plain
        service.moveCaret(1) // warns, no crash
    }
}
