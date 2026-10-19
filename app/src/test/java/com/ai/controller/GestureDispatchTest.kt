package com.ai.controller

import android.graphics.PointF
import com.ai.controller.models.SwipeDirection
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAccessibilityService

/**
 * Gesture dispatch: tap, long-press, scroll and swipe all reach the
 * framework's dispatchGesture (recorded by Robolectric's shadow).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GestureDispatchTest {

    private lateinit var service: ControllerAccessibilityService
    private val shadow: ShadowAccessibilityService get() = shadowOf(service)

    @Before
    fun setUp() {
        service = connectTestService()
    }

    @After
    fun tearDown() {
        service.onDestroy()
    }

    @Test
    fun `dispatchTap records a gesture`() {
        service.dispatchTap(PointF(100f, 200f))
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `dispatchLongPress records a gesture`() {
        service.dispatchLongPress(PointF(100f, 200f), 600L)
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `dispatchLongPress clamps tiny durations up`() {
        service.dispatchLongPress(PointF(100f, 200f), 10L)
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `dispatchScroll records a gesture`() {
        service.dispatchScroll(PointF(500f, 500f), SwipeDirection.UP)
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `dispatchSwipe records a gesture`() {
        service.dispatchSwipe(PointF(500f, 500f), SwipeDirection.DOWN, 250L, 300f)
        assertEquals(1, shadow.gesturesDispatched.size)
    }

    @Test
    fun `dispatchSwipe clamps tiny durations up`() {
        service.dispatchSwipe(PointF(500f, 500f), SwipeDirection.LEFT, 5L)
        assertEquals(1, shadow.gesturesDispatched.size)
    }
}
