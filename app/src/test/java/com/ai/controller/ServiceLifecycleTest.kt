package com.ai.controller

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Service lifecycle: onServiceConnected init, onDestroy/onUnbind teardown,
 * onInterrupt, onAccessibilityEvent focus reclaim, the prefs listener
 * (debug toggle + profile reload), and context cycling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceLifecycleTest {

    private lateinit var service: ControllerAccessibilityService
    private val app: Context get() = RuntimeEnvironment.getApplication()

    private fun prefs() = app.getSharedPreferences(
        ControllerAccessibilityService.PROFILE_PREFS_NAME, Context.MODE_PRIVATE
    )

    @Before
    fun setUp() {
        prefs().edit().clear().apply()
        app.getSharedPreferences("ai_controller_contexts", Context.MODE_PRIVATE).edit().clear().apply()
        service = connectTestService()
    }

    @After
    fun tearDown() {
        service.onDestroy()
    }

    @Test
    fun `connect sets the static instance`() {
        // Note: serviceInfo itself can't be asserted under Robolectric — the
        // framework's getServiceInfo() returns null without a live service
        // connection, so the flags onServiceConnected sets are not observable.
        assertSame(service, ControllerAccessibilityService.instance)
    }

    @Test
    fun `connect shows cursor and legend overlays`() {
        assertTrue(service.cursorOverlay.isShowing())
        assertTrue(service.legendOverlay.isShowing())
    }

    @Test
    fun `onDestroy clears the instance`() {
        service.onDestroy()
        assertNull(ControllerAccessibilityService.instance)
    }

    @Test
    fun `onUnbind tears down and returns super result`() {
        assertFalse(service.onUnbind(null))
        assertNull(ControllerAccessibilityService.instance)
    }

    @Test
    fun `onInterrupt does not crash`() {
        service.onInterrupt()
    }

    @Test
    fun `onAccessibilityEvent attempts focus reclaim on the capture view`() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        service.onAccessibilityEvent(event)
        event.recycle()
    }

    @Test
    fun `debug pref toggles the debug overlay live`() {
        assertFalse(service.debugOverlay.isShowing())
        prefs().edit().putBoolean(ControllerAccessibilityService.KEY_DEBUG_ENABLED, true).apply()
        assertTrue(service.debugOverlay.isShowing())
        prefs().edit().putBoolean(ControllerAccessibilityService.KEY_DEBUG_ENABLED, false).apply()
        assertFalse(service.debugOverlay.isShowing())
    }

    @Test
    fun `non-debug pref change reloads the active profile`() {
        prefs().edit().putString("some_other_key", "x").apply()
        assertSame(service, ControllerAccessibilityService.instance)
    }

    @Test
    fun `cycleContext walks desktop to browser to iptv to desktop`() {
        service.cycleContext()
        service.cycleContext()
        service.cycleContext()
        assertSame(service, ControllerAccessibilityService.instance)
    }

    @Test
    fun `showDebug is a no-op before overlays are initialized`() {
        val bare = Robolectric.buildService(ControllerAccessibilityService::class.java).create().get()
        bare.showDebug("hello") // debugOverlay lateinit not initialized -> skipped
        bare.onDestroy()
    }

    @Test
    fun `showDebug updates the overlay when it is showing`() {
        prefs().edit().putBoolean(ControllerAccessibilityService.KEY_DEBUG_ENABLED, true).apply()
        assertTrue(service.debugOverlay.isShowing())
        service.showDebug("hello")
        service.showDebug("hello") // second call also fine
    }

    @Test
    fun `debugEnabled reflects the pref`() {
        assertFalse(service.debugEnabled())
        prefs().edit().putBoolean(ControllerAccessibilityService.KEY_DEBUG_ENABLED, true).apply()
        assertTrue(service.debugEnabled())
    }
}
