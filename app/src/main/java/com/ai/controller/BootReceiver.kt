package com.ai.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Android does not allow silently (re)binding an AccessibilityService at boot —
 * only the user, via Settings > Accessibility, can grant/enable it. This receiver
 * exists so MainActivity can surface "was enabled before reboot, please re-enable"
 * state instead of the app just going quiet after a restart.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, false)
        if (!startOnBoot) return

        Log.i(TAG, "Boot completed with start-on-boot enabled; accessibility service requires manual re-enable per OS policy")
        prefs.edit().putBoolean(KEY_PENDING_REENABLE_PROMPT, true).apply()
    }

    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS_NAME = "ai_controller_prefs"
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_PENDING_REENABLE_PROMPT = "pending_reenable_prompt"
    }
}
