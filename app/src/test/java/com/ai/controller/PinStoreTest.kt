package com.ai.controller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun rawPrefs() =
        context.getSharedPreferences("ai_controller_keyboard", Context.MODE_PRIVATE)

    @Before fun setUp() { rawPrefs().edit().clear().commit() }
    @After fun tearDown() { rawPrefs().edit().clear().commit() }

    @Test fun `load returns three default pins when nothing saved`() {
        val pins = PinStore.load(context)
        assertEquals(3, pins.length())
        assertEquals("hello", pins.getJSONObject(0).getString("label"))
        assertEquals("Hello! ", pins.getJSONObject(0).getString("text"))
        assertEquals("thanks", pins.getJSONObject(1).getString("label"))
        assertEquals("email", pins.getJSONObject(2).getString("label"))
        assertEquals("@", pins.getJSONObject(2).getString("text"))
    }

    @Test fun `add appends a pin and returns true`() {
        assertTrue(PinStore.add(context, "sig", "Best, Lijah"))
        val pins = PinStore.load(context)
        assertEquals(4, pins.length())
        assertEquals("sig", pins.getJSONObject(3).getString("label"))
        assertEquals("Best, Lijah", pins.getJSONObject(3).getString("text"))
    }

    @Test fun `add beyond five slots returns false and stores nothing`() {
        assertTrue(PinStore.add(context, "a", "1"))
        assertTrue(PinStore.add(context, "b", "2"))
        assertFalse(PinStore.add(context, "c", "3"))
        assertEquals(PinStore.PIN_SLOTS, PinStore.load(context).length())
    }

    @Test fun `add persists across loads`() {
        PinStore.add(context, "sig", "Best, Lijah")
        // reload from a fresh read of the same storage
        val pins = PinStore.load(context)
        assertEquals("sig", pins.getJSONObject(3).getString("label"))
    }

    @Test fun `removeAt drops the pin at the index`() {
        PinStore.removeAt(context, 0)
        val pins = PinStore.load(context)
        assertEquals(2, pins.length())
        assertEquals("thanks", pins.getJSONObject(0).getString("label"))
        assertEquals("email", pins.getJSONObject(1).getString("label"))
    }

    @Test fun `removeAt with out-of-range index keeps everything`() {
        PinStore.removeAt(context, 99)
        assertEquals(3, PinStore.load(context).length())
    }

    @Test fun `save and load roundtrip a custom pin list`() {
        val custom = JSONArray().apply {
            put(JSONObject().put("label", "x").put("text", "X marks"))
            put(JSONObject().put("label", "y").put("text", "Y not"))
        }
        PinStore.save(context, custom)
        val loaded = PinStore.load(context)
        assertEquals(2, loaded.length())
        assertEquals("x", loaded.getJSONObject(0).getString("label"))
        assertEquals("Y not", loaded.getJSONObject(1).getString("text"))
    }

    @Test fun `corrupt stored json falls back to defaults`() {
        rawPrefs().edit().putString("pinned_snippets", "{not valid json").apply()
        val pins = PinStore.load(context)
        assertEquals(3, pins.length())
        assertEquals("hello", pins.getJSONObject(0).getString("label"))
    }
}
