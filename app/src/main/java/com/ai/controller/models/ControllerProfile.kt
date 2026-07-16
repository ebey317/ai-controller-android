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
         * Default mapping translated (not literally copied) from the AntimicroX desktop
         * profile — see android-controller-mapping-translation.md. Stock AccessibilityService
         * cannot inject arbitrary KeyEvents (modifiers, Escape/Enter/Tab/Backspace/Delete)
         * into another app's focused field, so those desktop keys are mapped to the closest
         * real AccessibilityService action instead of a KEY_EVENT that would silently no-op
         * in handleKeyEventAction(). Only the D-pad codes are real, dispatchable KEY_EVENTs
         * here (remapped to cursor nudges in that same handler).
         *
         * Picture-exact intent mapping:
         *  - Left stick     → move cursor
         *  - Right stick up → scroll up  (Mouse 4 has no Android code)
         *  - Right stick dn → scroll down (Mouse 5 has no Android code)
         *  - Right stick LR → NONE
         *  - D-pad          → DPAD_UP/DOWN/LEFT/RIGHT (cursor nudge)
         *  - A              → TAP (left mouse button / Mouse LB)
         *  - B              → BACK (desktop BACKSPACE has no equivalent; BACK is the closest cancel-ish gesture)
         *  - X              → BACK (desktop DELETE has no equivalent either; grouped with B rather than an unrelated feature)
         *  - Y              → RECENTS (desktop SUPER_L opens the app switcher; RECENTS is Android's real equivalent)
         *  - LB             → SCROLL UP (closest to desktop SHIFT_L, no real equivalent exists)
         *  - RB             → LONG_PRESS (closest to desktop Mouse RB / right-click)
         *  - LT             → NONE (no real equivalent to desktop CTRL_L)
         *  - RT             → VOICE_TRIGGER (desktop F13 IS the push-to-talk/dictation key for
         *                     ptt_pynput.py — this is the one input where Android's existing STT
         *                     feature is the literal functional match, not a substitute)
         *  - View/Back      → SHOW_KEYBOARD (toggle-slide-keyboard.sh on desktop)
         *  - Menu/Start     → FOCUS_NEXT (closest to desktop TAB)
         *  - LS click       → BACK (closest to desktop ESC)
         *  - RS click       → TAP (closest to desktop ENTER / "activate")
         *  - Guide/Xbox     → NONE
         */
        fun default(): ControllerProfile = ControllerProfile(
            name = DEFAULT_NAME,
            mappings = mutableMapOf(
                ControllerInput.BUTTON_A to ButtonAction.tap(),
                ControllerInput.BUTTON_B to ButtonAction.back(),
                ControllerInput.BUTTON_X to ButtonAction.back(),
                ControllerInput.BUTTON_Y to ButtonAction.recents(),
                ControllerInput.DPAD_UP to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_UP),
                ControllerInput.DPAD_DOWN to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_DOWN),
                ControllerInput.DPAD_LEFT to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_LEFT),
                ControllerInput.DPAD_RIGHT to ButtonAction(ActionType.KEY_EVENT, keyCode = android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
                ControllerInput.BUTTON_START to ButtonAction.focusNext(),
                ControllerInput.BUTTON_SELECT to ButtonAction.showKeyboard(),
                ControllerInput.BUTTON_L1 to ButtonAction.scroll(SwipeDirection.UP),
                ControllerInput.BUTTON_R1 to ButtonAction.longPress(),
                ControllerInput.BUTTON_THUMBL to ButtonAction.back(),
                ControllerInput.BUTTON_THUMBR to ButtonAction.tap(),
                ControllerInput.TRIGGER_L2 to ButtonAction.none(),
                ControllerInput.TRIGGER_R2 to ButtonAction.voiceTrigger()
            )
        )
    }
}
