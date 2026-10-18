package com.ai.controller

import android.content.Context
import com.ai.controller.models.ControllerProfile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ProfileSerializerTest] already covers the JSON roundtrip; these tests cover
 * [ProfileManager]'s persistence layer on top of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileManagerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var manager: ProfileManager

    private fun rawPrefs() =
        context.getSharedPreferences("ai_controller_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        rawPrefs().edit().clear().commit()
        manager = ProfileManager(context)
    }

    @After fun tearDown() { rawPrefs().edit().clear().commit() }

    @Test fun `loadProfile returns the default when nothing is saved`() {
        val profile = manager.loadProfile()
        assertEquals(ControllerProfile.default(), profile)
    }

    @Test fun `saveProfile then loadProfile roundtrips`() {
        val saved = ControllerProfile.default().copy(name = "Mine", sensitivity = 0.5f)
        manager.saveProfile(saved)
        val loaded = manager.loadProfile()
        assertEquals("Mine", loaded.name)
        assertEquals(0.5f, loaded.sensitivity, 0.0001f)
        assertEquals(saved.mappings.size, loaded.mappings.size)
    }

    @Test fun `saveProfile overwrites the previous profile`() {
        manager.saveProfile(ControllerProfile.default().copy(name = "First"))
        manager.saveProfile(ControllerProfile.default().copy(name = "Second"))
        assertEquals("Second", manager.loadProfile().name)
    }

    @Test fun `corrupt json falls back to the default`() {
        rawPrefs().edit().putString("active_profile", "{not valid json").apply()
        assertEquals(ControllerProfile.default(), manager.loadProfile())
    }

    @Test fun `resetToDefault saves and returns the default`() {
        manager.saveProfile(ControllerProfile.default().copy(name = "Custom"))
        val reset = manager.resetToDefault()
        assertEquals(ControllerProfile.default(), reset)
        assertEquals(ControllerProfile.default(), manager.loadProfile())
    }
}
