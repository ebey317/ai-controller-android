package com.ai.controller

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
import com.ai.controller.models.SwipeDirection
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists the active [ControllerProfile] to SharedPreferences as JSON.
 * Falls back to [ControllerProfile.default] whenever no profile has been
 * saved yet or the stored JSON is corrupt.
 */
class ProfileManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadProfile(): ControllerProfile {
        val raw = prefs.getString(KEY_PROFILE, null) ?: return ControllerProfile.default()
        return try {
            deserialize(JSONObject(raw))
        } catch (e: JSONException) {
            Log.w(TAG, "Corrupt profile JSON, falling back to default", e)
            ControllerProfile.default()
        }
    }

    fun saveProfile(profile: ControllerProfile) {
        prefs.edit()
            .putString(KEY_PROFILE, serialize(profile).toString())
            .apply()
    }

    fun resetToDefault(): ControllerProfile {
        val default = ControllerProfile.default()
        saveProfile(default)
        return default
    }

    private fun serialize(profile: ControllerProfile): JSONObject {
        val root = JSONObject()
        root.put("name", profile.name)
        root.put("sensitivity", profile.sensitivity)
        root.put("deadzone", profile.deadzone)
        root.put("cursorEnabled", profile.cursorEnabled)
        root.put("invertScroll", profile.invertScroll)

        val mappingsJson = JSONObject()
        for ((input, action) in profile.mappings) {
            val actionJson = JSONObject()
            actionJson.put("type", action.type.name)
            actionJson.put("keyCode", action.keyCode)
            actionJson.put("swipeDirection", action.swipeDirection?.name ?: JSONObject.NULL)
            actionJson.put("durationMs", action.durationMs)
            mappingsJson.put(input.name, actionJson)
        }
        root.put("mappings", mappingsJson)
        return root
    }

    private fun deserialize(root: JSONObject): ControllerProfile {
        val name = root.optString("name", ControllerProfile.DEFAULT_NAME)
        val sensitivity = root.optDouble("sensitivity", 1.0).toFloat()
        val deadzone = root.optDouble("deadzone", 0.15).toFloat()
        val cursorEnabled = root.optBoolean("cursorEnabled", true)
        val invertScroll = root.optBoolean("invertScroll", false)

        val mappings = mutableMapOf<ControllerInput, ButtonAction>()
        val mappingsJson = root.optJSONObject("mappings")
        if (mappingsJson != null) {
            for (key in mappingsJson.keys()) {
                val input = runCatching { ControllerInput.valueOf(key) }.getOrNull() ?: continue
                val actionJson = mappingsJson.getJSONObject(key)
                val type = runCatching { ActionType.valueOf(actionJson.getString("type")) }
                    .getOrDefault(ActionType.NONE)
                val swipeDirection = actionJson.optString("swipeDirection").takeIf { it.isNotBlank() }?.let { dir ->
                    runCatching { SwipeDirection.valueOf(dir) }.getOrNull()
                }
                mappings[input] = ButtonAction(
                    type = type,
                    keyCode = actionJson.optInt("keyCode", 0),
                    swipeDirection = swipeDirection,
                    durationMs = actionJson.optLong("durationMs", 500L)
                )
            }
        }

        // Backfill any inputs missing from a partially-saved/older profile.
        val defaults = ControllerProfile.default().mappings
        for ((input, action) in defaults) {
            mappings.putIfAbsent(input, action)
        }

        return ControllerProfile(
            name = name,
            mappings = mappings,
            sensitivity = sensitivity,
            deadzone = deadzone,
            cursorEnabled = cursorEnabled,
            invertScroll = invertScroll
        )
    }

    companion object {
        private const val TAG = "ProfileManager"
        private const val PREFS_NAME = "ai_controller_prefs"
        private const val KEY_PROFILE = "active_profile"
    }
}
