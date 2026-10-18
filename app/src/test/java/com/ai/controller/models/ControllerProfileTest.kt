package com.ai.controller.models

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class ControllerProfileTest {

    @Test
    fun `default profile binds every controller input`() {
        val profile = ControllerProfile.default()
        assertEquals(ControllerInput.values().size, profile.mappings.size)
        for (input in ControllerInput.values()) {
            assertTrue("missing binding for $input", profile.mappings.containsKey(input))
        }
    }

    @Test
    fun `default profile desktop-parity face buttons`() {
        val m = ControllerProfile.default().mappings
        assertEquals(ButtonAction.tap(label = "Click"), m[ControllerInput.BUTTON_A])
        assertEquals(ButtonAction.backspace(), m[ControllerInput.BUTTON_B])
        assertEquals(ButtonAction.deleteNext(), m[ControllerInput.BUTTON_X])
        val y = m[ControllerInput.BUTTON_Y]!!
        assertEquals(ActionType.RECENTS, y.type)
        assertEquals("Super", y.label)
    }

    @Test
    fun `default profile triggers`() {
        val m = ControllerProfile.default().mappings
        val rt = m[ControllerInput.TRIGGER_R2]!!
        assertEquals(ActionType.VOICE_TRIGGER, rt.type)
        assertEquals("Talk", rt.label)
        val lt = m[ControllerInput.TRIGGER_L2]!!
        assertEquals(ActionType.TAP, lt.type)
        assertEquals("Ctrl", lt.label)
    }

    @Test
    fun `default profile thumbsticks`() {
        val m = ControllerProfile.default().mappings
        val ls = m[ControllerInput.BUTTON_THUMBL]!!
        assertEquals(ActionType.BACK, ls.type)
        assertEquals("Esc", ls.label)
        val rs = m[ControllerInput.BUTTON_THUMBR]!!
        assertEquals(ActionType.TEXT_EDIT, rs.type)
        assertEquals("\n", rs.textPayload)
        assertEquals("Enter", rs.label)
    }

    @Test
    fun `default profile dpad carries real keycodes`() {
        val m = ControllerProfile.default().mappings
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, m[ControllerInput.DPAD_UP]!!.keyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, m[ControllerInput.DPAD_DOWN]!!.keyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, m[ControllerInput.DPAD_LEFT]!!.keyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, m[ControllerInput.DPAD_RIGHT]!!.keyCode)
    }

    @Test
    fun `default profile system buttons`() {
        val m = ControllerProfile.default().mappings
        assertEquals("Tab", m[ControllerInput.BUTTON_START]!!.label)
        assertEquals(ActionType.FOCUS_NEXT, m[ControllerInput.BUTTON_START]!!.type)
        assertEquals("Kbd", m[ControllerInput.BUTTON_SELECT]!!.label)
        assertEquals(ActionType.SHOW_KEYBOARD, m[ControllerInput.BUTTON_SELECT]!!.type)
    }

    @Test
    fun `default tuning values`() {
        val profile = ControllerProfile.default()
        assertEquals(ControllerProfile.DEFAULT_NAME, profile.name)
        assertEquals(1.0f, profile.sensitivity, 0.0001f)
        assertEquals(0.15f, profile.deadzone, 0.0001f)
        assertTrue(profile.cursorEnabled)
        assertFalse(profile.invertScroll)
    }
}
