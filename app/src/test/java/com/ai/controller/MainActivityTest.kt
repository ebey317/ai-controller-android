package com.ai.controller

import android.provider.Settings
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * Crew B — Robolectric activity test for [MainActivity].
 *
 * Covers the consent gate (shown once until decided), control wiring against
 * the default profile, the Groq key save flow, and navigation intents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun nextStartedAction(): String? =
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.action

    // ── Consent gate ─────────────────────────────────────────────────────

    @Test
    fun `consent dialog appears on first launch`() {
        launch()
        assertNotNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun `accepting consent persists the grant`() {
        val activity = launch()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // AppCompat dispatches dialog button clicks through a Handler, so the
        // main looper must idle before the listener runs.
        ShadowLooper.idleMainLooper()
        assertTrue(ConsentManager.isGranted(activity))
    }

    @Test
    fun `declining consent persists denial and shows a toast`() {
        val activity = launch()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(ConsentManager.isGranted(activity))
        assertEquals(
            activity.getString(R.string.toast_consent_declined),
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun `no consent dialog once consent is already granted`() {
        ConsentManager.setGranted(RuntimeEnvironment.getApplication(), true)
        launch()
        assertNull(ShadowDialog.getLatestDialog())
    }

    // ── Control wiring ───────────────────────────────────────────────────

    @Test
    fun `controls reflect the default profile`() {
        val activity = launch()
        // Default profile: cursor enabled, not inverted, sensitivity 1.0.
        assertTrue(activity.findViewById<CompoundButton>(R.id.switchCursorEnabled).isChecked)
        assertFalse(activity.findViewById<CompoundButton>(R.id.switchInvertScroll).isChecked)
        assertEquals(
            (1.0f * 33.3f).toInt(),
            activity.findViewById<SeekBar>(R.id.seekBarSensitivity).progress
        )
        // Skin-tone spinner offers every enum entry.
        val spinner = activity.findViewById<Spinner>(R.id.spinnerSkinTone)
        assertEquals(EmojiSkinTone.entries.size, spinner.adapter.count)
        // Debug overlay and start-on-boot default off.
        assertFalse(activity.findViewById<CompoundButton>(R.id.switchDebugOverlay).isChecked)
        assertFalse(activity.findViewById<CompoundButton>(R.id.switchStartOnBoot).isChecked)
    }

    @Test
    fun `toggling cursor switch persists the profile`() {
        val activity = launch()
        val switch = activity.findViewById<CompoundButton>(R.id.switchCursorEnabled)
        switch.isChecked = false
        assertFalse(ProfileManager(activity).loadProfile().cursorEnabled)
        switch.isChecked = true
        assertTrue(ProfileManager(activity).loadProfile().cursorEnabled)
    }

    @Test
    fun `service toggle without enabled service opens accessibility settings`() {
        val activity = launch()
        activity.findViewById<CompoundButton>(R.id.switchServiceToggle).isChecked = true
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, nextStartedAction())
    }

    @Test
    fun `status text shows disabled when the service is not enabled`() {
        val activity = launch() // onResume -> refreshServiceStatus()
        val status = activity.findViewById<TextView>(R.id.textServiceStatus)
        assertEquals(activity.getString(R.string.status_disabled), status.text.toString())
    }

    // ── Groq key flow ────────────────────────────────────────────────────

    @Test
    fun `saving a groq key persists it and toasts confirmation`() {
        val activity = launch()
        activity.findViewById<EditText>(R.id.editGroqApiKey).setText("sk-test-123")
        activity.findViewById<Button>(R.id.buttonSaveGroqKey).performClick()
        assertEquals("sk-test-123", GroqKeyStore.load(activity))
        assertEquals(
            activity.getString(R.string.toast_groq_key_saved),
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun `empty groq key is rejected with a toast and not saved`() {
        val activity = launch()
        assertEquals("", activity.findViewById<EditText>(R.id.editGroqApiKey).text.toString())
        activity.findViewById<Button>(R.id.buttonSaveGroqKey).performClick()
        assertNull(GroqKeyStore.load(activity))
        assertEquals(
            activity.getString(R.string.toast_groq_key_empty),
            ShadowToast.getTextOfLatestToast()
        )
    }

    // ── Navigation ───────────────────────────────────────────────────────

    @Test
    fun `open mappings launches the settings activity`() {
        val activity = launch()
        activity.findViewById<Button>(R.id.buttonOpenMappings).performClick()
        val next = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(next)
        assertTrue(next.component!!.className.contains("SettingsActivity"))
    }

    @Test
    fun `open custom keyboard launches the keyboard activity`() {
        val activity = launch()
        activity.findViewById<Button>(R.id.buttonOpenCustomKeyboard).performClick()
        val next = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(next)
        assertTrue(next.component!!.className.contains("KeyboardActivity"))
    }
}
