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
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
import com.ai.controller.models.SwipeDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Core AccessibilityService: reads Xbox-style gamepad input (buttons via
 * onKeyEvent, sticks/triggers via a focused joystick-capture overlay) and
 * turns it into touch gestures, global actions, cursor movement, and
 * push-to-talk voice dictation.
 *
 * No root, ADB, or companion device required — everything routes through
 * public AccessibilityService + WindowManager APIs.
 */
class ControllerAccessibilityService : AccessibilityService() {

    private val inputMapper = InputMapper()
    private lateinit var profileManager: ProfileManager
    private lateinit var contextSwitcher: ContextSwitcher
    private lateinit var cursorOverlay: CursorOverlay
    private lateinit var legendOverlay: LegendOverlay
    private lateinit var voiceManager: VoiceManager
    private lateinit var windowManager: WindowManager
    private var profile: ControllerProfile = ControllerProfile.default()
    private val driftCalibrator = DriftCalibrator()

    private var motionCaptureView: MotionCaptureView? = null
    private val activeTriggerJobs = mutableMapOf<ControllerInput, Job>()
    private val triggerHeldState = mutableMapOf<ControllerInput, Boolean>()
    private var lastStickScrollTimeMs = 0L

    // Lifecycle-scoped: every coroutine this service launches (trigger repeats,
    // legend tick, voice recording, the focus watchdog) is a child of this scope
    // and dies with it in onDestroy — fixes A5 (trigger runnables leaking across
    // service destroy).
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var voiceBridgeServer: VoiceBridgeServer? = null
    private var legendTickJob: Job? = null
    private var focusWatchdogJob: Job? = null

    // Push-to-talk state — A2: coroutine-driven recording with a sized AudioRecord
    // buffer and real cancellation, instead of a raw busy-read Thread.
    private lateinit var pttController: PttController
    private var audioRecord: AudioRecord? = null
    private var voiceRecordJob: Job? = null
    @Volatile private var isRecording = false
    @Volatile private var voiceRecordCancelled = false
    private var recordStartElapsedMs = 0L

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        reloadActiveProfile()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        profileManager = ProfileManager(this)
        contextSwitcher = ContextSwitcher(this)
        voiceManager = VoiceManager(this)
        // Apply the customer's saved emoji skin tone before any transcript can
        // be styled (SkinToneStore default: Dark, the Linux build's look).
        TextStyles.setSkinTone(SkinToneStore.load(this))
        profile = contextSwitcher.profileFor(contextSwitcher.activeContext(), profileManager)

        cursorOverlay = CursorOverlay(this)
        legendOverlay = LegendOverlay(this)
        if (profile.cursorEnabled) {
            cursorOverlay.show()
            legendOverlay.show()
        }

        pttController = PttController(
            onStart = { startVoiceRecording() },
            onStop = { stopVoiceRecording(cancel = false) },
            onCancel = { stopVoiceRecording(cancel = true) }
        )

        attachMotionCapture()
        startFocusWatchdog()
        startLegendTick()
        registerPrefsListener()

        voiceBridgeServer = VoiceBridgeServer(
            onTranscribeOnly = { audioBytes -> transcribeBytesIfConsented(audioBytes) },
            onSpeak = { text -> voiceManager.speak(text) }
        ).also { it.start() }

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
        cancelAllTriggerJobs()
        detachMotionCapture()
        hardStopVoiceRecording()
        pttController.reset()
        voiceBridgeServer?.stop()
        voiceBridgeServer = null
        if (::voiceManager.isInitialized) voiceManager.shutdown()
        legendTickJob?.cancel()
        focusWatchdogJob?.cancel()
        // Cancels every remaining child coroutine (trigger jobs, legend tick,
        // focus watchdog, any in-flight voice job) in one place — the actual
        // fix for A5, everything above is defense in depth for early bail-outs.
        serviceScope.cancel()
        if (::cursorOverlay.isInitialized) cursorOverlay.hide()
        if (::legendOverlay.isInitialized) legendOverlay.hide()
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

