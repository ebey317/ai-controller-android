package com.ai.controller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pinned text snippets — shared by [com.ai.controller.ui.KeyboardActivity] (legacy
 * fullscreen diagnostic page) and [com.ai.controller.keyboard.AIInputMethodService]
 * (the real IME overlay), so a pin added from either surface shows up in both.
 */
object PinStore {
    private const val PREFS_NAME = "ai_controller_keyboard"
    private const val KEY_PINS = "pinned_snippets"
    const val PIN_SLOTS = 5

    fun load(context: Context): JSONArray {
        val raw = prefs(context).getString(KEY_PINS, null) ?: return defaultPins()
        return runCatching { JSONArray(raw) }.getOrDefault(defaultPins())
    }

    fun save(context: Context, pins: JSONArray) {
        prefs(context).edit().putString(KEY_PINS, pins.toString()).apply()
    }

    /** Returns false without modifying storage when the slots are already full. */
    fun add(context: Context, label: String, text: String): Boolean {
        val pins = load(context)
        if (pins.length() >= PIN_SLOTS) return false
        pins.put(JSONObject().put("label", label).put("text", text))
        save(context, pins)
        return true
    }

    fun removeAt(context: Context, index: Int) {
        val pins = load(context)
        val kept = JSONArray()
        for (i in 0 until pins.length()) if (i != index) kept.put(pins.get(i))
        save(context, kept)
    }

    private fun defaultPins(): JSONArray = JSONArray().apply {
        put(JSONObject().put("label", "hello").put("text", "Hello! "))
        put(JSONObject().put("label", "thanks").put("text", "Thank you! "))
        put(JSONObject().put("label", "email").put("text", "@"))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
