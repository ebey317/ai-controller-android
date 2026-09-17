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
    NONE
}

enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT
}

/**
 * A single configured action bound to a controller input (button or trigger).
 *
 * [keyCode] is only meaningful for [ActionType.KEY_EVENT] (an Android KeyEvent code).
 * [swipeDirection] is only meaningful for [ActionType.SWIPE].
 * [durationMs] is used by LONG_PRESS (hold time) and SWIPE (gesture duration).
 */
data class ButtonAction(
    val type: ActionType,
    val keyCode: Int = 0,
    val swipeDirection: SwipeDirection? = null,
    val durationMs: Long = 500L
) {
    companion object {
        fun tap() = ButtonAction(ActionType.TAP)
        fun longPress(durationMs: Long = 500L) = ButtonAction(ActionType.LONG_PRESS, durationMs = durationMs)
        fun back() = ButtonAction(ActionType.BACK)
        fun home() = ButtonAction(ActionType.HOME)
        fun recents() = ButtonAction(ActionType.RECENTS)
        fun scroll(direction: SwipeDirection) = ButtonAction(ActionType.SCROLL, swipeDirection = direction)
        fun showKeyboard() = ButtonAction(ActionType.SHOW_KEYBOARD)
        fun focusNext() = ButtonAction(ActionType.FOCUS_NEXT)
        fun voiceTrigger() = ButtonAction(ActionType.VOICE_TRIGGER)
        fun cycleContext() = ButtonAction(ActionType.CYCLE_CONTEXT)
        fun none() = ButtonAction(ActionType.NONE)
    }
}
