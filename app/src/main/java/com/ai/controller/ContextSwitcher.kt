package com.ai.controller

import android.content.Context
import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
import com.ai.controller.models.SwipeDirection
import org.json.JSONObject

/**
 * Named profile contexts — the Android analogue of the desktop HUD's
 * desktop/browser/iptv layouts (controller-legend.py's `ALL_LAYOUTS` +
 * `PROFILE_STATE`). Each context is a full [ControllerProfile] persisted
 * under its own key, so switching contexts never clobbers another context's
 * customizations. "desktop" defers to [ProfileManager]'s single user-edited
 * profile (unchanged behavior for existing users); browser/iptv are new
 * presets translated — not literally copied — from the Linux layouts, same
 * approach [ControllerProfile.default] already documents for the desktop
 * translation.
 */
class ContextSwitcher(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun activeContext(): String = prefs.getString(KEY_ACTIVE, DESKTOP) ?: DESKTOP

    fun setActiveContext(name: String) {
        prefs.edit().putString(KEY_ACTIVE, name).apply()
    }

    /** Cycles desktop -> browser -> iptv -> desktop and returns the new context name. */
    fun cycleContext(): String {
        val order = listOf(DESKTOP, BROWSER, IPTV)
        val next = order[(order.indexOf(activeContext()) + 1) % order.size]
        setActiveContext(next)
        return next
    }

    /** Resolves the effective profile for [name]: the user's editable profile for
     * "desktop", or that context's persisted/default preset otherwise. */
    fun profileFor(name: String, profileManager: ProfileManager): ControllerProfile =
        if (name == DESKTOP) profileManager.loadProfile() else loadPreset(name)

    fun savePreset(name: String, profile: ControllerProfile) {
        if (name == DESKTOP) return // desktop is owned by ProfileManager
        prefs.edit().putString(presetKey(name), ProfileSerializer.serialize(profile).toString()).apply()
    }

    private fun loadPreset(name: String): ControllerProfile {
        val raw = prefs.getString(presetKey(name), null)
        if (raw != null) {
            return runCatching { ProfileSerializer.deserialize(JSONObject(raw)) }.getOrElse { defaultPreset(name) }
        }
        return defaultPreset(name)
    }

    private fun presetKey(name: String) = "preset_$name"

    private fun defaultPreset(name: String): ControllerProfile = when (name) {
        BROWSER -> browserDefault()
        IPTV -> iptvDefault()
        else -> ControllerProfile.default()
    }

    // Translated from controller-legend.py's ALL_LAYOUTS["browser"]. Actions are
    // limited to what AccessibilityService can actually dispatch (tap/back/scroll/
    // focus/voice); address-bar and bookmark shortcuts have no public equivalent and
    // are left NONE rather than silently no-op through an unreachable KEY_EVENT.
    // Labels are the literal Linux browser legend text ("A:Click B:Back X:Reload
    // Y:New Tab ⧉:Address ☰:Bookmark LB:←Tab RB:Tab→ LT:R·Clk RT:Talk") even on
    // slots (X/Reload, Y/New Tab) where the bound gesture is only the closest
    // supported stand-in, same label/action-mismatch precedent as the desktop preset.
    private fun browserDefault(): ControllerProfile = ControllerProfile(
        name = BROWSER,
        mappings = mutableMapOf(
            ControllerInput.BUTTON_A to ButtonAction.tap(label = "Click"),
            ControllerInput.BUTTON_B to ButtonAction.back(label = "Back"),
            ControllerInput.BUTTON_X to ButtonAction.none(label = "Reload"),
            ControllerInput.BUTTON_Y to ButtonAction.focusNext(label = "New Tab"),
            ControllerInput.BUTTON_L1 to ButtonAction.scroll(SwipeDirection.LEFT, label = "←Tab"),
            ControllerInput.BUTTON_R1 to ButtonAction.scroll(SwipeDirection.RIGHT, label = "Tab→"),
            ControllerInput.DPAD_UP to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_UP),
            ControllerInput.DPAD_DOWN to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_DOWN),
            ControllerInput.DPAD_LEFT to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_LEFT),
            ControllerInput.DPAD_RIGHT to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
            ControllerInput.BUTTON_START to ButtonAction.focusNext(label = "Bookmark"),
            ControllerInput.BUTTON_SELECT to ButtonAction.showKeyboard(label = "Address"),
            ControllerInput.BUTTON_THUMBL to ButtonAction.tap(label = "Space"),
            ControllerInput.BUTTON_THUMBR to ButtonAction.longPress(),
            ControllerInput.TRIGGER_L2 to ButtonAction.longPress(label = "R·Clk"),
            ControllerInput.TRIGGER_R2 to ButtonAction.voiceTrigger(label = "Talk")
        )
    )

    // Translated from ALL_LAYOUTS["iptv"]. Channel up/down map to scroll (the
    // AntiMicroX profile's own closest real-world action for "move selection"),
    // rather than KEYCODE_CHANNEL_UP/DOWN which AccessibilityService cannot inject.
    // Labels are the literal Linux iptv legend text ("A:▶‖ B:Stop X:Info Y:Full
    // ⧉:Menu ☰:Guide LB:Ch↑ RB:Ch↓ LT:◀◀ RT:▶▶ L3:Space"); LT/RT keep their existing
    // NONE/voice-trigger actions (no rewind/fast-forward path exists here) with only
    // the label updated to match Linux, same documented mismatch as the desktop preset.
    private fun iptvDefault(): ControllerProfile = ControllerProfile(
        name = IPTV,
        mappings = mutableMapOf(
            ControllerInput.BUTTON_A to ButtonAction.tap(label = "▶‖"),
            ControllerInput.BUTTON_B to ButtonAction.back(label = "Stop"),
            ControllerInput.BUTTON_X to ButtonAction.tap(label = "Info"),
            ControllerInput.BUTTON_Y to ButtonAction.longPress(label = "Full"),
            ControllerInput.BUTTON_L1 to ButtonAction.scroll(SwipeDirection.UP, label = "Ch↑"),
            ControllerInput.BUTTON_R1 to ButtonAction.scroll(SwipeDirection.DOWN, label = "Ch↓"),
            ControllerInput.DPAD_UP to ButtonAction.scroll(SwipeDirection.UP),
            ControllerInput.DPAD_DOWN to ButtonAction.scroll(SwipeDirection.DOWN),
            ControllerInput.DPAD_LEFT to ButtonAction.none(),
            ControllerInput.DPAD_RIGHT to ButtonAction.none(),
            ControllerInput.BUTTON_START to ButtonAction.focusNext(label = "Guide"),
            ControllerInput.BUTTON_SELECT to ButtonAction.showKeyboard(label = "Menu"),
            ControllerInput.BUTTON_THUMBL to ButtonAction.back(label = "Space"),
            ControllerInput.BUTTON_THUMBR to ButtonAction.tap(),
            ControllerInput.TRIGGER_L2 to ButtonAction.none(label = "◀◀"),
            ControllerInput.TRIGGER_R2 to ButtonAction.voiceTrigger(label = "▶▶")
        )
    )

    companion object {
        private const val PREFS_NAME = "ai_controller_contexts"
        private const val KEY_ACTIVE = "active_context"
        const val DESKTOP = "desktop"
        const val BROWSER = "browser"
        const val IPTV = "iptv"
    }
}
