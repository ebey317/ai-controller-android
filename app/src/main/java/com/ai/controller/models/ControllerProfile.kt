package com.ai.controller.models

/**
 * Identifies a physical controller input that can be bound to a [ButtonAction].
 * Sticks and triggers are handled as continuous axes elsewhere (see InputMapper)
 * and are not part of this discrete mapping table.
 */
enum class ControllerInput {
    BUTTON_A,
    BUTTON_B,
    BUTTON_X,
    BUTTON_Y,
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    BUTTON_START,
    BUTTON_SELECT,
    BUTTON_L1,
    BUTTON_R1,
    BUTTON_THUMBL,
    BUTTON_THUMBR,
    TRIGGER_L2,
    TRIGGER_R2
}

/**
 * A named, persistable set of [ButtonAction] bindings plus stick tuning.
 */
data class ControllerProfile(
    val name: String,
    val mappings: MutableMap<ControllerInput, ButtonAction>,
    var sensitivity: Float = 1.0f,
    var deadzone: Float = 0.15f,
    var cursorEnabled: Boolean = true,
    var invertScroll: Boolean = false
) {
    companion object {
        const val DEFAULT_NAME = "Default"

        /**
         * Exact desktop-profile parity: labels and slot assignments mirror the
         * AntiMicroX profile ai-controller (Linux) runs, button-for-button — see
         * controller-legend.py's desktop legend line. Stock AccessibilityService
         * cannot inject arbitrary KeyEvents (modifiers, Escape/Enter/Tab/Backspace/
         * Delete) into another app's focused field, so several desktop keys are
         * mapped to the closest real AccessibilityService action instead of a
         * KEY_EVENT that would silently no-op in handleKeyEventAction(); the
         * [ButtonAction.label] is kept as the literal desktop text regardless, so
         * the legend always matches Linux even where the underlying gesture is a
         * stand-in. Only the D-pad codes are real, dispatchable KEY_EVENTs here
         * (remapped to cursor nudges in that same handler).
         *
         * Desktop-exact intent mapping:
         *  - Left stick     → move cursor
         *  - Right stick up → scroll up  (Mouse 4 has no Android code)
         *  - Right stick dn → scroll down (Mouse 5 has no Android code)
         *  - D-pad          → DPAD_UP/DOWN/LEFT/RIGHT (cursor nudge)
         *  - A   "Click"    → TAP at cursor
         *  - B   "Bksp"     → TEXT_EDIT backspace-at-cursor
         *  - X   "Del"      → TEXT_EDIT forward-delete-at-cursor
         *  - Y   "Super"    → RECENTS (desktop SUPER_L opens the app switcher; RECENTS is Android's real equivalent)
         *  - LB  "Shift"    → TAP (no global modifier injection on Android; label-only parity)
         *  - RB  "R·Clk"    → LONG_PRESS at cursor (right-click equivalent)
         *  - LT  "Ctrl"     → TAP (no global modifier injection on Android; label-only parity)
         *  - RT  "Talk"     → VOICE_TRIGGER (desktop F13 IS the push-to-talk/dictation key for
         *                     ptt_pynput.py — this is the one input where Android's existing STT
         *                     feature is the literal functional match, not a substitute)
         *  - ⧉   "Kbd"      → SHOW_KEYBOARD (toggle-slide-keyboard.sh on desktop)
         *  - ☰   "Tab"      → FOCUS_NEXT
         *  - LS  "Esc"      → BACK (Esc closes/cancels; BACK is the Android analog)
         *  - RS  "Enter"    → TEXT_EDIT commit "\n" to the focused field
         */
        fun default(): ControllerProfile = ControllerProfile(
            name = DEFAULT_NAME,
            mappings = mutableMapOf(
                ControllerInput.BUTTON_A to ButtonAction.tap(label = "Click"),
                ControllerInput.BUTTON_B to ButtonAction.backspace(),
                ControllerInput.BUTTON_X to ButtonAction.deleteNext(),
                ControllerInput.BUTTON_Y to ButtonAction.recents(label = "Super"),
                ControllerInput.DPAD_UP to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_UP),
                ControllerInput.DPAD_DOWN to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_DOWN),
                ControllerInput.DPAD_LEFT to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_LEFT),
                ControllerInput.DPAD_RIGHT to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
                ControllerInput.BUTTON_START to ButtonAction.focusNext(label = "Tab"),
                ControllerInput.BUTTON_SELECT to ButtonAction.showKeyboard(label = "Kbd"),
                ControllerInput.BUTTON_L1 to ButtonAction.tap(label = "Shift"),
                ControllerInput.BUTTON_R1 to ButtonAction.longPress(label = "R·Clk"),
                ControllerInput.BUTTON_THUMBL to ButtonAction.back(label = "Esc"),
                ControllerInput.BUTTON_THUMBR to ButtonAction.commitTextTo("\n", "Enter"),
                ControllerInput.TRIGGER_L2 to ButtonAction.tap(label = "Ctrl"),
                ControllerInput.TRIGGER_R2 to ButtonAction.voiceTrigger(label = "Talk")
            )
        )
    }
}
