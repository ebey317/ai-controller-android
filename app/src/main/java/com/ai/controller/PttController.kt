package com.ai.controller

import android.os.SystemClock

/**
 * Push-to-talk edge-detection state machine — the Kotlin analogue of
 * ai-controller's ptt_pynput.py F13 on_press/on_release handlers. This class
 * owns only debounce + press/hold edge timing; recording and STT stay with
 * the caller (ControllerAccessibilityService), same separation of concerns
 * as the Linux original (evdev listener vs start_recording/stop_and_send).
 */
class PttController(
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onCancel: () -> Unit
) {
    private var lastEdgeMs = 0L

    /** True while a press is currently being held (between onButtonDown and onButtonUp). */
    @Volatile
    var isHeld: Boolean = false
        private set

    /** Call on the mapped input's ACTION_DOWN. */
    fun onButtonDown() {
        val now = SystemClock.elapsedRealtime()
        if (isHeld) {
            // A second "down" edge while already recording — treat it as an
            // explicit cancel gesture (double-press to abort) rather than
            // silently restarting, which would leak the first take's mic
            // handle. Mirrors the intent behind ptt_pynput.py's
            // _processing_lock guard, surfaced here as a user action.
            isHeld = false
            onCancel()
            return
        }
        if (now - lastEdgeMs < DEBOUNCE_MS) return // chatter from the controller trigger
        lastEdgeMs = now
        isHeld = true
        onStart()
    }

    /** Call on the mapped input's ACTION_UP. */
    fun onButtonUp() {
        if (!isHeld) return
        lastEdgeMs = SystemClock.elapsedRealtime()
        isHeld = false
        onStop()
    }

    /** Force-resets held state without firing a callback — used on service teardown. */
    fun reset() {
        isHeld = false
    }

    companion object {
        private const val DEBOUNCE_MS = 400L
    }
}
