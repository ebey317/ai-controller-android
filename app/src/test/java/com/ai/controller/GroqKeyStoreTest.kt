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
class GroqKeyStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun clear() {
        context.getSharedPreferences("ai_controller_groq_key", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Before fun setUp() = clear()
    @After fun tearDown() = clear()

    @Test fun `load returns null when nothing saved`() {
        assertNull(GroqKeyStore.load(context))
    }

    @Test fun `save then load roundtrips the key`() {
        GroqKeyStore.save(context, "gsk_testkey123")
        assertEquals("gsk_testkey123", GroqKeyStore.load(context))
    }

    @Test fun `save trims surrounding whitespace`() {
        GroqKeyStore.save(context, "  gsk_padded  ")
        assertEquals("gsk_padded", GroqKeyStore.load(context))
    }

    @Test fun `blank key loads as null`() {
        GroqKeyStore.save(context, "   ")
        assertNull(GroqKeyStore.load(context))
    }

    @Test fun `save overwrites the previous key`() {
        GroqKeyStore.save(context, "gsk_old")
        GroqKeyStore.save(context, "gsk_new")
        assertEquals("gsk_new", GroqKeyStore.load(context))
    }
}
