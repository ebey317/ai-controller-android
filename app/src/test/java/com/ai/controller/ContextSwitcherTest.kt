package com.ai.controller

import android.content.Context
import com.ai.controller.models.ActionType
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile
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
class ContextSwitcherTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var switcher: ContextSwitcher
    private lateinit var profileManager: ProfileManager

    private fun clearPrefs() {
        context.getSharedPreferences("ai_controller_contexts", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("ai_controller_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        switcher = ContextSwitcher(context)
        profileManager = ProfileManager(context)
    }

    @After fun tearDown() = clearPrefs()

    @Test fun `active context defaults to desktop`() {
        assertEquals(ContextSwitcher.DESKTOP, switcher.activeContext())
    }

    @Test fun `setActiveContext persists the context`() {
        switcher.setActiveContext(ContextSwitcher.BROWSER)
        assertEquals(ContextSwitcher.BROWSER, switcher.activeContext())
    }

    @Test fun `cycleContext walks desktop browser iptv and wraps`() {
        assertEquals(ContextSwitcher.BROWSER, switcher.cycleContext())
        assertEquals(ContextSwitcher.BROWSER, switcher.activeContext())
        assertEquals(ContextSwitcher.IPTV, switcher.cycleContext())
        assertEquals(ContextSwitcher.DESKTOP, switcher.cycleContext())
    }

    @Test fun `desktop context resolves to the profile manager profile`() {
        val mine = ControllerProfile.default().copy(name = "Mine")
        profileManager.saveProfile(mine)
        assertEquals("Mine", switcher.profileFor(ContextSwitcher.DESKTOP, profileManager).name)
    }

    @Test fun `browser default maps A to Click`() {
        val profile = switcher.profileFor(ContextSwitcher.BROWSER, profileManager)
        assertEquals(ContextSwitcher.BROWSER, profile.name)
        val a = profile.mappings[ControllerInput.BUTTON_A]
        assertEquals(ActionType.TAP, a?.type)
        assertEquals("Click", a?.label)
    }

    @Test fun `browser default keeps the Linux legend label on stand-in actions`() {
        val profile = switcher.profileFor(ContextSwitcher.BROWSER, profileManager)
        // X/Reload has no dispatchable action, so NONE carries the Linux label
        val x = profile.mappings[ControllerInput.BUTTON_X]
        assertEquals(ActionType.NONE, x?.type)
        assertEquals("Reload", x?.label)
        // LT is the closest stand-in for right-click
        val l2 = profile.mappings[ControllerInput.TRIGGER_L2]
        assertEquals(ActionType.LONG_PRESS, l2?.type)
        assertEquals("R·Clk", l2?.label)
    }

    @Test fun `iptv default maps A to play-pause`() {
        val profile = switcher.profileFor(ContextSwitcher.IPTV, profileManager)
        assertEquals(ContextSwitcher.IPTV, profile.name)
        val a = profile.mappings[ControllerInput.BUTTON_A]
        assertEquals(ActionType.TAP, a?.type)
        assertEquals("▶‖", a?.label)
    }

    @Test fun `unknown context falls back to the desktop default profile`() {
        val profile = switcher.profileFor("kodi", profileManager)
        assertEquals(ControllerProfile.DEFAULT_NAME, profile.name)
    }

    @Test fun `savePreset roundtrips a browser customization`() {
        val custom = ControllerProfile.default().copy(
            name = "MyBrowser",
            mappings = mutableMapOf(ControllerInput.BUTTON_A to ButtonAction.tap("Mine"))
        )
        switcher.savePreset(ContextSwitcher.BROWSER, custom)
        val loaded = switcher.profileFor(ContextSwitcher.BROWSER, profileManager)
        assertEquals("MyBrowser", loaded.name)
        assertEquals("Mine", loaded.mappings[ControllerInput.BUTTON_A]?.label)
    }

    @Test fun `savePreset for desktop is ignored`() {
        val custom = ControllerProfile.default().copy(name = "Hijack")
        switcher.savePreset(ContextSwitcher.DESKTOP, custom)
        // desktop stays owned by ProfileManager (still the untouched default)
        assertEquals(ControllerProfile.DEFAULT_NAME, switcher.profileFor(ContextSwitcher.DESKTOP, profileManager).name)
    }

    @Test fun `savePreset does not clobber other contexts`() {
        switcher.savePreset(
            ContextSwitcher.BROWSER,
            ControllerProfile.default().copy(name = "MyBrowser")
        )
        assertEquals(
            ControllerProfile.DEFAULT_NAME,
            switcher.profileFor(ContextSwitcher.DESKTOP, profileManager).name
        )
        assertEquals(
            ContextSwitcher.IPTV,
            switcher.profileFor(ContextSwitcher.IPTV, profileManager).name
        )
    }

    @Test fun `corrupt preset json falls back to the context default`() {
        context.getSharedPreferences("ai_controller_contexts", Context.MODE_PRIVATE)
            .edit().putString("preset_browser", "{corrupt").apply()
        val profile = switcher.profileFor(ContextSwitcher.BROWSER, profileManager)
        assertEquals(ContextSwitcher.BROWSER, profile.name)
        assertEquals("Click", profile.mappings[ControllerInput.BUTTON_A]?.label)
    }
}
