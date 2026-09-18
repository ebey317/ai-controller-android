package com.ai.controller

import android.os.SystemClock
import android.util.Log

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
        if (now - lastEdgeMs < DEBOUNCE_MS) {
            // Debounce is short for analog triggers (they're continuous, not
            // contact-bouncy like cheap buttons) and longer for digital buttons.
            Log.d("PttController", "down debounced: ${now - lastEdgeMs}ms since last edge")
            return
        }
        lastEdgeMs = now
        isHeld = true
        Log.d("PttController", "PTT down -> onStart()")
        onStart()
    }

    /** Call on the mapped input's ACTION_UP. */
    fun onButtonUp() {
        if (!isHeld) {
            Log.d("PttController", "PTT up ignored (not held)")
            return
        }
        lastEdgeMs = SystemClock.elapsedRealtime()
        isHeld = false
        Log.d("PttController", "PTT up -> onStop()")
        onStop()
    }

    /** Force-resets held state without firing a callback — used on service teardown. */
    fun reset() {
        isHeld = false
    }

    companion object {
        /** Short debounce: analog triggers don't bounce, and a 400ms gate made PTT feel dead. */
        private const val DEBOUNCE_MS = 150L
    }
}
