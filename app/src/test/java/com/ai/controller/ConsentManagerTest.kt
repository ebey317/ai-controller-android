package com.ai.controller

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsentManagerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun clear() {
        context.getSharedPreferences("ai_controller_consent", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Before fun setUp() = clear()
    @After fun tearDown() = clear()

    @Test fun `consent defaults to not granted`() {
        assertFalse(ConsentManager.isGranted(context))
    }

    @Test fun `granting consent persists`() {
        ConsentManager.setGranted(context, true)
        assertTrue(ConsentManager.isGranted(context))
    }

    @Test fun `revoking consent persists`() {
        ConsentManager.setGranted(context, true)
        ConsentManager.setGranted(context, false)
        assertFalse(ConsentManager.isGranted(context))
    }

    @Test fun `repeated grants stay granted`() {
        ConsentManager.setGranted(context, true)
        ConsentManager.setGranted(context, true)
        assertTrue(ConsentManager.isGranted(context))
    }
}
