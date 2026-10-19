package com.ai.controller

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowAudioRecord

/**
 * Digital-button handling: onKeyEvent for every mapped key code (down/up),
 * the MotionCaptureView handleCapturedKeyEvent path, and the shared
 * alreadyHandled dedup guard across both paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyEventPathTest {

    private lateinit var service: ControllerAccessibilityService
    private var downTime = 0L

    private val shadow: ShadowAccessibilityService get() = shadowOf(service)

    private fun keyEvent(action: Int, keyCode: Int): KeyEvent {
        downTime += 100
        return KeyEvent(downTime, downTime, action, keyCode, 0)
    }

    @Before
    fun setUp() {
        downTime = SystemClock.uptimeMillis()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        silenceMicrophone()
        ConsentManager.setGranted(RuntimeEnvironment.getApplication(), true)
        service = connectTestService()
        service.primePttDebounce()
    }

    @After
    fun tearDown() {
        ConsentManager.setGranted(RuntimeEnvironment.getApplication(), false)
        ShadowAudioRecord.clearSource()
        service.onDestroy()
    }

    @Test
    fun `unmapped key falls through to super`() {
        assertFalse(service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
    }

    @Test
    fun `repeat events are swallowed without dispatching`() {
        val e = KeyEvent(downTime + 7, downTime + 7, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 1)
        assertTrue(service.callOnKeyEvent(e))
        assertEquals(0, shadow.gesturesDispatched.size)
    }

    @Test
    fun `BUTTON_A tap dispatches a gesture`() {
        assertTrue(service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
        assertTrue(service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)))
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `BUTTON_B backspace with no target warns and returns true`() {
        assertTrue(service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)))
        assertTrue(service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)))
    }

    @Test
    fun `BUTTON_X forward-delete with no target warns and returns true`() {
        assertTrue(service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_X)))
    }

    @Test
    fun `BUTTON_Y recents performs the global action`() {
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y))
        assertTrue(shadow.globalActionsPerformed.contains(AccessibilityService.GLOBAL_ACTION_RECENTS))
    }

    @Test
    fun `BUTTON_THUMBL performs back`() {
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertTrue(shadow.globalActionsPerformed.contains(AccessibilityService.GLOBAL_ACTION_BACK))
    }

    @Test
    fun `BUTTON_L1 tap dispatches a gesture`() {
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `BUTTON_R1 long-press dispatches a gesture`() {
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `BUTTON_START moves accessibility focus`() {
        assertTrue(service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START)))
    }

    @Test
    fun `BUTTON_SELECT toggles the keyboard overlay`() {
        assertFalse(service.keyboardOverlay.isShowing())
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT))
        assertTrue(service.keyboardOverlay.isShowing())
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT))
        assertFalse(service.keyboardOverlay.isShowing())
    }

    @Test
    fun `BUTTON_THUMBR enter slot presses enter with no target`() {
        assertTrue(service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBR)))
    }

    @Test
    fun `BUTTON_THUMBR custom text payload commits text`() {
        service.profile.mappings[ControllerInput.BUTTON_THUMBR] =
            ButtonAction.commitTextTo("hello", "Hi")
        assertTrue(service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBR)))
    }

    @Test
    fun `D-pad nudges the cursor`() {
        val before = service.cursorOverlay.getPosition()
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        idleMain(300)
        val afterUp = service.cursorOverlay.getPosition()
        assertTrue("y should decrease, was ${before.y} now ${afterUp.y}", afterUp.y < before.y)
        assertEquals(before.x, afterUp.x, 1f)

        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        idleMain(300)
        val afterRight = service.cursorOverlay.getPosition()
        assertTrue(afterRight.x > afterUp.x)

        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun `D-pad while the keyboard is being dragged nudges the panel`() {
        service.startCustomKeyboard()
        val overlay = service.keyboardOverlay
        overlay.javaClass.getDeclaredField("dragging").apply { isAccessible = true }
            .setBoolean(overlay, true)
        val before = overlay.windowPositionForTest()
        assertNotNull(before)
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        assertNotEquals(before, overlay.windowPositionForTest())
    }

    @Test
    fun `KEY_EVENT action with an unknown key code is ignored`() {
        service.profile.mappings[ControllerInput.DPAD_UP] =
            ButtonAction(ActionType.KEY_EVENT, keyCode = KeyEvent.KEYCODE_A)
        val before = service.cursorOverlay.getPosition()
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        idleMain(300)
        assertEquals(before.x, service.cursorOverlay.getPosition().x, 0.001f)
        assertEquals(before.y, service.cursorOverlay.getPosition().y, 0.001f)
    }

    @Test
    fun `BUTTON_Y home action performs the home global action`() {
        service.profile.mappings[ControllerInput.BUTTON_Y] =
            ButtonAction(ActionType.HOME, label = "Home")
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y))
        assertTrue(shadow.globalActionsPerformed.contains(AccessibilityService.GLOBAL_ACTION_HOME))
    }

    @Test
    fun `D-pad left with keyboard open moves the text caret and claims ownership`() {
        service.startCustomKeyboard()
        assertTrue(service.keyboardOverlay.isShowing())
        val field = FakeField("hello")
        service.keyboardTypingTarget = field.node

        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(4 to 4, field.caret())
        assertEquals(
            ControllerAccessibilityService.DpadCaretOwner.KEY_EVENT,
            service.dpadCaretLeftOwner
        )

        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(
            ControllerAccessibilityService.DpadCaretOwner.NONE,
            service.dpadCaretLeftOwner
        )
    }

    @Test
    fun `D-pad right with keyboard open moves the caret forward`() {
        service.startCustomKeyboard()
        val field = FakeField("hello")
        field.node.setTextSelection(2, 2)
        service.keyboardTypingTarget = field.node

        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(3 to 3, field.caret())
        assertEquals(
            ControllerAccessibilityService.DpadCaretOwner.KEY_EVENT,
            service.dpadCaretRightOwner
        )
    }

    @Test
    fun `handleCapturedKeyEvent handles mapped keys`() {
        assertTrue(service.handleCapturedKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `handleCapturedKeyEvent returns false for unmapped keys`() {
        assertFalse(service.handleCapturedKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
    }

    @Test
    fun `handleCapturedKeyEvent swallows repeats`() {
        val e = KeyEvent(downTime + 9, downTime + 9, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 2)
        assertTrue(service.handleCapturedKeyEvent(e))
        assertEquals(0, shadow.gesturesDispatched.size)
    }

    @Test
    fun `dedup guard is shared between the a11y filter and capture view paths`() {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0)
        assertTrue(service.callOnKeyEvent(down))
        // Same event arriving via the capture view inside the recency window: dropped.
        assertTrue(service.handleCapturedKeyEvent(down))
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `captured-view PTT edges drive the controller`() {
        service.profile.mappings[ControllerInput.BUTTON_A] = ButtonAction.voiceTrigger()
        val downAt = SystemClock.uptimeMillis() + 5000
        assertTrue(
            service.handleCapturedKeyEvent(
                KeyEvent(downAt, downAt, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0)
            )
        )
        assertTrue(service.pttController.isHeld)
        waitForMain { service.isRecording }
        val upAt = downAt + 100
        assertTrue(
            service.handleCapturedKeyEvent(
                KeyEvent(upAt, upAt, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 0)
            )
        )
        assertFalse(service.pttController.isHeld)
        waitForMain { service.voiceRecordJob?.isCompleted == true }
        assertFalse(service.isRecording)
    }

    @Test
    fun `handleButtonDown covers voice-trigger cycle-context and none`() {
        // VOICE_TRIGGER through handleButtonDown is a no-op; the PTT edges own it.
        service.handleButtonDown(ControllerInput.BUTTON_A, ButtonAction.voiceTrigger())
        service.handleButtonDown(ControllerInput.BUTTON_A, ButtonAction(ActionType.CYCLE_CONTEXT))
        service.handleButtonDown(ControllerInput.BUTTON_A, ButtonAction(ActionType.NONE))
    }

    @Test
    fun `D-pad down-left-right while dragging nudges the panel`() {
        service.startCustomKeyboard()
        val overlay = service.keyboardOverlay
        overlay.javaClass.getDeclaredField("dragging").apply { isAccessible = true }
            .setBoolean(overlay, true)
        val before = overlay.windowPositionForTest()
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT
        )) {
            service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, key))
        }
        assertNotEquals(before, overlay.windowPositionForTest())
        // Unknown key code while dragging is logged and ignored.
        service.profile.mappings[ControllerInput.DPAD_UP] =
            ButtonAction(ActionType.KEY_EVENT, keyCode = KeyEvent.KEYCODE_A)
        service.callOnKeyEvent(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
    }
}
