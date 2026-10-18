package com.ai.controller

import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileSerializerTest {

    @Test
    fun `round trip preserves all mappings`() {
        val original = ControllerProfile.default()
        val restored = ProfileSerializer.deserialize(ProfileSerializer.serialize(original))
        assertEquals(original.name, restored.name)
        assertEquals(original.mappings.size, restored.mappings.size)
        for ((input, action) in original.mappings) {
            assertEquals("mismatch for $input", action, restored.mappings[input])
        }
    }

    @Test
    fun `round trip preserves tuning values`() {
        val original = ControllerProfile.default().copy(
            sensitivity = 1.7f, deadzone = 0.22f, cursorEnabled = false, invertScroll = true
        )
        val restored = ProfileSerializer.deserialize(ProfileSerializer.serialize(original))
        assertEquals(1.7f, restored.sensitivity, 0.0001f)
        assertEquals(0.22f, restored.deadzone, 0.0001f)
        assertFalse(restored.cursorEnabled)
        assertTrue(restored.invertScroll)
    }

    @Test
    fun `round trip preserves enter-key text payload - regression for 2026-09-19 bug`() {
        // The optString-no-default bug turned RS/Enter's "\n" payload into the
        // literal string "null", silently breaking Enter on device.
        val restored = ProfileSerializer.deserialize(
            ProfileSerializer.serialize(ControllerProfile.default())
        )
        val rs = restored.mappings[ControllerInput.BUTTON_THUMBR]!!
        assertEquals("\n", rs.textPayload)
        assertNotEquals("null", rs.textPayload)
    }

    @Test
    fun `legacy rev-1 profile migrates to parity defaults keeping tuning`() {
        val legacy = JSONObject()
        legacy.put("rev", 1)
        legacy.put("name", "Old")
        legacy.put("sensitivity", 2.0)
        legacy.put("deadzone", 0.3)
        legacy.put("cursorEnabled", false)
        legacy.put("invertScroll", true)
        legacy.put("mappings", JSONObject()) // stale invented mappings, ignored
        val restored = ProfileSerializer.deserialize(legacy)
        // mappings come from current defaults...
        assertEquals(
            ControllerProfile.default().mappings[ControllerInput.BUTTON_A],
            restored.mappings[ControllerInput.BUTTON_A]
        )
        // ...but hardware feel is preserved
        assertEquals(2.0f, restored.sensitivity, 0.0001f)
        assertEquals(0.3f, restored.deadzone, 0.0001f)
        assertFalse(restored.cursorEnabled)
        assertTrue(restored.invertScroll)
    }

    @Test
    fun `partial profile backfills missing inputs from defaults`() {
        val partial = JSONObject()
        partial.put("rev", 99)
        partial.put("name", "Partial")
        val mappings = JSONObject()
        val aAction = JSONObject()
        aAction.put("type", ActionType.TAP.name)
        aAction.put("label", "Click")
        aAction.put("keyCode", 0)
        aAction.put("durationMs", 500)
        mappings.put(ControllerInput.BUTTON_A.name, aAction)
        partial.put("mappings", mappings)
        val restored = ProfileSerializer.deserialize(partial)
        assertEquals(ControllerInput.values().size, restored.mappings.size)
        assertEquals("Click", restored.mappings[ControllerInput.BUTTON_A]!!.label)
        // backfilled from defaults
        assertEquals(
            ButtonAction.backspace(),
            restored.mappings[ControllerInput.BUTTON_B]
        )
    }

    @Test
    fun `unknown input keys and bad enum values are tolerated`() {
        val root = ProfileSerializer.serialize(ControllerProfile.default())
        val mappings = root.getJSONObject("mappings")
        // corrupt one entry's type and add a bogus input key
        val aAction = mappings.getJSONObject(ControllerInput.BUTTON_A.name)
        aAction.put("type", "NOT_A_REAL_TYPE")
        mappings.put("BUTTON_ZZZ", aAction)
        val restored = ProfileSerializer.deserialize(root)
        assertEquals(ActionType.NONE, restored.mappings[ControllerInput.BUTTON_A]!!.type)
        assertEquals(ControllerInput.values().size, restored.mappings.size)
    }
}
