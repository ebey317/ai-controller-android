package com.ai.controller

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ai.controller.models.ControllerProfile
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists the active [ControllerProfile] to SharedPreferences as JSON.
 * Falls back to [ControllerProfile.default] whenever no profile has been
 * saved yet or the stored JSON is corrupt. Serialization itself lives in
 * [ProfileSerializer], shared with [ContextSwitcher]'s preset profiles.
 */
class ProfileManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadProfile(): ControllerProfile {
        val raw = prefs.getString(KEY_PROFILE, null) ?: return ControllerProfile.default()
        return try {
            ProfileSerializer.deserialize(JSONObject(raw))
        } catch (e: JSONException) {
            Log.w(TAG, "Corrupt profile JSON, falling back to default", e)
            ControllerProfile.default()
        }
    }

    fun saveProfile(profile: ControllerProfile) {
        prefs.edit()
            .putString(KEY_PROFILE, ProfileSerializer.serialize(profile).toString())
            .apply()
    }

    fun resetToDefault(): ControllerProfile {
        val default = ControllerProfile.default()
        saveProfile(default)
        return default
    }

    companion object {
        private const val TAG = "ProfileManager"
        private const val PREFS_NAME = "ai_controller_prefs"
        private const val KEY_PROFILE = "active_profile"
    }
}
