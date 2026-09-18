package com.ai.controller.models

/**
 * The set of gestures/system actions a controller button or axis can trigger.
 */
enum class ActionType {
    TAP,
    LONG_PRESS,
    SWIPE,
    KEY_EVENT,
    SCROLL,
    BACK,
    HOME,
    RECENTS,
    VOICE_TRIGGER,
    SHOW_KEYBOARD,
    FOCUS_NEXT,
    CYCLE_CONTEXT,
    TEXT_EDIT,
    NONE
}

enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT
}

/** Sub-operation for [ActionType.TEXT_EDIT] bindings that edit relative to the cursor. */
enum class TextEditOp {
    BACKSPACE,
    DELETE_NEXT
}

/**
 * A single configured action bound to a controller input (button or trigger).
 *
 * [label] is the display string shown in the floating legend (LegendOverlay) — the
 * Android analogue of the desktop AntiMicroX profile's button label, e.g. "Click" or
 * "Bksp". Falls back to [type]'s enum name when blank.
 * [keyCode] is only meaningful for [ActionType.KEY_EVENT] (an Android KeyEvent code).
 * [swipeDirection] is only meaningful for [ActionType.SWIPE]/[ActionType.SCROLL].
 * [durationMs] is used by LONG_PRESS (hold time) and SWIPE (gesture duration).
 * [textOp]/[textPayload] are only meaningful for [ActionType.TEXT_EDIT]: [textOp] selects
 * a cursor-relative edit (backspace/delete-next); when it's null, [textPayload] is
 * committed to the focused field instead (e.g. Enter as a literal "\n").
 */
data class ButtonAction(
    val type: ActionType,
    val label: String = "",
    val keyCode: Int = 0,
    val swipeDirection: SwipeDirection? = null,
    val durationMs: Long = 500L,
    val textOp: TextEditOp? = null,
    val textPayload: String? = null
) {
    companion object {
        fun tap(label: String = "") = ButtonAction(ActionType.TAP, label = label)
        fun longPress(durationMs: Long = 500L, label: String = "") =
            ButtonAction(ActionType.LONG_PRESS, label = label, durationMs = durationMs)
        fun back(label: String = "") = ButtonAction(ActionType.BACK, label = label)
        fun home(label: String = "") = ButtonAction(ActionType.HOME, label = label)
        fun recents(label: String = "") = ButtonAction(ActionType.RECENTS, label = label)
        fun scroll(direction: SwipeDirection, label: String = "") =
            ButtonAction(ActionType.SCROLL, label = label, swipeDirection = direction)
        fun showKeyboard(label: String = "") = ButtonAction(ActionType.SHOW_KEYBOARD, label = label)
        fun focusNext(label: String = "") = ButtonAction(ActionType.FOCUS_NEXT, label = label)
        fun voiceTrigger(label: String = "") = ButtonAction(ActionType.VOICE_TRIGGER, label = label)
        fun cycleContext(label: String = "") = ButtonAction(ActionType.CYCLE_CONTEXT, label = label)
        fun none(label: String = "") = ButtonAction(ActionType.NONE, label = label)

        /** Backspace-at-cursor — the desktop profile's B/Bksp slot. */
        fun backspace() = ButtonAction(ActionType.TEXT_EDIT, label = "Bksp", textOp = TextEditOp.BACKSPACE)

        /** Forward-delete-at-cursor — the desktop profile's X/Del slot. */
        fun deleteNext() = ButtonAction(ActionType.TEXT_EDIT, label = "Del", textOp = TextEditOp.DELETE_NEXT)

        /** Commits [text] verbatim to the focused field — e.g. Enter as a literal newline. */
        fun commitTextTo(text: String, label: String) =
            ButtonAction(ActionType.TEXT_EDIT, label = label, textPayload = text)
    }
}
