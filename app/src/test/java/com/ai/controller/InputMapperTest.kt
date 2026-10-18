package com.ai.controller

import android.view.KeyEvent
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InputMapperTest {

    private lateinit var mapper: InputMapper

    @Before
    fun setUp() { mapper = InputMapper() }

    @Test
    fun `keyCodeToInput maps all gamepad buttons`() {
        val expected = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to ControllerInput.BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B to ControllerInput.BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X to ControllerInput.BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y to ControllerInput.BUTTON_Y,
            KeyEvent.KEYCODE_DPAD_UP to ControllerInput.DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to ControllerInput.DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to ControllerInput.DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to ControllerInput.DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_START to ControllerInput.BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT to ControllerInput.BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_L1 to ControllerInput.BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1 to ControllerInput.BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_THUMBL to ControllerInput.BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR to ControllerInput.BUTTON_THUMBR
        )
        for ((keyCode, input) in expected) {
            assertEquals("keyCode $keyCode", input, mapper.keyCodeToInput(keyCode))
        }
    }

    @Test
    fun `keyCodeToInput returns null for unmapped keys`() {
        assertNull(mapper.keyCodeToInput(KeyEvent.KEYCODE_A))
        assertNull(mapper.keyCodeToInput(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(mapper.keyCodeToInput(-1))
    }

    @Test
    fun `resolveAction returns configured action`() {
        val profile = ControllerProfile.default()
        val action = mapper.resolveAction(profile, ControllerInput.BUTTON_A)
        assertEquals(ButtonAction.tap(label = "Click"), action)
    }

    @Test
    fun `resolveAction defaults to NONE for unmapped input`() {
        val profile = ControllerProfile(name = "empty", mappings = mutableMapOf())
        val action = mapper.resolveAction(profile, ControllerInput.BUTTON_A)
        assertEquals(ButtonAction.none(), action)
    }

    @Test
    fun `deadzone zeroes small deflections`() {
        val (x, y) = mapper.applyDeadzoneAndSensitivity(0.05f, 0.05f, deadzone = 0.15f, sensitivity = 1.0f)
        assertEquals(0f, x, 0.0001f)
        assertEquals(0f, y, 0.0001f)
    }

    @Test
    fun `deadzone ramps to full scale at max deflection`() {
        val (x, y) = mapper.applyDeadzoneAndSensitivity(1.0f, 0.0f, deadzone = 0.15f, sensitivity = 1.0f)
        assertEquals(1.0f, x, 0.0001f)
        assertEquals(0.0f, y, 0.0001f)
    }

    @Test
    fun `deadzone preserves direction`() {
        // (0.6, 0.8) has magnitude 1.0 -> normalized (0.6, 0.8), scale 1.0
        val (x, y) = mapper.applyDeadzoneAndSensitivity(0.6f, 0.8f, deadzone = 0.15f, sensitivity = 1.0f)
        assertEquals(0.6f, x, 0.0001f)
        assertEquals(0.8f, y, 0.0001f)
    }

    @Test
    fun `deadzone applies sensitivity multiplier`() {
        val (x, _) = mapper.applyDeadzoneAndSensitivity(1.0f, 0.0f, deadzone = 0.15f, sensitivity = 2.0f)
        assertEquals(2.0f, x, 0.0001f)
    }

    @Test
    fun `deadzone mid-deflection ramps proportionally`() {
        // magnitude 0.575, deadzone 0.15 -> scale = (0.575-0.15)/(0.85) = 0.5
        val (x, y) = mapper.applyDeadzoneAndSensitivity(0.345f, 0.46f, deadzone = 0.15f, sensitivity = 1.0f)
        assertEquals(0.3f, x, 0.0001f)
        assertEquals(0.4f, y, 0.0001f)
    }

    @Test
    fun `trigger hysteresis requires press threshold when inactive`() {
        assertFalse(mapper.isTriggerActive(0.4f, currentlyActive = false))
        assertTrue(mapper.isTriggerActive(0.5f, currentlyActive = false))
        assertTrue(mapper.isTriggerActive(0.9f, currentlyActive = false))
    }

    @Test
    fun `trigger hysteresis uses lower release threshold when active`() {
        // Stays active down to 0.25 (kills the dither bug from 2026-09-19)
        assertTrue(mapper.isTriggerActive(0.3f, currentlyActive = true))
        assertTrue(mapper.isTriggerActive(0.25f, currentlyActive = true))
        assertFalse(mapper.isTriggerActive(0.24f, currentlyActive = true))
        assertFalse(mapper.isTriggerActive(0.0f, currentlyActive = true))
    }

    @Test
    fun `trigger hysteresis accepts custom thresholds`() {
        assertTrue(mapper.isTriggerActive(0.8f, false, pressThreshold = 0.7f, releaseThreshold = 0.4f))
        assertFalse(mapper.isTriggerActive(0.6f, false, pressThreshold = 0.7f, releaseThreshold = 0.4f))
        assertTrue(mapper.isTriggerActive(0.5f, true, pressThreshold = 0.7f, releaseThreshold = 0.4f))
        assertFalse(mapper.isTriggerActive(0.3f, true, pressThreshold = 0.7f, releaseThreshold = 0.4f))
    }

    @Test
    fun `stepFor ignores sub-threshold values`() {
        assertEquals(0f, mapper.stepFor(0.49f, 10f), 0.0001f)
        assertEquals(0f, mapper.stepFor(-0.49f, 10f), 0.0001f)
        assertEquals(0f, mapper.stepFor(0f, 10f), 0.0001f)
    }

    @Test
    fun `stepFor preserves sign at threshold`() {
        assertEquals(10f, mapper.stepFor(0.5f, 10f), 0.0001f)
        assertEquals(10f, mapper.stepFor(0.9f, 10f), 0.0001f)
        assertEquals(-10f, mapper.stepFor(-0.9f, 10f), 0.0001f)
    }
}
