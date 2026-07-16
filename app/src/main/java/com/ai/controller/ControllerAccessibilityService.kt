package com.ai.controller

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
import com.ai.controller.models.SwipeDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Core AccessibilityService: reads Xbox-style gamepad input (buttons via
 * onKeyEvent, sticks/triggers via a focused joystick-capture overlay) and
 * turns it into touch gestures, global actions, and cursor movement.
 *
 * No root, ADB, or companion device required — everything routes through
 * public AccessibilityService + WindowManager APIs.
 */
class ControllerAccessibilityService : AccessibilityService() {

    private val inputMapper = InputMapper()
    private lateinit var profileManager: ProfileManager
    private lateinit var cursorOverlay: CursorOverlay
    private lateinit var windowManager: WindowManager
    private var profile: ControllerProfile = ControllerProfile.default()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var motionCaptureView: MotionCaptureView? = null
    private val activeTriggerRunnables = mutableMapOf<ControllerInput, Runnable>()
    private var lastStickScrollTimeMs = 0L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    private val pcmBuffer = ByteArrayOutputStream()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        profile = profileManager.loadProfile()
        if (profile.cursorEnabled) cursorOverlay.show() else cursorOverlay.hide()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        profileManager = ProfileManager(this)
        profile = profileManager.loadProfile()

        cursorOverlay = CursorOverlay(this)
        if (profile.cursorEnabled) cursorOverlay.show()

        attachMotionCapture()
        registerPrefsListener()
        instance = this
        Log.i(TAG, "ControllerAccessibilityService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        unregisterPrefsListener()
        cancelAllTriggerRunnables()
        detachMotionCapture()
        stopVoiceRecording()
        serviceScope.cancel()
        if (::cursorOverlay.isInitialized) cursorOverlay.hide()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No content-tree reactions needed; input is driven entirely by key/motion callbacks.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    // ---------------------------------------------------------------------
    // Digital buttons
    // ---------------------------------------------------------------------

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val input = inputMapper.keyCodeToInput(event.keyCode) ?: return super.onKeyEvent(event)
        if (event.repeatCount > 0) return true // swallow OS auto-repeat; we drive our own timing

        if (event.action == KeyEvent.ACTION_DOWN) {
            handleButtonDown(input)
        }
        return true
    }

    private fun handleButtonDown(input: ControllerInput) {
        val action = inputMapper.resolveAction(profile, input)
        val cursor = cursorOverlay.getPosition()

        when (action.type) {
            ActionType.TAP -> dispatchTap(cursor)
            ActionType.LONG_PRESS -> dispatchLongPress(cursor, action.durationMs)
            ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ActionType.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            ActionType.SCROLL -> action.swipeDirection?.let { dispatchScroll(cursor, it) }
            ActionType.SWIPE -> action.swipeDirection?.let { dispatchSwipe(cursor, it, action.durationMs) }
            ActionType.KEY_EVENT -> handleKeyEventAction(action)
            ActionType.VOICE_TRIGGER -> triggerVoiceDictation()
            ActionType.SHOW_KEYBOARD -> setSoftKeyboardMode(true)
            ActionType.FOCUS_NEXT -> moveAccessibilityFocus(true)
            ActionType.NONE -> Unit
        }
    }