        val action = inputMapper.resolveAction(profile, input)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (action.type == ActionType.VOICE_TRIGGER) {
                    pttController.onButtonDown()
                } else {
                    handleButtonDown(input, action)
                }
            }
            KeyEvent.ACTION_UP -> {
                // Only voice-trigger cares about release; every other action already
                // fired on ACTION_DOWN above, matching the original tap-on-press model.
                if (action.type == ActionType.VOICE_TRIGGER) {
                    pttController.onButtonUp()
                }
            }
        }
        return true
    }

    private fun handleButtonDown(input: ControllerInput, action: ButtonAction = inputMapper.resolveAction(profile, input)) {
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
            ActionType.VOICE_TRIGGER -> Unit // handled by PttController edges, not a single-shot tap
            ActionType.SHOW_KEYBOARD -> startCustomKeyboard()
            ActionType.FOCUS_NEXT -> moveAccessibilityFocus(true)
            ActionType.CYCLE_CONTEXT -> cycleContext()
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

    private fun startCustomKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(null, 0)
            val intent = Intent(this, com.ai.controller.ui.KeyboardActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start custom keyboard", e)
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

    // ---------------------------------------------------------------------
    // Profile contexts (desktop / browser / iptv) — ContextSwitcher
    // ---------------------------------------------------------------------

    private fun cycleContext() {
        val next = contextSwitcher.cycleContext()
        reloadActiveProfile()
        Toast.makeText(applicationContext, "Context: ${next.replaceFirstChar { it.uppercase() }}", Toast.LENGTH_SHORT).show()
        voiceManager.speak("${next} mode")
    }

    private fun reloadActiveProfile() {
        profile = contextSwitcher.profileFor(contextSwitcher.activeContext(), profileManager)
        if (profile.cursorEnabled) {
            cursorOverlay.show()
            legendOverlay.show()
        } else {
            cursorOverlay.hide()
            legendOverlay.hide()
        }
    }

    // ---------------------------------------------------------------------
    // Voice dictation — push-to-talk via PttController, Groq Whisper STT
    // ---------------------------------------------------------------------

    /** A4: gate every path into Groq behind explicit, persisted user consent. */
    private fun consentGranted(): Boolean = ConsentManager.isGranted(this)

    private fun startVoiceRecording() {
        if (isRecording) return // debounce reentry; PttController already guards this too
        if (!consentGranted()) {
            Toast.makeText(
                applicationContext,
                "Voice dictation needs consent — open AI Controller to allow microphone use.",
                Toast.LENGTH_LONG
            ).show()
            pttController.reset()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pttController.reset()
            return
        }

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(sampleRate * 2) // at least 1 second, sized chunk buffer (A2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuffer
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            recorder.release()
            pttController.reset()
            return
        }

        audioRecord = recorder
        isRecording = true
        voiceRecordCancelled = false
        recordStartElapsedMs = SystemClock.elapsedRealtime()
        recorder.startRecording()
        showVoiceToast(true)

        // Coroutine instead of a raw Thread: cancellable via Job.cancel(), tied to
        // the service's own lifecycle scope, no separate busy-poll flag to leak.
        voiceRecordJob = serviceScope.launch(Dispatchers.IO) {
            val pcmBuffer = ByteArrayOutputStream()
            val chunk = ByteArray(minBuffer) // sized buffer — bounded per-read chunking, not one giant read
            try {
                while (isRecording && isActive) {
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read > 0) pcmBuffer.write(chunk, 0, read)
                    if (SystemClock.elapsedRealtime() - recordStartElapsedMs > MAX_RECORDING_MS) {
                        // Long-press safety net: a stuck/forgotten trigger must never
                        // record forever. Auto-cancel — no STT, no injected text — same
                        // "never leave a live capture" contract as the desktop's
                        // _kill_recorder, just expressed as a duration cap instead of a
                        // debounce window.
                        Log.w(TAG, "Voice recording exceeded ${MAX_RECORDING_MS}ms — auto-cancelling")
                        voiceRecordCancelled = true
                        isRecording = false
                    }
                }
            } finally {
                try { recorder.stop() } catch (e: IllegalStateException) { /* never started recording */ }
                recorder.release()
                // Identity check: only clear the field if it still points at THIS
                // recorder. A newer startVoiceRecording() may already have replaced
                // it with a fresh instance by the time this finally block runs —
                // clearing unconditionally would null out (and, via stop's release
                // calls, double-release) that newer recorder out from under it.
                if (audioRecord === recorder) audioRecord = null
            }

            withContext(Dispatchers.Main) { showVoiceToast(false) }

            if (!voiceRecordCancelled && isActive) {
                val pcmBytes = pcmBuffer.toByteArray()
                if (pcmBytes.isNotEmpty()) processRecording(pcmBytes, sampleRate)
            }
        }
    }

    /** [cancel]=true is the PTT-release-into-cancel / long-press-timeout path: the
     * Job is cancelled outright (A2) so no STT call and no text ever lands. */
    private fun stopVoiceRecording(cancel: Boolean) {
        if (!isRecording && voiceRecordJob?.isActive != true) return
        voiceRecordCancelled = cancel
        isRecording = false // signals the read loop to exit its while-condition normally
        if (cancel) {
            voiceRecordJob?.cancel()
            // Only stop() here — never release()/null the field. The recording
            // coroutine's own finally block owns releasing its local recorder
            // reference and clears the field itself (with an identity check), so
            // a newer startVoiceRecording() can never have its recorder released
            // or its field reference clobbered by this stop path (F1).
            try { audioRecord?.stop() } catch (e: IllegalStateException) { /* not recording */ }
            showVoiceToast(false)
        }
    }

    /** Used only from teardown: unconditionally drop any in-flight recording. */
    private fun hardStopVoiceRecording() {
        isRecording = false
        voiceRecordCancelled = true
        voiceRecordJob?.cancel()
        voiceRecordJob = null
        // See stopVoiceRecording(cancel=true): stop() only, release/clear is the
        // recording coroutine's own responsibility via its finally block.
        try { audioRecord?.stop() } catch (e: IllegalStateException) { /* not recording */ }
    }

    private suspend fun processRecording(pcmBytes: ByteArray, sampleRate: Int) {
        try {
            val wavFile = writeWavToCache(pcmBytes, sampleRate)
            val rawTranscript = transcribeWithGroq(wavFile)
            wavFile.delete()
            if (rawTranscript.isNotBlank()) {
                val mode = PttModeStore.load(applicationContext)
                val styled = TextStyles.transform(rawTranscript, mode)
                withContext(Dispatchers.Main) { injectText(styled) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Voice transcription failed", e)
        }
    }

    /** Used by [VoiceBridgeServer]'s /voice route — same consent gate as the PTT path. */
    private suspend fun transcribeBytesIfConsented(wavBytes: ByteArray): String {
        if (!consentGranted()) return ""
        return try {
            val tmp = File(cacheDir, "bridge_voice_${System.currentTimeMillis()}.wav")
            FileOutputStream(tmp).use { it.write(wavBytes) }
            val text = transcribeWithGroq(tmp)
            tmp.delete()
            text
        } catch (e: Exception) {
            Log.e(TAG, "voice bridge transcription failed", e)
            ""
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
        // Runtime-entered key (MainActivity's Groq API key field) takes priority
        // over the build resource — F7: entering it at runtime, instead of baking
        // it into groq_api_key.xml, keeps it out of any distributed APK.
        val apiKey = GroqKeyStore.load(this) ?: getString(R.string.groq_api_key)
        if (apiKey.isBlank() || apiKey == "YOUR_GROQ_API_KEY") {
            throw IllegalStateException("Groq API key not configured — set it in AI Controller settings")
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

    /** Replaces the focused field's text — used for whole-utterance dictation drops. */
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

    /** Appends one key's worth of text to whatever is focused — used by KeyboardActivity,
     * which types incrementally rather than dropping a whole utterance at once. */
    fun typeCharacter(ch: String) {
        try {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
            val current = focused.text?.toString().orEmpty()
            injectFocusedText(focused, current + ch)
        } catch (e: Exception) {
            Log.e(TAG, "typeCharacter failed", e)
        }
    }

    /** Appends a whole string in one accessibility call — used by KeyboardActivity's
     * pinned-snippet buttons, so a multi-word pin doesn't cost one call per character. */
    fun typeText(text: String) {
        try {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
            val current = focused.text?.toString().orEmpty()
            injectFocusedText(focused, current + text)
        } catch (e: Exception) {
            Log.e(TAG, "typeText failed", e)
        }
    }

    /** Removes the last character of the focused field's text — KeyboardActivity's backspace. */
    fun backspaceOnce() {
        try {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
            val current = focused.text?.toString().orEmpty()
            if (current.isEmpty()) return
            injectFocusedText(focused, current.dropLast(1))
        } catch (e: Exception) {
            Log.e(TAG, "backspaceOnce failed", e)
        }
    }

    private fun injectFocusedText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---------------------------------------------------------------------
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

    /**
     * A3 fix: joystick MotionEvents (SOURCE_CLASS_JOYSTICK) are routed to
     * whatever window currently holds input focus — unlike touch events,
     * there is no hit-testing fallback. This 1x1 overlay must therefore stay
     * genuinely focusable (FLAG_NOT_FOCUSABLE would make it permanently deaf
     * to the controller, which is worse than the bug it would "fix"). What
     * was actually broken is that nothing ever noticed or recovered when
     * something else — an IME opening, another accessibility overlay, a
     * system dialog — stole that focus away, silently killing stick input
     * until the service was restarted. This watchdog (started in
     * onServiceConnected) plus the view's own onWindowFocusChanged hook
     * below re-request focus the moment it's lost, so joystick capture
     * self-heals instead of going quietly dead.
     */
    private fun startFocusWatchdog() {
        focusWatchdogJob = serviceScope.launch {
            while (isActive) {
                motionCaptureView?.let { view ->
                    if (!view.isFocused) {
                        try {
                            view.requestFocus()
                        } catch (e: Exception) {
                            Log.w(TAG, "Focus watchdog could not reclaim motion capture focus", e)
                        }
                    }
                }
                delay(FOCUS_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private fun startLegendTick() {
        legendTickJob = serviceScope.launch {
            while (isActive) {
                if (::legendOverlay.isInitialized && legendOverlay.isShowing() && ::cursorOverlay.isInitialized) {
                    legendOverlay.update(cursorOverlay.getPosition(), LegendOverlay.legendTextFor(profile))
                }
                delay(LEGEND_TICK_INTERVAL_MS)
            }
        }
    }

    private fun handleGenericMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false

        // Left stick → cursor movement, drift-corrected before deadzone/sensitivity.
        val rawLx = inputMapper.axisValue(event, MotionEvent.AXIS_X)
        val rawLy = inputMapper.axisValue(event, MotionEvent.AXIS_Y)
        val (lx, ly) = driftCalibrator.correct(rawLx, rawLy)
        val (dx, dy) = inputMapper.applyDeadzoneAndSensitivity(lx, ly, profile.deadzone, profile.sensitivity)
        if (dx != 0f || dy != 0f) {
            cursorOverlay.applyDelta(dx * CURSOR_SPEED_PX, dy * CURSOR_SPEED_PX)
        }

        // Right stick → scroll from cursor position.
        val rx = inputMapper.axisValue(event, MotionEvent.AXIS_Z)
        val ry = inputMapper.axisValue(event, MotionEvent.AXIS_RZ)
        val (_, sy) = inputMapper.applyDeadzoneAndSensitivity(rx, ry, profile.deadzone, 1f)
        if (sy != 0f) maybeDispatchStickScroll(sy)

        // Triggers → PTT edges (VOICE_TRIGGER) or held scroll (SCROLL), depending on
        // mapping. Different pads report these on different axes.
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

    /** Analog triggers have no discrete KeyEvent up/down — this derives rising/falling
     * edges from the continuous axis value so VOICE_TRIGGER (PttController) and
     * SCROLL (repeat job) both get proper press/release semantics. */
    private fun handleTrigger(input: ControllerInput, value: Float) {
        val active = inputMapper.isTriggerActive(value)
        val wasActive = triggerHeldState[input] ?: false
        if (active == wasActive) return

        val action = inputMapper.resolveAction(profile, input)
        triggerHeldState[input] = active

        if (active) {
            when (action.type) {
                ActionType.VOICE_TRIGGER -> pttController.onButtonDown()
                ActionType.SCROLL -> startTriggerRepeat(input, action)
                else -> Unit
            }
        } else {
            when (action.type) {
                ActionType.VOICE_TRIGGER -> pttController.onButtonUp()
                ActionType.SCROLL -> stopTriggerRepeat(input)
                else -> Unit
            }
        }
    }

    private fun startTriggerRepeat(input: ControllerInput, action: ButtonAction) {
        if (activeTriggerJobs.containsKey(input)) return
        // Child of serviceScope, not a Handler.postDelayed chain — cancelled
        // uniformly by serviceScope.cancel() in teardown() (A5).
        val job = serviceScope.launch {
            while (isActive) {
                action.swipeDirection?.let { dispatchScroll(cursorOverlay.getPosition(), it) }
                delay(TRIGGER_REPEAT_MS)
            }
        }
        activeTriggerJobs[input] = job
    }

    private fun stopTriggerRepeat(input: ControllerInput) {
        activeTriggerJobs.remove(input)?.cancel()
    }

    private fun cancelAllTriggerJobs() {
        activeTriggerJobs.values.forEach { it.cancel() }
        activeTriggerJobs.clear()
        triggerHeldState.clear()
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
        context: Context,
        private val onMotion: (MotionEvent) -> Boolean
    ) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            return if (onMotion(event)) true else super.onGenericMotionEvent(event)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (!hasWindowFocus) {
                // Immediate reclaim attempt; startFocusWatchdog() above is the
                // periodic backstop in case this callback itself is missed.
                post { requestFocus() }
            }
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
        private const val FOCUS_WATCHDOG_INTERVAL_MS = 2000L
        private const val LEGEND_TICK_INTERVAL_MS = 100L
        private const val MAX_RECORDING_MS = 30_000L

        /** Set while the service is bound; lets the UI reflect live enabled state. */
        var instance: ControllerAccessibilityService? = null
            private set
    }
}
