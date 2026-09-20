package com.ai.controller

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
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
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
import com.ai.controller.models.SwipeDirection
import com.ai.controller.models.TextEditOp
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
    private lateinit var debugOverlay: DebugOverlay
    private lateinit var keyboardOverlay: FloatingKeyboardOverlay
    private var keyboardTypingTarget: AccessibilityNodeInfo? = null
    private lateinit var voiceManager: VoiceManager
    private lateinit var windowManager: WindowManager
    private var profile: ControllerProfile = ControllerProfile.default()
    private val driftCalibrator = DriftCalibrator()

    private var motionCaptureView: MotionCaptureView? = null
    private val activeTriggerJobs = mutableMapOf<ControllerInput, Job>()
    private val triggerHeldState = mutableMapOf<ControllerInput, Boolean>()
    // Owner-based edge state for D-pad left/right caret movement — used by BOTH the
    // KeyEvent path (KEYCODE_DPAD_LEFT/RIGHT in handleKeyEventAction/onKeyEvent)
    // AND the HAT-axis path (AXIS_HAT_X in handleGenericMotion). Each mechanism
    // (KEY_EVENT or HAT) only ever sets/clears its own claim, eliminating the race
    // where KeyEvent UP could clear a flag that HAT still actively holds (or vice versa).
    private enum class DpadCaretOwner { NONE, KEY_EVENT, HAT }
    private var dpadCaretLeftOwner = DpadCaretOwner.NONE
    private var dpadCaretRightOwner = DpadCaretOwner.NONE
    // Last known HAT-axis values from handleGenericMotion — retained for edge
    // detection. Initialized to NaN so the first motion event after service start
    // is never treated as a rising edge (comparisons with NaN are always false),
    // preventing a spurious moveCaret() if the D-pad is already held at startup.
    private var lastHatX = Float.NaN
    private var lastHatY = Float.NaN
    private var lastStickScrollTimeMs = 0L

    // Lifecycle-scoped: every coroutine this service launches (trigger repeats,
    // legend tick, voice recording, the focus watchdog) is a child of this scope
    // and dies with it in onDestroy — fixes A5 (trigger runnables leaking across
    // service destroy).
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var voiceBridgeServer: VoiceBridgeServer? = null
    private var legendTickJob: Job? = null
    private var focusWatchdogJob: Job? = null
    private var backspaceHoldJob: Job? = null

    // Idempotent guard: tracks the last KeyEvent (downTime, keyCode, action) handled
    // by either onKeyEvent (a11y filter) or handleCapturedKeyEvent (MotionCaptureView)
    // to prevent double-firing on devices where both paths deliver the same event.
    private var lastHandledKeyEvent: Triple<Long, Int, Int>? = null
    private val KEY_EVENT_RECENCY_MS = 50L

    // Push-to-talk state — A2: coroutine-driven recording with a sized AudioRecord
    // buffer and real cancellation, instead of a raw busy-read Thread.
    private lateinit var pttController: PttController
    private var audioRecord: AudioRecord? = null
    private var voiceRecordJob: Job? = null
    @Volatile private var isRecording = false
    @Volatile private var voiceRecordCancelled = false
    private var recordStartElapsedMs = 0L

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_DEBUG_ENABLED) {
            applyDebugPref()
        } else {
            reloadActiveProfile()
        }
    }

    private fun debugEnabled(): Boolean =
        getSharedPreferences(PROFILE_PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_DEBUG_ENABLED, false)

    private fun applyDebugPref() {
        if (!::debugOverlay.isInitialized) return
        if (debugEnabled()) debugOverlay.show() else debugOverlay.hide()
    }

    /** Updates the floating debug readout when it's enabled; always logs regardless
     * (see individual Log.d calls) so toast suppression never hides input activity. */
    private fun showDebug(text: String) {
        if (::debugOverlay.isInitialized && debugOverlay.isShowing()) debugOverlay.update(text)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // res/xml/accessibility_service_config.xml already declares these, but that
        // config is only guaranteed to be applied by the framework on some OEM
        // skins — setting it on the live serviceInfo here is the belt-and-suspenders
        // fix for "controller buttons don't reach the service at all" reports,
        // since a missing FLAG_REQUEST_FILTER_KEY_EVENTS silently drops onKeyEvent.
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        // NOTE: FLAG_INPUT_METHOD_EDITOR removed deliberately. The a11y service is
        // NOT the IME; the real keyboard is com.ai.controller.keyboard.AIInputMethodService.
        // Having this flag caused the framework to treat the a11y service as an internal
        // IME, which blocked the separate IME from showing its input view.
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 0
        serviceInfo = info

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
        debugOverlay = DebugOverlay(this)
        keyboardOverlay = FloatingKeyboardOverlay(this)
        if (profile.cursorEnabled) {
            cursorOverlay.show()
            legendOverlay.show()
        }
        if (debugEnabled()) debugOverlay.show()

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
        backspaceHoldJob?.cancel()
        // Cancels every remaining child coroutine (trigger jobs, legend tick,
        // focus watchdog, any in-flight voice job) in one place — the actual
        // fix for A5, everything above is defense in depth for early bail-outs.
        serviceScope.cancel()
        if (::cursorOverlay.isInitialized) cursorOverlay.hide()
        if (::legendOverlay.isInitialized) legendOverlay.hide()
        if (::debugOverlay.isInitialized) debugOverlay.hide()
        if (::keyboardOverlay.isInitialized) keyboardOverlay.hide()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window-state changes (new foreground app, IME opening, dialog appearing)
        // are exactly the moments that can silently steal focus from the 1x1
        // joystick-capture overlay — reclaim it immediately here instead of
        // waiting for the periodic watchdog (startFocusWatchdog) to notice.
        //
        // hasWindowFocus(), not isFocused: isFocused reports VIEW focus within
        // this overlay's own window, which Android leaves true even after the
        // WINDOW itself loses input focus to the newly-foregrounded app — so this
        // check was a no-op at exactly the moment it needed to fire. That's why
        // A worked, B worked "sometimes," and X/Y/L1/R1 mostly didn't (live
        // 2026-09-19): whichever button got pressed during a real-but-undetected
        // focus loss was silently dropped, and there was no signal telling us it
        // had happened.
        motionCaptureView?.let { view ->
            if (!view.hasWindowFocus()) {
                try {
                    view.requestFocus()
                } catch (e: Exception) {
                    Log.w(TAG, "onAccessibilityEvent focus reclaim failed", e)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    // ---------------------------------------------------------------------
    // Digital buttons
    // ---------------------------------------------------------------------

    /**
     * Key handler shared by the a11y onKeyEvent filter AND the focused capture view's
     * onKeyDown/onKeyUp. Both paths funnel into identical handling; whichever receives
     * the event first wins, and the double-delivery risk is handled by the same
     * idempotent edges (PTT down/up, press-fired actions).
     */
    private fun handleCapturedKeyEvent(event: KeyEvent): Boolean {
        val input = inputMapper.keyCodeToInput(event.keyCode) ?: return false
        if (event.repeatCount > 0) return true

        // Idempotent guard: skip if this exact KeyEvent was already handled
        // via the other path (onKeyEvent) within the recency window.
        val key = Triple(event.downTime, event.keyCode, event.action)
        val last = lastHandledKeyEvent
        if (last != null) {
            val (lastDownTime, _, _) = last
            if (key == last && (SystemClock.uptimeMillis() - lastDownTime) < KEY_EVENT_RECENCY_MS) {
                return true
            }
        }
        lastHandledKeyEvent = key

        val action = inputMapper.resolveAction(profile, input)
        Log.d(TAG, "captured input=$input action=${action.type} profile=${profile.name}")
        showDebug("Last: $input\nAction: ${action.type}\nPTT: ${if (pttController.isHeld) "HELD" else "idle"}")
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (action.type == ActionType.VOICE_TRIGGER) {
                    Log.d(TAG, "PTT down received (captured view, input=$input)")
                    pttController.onButtonDown()
                } else {
                    handleButtonDown(input, action)
                    armBackspaceHold(action)
                }
            }
            KeyEvent.ACTION_UP -> {
                if (action.type == ActionType.VOICE_TRIGGER) {
                    Log.d(TAG, "PTT up received (captured view, input=$input)")
                    pttController.onButtonUp()
                } else {
                    disarmBackspaceHold()
                    // KeyEvent path only clears its own claim; never touches HAT's claim.
                    if (action.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && dpadCaretLeftOwner == DpadCaretOwner.KEY_EVENT) dpadCaretLeftOwner = DpadCaretOwner.NONE
                    if (action.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && dpadCaretRightOwner == DpadCaretOwner.KEY_EVENT) dpadCaretRightOwner = DpadCaretOwner.NONE
                }
            }
        }
        return true
    }

    /**
     * a11y key filter path (FLAG_REQUEST_FILTER_KEY_EVENTS). Returns true for any
     * mapped input so Android stops delivering it to the foreground app — the
     * whole point of a controller-as-input-method service — and false (via
     * super, which is itself false) only for keys we don't recognize at all.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val input = inputMapper.keyCodeToInput(event.keyCode) ?: return super.onKeyEvent(event)
        if (event.repeatCount > 0) return true // swallow OS auto-repeat; we drive our own timing

        // Idempotent guard: skip if this exact KeyEvent was already handled
        // via the other path (handleCapturedKeyEvent) within the recency window.
        val key = Triple(event.downTime, event.keyCode, event.action)
        val last = lastHandledKeyEvent
        if (last != null) {
            val (lastDownTime, _, _) = last
            if (key == last && (SystemClock.uptimeMillis() - lastDownTime) < KEY_EVENT_RECENCY_MS) {
                return true
            }
        }
        lastHandledKeyEvent = key

        val action = inputMapper.resolveAction(profile, input)
        Log.d(TAG, "onKeyEvent input=$input action=${action.type} profile=${profile.name}")
        showDebug("Last: $input\nAction: ${action.type}\nPTT: ${if (pttController.isHeld) "HELD" else "idle"}")
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (action.type == ActionType.VOICE_TRIGGER) {
                    Log.d(TAG, "PTT down received (a11y filter, input=$input)")
                    pttController.onButtonDown()
                } else {
                    handleButtonDown(input, action)
                    armBackspaceHold(action)
                }
            }
            KeyEvent.ACTION_UP -> {
                // Voice-trigger and backspace-hold care about release; every other action
                // already fired on ACTION_DOWN above, matching the original tap-on-press model.
                if (action.type == ActionType.VOICE_TRIGGER) {
                    Log.d(TAG, "PTT up received (a11y filter, input=$input)")
                    pttController.onButtonUp()
                } else {
                    disarmBackspaceHold()
                    // KeyEvent path only clears its own claim; never touches HAT's claim.
                    if (action.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && dpadCaretLeftOwner == DpadCaretOwner.KEY_EVENT) dpadCaretLeftOwner = DpadCaretOwner.NONE
                    if (action.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && dpadCaretRightOwner == DpadCaretOwner.KEY_EVENT) dpadCaretRightOwner = DpadCaretOwner.NONE
                }
            }
        }
        return true
    }

    private fun handleButtonDown(input: ControllerInput, action: ButtonAction = inputMapper.resolveAction(profile, input)) {
        val cursor = cursorOverlay.getPosition()

        when (action.type) {
            // Live-tested 2026-09-18: dispatchGesture()'s synthetic tap does NOT register
            // as a click on this service's own FloatingKeyboardOverlay window — confirmed
            // with the cursor centered exactly on a key, action.type correctly resolved,
            // and still nothing typed. Real touches (a finger, or adb's `input tap`) work
            // fine on that same window; only accessibility-dispatched gestures don't land
            // on a window owned by the dispatching service itself. Rather than fight that
            // platform quirk, hit-test the keyboard's own view tree directly and click
            // whatever's under the cursor in code — no OS gesture pipeline involved.
            ActionType.TAP -> if (keyboardOverlay.isShowing() && keyboardOverlay.handleTapAt(cursor.x, cursor.y)) {
                Unit
            } else {
                dispatchTap(cursor)
            }
            ActionType.LONG_PRESS -> dispatchLongPress(cursor, action.durationMs)
            ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ActionType.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            ActionType.SCROLL -> action.swipeDirection?.let { dispatchScroll(cursor, it) }
            ActionType.SWIPE -> action.swipeDirection?.let { dispatchSwipe(cursor, it, action.durationMs) }
            ActionType.KEY_EVENT -> handleKeyEventAction(action)
            ActionType.TEXT_EDIT -> handleTextEditAction(action)
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
        //
        // While the keyboard's drag handle is armed (FloatingKeyboardOverlay.dragging),
        // the same D-pad input moves the keyboard panel itself instead of the cursor —
        // this is the "drag and pull it" the desktop's mouse-drag handle does with a
        // real pointer, translated to the one input this app already has for movement.
        if (::keyboardOverlay.isInitialized && keyboardOverlay.isShowing() && keyboardOverlay.isDragging()) {
            val step = CURSOR_STEP_PX.toInt()
            when (action.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> keyboardOverlay.nudgePosition(0, -step)
                KeyEvent.KEYCODE_DPAD_DOWN -> keyboardOverlay.nudgePosition(0, step)
                KeyEvent.KEYCODE_DPAD_LEFT -> keyboardOverlay.nudgePosition(-step, 0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> keyboardOverlay.nudgePosition(step, 0)
                else -> Log.w(TAG, "keyCode=${action.keyCode} has no public injection path; ignoring")
            }
            return
        }
        // While the keyboard is open (but not being dragged), left/right moves the TEXT
        // CARET inside the focused field instead of the on-screen pointer — up/down and
        // the analog stick still aim the pointer, so there's still a way to tap something
        // outside the keyboard (e.g. Send) without closing it. Reported live 2026-09-19:
        // "left and right moves the mouse cursor and not the caret for typing dictation" —
        // until now there was no way to move the caret at all.
        if (::keyboardOverlay.isInitialized && keyboardOverlay.isShowing() &&
            (action.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || action.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            // Edge detection using owner markers: each mechanism (KEY_EVENT or HAT)
            // only sets/clears its own claim, so there's no race window.
            val leftDown = action.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            val rightDown = action.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (leftDown && dpadCaretLeftOwner == DpadCaretOwner.NONE) moveCaret(-1)
            if (rightDown && dpadCaretRightOwner == DpadCaretOwner.NONE) moveCaret(1)
            if (leftDown) dpadCaretLeftOwner = DpadCaretOwner.KEY_EVENT
            if (rightDown) dpadCaretRightOwner = DpadCaretOwner.KEY_EVENT
            return
        }
        when (action.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> cursorOverlay.applyDelta(0f, -CURSOR_STEP_PX)
            KeyEvent.KEYCODE_DPAD_DOWN -> cursorOverlay.applyDelta(0f, CURSOR_STEP_PX)
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorOverlay.applyDelta(-CURSOR_STEP_PX, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorOverlay.applyDelta(CURSOR_STEP_PX, 0f)
            else -> Log.w(TAG, "keyCode=${action.keyCode} has no public injection path; ignoring")
        }
    }

    /**
     * ⧉ (Select/View) — toggles [FloatingKeyboardOverlay], AI Controller's own
     * emoji-skin-tone/text-style/pin keyboard, drawn directly by this service via
     * WindowManager (same mechanism as [CursorOverlay]/[LegendOverlay]).
     *
     * This used to route through [com.ai.controller.keyboard.AIInputMethodService]
     * (a real system IME) via `switchToInputMethod()` + `setShowMode()`. Live-tested
     * 2026-09-18: both calls reported success, but `dumpsys input_method`'s
     * `mInputShown` stayed false except for one fleeting instance — the system
     * never reliably surfaced that IME's input view even when fully bound. The
     * overlay approach sidesteps that arbitration entirely: no OS "is a keyboard
     * currently showing" state to fight, just a window this service adds and
     * removes itself, on demand, every time.
     */
    private fun startCustomKeyboard() {
        if (!keyboardOverlay.isShowing()) {
            keyboardTypingTarget = findEditableTarget()
            selectAllOnFreshFocus(keyboardTypingTarget)
        }
        keyboardOverlay.toggle()
        if (!keyboardOverlay.isShowing()) {
            keyboardTypingTarget = null
        } else {
            // The keyboard's own window was just added, which stacks it above the
            // cursor (same overlay type, later add wins) — bring the cursor back on
            // top so it's still visible/aimable over the keyboard, not buried under it.
            cursorOverlay.raise()
        }
        showDebug(if (keyboardOverlay.isShowing()) "Kbd: shown" else "Kbd: hidden")
    }

    /** Selects a freshly-focused field's entire current text, so the first keystroke
     * replaces it instead of landing after it. Reported live 2026-09-20: "the harness...
     * doesn't put a text cursor up there... instead of deleting [the placeholder] to have
     * a clean text space, it just adds to the end" — a search/URL bar's "type or enter
     * url" hint text (or any pre-existing content) was never selected, and every
     * text-mutation function falls back to "insert at the end" when a field has no
     * selection at all, so nothing ever cleared it. A real tap into a field like this
     * normally leaves it fully selected via the app's own focus handling; this app
     * captures focus through the accessibility API instead of a real touch, so that
     * never happened on its own — this establishes the same selection explicitly. Only
     * runs once per fresh keyboard-open (not on every keystroke), so resuming a
     * partially-typed field across keyboard toggles doesn't keep wiping it. */
    private fun selectAllOnFreshFocus(node: AccessibilityNodeInfo?) {
        val target = node ?: return
        try {
            target.refresh()
            val length = target.text?.length ?: 0
            if (length <= 0) return
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (e: Exception) {
            Log.w(TAG, "selectAllOnFreshFocus failed", e)
        }
    }

    /**
     * The window everything below should look for a target field in, instead of
     * [rootInActiveWindow] (which "active" really just means "most recently touched," not
     * "the real app"). Root cause of "keyboard doesn't emit text," found live 2026-09-18:
     * this service permanently keeps a 1x1 [MotionCaptureView] focused system-wide (see
     * [attachMotionCapture]/[startFocusWatchdog]) so controller button presses always
     * reach it — but that same permanent focus means `rootInActiveWindow` almost always
     * resolves to OUR OWN window, not whatever app the user is actually looking at.
     * `windows` (available via `flagRetrieveInteractiveWindows`, already set) lists every
     * currently visible window regardless of which one holds focus, so picking the first
     * non-overlay window that isn't our own package reliably finds the real target.
     */
    private fun externalAppRoot(): AccessibilityNodeInfo? = externalAppRoots().firstOrNull()

    /**
     * Every window that could hold the field being typed into, best candidate first.
     * Reported live 2026-09-18 as "it doesn't print on every text box": the previous
     * version looked only at TYPE_APPLICATION and took the *first* non-ours window the
     * system happened to list, which misses dialogs/popups entirely and can land on a
     * background app's window instead of the one on screen. Ordering here prefers the
     * window the user is actually interacting with (active, then focused), and the
     * callers fall through the list until a field is actually found.
     */
    private fun externalAppRoots(): List<AccessibilityNodeInfo> {
        val candidates = windows?.filter { it.root != null && it.root?.packageName != packageName }
            ?: return emptyList()
        return candidates
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isActive }
                    .thenByDescending { it.isFocused }
                    .thenByDescending { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            )
            .mapNotNull { it.root }
    }

    /** First focused editable field across the candidate windows, falling back to the
     * first editable field found at all — some apps expose an editable node without ever
     * marking it input-focused, which is the other half of "doesn't print on every box." */
    private fun findEditableTarget(): AccessibilityNodeInfo? {
        val roots = externalAppRoots()
        roots.forEach { root ->
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        }
        roots.forEach { root ->
            firstEditableNode(root)?.let { return it }
        }
        return null
    }

    private fun firstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            firstEditableNode(child)?.let { return it }
        }
        return null
    }

    /** The field text/backspace/delete/commit actions should target. While the floating
     * keyboard is open, this is the node captured in [startCustomKeyboard] rather than a
     * live lookup, since the overlay's own buttons keep re-touching our own window while
     * typing — see that function and [externalAppRoot] for why a live lookup can't be
     * trusted moment-to-moment. Everywhere else (voice dictation, controller TEXT_EDIT
     * presses with no keyboard open) a fresh [externalAppRoot] lookup is correct and
     * simpler, since nothing has changed since the last touch. */
    /** Logs and returns null when there's no focused field, so a text action that does
     * nothing says why instead of returning silently — the failure mode that hid the
     * backspace/delete/enter breakage reported 2026-09-18. */
    private fun typingTargetOrWarn(op: String): AccessibilityNodeInfo? {
        val t = typingTarget()
        if (t == null) Log.w(TAG, "$op: no focused text field (nothing to act on)")
        return t
    }

    private fun typingTarget(): AccessibilityNodeInfo? =
        if (::keyboardOverlay.isInitialized && keyboardOverlay.isShowing()) {
            // Root cause of "only the last letter of a sentence survives," found live
            // 2026-09-18: this node is captured once when the keyboard opens and reused
            // for every keystroke in the session. AccessibilityNodeInfo.text is a
            // snapshot, not a live view — without refresh(), every call here kept
            // reading the ORIGINAL (often empty) text, so `current + ch` was really
            // `"" + ch` every single time: each new letter silently overwrote the field
            // instead of appending to it, not failing to type at all.
            keyboardTypingTarget?.also { it.refresh() }
        } else {
            findEditableTarget()
        }

    private fun moveAccessibilityFocus(forward: Boolean) {
        typingTarget()?.let { focused ->
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
        cancelAllTriggerJobs()
        backspaceHoldJob?.cancel()
        backspaceHoldJob = null
        // If a voice trigger was held, cancel the recording instead of just resetting
        // the PTT state machine — a bare reset() only clears isHeld and leaves the
        // AudioRecord/coroutine running until MAX_RECORDING_MS (30s).
        if (pttController.isHeld) {
            stopVoiceRecording(cancel = true)
        } else {
            pttController.reset()
        }
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
        showDebug("Voice: ${if (recording) "recording" else "processing"}\nPTT: ${if (pttController.isHeld) "HELD" else "idle"}")
    }

    /** Voice/mic key on [com.ai.controller.keyboard.AIInputMethodService]'s overlay
     * keyboard — routes through the exact same PttController edges as a controller
     * trigger, so recording/transcription/consent/debug behavior stays identical
     * regardless of which surface (controller or on-screen IME) started it. */
    fun pttDownFromKeyboard() {
        Log.d(TAG, "PTT down received (IME voice key)")
        pttController.onButtonDown()
    }

    fun pttUpFromKeyboard() {
        Log.d(TAG, "PTT up received (IME voice key)")
        pttController.onButtonUp()
    }

    private fun writeWavToCache(pcmBytes: ByteArray, sampleRate: Int): File {
        // Unique per recording: two rapid PTT takes ran processRecording concurrently
        // and both wrote/read/deleted the SHARED voice_prompt.wav — the loser hit
        // ENOENT (the exact "Voice transcription failed" stack). Unique names kill the race.
        val wav = File(cacheDir, "voice_prompt_${System.currentTimeMillis()}_${(0..999).random()}.wav")
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

    /** Replaces the focused field's text — used for whole-utterance dictation drops.
     * Same [typingTarget] fix as typeCharacter/typeText below applies here: a raw
     * `rootInActiveWindow` lookup almost always resolves to this service's own
     * permanently-focused MotionCaptureView, not whatever app the user is dictating
     * into — this was silently swallowing every voice transcript before the fix. */
    /** Where in [current] a typed/deleted/inserted char belongs: the field's own reported
     * caret when it's in range, else the end. Every text-mutation call below shares this
     * so "where does this land" is answered the same way everywhere — before this, only
     * deleteNextOnce/commitTextOnce respected a real caret position; typeCharacter,
     * typeText, injectText, and backspaceOnce always assumed "the end," so once
     * [moveCaret] gave the user a way to park the caret mid-field, those calls kept
     * acting on the end anyway instead of where the user was actually pointed. Reported
     * live 2026-09-19: "it's still printing what it wants to print... I can't [move]...
     * the caret for typing dictation." */
    private fun caretIndex(node: AccessibilityNodeInfo, current: String): Int {
        val sel = node.textSelectionStart
        return if (sel in 0..current.length) sel else current.length
    }

    /** Where in [current] a mutation should apply, as a (start, end) range — the field's
     * own reported selection when both ends are in range, else a collapsed point at the
     * end. Typing/deleting with a real selection active (start != end) replaces/removes
     * the whole selection, matching every normal text editor's behavior, instead of only
     * ever acting relative to a single insertion point. Without this, a field whose
     * selection was just set to "everything" (see startCustomKeyboard) would still only
     * insert at the selection's start, leaving the "selected" text sitting untouched
     * right after it instead of being replaced. */
    private fun caretRange(node: AccessibilityNodeInfo, current: String): Pair<Int, Int> {
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        return if (start in 0..current.length && end in 0..current.length) {
            minOf(start, end) to maxOf(start, end)
        } else {
            current.length to current.length
        }
    }

    private fun injectText(text: String) {
        try {
            val focused = typingTarget()
                ?: externalAppRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null) {
                // Reported live 2026-09-19: "the STT prints whatever it wants — sometimes
                // it reads it, sometimes it doesn't." This SET_TEXT call was replacing the
                // field's entire content with only the new transcript — every other
                // injection path here (typeCharacter/typeText/commitTextOnce) appends
                // current text first; this one alone silently didn't, so a second dictation
                // wiped out whatever the first one wrote instead of adding to it.
                focused.refresh()
                val current = focused.text?.toString().orEmpty()
                val (start, end) = caretRange(focused, current)
                injectFocusedText(focused, current.substring(0, start) + text + current.substring(end))
            } else {
                Log.w(TAG, "No focused node for text injection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Text injection failed", e)
        }
    }

    /** Inserts one key's worth of text, replacing any active selection — used by
     * KeyboardActivity, which types incrementally rather than dropping a whole utterance
     * at once. */
    fun typeCharacter(ch: String) {
        try {
            val focused = typingTarget() ?: return
            val current = focused.text?.toString().orEmpty()
            val (start, end) = caretRange(focused, current)
            injectFocusedText(focused, current.substring(0, start) + ch + current.substring(end))
        } catch (e: Exception) {
            Log.e(TAG, "typeCharacter failed", e)
        }
    }

    /** Inserts a whole string in one accessibility call, replacing any active selection —
     * used by KeyboardActivity's pinned-snippet buttons, so a multi-word pin doesn't cost
     * one call per character. */
    fun typeText(text: String) {
        try {
            val focused = typingTarget() ?: return
            val current = focused.text?.toString().orEmpty()
            val (start, end) = caretRange(focused, current)
            injectFocusedText(focused, current.substring(0, start) + text + current.substring(end))
        } catch (e: Exception) {
            Log.e(TAG, "typeText failed", e)
        }
    }

    /** Removes the active selection if there is one, else the character before the caret —
     * KeyboardActivity's backspace, and the desktop profile's B/Bksp slot. */
    fun backspaceOnce() {
        try {
            val focused = typingTargetOrWarn("backspaceOnce") ?: return
            val current = focused.text?.toString().orEmpty()
            val (start, end) = caretRange(focused, current)
            if (start != end) {
                injectFocusedText(focused, current.substring(0, start) + current.substring(end))
                return
            }
            if (start <= 0) return
            injectFocusedText(focused, current.substring(0, start - 1) + current.substring(start))
        } catch (e: Exception) {
            Log.e(TAG, "backspaceOnce failed", e)
        }
    }

    /**
     * Cursor-relative text edits and literal text commits — the desktop profile's
     * B/Bksp, X/Del and RS/Enter slots (ActionType.TEXT_EDIT). Uses the same
     * findFocus + SET_TEXT path as KeyboardActivity so no extra permissions are needed.
     */
    private fun handleTextEditAction(action: ButtonAction) {
        when (action.textOp) {
            TextEditOp.BACKSPACE -> backspaceOnce()
            TextEditOp.DELETE_NEXT -> deleteNextOnce()
            // A "\n" payload is the RS/Enter slot, not literal text to insert — route it
            // through the real editor action (see pressEnter) so search/send/go actually fire.
            null -> action.textPayload?.let { payload ->
                if (payload == "\n") pressEnter() else commitTextOnce(payload)
            }
        }
    }

    /** Forward-delete: removes the active selection if there is one, else the character
     * after the caret. Uses the selection cursor when the field exposes one; falls back to
     * a cursor-position-aware edit on the field's text so a mid-field caret is honored
     * when available. */
    fun deleteNextOnce() {
        try {
            val focused = typingTargetOrWarn("deleteNextOnce") ?: return
            val current = focused.text?.toString().orEmpty()
            if (current.isEmpty()) return
            val (start, end) = caretRange(focused, current)
            if (start != end) {
                injectFocusedText(focused, current.substring(0, start) + current.substring(end))
                return
            }
            if (start >= current.length) return
            injectFocusedText(focused, current.substring(0, start) + current.substring(start + 1))
        } catch (e: Exception) {
            Log.e(TAG, "deleteNextOnce failed", e)
        }
    }

    /** Commits [text] verbatim to the focused field at the caret, replacing any active
     * selection — the RS "Enter" slot. */
    fun commitTextOnce(text: String) {
        try {
            val focused = typingTargetOrWarn("commitTextOnce") ?: return
            val current = focused.text?.toString().orEmpty()
            val (start, end) = caretRange(focused, current)
            injectFocusedText(focused, current.substring(0, start) + text + current.substring(end))
        } catch (e: Exception) {
            Log.e(TAG, "commitTextOnce failed", e)
        }
    }

    /** Moves the text caret in the focused field by [delta] characters (±1) — the D-pad
     * left/right slot while the keyboard is open (see handleKeyEventAction and the HAT-axis
     * branch in handleGenericMotion). Sets a zero-width selection via ACTION_SET_SELECTION
     * so every text-mutation call above, which reads textSelectionStart via [caretIndex],
     * sees the new position. Reported live 2026-09-19: "left and right moves the mouse
     * cursor and not the caret for typing dictation" — there was previously no way to move
     * the caret at all. */
    private fun moveCaret(delta: Int) {
        try {
            val focused = typingTargetOrWarn("moveCaret") ?: return
            val current = focused.text?.toString().orEmpty()
            val at = caretIndex(focused, current)
            val next = (at + delta).coerceIn(0, current.length)
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, next)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, next)
            }
            val success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            if (!success) {
                Log.w(TAG, "moveCaret: ACTION_SET_SELECTION not supported by focused view")
            }
        } catch (e: Exception) {
            Log.e(TAG, "moveCaret failed", e)
        }
    }

    /** Starts the hold-timer for B/Bksp only; every other action already fired in full on
     * ACTION_DOWN and has nothing to do on a hold. Reported live 2026-09-19: "I have to
     * press B, I can't hold it to delete a whole line." */
    private fun armBackspaceHold(action: ButtonAction) {
        if (action.type != ActionType.TEXT_EDIT || action.textOp != TextEditOp.BACKSPACE) return
        backspaceHoldJob?.cancel()
        backspaceHoldJob = serviceScope.launch {
            delay(BACKSPACE_HOLD_MS)
            deleteToLineStart()
        }
    }

    private fun disarmBackspaceHold() {
        backspaceHoldJob?.cancel()
        backspaceHoldJob = null
    }

    /** B held past [BACKSPACE_HOLD_MS]: clears from the caret back to the start of the
     * current line (the last '\n' at-or-before the caret, or the field start) in one shot.
     * For the common single-line field (no '\n' at all) this clears everything from the
     * start of the field up to the caret — with no prior caret movement, that's the whole
     * field, matching what was asked for. */
    private fun deleteToLineStart() {
        try {
            val focused = typingTargetOrWarn("deleteToLineStart") ?: return
            val current = focused.text?.toString().orEmpty()
            if (current.isEmpty()) return
            val at = caretIndex(focused, current)
            if (at <= 0) return
            val lineStart = current.lastIndexOf('\n', at - 1) + 1
            injectFocusedText(focused, current.substring(0, lineStart) + current.substring(at))
        } catch (e: Exception) {
            Log.e(TAG, "deleteToLineStart failed", e)
        }
    }

    private fun injectFocusedText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!success) {
            Log.w(TAG, "injectFocusedText: ACTION_SET_TEXT not supported by focused view")
        }
    }

    /**
     * The ⏎ key and the RS/Enter controller slot. Reported live 2026-09-18 as "enter
     * doesn't work": it used to inject a literal "\n" character via SET_TEXT, which
     * single-line fields (search boxes, chat composers, login forms — i.e. most of the
     * places this keyboard gets used) silently strip instead of acting on. ACTION_IME_ENTER
     * is the real "the user pressed the enter/search/send key" signal, so the field runs
     * its actual editor action. Falls back to a newline for genuinely multi-line fields,
     * which don't advertise IME_ENTER.
     */
    fun pressEnter() {
        try {
            val focused = typingTargetOrWarn("pressEnter") ?: return
            // ACTION_IME_ENTER (value 4096) was added in API 30; minSdk is 26.
            // Use the constant directly so this compiles on older targets; the
            // performAction call simply returns false on API < 30, which we
            // already handle by falling back to a newline.
            val ACTION_IME_ENTER_ID = 4096
            val supportsImeEnter = focused.actionList.any { it.id == ACTION_IME_ENTER_ID }
            if (supportsImeEnter) {
                val ok = focused.performAction(ACTION_IME_ENTER_ID)
                Log.d(TAG, "pressEnter: ACTION_IME_ENTER returned $ok")
                if (ok) return
            }
            Log.d(TAG, "pressEnter: falling back to newline (supportsImeEnter=$supportsImeEnter)")
            typeCharacter("\n")
        } catch (e: Exception) {
            Log.e(TAG, "pressEnter failed", e)
        }
    }

    // ---------------------------------------------------------------------
    // Sticks + triggers (joystick motion, captured via a focused overlay)
    // ---------------------------------------------------------------------

    private fun attachMotionCapture() {
        val view = MotionCaptureView(
            this,
            onMotion = { event -> handleGenericMotion(event) },
            onKey = { event -> handleCapturedKeyEvent(event) }
        )
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
                // hasWindowFocus(), not isFocused — see onAccessibilityEvent above for why.
                motionCaptureView?.let { view ->
                    if (!view.hasWindowFocus()) {
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
        Log.d(TAG, "onGenericMotionEvent source=${event.source} profile=${profile.name}")

        // Left stick → cursor movement, drift-corrected before deadzone/sensitivity.
        val rawLx = inputMapper.axisValue(event, MotionEvent.AXIS_X)
        val rawLy = inputMapper.axisValue(event, MotionEvent.AXIS_Y)
        val (lx, ly) = driftCalibrator.correct(rawLx, rawLy)
        val (dx, dy) = inputMapper.applyDeadzoneAndSensitivity(lx, ly, profile.deadzone, profile.sensitivity)
        if (dx != 0f || dy != 0f) {
            // Reported live 2026-09-19: "I still cannot move the slide keyboard — I can't
            // move it." The drag-mode redirect only existed on the D-pad path
            // (handleKeyEventAction); this stick path — the one actually used to move the
            // cursor, per every prior report — always drove the cursor regardless of
            // FloatingKeyboardOverlay.isDragging(), so arming "Move" did nothing reachable.
            if (::keyboardOverlay.isInitialized && keyboardOverlay.isShowing() && keyboardOverlay.isDragging()) {
                keyboardOverlay.nudgePosition((dx * CURSOR_SPEED_PX).toInt(), (dy * CURSOR_SPEED_PX).toInt())
            } else {
                cursorOverlay.applyDelta(dx * CURSOR_SPEED_PX, dy * CURSOR_SPEED_PX)
            }
        }

        // D-pad → cursor nudge (or panel nudge while dragging), same as the stick above.
        // Reported live 2026-09-19: "the directional buttons aren't working." This
        // controller (confirmed via `dumpsys input`: real HAT_X/HAT_Y motion ranges on its
        // device entry) reports the D-pad as hat-switch axis motion, not KEYCODE_DPAD_*
        // key events — the only path handleKeyEventAction's D-pad case ever listened on.
        // No KeyEvent means onKeyEvent/onKeyDown never fire for this control at all on this
        // hardware; reading it here, alongside the sticks it shares a motion event with, is
        // the actual signal this controller sends.
        val hatX = inputMapper.axisValue(event, MotionEvent.AXIS_HAT_X)
        val hatY = inputMapper.axisValue(event, MotionEvent.AXIS_HAT_Y)
        val keyboardOpenNotDragging = ::keyboardOverlay.isInitialized && keyboardOverlay.isShowing() &&
            !keyboardOverlay.isDragging()
        // Same left/right-moves-the-caret redirect as handleKeyEventAction's D-pad case,
        // but edge-detected via owner markers: this event fires continuously while the
        // D-pad is held (unlike a KeyEvent, which fires once per press), so acting on
        // every tick would blow through the whole field in a fraction of a second instead
        // of moving one character at a time. HAT path only sets/clears its own claim.
        // Rising edge: claim ownership and move caret. Falling edge: release ownership.
        if (keyboardOpenNotDragging) {
            val leftRising = hatX < -0.5f && lastHatX >= -0.5f
            val rightRising = hatX > 0.5f && lastHatX <= 0.5f
            val leftFalling = hatX >= -0.5f && lastHatX < -0.5f
            val rightFalling = hatX <= 0.5f && lastHatX > 0.5f
            if (leftRising && dpadCaretLeftOwner == DpadCaretOwner.NONE) moveCaret(-1)
            if (rightRising && dpadCaretRightOwner == DpadCaretOwner.NONE) moveCaret(1)
            if (leftRising) dpadCaretLeftOwner = DpadCaretOwner.HAT
            if (rightRising) dpadCaretRightOwner = DpadCaretOwner.HAT
            if (leftFalling && dpadCaretLeftOwner == DpadCaretOwner.HAT) dpadCaretLeftOwner = DpadCaretOwner.NONE
            if (rightFalling && dpadCaretRightOwner == DpadCaretOwner.HAT) dpadCaretRightOwner = DpadCaretOwner.NONE
        } else {
            // Keyboard closed or being dragged: clear HAT claims so they don't stick.
            if (dpadCaretLeftOwner == DpadCaretOwner.HAT) dpadCaretLeftOwner = DpadCaretOwner.NONE
            if (dpadCaretRightOwner == DpadCaretOwner.HAT) dpadCaretRightOwner = DpadCaretOwner.NONE
        }
        if (hatX != 0f || hatY != 0f) {
            if (::keyboardOverlay.isInitialized && keyboardOverlay.isShowing() && keyboardOverlay.isDragging()) {
                keyboardOverlay.nudgePosition((hatX * CURSOR_SPEED_PX).toInt(), (hatY * CURSOR_SPEED_PX).toInt())
            } else if (keyboardOpenNotDragging) {
                // Left/right already handled above (caret); up/down still nudges the pointer
                // so there's a way to aim at something outside the keyboard while it's open.
                if (hatY != 0f) cursorOverlay.applyDelta(0f, hatY * CURSOR_SPEED_PX)
            } else {
                cursorOverlay.applyDelta(hatX * CURSOR_SPEED_PX, hatY * CURSOR_SPEED_PX)
            }
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

        // Update last values AFTER all edge detection so next tick can compare against them.
        lastHatX = hatX
        lastHatY = hatY

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
        val wasActive = triggerHeldState[input] ?: false
        val active = inputMapper.isTriggerActive(value, wasActive)
        if (active == wasActive) return

        val action = inputMapper.resolveAction(profile, input)
        triggerHeldState[input] = active
        Log.d(TAG, "trigger input=$input action=${action.type} value=$value profile=${profile.name}")
        showDebug("Last: $input (trigger)\nAction: ${action.type}\nPTT: ${if (active && action.type == ActionType.VOICE_TRIGGER) "HELD" else if (pttController.isHeld) "HELD" else "idle"}")

        if (active) {
            when (action.type) {
                ActionType.VOICE_TRIGGER -> {
                    Log.d(TAG, "PTT down received (trigger, input=$input)")
                    pttController.onButtonDown()
                }
                ActionType.SCROLL -> startTriggerRepeat(input, action)
                else -> Unit
            }
        } else {
            when (action.type) {
                ActionType.VOICE_TRIGGER -> {
                    Log.d(TAG, "PTT up received (trigger, input=$input)")
                    pttController.onButtonUp()
                }
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

    /** Callback intentionally logs, never acts — this exists purely to answer "does the
     * framework actually deliver this gesture," reported live 2026-09-18 as "the A button
     * only works every once in a while." dispatchGesture() was called with a null callback
     * everywhere in this file, so there was no way to tell a dropped/cancelled gesture apart
     * from one that reached the target and simply didn't do anything there. */
    private val tapResultCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            Log.d(TAG, "dispatchTap: gesture completed")
        }
        override fun onCancelled(gestureDescription: GestureDescription?) {
            Log.w(TAG, "dispatchTap: gesture CANCELLED by framework")
        }
    }

    private fun dispatchTap(point: PointF) {
        val path = Path().apply { moveTo(point.x, point.y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val dispatched = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), tapResultCallback, null)
        Log.d(TAG, "dispatchTap at (${point.x}, ${point.y}): dispatchGesture() accepted=$dispatched")
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
        private val onMotion: (MotionEvent) -> Boolean,
        private val onKey: (KeyEvent) -> Boolean
    ) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            return if (onMotion(event)) true else super.onGenericMotionEvent(event)
        }

        /**
         * Digital gamepad buttons route through the FOCUSED view, not the a11y key
         * filter — the a11y onKeyEvent path loses the race whenever the focused app
         * window (Chrome, an IME…) consumes gamepad keycodes first. The stick/trigger
         * capture view holds focus (watchdog-reclaimed), so handling KEY events here
         * guarantees A/B/X/Y/⧉/☰ reach the mapper exactly like the motion path does.
         */
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
            if (onKey(event)) true else super.onKeyDown(keyCode, event)

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
            if (onKey(event)) true else super.onKeyUp(keyCode, event)

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
        const val KEY_DEBUG_ENABLED = "debug_overlay_enabled"

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
        // Was 2000ms: with the isFocused->hasWindowFocus() fix above making this
        // check finally see real focus loss, 2s between polls still meant a
        // dropped-focus window up to 2 full seconds long with nothing catching a
        // button press in it. Tight enough to feel instant, cheap enough (a single
        // hasWindowFocus() read most ticks) not to matter for battery.
        private const val FOCUS_WATCHDOG_INTERVAL_MS = 150L
        private const val LEGEND_TICK_INTERVAL_MS = 100L
        private const val MAX_RECORDING_MS = 30_000L
        /** How long B must be held before it clears the whole current line instead of
         * one character. Long enough that a normal tap-tap-tap backspace rhythm never
         * fires it by accident, short enough that a deliberate hold doesn't feel laggy. */
        private const val BACKSPACE_HOLD_MS = 500L

        /** Set while the service is bound; lets the UI reflect live enabled state. */
        var instance: ControllerAccessibilityService? = null
            private set
    }
}
