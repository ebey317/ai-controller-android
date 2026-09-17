package com.ai.controller

import android.content.Context

/**
 * Runtime-entered Groq API key, stored in this app's own private
 * (MODE_PRIVATE) SharedPreferences file — sandboxed per-app storage, not
 * readable by other apps, and never compiled into the APK. This is the
 * supported alternative to editing res/values/groq_api_key.xml: a key
 * entered here at install time never ships inside a distributed build.
 * See MainActivity's Groq API key field and
 * ControllerAccessibilityService.transcribeWithGroq, which checks this store
 * before falling back to the (placeholder-only) build resource.
 */
object GroqKeyStore {
    private const val PREFS_NAME = "ai_controller_groq_key"
    private const val KEY_API_KEY = "api_key"

    fun load(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, null)
            ?.takeIf { it.isNotBlank() }

    fun save(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }
}
