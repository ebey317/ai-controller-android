package com.ai.controller

import android.content.Context

/**
 * Tracks whether the user has explicitly consented to sending microphone
 * audio to the Groq Whisper API for transcription. Shared between
 * MainActivity (which shows the consent dialog) and
 * ControllerAccessibilityService (which gates the voice trigger on it) via a
 * common SharedPreferences file — see PRIVACY.md for the user-facing flow.
 */
object ConsentManager {
    private const val PREFS_NAME = "ai_controller_consent"
    private const val KEY_CONSENT = "consent"

    fun isGranted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONSENT, false)

    fun setGranted(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONSENT, granted)
            .apply()
    }
}