    private fun handleKeyEventAction(action: ButtonAction) {
        // AccessibilityService cannot inject arbitrary KeyEvents into another app without
        // system permission INJECT_EVENTS. For this standalone product, D-pad codes are
        // remapped to cursor nudges. F13-style "key" functionality is replaced by text
        // injection via the accessibility input method connection (see injectText).
        when (action.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> cursorOverlay.applyDelta(0f, -CURSOR_STEP_PX)
            KeyEvent.KEYCODE_DPAD_DOWN -> cursorOverlay.applyDelta(0f, CURSOR_STEP_PX)
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorOverlay.applyDelta(-CURSOR_STEP_PX, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorOverlay.applyDelta(CURSOR_STEP_PX, 0f)
            else -> Log.w(TAG, "keyCode=${action.keyCode} has no public injection path; ignoring")
        }
    }

    private fun setSoftKeyboardMode(show: Boolean) {
        try {
            // SHOW_MODE_AUTO = 0, SHOW_MODE_HIDDEN = 1, SHOW_MODE_VISIBLE = 2
            // Constants are not resolved by this build, so use raw ints.
            val mode = if (show) 2 else 1
            softKeyboardController.setShowMode(mode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle soft keyboard", e)
        }
    }

    private fun moveAccessibilityFocus(forward: Boolean) {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            val direction = if (forward) 2 else 1
            val next = focused.focusSearch(direction)
            next?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
    }

    private fun startVoiceRecording() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(sampleRate * 2) // at least 1 second

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuffer
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }
        audioRecord = recorder
        pcmBuffer.reset()
        isRecording = true
        recorder.startRecording()
        showVoiceToast(true)

        recordingThread = Thread({
            val buffer = ByteArray(minBuffer)
            while (isRecording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) pcmBuffer.write(buffer, 0, read)
            }
            recorder.stop()
            recorder.release()
        }, "VoiceRecordingThread").apply { start() }
    }

    private fun stopVoiceRecording() {
        if (!isRecording) return
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        audioRecord = null
        showVoiceToast(false)

        val pcmBytes = pcmBuffer.toByteArray()
        if (pcmBytes.isEmpty()) return

        serviceScope.launch(Dispatchers.IO) {
            try {
                val wavFile = writeWavToCache(pcmBytes, 16000)
                val transcript = transcribeWithGroq(wavFile)
                wavFile.delete()
                if (transcript.isNotBlank()) {
                    withContext(Dispatchers.Main) { injectText(transcript) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice transcription failed", e)
            }
        }
    }

    private fun showVoiceToast(recording: Boolean) {
        val text = if (recording) "Listening..." else "Processing voice..."
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    private fun writeWavToCache(pcmBytes: ByteArray, sampleRate: Int): File {
        val wav = File(cacheDir, "voice_prompt.wav")
        FileOutputStream(wav).use { fos ->
            DataOutputStream(fos).use { out ->
                val byteRate = sampleRate * 1 * 16 / 8
                val totalDataLen = pcmBytes.size + 36

                fun Int.toLittleEndian(): ByteArray = byteArrayOf(
                    (this and 0xFF).toByte(),
                    (this shr 8 and 0xFF).toByte(),
                    (this shr 16 and 0xFF).toByte(),
                    (this shr 24 and 0xFF).toByte()
                )

                fun Short.toLittleEndian(): ByteArray = byteArrayOf(
                    (this.toInt() and 0xFF).toByte(),
                    (this.toInt() shr 8 and 0xFF).toByte()
                )

                out.writeBytes("RIFF")
                out.write(totalDataLen.toLittleEndian())
                out.writeBytes("WAVEfmt ")
                out.write(16.toLittleEndian())
                out.write(1.toShort().toLittleEndian()) // PCM
                out.write(1.toShort().toLittleEndian()) // mono
                out.write(sampleRate.toLittleEndian())
                out.write(byteRate.toLittleEndian())
                out.write(2.toShort().toLittleEndian()) // block align
                out.write(16.toShort().toLittleEndian()) // bits per sample
                out.writeBytes("data")
                out.write(pcmBytes.size.toLittleEndian())
                out.write(pcmBytes)
            }
        }
        return wav
    }

    private fun transcribeWithGroq(file: File): String {
        val apiKey = getString(R.string.groq_api_key)
        if (apiKey.isBlank() || apiKey == "YOUR_GROQ_API_KEY") {
            throw IllegalStateException("Groq API key not configured in groq_api_key.xml")
        }
        val boundary = "Boundary-${System.currentTimeMillis()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val url = URL("https://api.groq.com/openai/v1/audio/transcriptions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doInput = true
        conn.doOutput = true
        conn.useCaches = false
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        conn.outputStream.use { output ->
            output.write("$twoHyphens$boundary$lineEnd".toByteArray())
            output.write("Content-Disposition: form-data; name=\"model\"$lineEnd$lineEnd".toByteArray())
            output.write("whisper-large-v3$lineEnd".toByteArray())
            output.write("$twoHyphens$boundary$lineEnd".toByteArray())
            output.write("Content-Disposition: form-data; name=\"file\"; filename=\"voice_prompt.wav\"$lineEnd".toByteArray())
            output.write("Content-Type: audio/wav$lineEnd$lineEnd".toByteArray())
            file.inputStream().use { it.copyTo(output) }
            output.write(lineEnd.toByteArray())
            output.write("$twoHyphens$boundary$twoHyphens$lineEnd".toByteArray())
        }

        val responseCode = conn.responseCode
        val response = conn.inputStream.use { it.reader().readText() }
        if (responseCode !in 200..299) {
            throw IllegalStateException("Groq API error $responseCode: $response")
        }
        val json = JSONObject(response)
        return json.optString("text", "").trim()
    }

    private fun injectText(text: String) {
        try {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null) {
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else {
                Log.w(TAG, "No focused node for text injection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Text injection failed", e)
        }
    }

    private fun triggerVoiceDictation() {
        if (isRecording) stopVoiceRecording() else startVoiceRecording()
    }
    // Sticks + triggers (joystick motion, captured via a focused overlay)
    // ---------------------------------------------------------------------

    private fun attachMotionCapture() {
        val view = MotionCaptureView(this) { event -> handleGenericMotion(event) }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(view, params)
            view.requestFocus()
            motionCaptureView = view
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Failed to attach joystick capture overlay", e)
        }
    }

    private fun detachMotionCapture() {
        motionCaptureView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Motion capture view already detached", e)
            }
        }
        motionCaptureView = null
    }

    private fun handleGenericMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false

        // Left stick → cursor movement.
        val lx = inputMapper.axisValue(event, MotionEvent.AXIS_X)
        val ly = inputMapper.axisValue(event, MotionEvent.AXIS_Y)
        val (dx, dy) = inputMapper.applyDeadzoneAndSensitivity(lx, ly, profile.deadzone, profile.sensitivity)
        if (dx != 0f || dy != 0f) {
            cursorOverlay.applyDelta(dx * CURSOR_SPEED_PX, dy * CURSOR_SPEED_PX)
        }

        // Right stick → scroll from cursor position.
        val rx = inputMapper.axisValue(event, MotionEvent.AXIS_Z)
        val ry = inputMapper.axisValue(event, MotionEvent.AXIS_RZ)
        val (_, sy) = inputMapper.applyDeadzoneAndSensitivity(rx, ry, profile.deadzone, 1f)
        if (sy != 0f) maybeDispatchStickScroll(sy)

        // Triggers → held scroll up/down. Different pads report these on different axes.
        val leftTrigger = inputMapper.axisValue(event, MotionEvent.AXIS_LTRIGGER)
            .takeIf { it != 0f } ?: inputMapper.axisValue(event, MotionEvent.AXIS_BRAKE)
        val rightTrigger = inputMapper.axisValue(event, MotionEvent.AXIS_RTRIGGER)
            .takeIf { it != 0f } ?: inputMapper.axisValue(event, MotionEvent.AXIS_GAS)
        handleTrigger(ControllerInput.TRIGGER_L2, leftTrigger)
        handleTrigger(ControllerInput.TRIGGER_R2, rightTrigger)

        return true
    }

    private fun maybeDispatchStickScroll(sy: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastStickScrollTimeMs < STICK_SCROLL_COOLDOWN_MS) return
        lastStickScrollTimeMs = now
        val direction = if (sy < 0f) SwipeDirection.UP else SwipeDirection.DOWN
        dispatchScroll(cursorOverlay.getPosition(), direction)
    }

    private fun handleTrigger(input: ControllerInput, value: Float) {
        val active = inputMapper.isTriggerActive(value)
        val running = activeTriggerRunnables.containsKey(input)
        if (active && !running) {
            val action = inputMapper.resolveAction(profile, input)
            val runnable = object : Runnable {
                override fun run() {
                    if (action.type == ActionType.SCROLL) {
                        action.swipeDirection?.let { dispatchScroll(cursorOverlay.getPosition(), it) }
                    }
                    mainHandler.postDelayed(this, TRIGGER_REPEAT_MS)
                }
            }
            activeTriggerRunnables[input] = runnable
            mainHandler.post(runnable)
        } else if (!active && running) {
            activeTriggerRunnables.remove(input)?.let { mainHandler.removeCallbacks(it) }
        }
    }

    private fun cancelAllTriggerRunnables() {
        activeTriggerRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        activeTriggerRunnables.clear()
    }

    // ---------------------------------------------------------------------
    // Gesture dispatch
    // ---------------------------------------------------------------------

    private fun dispatchTap(point: PointF) {
        val path = Path().apply { moveTo(point.x, point.y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun dispatchLongPress(point: PointF, durationMs: Long) {
        val path = Path().apply { moveTo(point.x, point.y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(MIN_LONG_PRESS_MS))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun dispatchScroll(origin: PointF, direction: SwipeDirection) {
        dispatchSwipe(origin, direction, SCROLL_DURATION_MS, SCROLL_DISTANCE_PX)
    }

    private fun dispatchSwipe(
        origin: PointF,
        direction: SwipeDirection,
        durationMs: Long,
        distance: Float = SWIPE_DISTANCE_PX
    ) {
        val metrics = resources.displayMetrics
        val (rawEndX, rawEndY) = when (direction) {
            SwipeDirection.UP -> origin.x to (origin.y - distance)
            SwipeDirection.DOWN -> origin.x to (origin.y + distance)
            SwipeDirection.LEFT -> (origin.x - distance) to origin.y
            SwipeDirection.RIGHT -> (origin.x + distance) to origin.y
        }
        val endX = rawEndX.coerceIn(0f, metrics.widthPixels.toFloat())
        val endY = rawEndY.coerceIn(0f, metrics.heightPixels.toFloat())

        val path = Path().apply {
            moveTo(origin.x, origin.y)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(MIN_SWIPE_MS))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ---------------------------------------------------------------------
    // Preference change → live profile reload
    // ---------------------------------------------------------------------

    private fun registerPrefsListener() {
        getSharedPreferences(PROFILE_PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun unregisterPrefsListener() {
        getSharedPreferences(PROFILE_PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    /** Tiny focusable overlay whose sole purpose is to receive joystick MotionEvents. */
    private class MotionCaptureView(
        context: android.content.Context,
        private val onMotion: (MotionEvent) -> Boolean
    ) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            return if (onMotion(event)) true else super.onGenericMotionEvent(event)
        }
    }

    companion object {
        private const val TAG = "AIControllerService"
        private const val PROFILE_PREFS_NAME = "ai_controller_prefs"

        private const val CURSOR_SPEED_PX = 24f
        private const val CURSOR_STEP_PX = 60f
        private const val TAP_DURATION_MS = 50L
        private const val MIN_LONG_PRESS_MS = 500L
        private const val MIN_SWIPE_MS = 100L
        private const val SCROLL_DURATION_MS = 250L
        private const val SCROLL_DISTANCE_PX = 400f
        private const val SWIPE_DISTANCE_PX = 300f
        private const val TRIGGER_REPEAT_MS = 300L
        private const val STICK_SCROLL_COOLDOWN_MS = 200L

        /** Set while the service is bound; lets the UI reflect live enabled state. */
        var instance: ControllerAccessibilityService? = null
            private set
    }
}
