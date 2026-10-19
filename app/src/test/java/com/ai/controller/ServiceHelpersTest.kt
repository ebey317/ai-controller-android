package com.ai.controller

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ai.controller.models.ButtonAction
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
import java.util.concurrent.TimeUnit

/**
 * Odds and ends: window/root discovery (externalAppRoots, findEditableTarget),
 * the MotionCaptureView event plumbing, the focus watchdog + legend tick loops,
 * the backspace-hold timer, startCustomKeyboard's fresh-focus clear, and the
 * gesture-result callback branches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [SealedRootWindowInfoShadow::class])
class ServiceHelpersTest {

    private lateinit var service: ControllerAccessibilityService
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val shadow: ShadowAccessibilityService get() = shadowOf(service)

    private fun windowWithRoot(
        packageName: String,
        editable: Boolean = false,
        initialText: String = "",
        onSetText: ((String) -> Unit)? = null
    ): AccessibilityWindowInfo {
        val info = AccessibilityWindowInfo.obtain()
        val shadow = org.robolectric.Shadows.shadowOf(info) as SealedRootWindowInfoShadow
        SealedRootWindowInfoShadow.setFactory(shadow) {
            sealedTestRoot(packageName, editable, onSetText).apply {
                if (initialText.isNotEmpty()) text = initialText
            }
        }
        return info
    }

    private fun captureView(): View {
        val field = service.javaClass.getDeclaredField("motionCaptureView").apply {
            isAccessible = true
        }
        return field.get(service) as View
    }

    @Before
    fun setUp() {
        SealedRootWindowInfoShadow.clearFactories()
        service = connectTestService()
    }

    @After
    fun tearDown() {
        service.onDestroy()
    }

    @Test
    fun `externalAppRoots skips this service's own windows`() {
        val own = windowWithRoot(service.packageName)
        val other = windowWithRoot("com.other.app")
        shadow.setWindows(listOf(own, other))

        val roots = service.externalAppRoots()
        assertEquals(1, roots.size)
        assertEquals("com.other.app", roots[0].packageName)
    }

    @Test
    fun `externalAppRoots is empty with no windows`() {
        assertTrue(service.externalAppRoots().isEmpty())
        assertNull(service.externalAppRoot())
    }

    @Test
    fun `findEditableTarget finds the editable root`() {
        val other = windowWithRoot("com.other.app", editable = true)
        shadow.setWindows(listOf(other))

        val target = service.findEditableTarget()
        assertNotNull(target)
        assertEquals("com.other.app", target!!.packageName)
    }

    @Test
    fun `findEditableTarget returns null when nothing is editable`() {
        val other = windowWithRoot("com.other.app", editable = false)
        shadow.setWindows(listOf(other))
        assertNull(service.findEditableTarget())
    }

    @Test
    fun `typingTarget falls back to the live window lookup`() {
        val other = windowWithRoot("com.other.app", editable = true)
        shadow.setWindows(listOf(other))
        val target = service.typingTargetOrWarn("test")
        assertNotNull(target)
    }

    @Test
    fun `startCustomKeyboard clears a freshly focused non-empty field`() {
        val cleared = mutableListOf<String>()
        val other = windowWithRoot("com.other.app", editable = true, initialText = "stale") {
            cleared.add(it)
        }
        shadow.setWindows(listOf(other))

        service.startCustomKeyboard()
        assertTrue(service.keyboardOverlay.isShowing())
        // clearOnFreshFocus wiped the field exactly once for this fresh identity.
        assertEquals(listOf(""), cleared)
        assertNotNull(service.lastFreshFocusIdentity)
    }

    @Test
    fun `startCustomKeyboard does not clear when the field identity is unchanged`() {
        val cleared = mutableListOf<String>()
        val other = windowWithRoot("com.other.app", editable = true, initialText = "typed") {
            cleared.add(it)
        }
        shadow.setWindows(listOf(other))
        // Pretend the keyboard was already opened on this exact field: the
        // factory builds structurally identical nodes, so this identity matches
        // the one startCustomKeyboard will compute.
        service.lastFreshFocusIdentity = service.fieldIdentity(
            sealedTestRoot("com.other.app", editable = true).apply { text = "typed" }
        )

        service.startCustomKeyboard()
        assertTrue(service.keyboardOverlay.isShowing())
        assertTrue(cleared.isEmpty()) // not wiped again
    }

    @Test
    fun `injectText falls back to the accessibility-focus lookup`() {
        // Typing target lookup finds nothing editable, but a window exists:
        // injectText then tries externalAppRoot().findFocus(FOCUS_ACCESSIBILITY).
        val other = windowWithRoot("com.other.app", editable = false)
        shadow.setWindows(listOf(other))
        service.injectText("hello") // no crash, warns about the missing node
    }

    @Test
    fun `motion capture view routes motion events to handleGenericMotion`() {
        val view = captureView()
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1f, 1f, 0)
        event.source = android.view.InputDevice.SOURCE_JOYSTICK
        assertTrue(view.onGenericMotionEvent(event))
        event.recycle()
    }

    @Test
    fun `motion capture view routes key events to handleCapturedKeyEvent`() {
        val view = captureView()
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0)
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_BUTTON_A, down))
        val up = KeyEvent(now + 10, now + 10, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 0)
        assertTrue(view.onKeyUp(KeyEvent.KEYCODE_BUTTON_A, up))
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `motion capture view reclaims focus when its window loses it`() {
        val view = captureView()
        val method = View::class.java.getDeclaredMethod(
            "onWindowFocusChanged", java.lang.Boolean.TYPE
        ).apply { isAccessible = true }
        method.invoke(view, false)
        idleMain(200)
    }

    @Test
    fun `focus watchdog and legend tick survive a looper advance`() {
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertSame(service, ControllerAccessibilityService.instance)
    }

    @Test
    fun `armBackspaceHold fires deleteToLineStart after the hold delay`() {
        service.startCustomKeyboard()
        val field = FakeField("hello")
        service.keyboardTypingTarget = field.node

        service.armBackspaceHold(ButtonAction.backspace())
        assertNotNull(service.backspaceHoldJob)
        shadowOf(Looper.getMainLooper()).idleFor(700, TimeUnit.MILLISECONDS)
        assertEquals("", field.text)
    }

    @Test
    fun `disarmBackspaceHold cancels the pending hold`() {
        service.startCustomKeyboard()
        val field = FakeField("hello")
        service.keyboardTypingTarget = field.node

        service.armBackspaceHold(ButtonAction.backspace())
        service.disarmBackspaceHold()
        assertNull(service.backspaceHoldJob)
        shadowOf(Looper.getMainLooper()).idleFor(700, TimeUnit.MILLISECONDS)
        assertEquals("hello", field.text)
    }

    @Test
    fun `armBackspaceHold ignores non-backspace actions`() {
        service.armBackspaceHold(ButtonAction.tap())
        assertNull(service.backspaceHoldJob)
    }

    @Test
    fun `deleteToLineStart on an empty field is a no-op`() {
        service.startCustomKeyboard()
        val field = FakeField("")
        service.keyboardTypingTarget = field.node
        service.deleteToLineStart()
        assertTrue(field.setTextCalls.isEmpty())
    }

    @Test
    fun `attach and detach of the motion capture view are safe to repeat`() {
        service.detachMotionCapture()
        service.attachMotionCapture()
        service.detachMotionCapture()
    }

    @Test
    fun `gesture callback reports completion when gestures can dispatch`() {
        shadow.setCanDispatchGestures(true)
        service.dispatchTap(android.graphics.PointF(10f, 10f))
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `tap gesture callback handles completion and cancellation`() {
        // Robolectric's shadow never invokes the callback itself; drive it directly.
        val field = service.javaClass.getDeclaredField("tapResultCallback").apply {
            isAccessible = true
        }
        val callback = field.get(service)
            as android.accessibilityservice.AccessibilityService.GestureResultCallback
        callback.onCompleted(null)
        callback.onCancelled(null)
    }

    @Test
    fun `dispatchTap with gestures disabled returns false from the framework`() {
        shadow.setCanDispatchGestures(false)
        service.dispatchTap(android.graphics.PointF(10f, 10f))
        assertEquals(0, shadow.gesturesDispatched.size)
        shadow.setCanDispatchGestures(true)
    }

    @Test
    fun `moveAccessibilityFocus searches from the focused node`() {
        val other = windowWithRoot("com.other.app", editable = true)
        shadow.setWindows(listOf(other))
        // Sealed node with no focusable children: focusSearch returns null, no crash.
        service.moveAccessibilityFocus(true)
        service.moveAccessibilityFocus(false)
    }
}
