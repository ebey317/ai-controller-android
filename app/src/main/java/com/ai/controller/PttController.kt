package com.ai.controller

import android.os.SystemClock
import android.util.Log

/**
 * Push-to-talk edge-detection state machine — the Kotlin analogue of
 * ai-controller's ptt_pynput.py F13 on_press/on_release handlers. This class
 * owns only debounce + press/hold edge timing; recording and STT stay with
 * the caller (ControllerAccessibilityService), same separation of concerns
 * as the Linux original (evdev listener vs start_recording/stop_and_send).
 *
 * Thread safety: all state transitions run under [lock]. The callbacks are
 * invoked OUTSIDE the lock but wrapped in try/catch — a throwing caller must
 * never corrupt the state machine, and holding the lock across an arbitrary
 * caller risks deadlock if the caller re-enters this controller.
 */
class PttController(
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onCancel: () -> Unit
) {
    private val lock = Any()
    private var lastEdgeMs = 0L

    /** True while a press is currently being held (between onButtonDown and onButtonUp). */
    @Volatile
    var isHeld: Boolean = false
        private set

    /** Call on the mapped input's ACTION_DOWN. */
    fun onButtonDown() {
        val now = SystemClock.elapsedRealtime()
        // Decide the transition under lock; fire the callback outside it.
        // (Assigned to a var inside the lock rather than using the block's
        // value: a `{ ... }` lambda on the line after Log.d(...) parses as a
        // trailing-lambda argument to Log.d and won't compile.)
        var action: (() -> Unit)? = null
        synchronized(lock) {
            if (isHeld) {
                // A second "down" edge while already recording — treat it as an
                // explicit cancel gesture (double-press to abort) rather than
                // silently restarting, which would leak the first take's mic
                // handle. Mirrors the intent behind ptt_pynput.py's
                // _processing_lock guard, surfaced here as a user action.
                // Refresh lastEdgeMs to the cancel edge: without this, a legit
                // press within DEBOUNCE_MS of the ORIGINAL down edge would be
                // wrongly debounced and dropped, leaving PTT unresponsive.
                isHeld = false
                lastEdgeMs = now
                Log.d("PttController", "PTT double-press -> onCancel()")
                action = { onCancel() }
            } else if (now - lastEdgeMs < DEBOUNCE_MS) {
                // Debounce is short for analog triggers (they're continuous, not
                // contact-bouncy like cheap buttons) and longer for digital buttons.
                Log.d("PttController", "down debounced: ${now - lastEdgeMs}ms since last edge")
            } else {
                lastEdgeMs = now
                isHeld = true
                Log.d("PttController", "PTT down -> onStart()")
                action = { onStart() }
            }
        }
        action?.let { runCallback("onStart/onCancel", it) }
    }

    /** Call on the mapped input's ACTION_UP. */
    fun onButtonUp() {
        var action: (() -> Unit)? = null
        synchronized(lock) {
            if (!isHeld) {
                Log.d("PttController", "PTT up ignored (not held)")
            } else {
                lastEdgeMs = SystemClock.elapsedRealtime()
                isHeld = false
                Log.d("PttController", "PTT up -> onStop()")
                action = { onStop() }
            }
        }
        action?.let { runCallback("onStop", it) }
    }

    /** Force-resets held state without firing a callback — used on service teardown. */
    fun reset() {
        synchronized(lock) {
            isHeld = false
        }
    }

    /**
     * Callbacks are caller code: if one throws, the exception must not unwind
     * into whoever delivered the button edge, and the state machine above has
     * already committed its transition, so the controller stays consistent.
     */
    private fun runCallback(name: String, cb: () -> Unit) {
        try {
            cb()
        } catch (e: Exception) {
            Log.e("PttController", "PTT callback $name threw; state machine unaffected", e)
        }
    }

    companion object {
        /** Short debounce: analog triggers don't bounce, and a 400ms gate made PTT feel dead. */
        private const val DEBOUNCE_MS = 150L
    }
}
