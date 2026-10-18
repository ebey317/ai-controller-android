package com.ai.controller

import android.content.Context
import android.content.Intent
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
class BootReceiverTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val receiver = BootReceiver()

    private fun prefs() =
        context.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)

    @Before fun setUp() { prefs().edit().clear().commit() }
    @After fun tearDown() { prefs().edit().clear().commit() }

    @Test fun `non-boot intent is ignored`() {
        prefs().edit().putBoolean(BootReceiver.KEY_START_ON_BOOT, true).apply()
        receiver.onReceive(context, Intent("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(prefs().getBoolean(BootReceiver.KEY_PENDING_REENABLE_PROMPT, false))
    }

    @Test fun `boot without start-on-boot sets no prompt`() {
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(prefs().getBoolean(BootReceiver.KEY_PENDING_REENABLE_PROMPT, false))
    }

    @Test fun `boot with start-on-boot sets the re-enable prompt flag`() {
        prefs().edit().putBoolean(BootReceiver.KEY_START_ON_BOOT, true).apply()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(prefs().getBoolean(BootReceiver.KEY_PENDING_REENABLE_PROMPT, false))
    }

    @Test fun `boot with start-on-boot disabled later leaves no prompt`() {
        prefs().edit().putBoolean(BootReceiver.KEY_START_ON_BOOT, true).apply()
        prefs().edit().putBoolean(BootReceiver.KEY_START_ON_BOOT, false).apply()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(prefs().getBoolean(BootReceiver.KEY_PENDING_REENABLE_PROMPT, false))
    }
}
