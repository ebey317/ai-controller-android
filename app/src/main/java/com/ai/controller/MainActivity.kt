package com.ai.controller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ai.controller.databinding.ActivityMainBinding
import com.ai.controller.ui.SettingsActivity

private const val RC_RECORD_AUDIO = 1001
private const val PREFS_MAIN = "main"
private const val KEY_SERVICE_ACTIVE = "service_active"

/**
 * Top-level settings screen: enable/disable the accessibility service, tune
 * cursor sensitivity/deadzone, toggle the on-screen cursor and start-on-boot,
 * and jump into per-button mapping configuration.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var profileManager: ProfileManager
    private lateinit var profile: com.ai.controller.models.ControllerProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profileManager = ProfileManager(this)
        profile = profileManager.loadProfile()

        bindControls()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
    }

    private fun bindControls() {
        binding.switchCursorEnabled.isChecked = profile.cursorEnabled
        binding.switchCursorEnabled.setOnCheckedChangeListener { _, checked ->
            profile.cursorEnabled = checked
            profileManager.saveProfile(profile)
        }

        binding.switchInvertScroll.isChecked = profile.invertScroll
        binding.switchInvertScroll.setOnCheckedChangeListener { _, checked ->
            profile.invertScroll = checked
            profileManager.saveProfile(profile)
        }

        binding.switchStartOnBoot.isChecked = getStartOnBootPref()
        binding.switchStartOnBoot.setOnCheckedChangeListener { _, checked ->
            setStartOnBootPref(checked)
        }

        binding.seekBarSensitivity.progress = (profile.sensitivity * SENSITIVITY_SCALE).toInt()
            .coerceIn(0, binding.seekBarSensitivity.max)
        binding.seekBarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                profile.sensitivity = (progress / SENSITIVITY_SCALE).coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
                profileManager.saveProfile(profile)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.seekBarDeadzone.progress = (profile.deadzone * DEADZONE_SCALE).toInt()
            .coerceIn(0, binding.seekBarDeadzone.max)
        binding.seekBarDeadzone.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                profile.deadzone = (progress / DEADZONE_SCALE).coerceIn(MIN_DEADZONE, MAX_DEADZONE)
                profileManager.saveProfile(profile)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.buttonEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.buttonOpenMappings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.buttonResetDefaults.setOnClickListener { confirmReset() }

        binding.switchServiceToggle.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!isAccessibilityServiceEnabled()) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    binding.switchServiceToggle.isChecked = false
                } else {
                    requestRecordAudioPermission()
                }
            }
            setServiceActivePref(checked)
        }

        binding.buttonTestKeyboard.setOnClickListener {
            binding.editTestInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.editTestInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun requestRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.RECORD_AUDIO), RC_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_RECORD_AUDIO) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                showToast(getString(R.string.toast_mic_permission_needed))
                binding.switchServiceToggle.isChecked = false
                setServiceActivePref(false)
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.mapping_reset_confirm_title)
            .setMessage(R.string.mapping_reset_confirm_message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                profile = profileManager.resetToDefault()
                bindControls()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun refreshServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        binding.textServiceStatus.text = getString(
            if (enabled) R.string.status_enabled else R.string.status_disabled
        )
        binding.switchServiceToggle.isChecked = getServiceActivePref()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${ControllerAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
            .asSequence()
            .any { it.equals(expectedComponent, ignoreCase = true) }
    }

    private fun getServiceActivePref(): Boolean =
        getSharedPreferences(PREFS_MAIN, MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_ACTIVE, false)

    private fun setServiceActivePref(value: Boolean) {
        getSharedPreferences(PREFS_MAIN, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVICE_ACTIVE, value)
            .apply()
        showToast(getString(if (value) R.string.toast_service_started else R.string.toast_service_stopped))
    }

    private fun getStartOnBootPref(): Boolean =
        getSharedPreferences(BootReceiver.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(BootReceiver.KEY_START_ON_BOOT, false)

    private fun setStartOnBootPref(value: Boolean) {
        getSharedPreferences(BootReceiver.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(BootReceiver.KEY_START_ON_BOOT, value)
            .apply()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun TextUtils.SimpleStringSplitter.asSequence(): Sequence<String> = sequence {
        while (hasNext()) yield(next())
    }

    companion object {
        private const val SENSITIVITY_SCALE = 33.3f // progress 0..100 -> sensitivity 0..3.0
        private const val DEADZONE_SCALE = 200f     // progress 0..100 -> deadzone 0..0.5
        private const val MIN_SENSITIVITY = 0.1f
        private const val MAX_SENSITIVITY = 3.0f
        private const val MIN_DEADZONE = 0.0f
        private const val MAX_DEADZONE = 0.5f
    }
}
