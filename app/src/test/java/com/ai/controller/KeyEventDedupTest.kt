package com.ai.controller

import android.os.SystemClock
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for ControllerAccessibilityService.alreadyHandled — the idempotency guard
 * shared by the onKeyEvent a11y-filter path and the MotionCaptureView key path,
 * which prevents double-firing on devices where both paths deliver the same event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyEventDedupTest {

    private lateinit var service: ControllerAccessibilityService

    @Before
    fun setUp() {
        service = Robolectric.buildService(ControllerAccessibilityService::class.java).create().get()
    }

    private fun keyEvent(downTime: Long, keyCode: Int, action: Int): KeyEvent =
        KeyEvent(downTime, downTime, action, keyCode, 0)

    @Test
    fun `first event is not a duplicate`() {
        val now = SystemClock.uptimeMillis()
        assertFalse(service.alreadyHandled(keyEvent(now, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)))
    }

    @Test
    fun `same event twice within the window is a duplicate`() {
        val now = SystemClock.uptimeMillis()
        assertFalse(service.alreadyHandled(keyEvent(now, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)))
        assertTrue(service.alreadyHandled(keyEvent(now, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)))
    }

    @Test
    fun `different key code is not a duplicate`() {
        val now = SystemClock.uptimeMillis()
        service.alreadyHandled(keyEvent(now, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
        assertFalse(service.alreadyHandled(keyEvent(now, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN)))
    }

    @Test
    fun `down then up is not a duplicate`() {
        val now = SystemClock.uptimeMillis()
        service.alreadyHandled(keyEvent(now, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
        assertFalse(service.alreadyHandled(keyEvent(now, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP)))
    }

    @Test
    fun `event older than the recency window is never a duplicate`() {
        val old = SystemClock.uptimeMillis() - 5000
        assertFalse(service.alreadyHandled(keyEvent(old, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)))
        // even an immediate repeat sits outside the 50ms window
        assertFalse(service.alreadyHandled(keyEvent(old, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)))
    }

    @Test
    fun `dedup tracks the newest event`() {
        val now = SystemClock.uptimeMillis()
        assertFalse(service.alreadyHandled(keyEvent(now, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)))
        assertFalse(service.alreadyHandled(keyEvent(now, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN)))
        assertTrue(service.alreadyHandled(keyEvent(now, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN)))
        // A is no longer the last-handled event
        assertFalse(service.alreadyHandled(keyEvent(now, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)))
    }
}
