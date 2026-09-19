package com.ai.controller

import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
import com.ai.controller.models.SwipeDirection
import com.ai.controller.models.TextEditOp
import org.json.JSONObject

/**
 * JSON (de)serialization for [ControllerProfile], shared by [ProfileManager]
 * (the single user-editable "Default" profile) and [ContextSwitcher] (the
 * desktop/browser/iptv preset profiles), so both persist profiles the same
 * way instead of maintaining two copies of this format.
 */
object ProfileSerializer {

    /** Current profile revision. rev 2 = desktop-parity mappings (labels match the
     * AntiMicroX legend); anything written before rev existed is a legacy profile
     * whose mappings came from the old invented defaults, not real intent.
     *
     * rev 3, 2026-09-19: a rev-2 profile stored before RS/Enter had a real mapping
     * serialized `textPayload: null` for BUTTON_THUMBR (accurate at the time — Enter
     * wasn't wired to anything yet). Combined with the `optString()`-with-no-default
     * bug fixed below (which turned that JSON null into the literal string "null" on
     * read, since `\n` != "null"), RS silently did nothing — reported live as "I still
     * can't press enter." Bumping rev forces every on-device profile back through
     * default(), where BUTTON_THUMBR carries its real "\n" payload again. */
    private const val REV = 3

    fun serialize(profile: ControllerProfile): JSONObject {
        val root = JSONObject()
        root.put("rev", REV)
        root.put("name", profile.name)
        root.put("sensitivity", profile.sensitivity)
        root.put("deadzone", profile.deadzone)
        root.put("cursorEnabled", profile.cursorEnabled)
        root.put("invertScroll", profile.invertScroll)

        val mappingsJson = JSONObject()
        for ((input, action) in profile.mappings) {
            val actionJson = JSONObject()
            actionJson.put("type", action.type.name)
            actionJson.put("label", action.label)
            actionJson.put("keyCode", action.keyCode)
            actionJson.put("swipeDirection", action.swipeDirection?.name ?: JSONObject.NULL)
            actionJson.put("durationMs", action.durationMs)
            actionJson.put("textOp", action.textOp?.name ?: JSONObject.NULL)
            actionJson.put("textPayload", action.textPayload ?: JSONObject.NULL)
            mappingsJson.put(input.name, actionJson)
        }
        root.put("mappings", mappingsJson)
        return root
    }

    fun deserialize(root: JSONObject): ControllerProfile {
        val rev = root.optInt("rev", 1)
        val name = root.optString("name", ControllerProfile.DEFAULT_NAME)
        val sensitivity = root.optDouble("sensitivity", 1.0).toFloat()
        val deadzone = root.optDouble("deadzone", 0.15).toFloat()
        val cursorEnabled = root.optBoolean("cursorEnabled", true)
        val invertScroll = root.optBoolean("invertScroll", false)

        // Legacy profiles (pre-rev) carried the old invented mappings (B=long-press,
        // X=voice, Y=none, ...). The user's directive is exact AntiMicroX parity, so
        // a legacy profile migrates to the parity defaults wholesale while keeping
        // its tuning values (sensitivity/deadzone/cursor/invert), which are hardware
        // feel, not button intent. rev 2+ profiles keep their stored mappings.
        if (rev < REV) {
            return ControllerProfile(
                name = name,
                mappings = ControllerProfile.default().mappings.toMutableMap(),
                sensitivity = sensitivity,
                deadzone = deadzone,
                cursorEnabled = cursorEnabled,
                invertScroll = invertScroll
            )
        }

        val mappings = mutableMapOf<ControllerInput, ButtonAction>()
        val mappingsJson = root.optJSONObject("mappings")
        if (mappingsJson != null) {
            for (key in mappingsJson.keys()) {
                val input = runCatching { ControllerInput.valueOf(key) }.getOrNull() ?: continue
                val actionJson = mappingsJson.getJSONObject(key)
                val type = runCatching { ActionType.valueOf(actionJson.getString("type")) }
                    .getOrDefault(ActionType.NONE)
                // optString(key) — the no-default overload — returns the literal string
                // "null" for a missing/JSONObject.NULL value, not an empty string or real
                // null. For an enum lookup (swipeDirection/textOp below) that's harmless:
                // SwipeDirection.valueOf("null") throws and runCatching swallows it. For a
                // raw string field (textPayload) there's no such enum gate — "null" passed
                // straight through as if it were real text. Root cause of "can't press
                // enter," live 2026-09-19: see the rev-3 note above. Explicit "" defaults
                // throughout so a missing value reads as missing everywhere, consistently.
                val swipeDirection = if (!actionJson.isNull("swipeDirection")) {
                    actionJson.optString("swipeDirection", "").takeIf { it.isNotBlank() }?.let { dir ->
                        runCatching { SwipeDirection.valueOf(dir) }.getOrNull()
                    }
                } else null
                val textOp = if (!actionJson.isNull("textOp")) {
                    actionJson.optString("textOp", "").takeIf { it.isNotBlank() }?.let { op ->
                        runCatching { TextEditOp.valueOf(op) }.getOrNull()
                    }
                } else null
                val textPayload = if (!actionJson.isNull("textPayload")) {
                    actionJson.optString("textPayload", "").takeIf { it.isNotBlank() }
                } else null
                mappings[input] = ButtonAction(
                    type = type,
                    label = actionJson.optString("label", ""),
                    keyCode = actionJson.optInt("keyCode", 0),
                    swipeDirection = swipeDirection,
                    durationMs = actionJson.optLong("durationMs", 500L),
                    textOp = textOp,
                    textPayload = textPayload
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
}
