package com.ai.controller

import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.fail
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

/**
 * Shared helpers for ControllerAccessibilityService tests.
 */

/** Builds the service and drives it through onServiceConnected via reflection
 * (the method is protected on AccessibilityService, and Robolectric's
 * ServiceController does not invoke it). */
internal fun connectTestService(): ControllerAccessibilityService {
    val service = Robolectric.buildService(ControllerAccessibilityService::class.java).create().get()
    service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    return service
}

/** Spins the main looper until [condition] holds or [timeoutMs] of WALL-CLOCK
 * time elapses. Must use System.currentTimeMillis (real time): Robolectric's
 * shadow SystemClock is frozen and only advances with the looper, so a
 * SystemClock-based timeout would never fire. No Thread.sleep: this runs on
 * Robolectric's main thread, and sleeping there deadlocks the test runner. */
internal fun waitForMain(timeoutMs: Long = 8000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    val looper = shadowOf(Looper.getMainLooper())
    while (!condition()) {
        looper.idle()
        if (System.currentTimeMillis() > deadline) {
            fail("timed out waiting for condition after ${timeoutMs}ms")
        }
    }
    looper.idle()
}

/** PttController debounces edges within 150ms of the previous one, and its
 * lastEdgeMs starts at 0 — under Robolectric's frozen shadow clock
 * (uptimeMillis/elapsedRealtime start at 100ms) the first edge of every test
 * would always be debounced. Backdate it so test edges always pass. */
internal fun ControllerAccessibilityService.primePttDebounce() {
    val field = pttController.javaClass.getDeclaredField("lastEdgeMs").apply {
        isAccessible = true
    }
    field.setLong(pttController, -1_000_000L)
}

/** Makes ShadowAudioRecord.read() return 0 (silence) instead of full buffers.
 * The service's recording loop has no pacing -- with the default source it
 * spins writing 32KB per iteration into an unbounded buffer and OOMs the
 * 512MB test worker within milliseconds, killing the recording coroutine
 * nondeterministically. Silence keeps the loop cheap and deterministic. */
internal fun silenceMicrophone() {
    org.robolectric.shadows.ShadowAudioRecord.setSource(
        object : org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(
                audioData: ByteArray,
                offsetInBytes: Int,
                sizeInBytes: Int,
                isBlocking: Boolean
            ): Int = 0
        }
    )
}

/** Advances the main looper, letting the service's Handler/coroutine work run
 * (cursor render loop, watchdog/legend ticks, Main-dispatcher coroutines). */
internal fun idleMain(ms: Long = 500) {
    shadowOf(Looper.getMainLooper()).idleFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
}

/** Calls the protected AccessibilityService.onKeyEvent via reflection
 * (Kotlin does not allow calling a protected member from a test, even in
 * the same package). */
internal fun ControllerAccessibilityService.callOnKeyEvent(event: KeyEvent): Boolean {
    val m = javaClass.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply {
        isAccessible = true
    }
    return m.invoke(this, event) as Boolean
}

/** A fake editable text field backed by a shadowed AccessibilityNodeInfo:
 * ACTION_SET_TEXT updates [text], ACTION_SET_SELECTION moves the caret. */
internal class FakeField(initial: String = "") {
    val node: AccessibilityNodeInfo = AccessibilityNodeInfo.obtain()
    val setTextCalls = mutableListOf<String>()
    var text: String = initial
        private set

    init {
        val shadow = shadowOf(node)
        shadow.setOnPerformActionListener(
            ShadowAccessibilityNodeInfo.OnPerformActionListener { action, args ->
                when (action) {
                    AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                        val t = args?.getCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
                        )?.toString().orEmpty()
                        text = t
                        setTextCalls.add(t)
                        node.text = t
                        true
                    }
                    AccessibilityNodeInfo.ACTION_SET_SELECTION -> {
                        val s = args?.getInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, -1
                        ) ?: -1
                        val e = args?.getInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, -1
                        ) ?: -1
                        if (s >= 0 && e >= 0) node.setTextSelection(s, e)
                        true
                    }
                    else -> false
                }
            }
        )
        node.text = initial
        if (initial.isNotEmpty()) node.setTextSelection(initial.length, initial.length)
    }

    fun caret(): Pair<Int, Int> = node.textSelectionStart to node.textSelectionEnd
}
