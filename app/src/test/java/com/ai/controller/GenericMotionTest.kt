package com.ai.controller

import android.Manifest
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.SwipeDirection
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
 * Joystick motion handling: left-stick cursor movement, D-pad hat edges
 * (caret moves, cursor nudges, keyboard dragging), right-stick scroll with
 * throttle, and analog trigger edges (PTT + held-scroll repeat).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenericMotionTest {

    private lateinit var service: ControllerAccessibilityService
    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()
    private val shadow: ShadowAccessibilityService get() = shadowOf(service)

    private fun joystickEvent(configure: MotionEvent.PointerCoords.() -> Unit = {}): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply(configure))
        return MotionEvent.obtain(
            now, now, MotionEvent.ACTION_MOVE, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0
        )
    }

    private fun setDragging(dragging: Boolean) {
        val overlay = service.keyboardOverlay
        val field = overlay.javaClass.getDeclaredField("dragging").apply { isAccessible = true }
        field.setBoolean(overlay, dragging)
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        silenceMicrophone()
        ConsentManager.setGranted(app, true)
        service = connectTestService()
        service.primePttDebounce()
    }

    @After
    fun tearDown() {
        ConsentManager.setGranted(app, false)
        ShadowAudioRecord.clearSource()
        service.onDestroy()
    }

    @Test
    fun `non-joystick source is ignored`() {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
        assertFalse(service.handleGenericMotion(event))
        event.recycle()
    }

    @Test
    fun `left stick deflection moves the cursor`() {
        val before = service.cursorOverlay.getPosition()
        val event = joystickEvent { setAxisValue(MotionEvent.AXIS_X, 0.9f) }
        assertTrue(service.handleGenericMotion(event))
        idleMain(300)
        val after = service.cursorOverlay.getPosition()
        assertTrue("x should increase", after.x > before.x)
        assertEquals(before.y, after.y, 1f)
        event.recycle()
    }

    @Test
    fun `stick inside the deadzone does not move the cursor`() {
        val before = service.cursorOverlay.getPosition()
        val event = joystickEvent { setAxisValue(MotionEvent.AXIS_X, 0.05f) }
        service.handleGenericMotion(event)
        assertEquals(before, service.cursorOverlay.getPosition())
        event.recycle()
    }

    @Test
    fun `hat edges move the caret when the keyboard is open`() {
        service.startCustomKeyboard()
        val field = FakeField("hello")
        service.keyboardTypingTarget = field.node

        // First tick after connect: lastHatX is NaN -> no spurious edge.
        service.handleGenericMotion(joystickEvent { setAxisValue(MotionEvent.AXIS_HAT_X, 0f) })
        assertEquals(5 to 5, field.caret())

        // Rising edge on the left: caret moves, HAT claims ownership.
        service.handleGenericMotion(joystickEvent { setAxisValue(MotionEvent.AXIS_HAT_X, -1f) })
        assertEquals(4 to 4, field.caret())
        assertEquals(
            ControllerAccessibilityService.DpadCaretOwner.HAT,
            service.dpadCaretLeftOwner
        )

        // Held: no refire.
        service.handleGenericMotion(joystickEvent { setAxisValue(MotionEvent.AXIS_HAT_X, -1f) })
        assertEquals(4 to 4, field.caret())

        // Falling edge: ownership released.
        service.handleGenericMotion(joystickEvent { setAxisValue(MotionEvent.AXIS_HAT_X, 0f) })
        assertEquals(
            ControllerAccessibilityService.DpadCaretOwner.NONE,
            service.dpadCaretLeftOwner
        )
        assertEquals(0f, service.lastHatX, 0.001f)
    }

    @Test
    fun `hat nudges the cursor when the keyboard is closed`() {
        val before = service.cursorOverlay.getPosition()
        val event = joystickEvent { setAxisValue(MotionEvent.AXIS_HAT_X, 1f) }
        service.handleGenericMotion(event)
        idleMain(300)
        assertTrue(service.cursorOverlay.getPosition().x > before.x)
        assertEquals(1f, service.lastHatX, 0.001f)
        event.recycle()
    }

    @Test
    fun `hat up-down still nudges the cursor with the keyboard open`() {
        service.startCustomKeyboard()
        val before = service.cursorOverlay.getPosition()
        val event = joystickEvent { setAxisValue(MotionEvent.AXIS_HAT_Y, -1f) }
        service.handleGenericMotion(event)
        idleMain(300)
        assertTrue(service.cursorOverlay.getPosition().y < before.y)
        event.recycle()
    }

    @Test
    fun `hat while dragging nudges the keyboard panel`() {
        service.startCustomKeyboard()
        setDragging(true)
        val before = service.keyboardOverlay.windowPositionForTest()
        assertNotNull(before)
        val event = joystickEvent {
            setAxisValue(MotionEvent.AXIS_HAT_X, 1f)
            setAxisValue(MotionEvent.AXIS_HAT_Y, 0f)
        }
        service.handleGenericMotion(event)
        val after = service.keyboardOverlay.windowPositionForTest()
        assertNotEquals(before, after)
        // HAT claims are cleared while dragging.
        assertEquals(
            ControllerAccessibilityService.DpadCaretOwner.NONE,
            service.dpadCaretLeftOwner
        )
        event.recycle()
    }

    @Test
    fun `stick while dragging nudges the keyboard instead of the cursor`() {
        service.startCustomKeyboard()
        setDragging(true)
        val cursorBefore = service.cursorOverlay.getPosition()
        val kbdBefore = service.keyboardOverlay.windowPositionForTest()
        val event = joystickEvent { setAxisValue(MotionEvent.AXIS_X, 0.9f) }
        service.handleGenericMotion(event)
        assertEquals(cursorBefore, service.cursorOverlay.getPosition())
        assertNotEquals(kbdBefore, service.keyboardOverlay.windowPositionForTest())
        event.recycle()
    }

    @Test
    fun `right stick scrolls with a cooldown`() {
        service.lastStickScrollTimeMs = -100000L
        val base = shadow.gesturesDispatched.size
        service.handleGenericMotion(joystickEvent { setAxisValue(MotionEvent.AXIS_RZ, -0.8f) })
        assertEquals(base + 1, shadow.gesturesDispatched.size)
        // Immediate second tick: throttled.
        service.handleGenericMotion(joystickEvent { setAxisValue(MotionEvent.AXIS_RZ, -0.8f) })
        assertEquals(base + 1, shadow.gesturesDispatched.size)
        // After the cooldown: scrolls again.
        service.lastStickScrollTimeMs = -100000L
        service.handleGenericMotion(joystickEvent { setAxisValue(MotionEvent.AXIS_RZ, 0.8f) })
        assertEquals(base + 2, shadow.gesturesDispatched.size)
    }

    @Test
    fun `maybeDispatchStickScroll maps sign to direction`() {
        service.lastStickScrollTimeMs = -100000L
        val base = shadow.gesturesDispatched.size
        service.maybeDispatchStickScroll(-0.6f)
        assertEquals(base + 1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `trigger L2 press and release with tap action toggles held state`() {
        // Default desktop profile: L2 -> TAP (the else branch on both edges).
        service.handleTrigger(ControllerInput.TRIGGER_L2, 0.9f)
        assertEquals(true, service.triggerHeldState[ControllerInput.TRIGGER_L2])
        assertTrue(service.activeTriggerJobs.isEmpty())
        // Hysteresis: 0.3 is still above the release threshold -> no edge.
        service.handleTrigger(ControllerInput.TRIGGER_L2, 0.3f)
        assertEquals(true, service.triggerHeldState[ControllerInput.TRIGGER_L2])
        service.handleTrigger(ControllerInput.TRIGGER_L2, 0.1f)
        assertEquals(false, service.triggerHeldState[ControllerInput.TRIGGER_L2])
    }

    @Test
    fun `trigger with scroll action starts and stops the repeat job`() {
        service.profile.mappings[ControllerInput.TRIGGER_L2] =
            ButtonAction.scroll(SwipeDirection.UP, "t")
        service.handleTrigger(ControllerInput.TRIGGER_L2, 0.9f)
        assertTrue(service.activeTriggerJobs.containsKey(ControllerInput.TRIGGER_L2))
        // Let the repeat coroutine fire once on the main looper.
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(shadow.gesturesDispatched.isNotEmpty())

        service.handleTrigger(ControllerInput.TRIGGER_L2, 0f)
        assertFalse(service.activeTriggerJobs.containsKey(ControllerInput.TRIGGER_L2))
        assertEquals(false, service.triggerHeldState[ControllerInput.TRIGGER_L2])
    }

    @Test
    fun `cancelAllTriggerJobs clears state`() {
        service.profile.mappings[ControllerInput.TRIGGER_L2] =
            ButtonAction.scroll(SwipeDirection.UP, "t")
        service.handleTrigger(ControllerInput.TRIGGER_L2, 0.9f)
        assertFalse(service.activeTriggerJobs.isEmpty())
        service.cancelAllTriggerJobs()
        assertTrue(service.activeTriggerJobs.isEmpty())
        assertTrue(service.triggerHeldState.isEmpty())
    }

    @Test
    fun `R2 trigger drives the PTT edges`() {
        // Default desktop profile: R2 -> VOICE_TRIGGER.
        service.handleTrigger(ControllerInput.TRIGGER_R2, 0.9f)
        assertTrue(service.pttController.isHeld)
        waitForMain { service.isRecording }
        service.handleTrigger(ControllerInput.TRIGGER_R2, 0f)
        assertFalse(service.pttController.isHeld)
        waitForMain { service.voiceRecordJob?.isCompleted == true }
        assertFalse(service.isRecording)
    }
}
