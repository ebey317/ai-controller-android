package com.ai.controller

import android.view.KeyEvent
import android.view.MotionEvent
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
import kotlin.math.abs
import kotlin.math.sign

/**
 * Pure translation layer between raw Android gamepad input (KeyEvent / MotionEvent)
 * and the app's [ControllerInput] / [ButtonAction] vocabulary. Holds no state and
 * performs no dispatch — [ControllerAccessibilityService] owns timing and gesture
 * dispatch, this class only answers "what does this input mean".
 */
class InputMapper {

    /** Maps a gamepad KeyEvent keyCode to a discrete [ControllerInput], or null if unmapped. */
    fun keyCodeToInput(keyCode: Int): ControllerInput? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> ControllerInput.BUTTON_A
        KeyEvent.KEYCODE_BUTTON_B -> ControllerInput.BUTTON_B
        KeyEvent.KEYCODE_BUTTON_X -> ControllerInput.BUTTON_X
        KeyEvent.KEYCODE_BUTTON_Y -> ControllerInput.BUTTON_Y
        KeyEvent.KEYCODE_DPAD_UP -> ControllerInput.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> ControllerInput.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> ControllerInput.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerInput.DPAD_RIGHT
        KeyEvent.KEYCODE_BUTTON_START -> ControllerInput.BUTTON_START
        KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerInput.BUTTON_SELECT
        KeyEvent.KEYCODE_BUTTON_L1 -> ControllerInput.BUTTON_L1
        KeyEvent.KEYCODE_BUTTON_R1 -> ControllerInput.BUTTON_R1
        KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerInput.BUTTON_THUMBL
        KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerInput.BUTTON_THUMBR
        else -> null
    }

    /** Resolves the configured [ButtonAction] for a given input, defaulting to NONE. */
    fun resolveAction(profile: ControllerProfile, input: ControllerInput): ButtonAction =
        profile.mappings[input] ?: ButtonAction.none()

    /**
     * Applies radial deadzone + sensitivity curve to a raw stick axis pair.
     * Returns normalized (-1f..1f) values ready to scale by screen-space speed.
     */
    fun applyDeadzoneAndSensitivity(rawX: Float, rawY: Float, deadzone: Float, sensitivity: Float): Pair<Float, Float> {
        val magnitude = kotlin.math.sqrt(rawX * rawX + rawY * rawY)
        if (magnitude < deadzone) return 0f to 0f

        // Rescale so output ramps from 0 at the deadzone edge to 1 at full deflection.
        val scale = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f) * sensitivity
        val normalizedX = if (magnitude > 0f) rawX / magnitude else 0f
        val normalizedY = if (magnitude > 0f) rawY / magnitude else 0f
        return (normalizedX * scale) to (normalizedY * scale)
    }

    /** Reads a named axis from a MotionEvent, defaulting to 0f when the device lacks it. */
    fun axisValue(event: MotionEvent, axis: Int): Float = event.getAxisValue(axis)

    /** True once an analog trigger has crossed the "pressed" threshold. */
    fun isTriggerActive(value: Float, threshold: Float = TRIGGER_THRESHOLD): Boolean =
        value >= threshold

    /** Sign-preserving step size for discrete D-pad-style cursor nudges. */
    fun stepFor(axisValue: Float, stepSize: Float): Float =
        if (abs(axisValue) < 0.5f) 0f else sign(axisValue) * stepSize

    companion object {
        const val TRIGGER_THRESHOLD = 0.4f
    }
}
