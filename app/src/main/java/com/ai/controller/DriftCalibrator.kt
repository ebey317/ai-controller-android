package com.ai.controller

import kotlin.math.sqrt

/**
 * Analog-stick drift compensator — the Android analogue of settle_wiggle.sh's
 * anti-drift intent, expressed as input math instead of an X11 mouse nudge
 * (there is no cursor-hover-recompute concept to nudge on Android). Worn
 * analog sticks tend to rest a few percent off dead center rather than
 * snapping back to exactly (0,0); left uncorrected that reads as a constant
 * slow cursor creep. This watches raw stick samples and, when the stick
 * sits consistently near-but-not-quite zero, learns a small bias vector and
 * subtracts it from future readings so "at rest" reads as true zero.
 */
class DriftCalibrator {

    private var biasX = 0f
    private var biasY = 0f
    private var restSamples = 0
    private var restSumX = 0f
    private var restSumY = 0f

    /** Feed a raw (pre-deadzone) sample; returns the bias-corrected value. */
    fun correct(rawX: Float, rawY: Float): Pair<Float, Float> {
        val correctedX = rawX - biasX
        val correctedY = rawY - biasY
        trackRest(correctedX, correctedY)
        return correctedX to correctedY
    }

    private fun trackRest(x: Float, y: Float) {
        val magnitude = sqrt(x * x + y * y)
        when {
            magnitude in REST_MIN..REST_MAX -> {
                // Stick reads as "almost at rest but slightly off" — accumulate
                // toward a new bias estimate rather than snapping immediately,
                // so one noisy sample can't yank the calibration around.
                restSumX += x
                restSumY += y
                restSamples++
                if (restSamples >= HISTORY_SIZE) {
                    biasX += (restSumX / restSamples) * LEARN_RATE
                    biasY += (restSumY / restSamples) * LEARN_RATE
                    resetHistory()
                }
            }
            magnitude > REST_MAX -> {
                // Stick is being actively used — drop any partial history so an
                // in-motion sample is never mistaken for rest drift.
                resetHistory()
            }
            // magnitude < REST_MIN: true zero, nothing to learn from either way.
        }
    }

    private fun resetHistory() {
        restSamples = 0
        restSumX = 0f
        restSumY = 0f
    }

    fun reset() {
        biasX = 0f
        biasY = 0f
        resetHistory()
    }

    companion object {
        private const val REST_MIN = 0.01f
        private const val REST_MAX = 0.12f
        private const val HISTORY_SIZE = 30 // ~0.5s at 60Hz motion events
        private const val LEARN_RATE = 0.5f // partial correction per window, avoids overshoot
    }
}
