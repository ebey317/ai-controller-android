package com.ai.controller

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The actual window attach/detach goes through the framework WindowManager, so
 * these tests assert the overlay's state machine (show/hide/update semantics)
 * under Robolectric's shadow WindowManager rather than real window placement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugOverlayTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun `overlay starts hidden`() {
        assertFalse(DebugOverlay(context).isShowing())
    }

    @Test fun `show makes it visible and is idempotent`() {
        val overlay = DebugOverlay(context)
        overlay.show()
        assertTrue(overlay.isShowing())
        overlay.show()
        assertTrue(overlay.isShowing())
    }

    @Test fun `hide after show removes it`() {
        val overlay = DebugOverlay(context)
        overlay.show()
        overlay.hide()
        assertFalse(overlay.isShowing())
    }

    @Test fun `hide without show is a safe no-op`() {
        val overlay = DebugOverlay(context)
        overlay.hide()
        assertFalse(overlay.isShowing())
    }

    @Test fun `update before show does not crash and show-hide-show cycles work`() {
        val overlay = DebugOverlay(context)
        overlay.update("before show")
        overlay.show()
        overlay.update("after show")
        assertTrue(overlay.isShowing())
        overlay.hide()
        overlay.show()
        assertTrue(overlay.isShowing())
        overlay.hide()
        assertFalse(overlay.isShowing())
    }
}
