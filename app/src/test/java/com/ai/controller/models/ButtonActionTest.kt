package com.ai.controller.models

import org.junit.Assert.*
import org.junit.Test

class ButtonActionTest {

    @Test
    fun `tap factory`() {
        val a = ButtonAction.tap("Click")
        assertEquals(ActionType.TAP, a.type)
        assertEquals("Click", a.label)
    }

    @Test
    fun `backspace factory carries BACKSPACE op`() {
        val a = ButtonAction.backspace()
        assertEquals(ActionType.TEXT_EDIT, a.type)
        assertEquals(TextEditOp.BACKSPACE, a.textOp)
        assertEquals("Bksp", a.label)
        assertNull(a.textPayload)
    }

    @Test
    fun `deleteNext factory carries DELETE_NEXT op`() {
        val a = ButtonAction.deleteNext()
        assertEquals(ActionType.TEXT_EDIT, a.type)
        assertEquals(TextEditOp.DELETE_NEXT, a.textOp)
        assertEquals("Del", a.label)
    }

    @Test
    fun `commitTextTo carries payload without op`() {
        val a = ButtonAction.commitTextTo("\n", "Enter")
        assertEquals(ActionType.TEXT_EDIT, a.type)
        assertEquals("\n", a.textPayload)
        assertEquals("Enter", a.label)
        assertNull(a.textOp)
    }

    @Test
    fun `voiceTrigger factory`() {
        val a = ButtonAction.voiceTrigger("Talk")
        assertEquals(ActionType.VOICE_TRIGGER, a.type)
        assertEquals("Talk", a.label)
    }

    @Test
    fun `none factory`() {
        val a = ButtonAction.none()
        assertEquals(ActionType.NONE, a.type)
    }

    @Test
    fun `scroll factory carries direction`() {
        val a = ButtonAction.scroll(SwipeDirection.UP, "Scr^")
        assertEquals(ActionType.SCROLL, a.type)
        assertEquals(SwipeDirection.UP, a.swipeDirection)
    }

    @Test
    fun `longPress factory carries duration`() {
        val a = ButtonAction.longPress(durationMs = 800L, label = "R-Clk")
        assertEquals(ActionType.LONG_PRESS, a.type)
        assertEquals(800L, a.durationMs)
    }

    @Test
    fun `data class equality holds`() {
        assertEquals(ButtonAction.tap("Click"), ButtonAction.tap("Click"))
        assertNotEquals(ButtonAction.tap("Click"), ButtonAction.tap("Tap"))
    }
}
