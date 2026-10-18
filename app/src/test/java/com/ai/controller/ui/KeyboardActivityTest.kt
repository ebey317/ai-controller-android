package com.ai.controller.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.ai.controller.PttModeStore
import com.ai.controller.R
import com.ai.controller.TextStyles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Crew B — Robolectric activity test for [KeyboardActivity].
 *
 * The activity builds its whole UI programmatically; the tests walk the view
 * tree to find keys by label. Text injection routes through
 * ControllerAccessibilityService.instance, which is null in tests, so key
 * taps are safe no-ops for the injection path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardActivityTest {

    private fun launch() = Robolectric.buildActivity(KeyboardActivity::class.java).setup().get()

    private fun rootOf(activity: KeyboardActivity): View =
        activity.findViewById(android.R.id.content)

    private fun findButton(root: View, text: String): Button? {
        if (root is Button && root.text.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findButton(root.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun `creates with title and a full qwerty grid`() {
        val activity = launch()
        assertEquals(activity.getString(R.string.keyboard_title), activity.title.toString())
        val root = rootOf(activity)
        for (key in listOf("q", "w", "p", "a", "l", "z", "m", ",", "?", "@", "space", "⇧", "⌫", "⏎")) {
            assertNotNull("key '$key' present", findButton(root, key))
        }
    }

    @Test
    fun `shift uppercases the keys and resets after one character`() {
        val activity = launch()
        val root = rootOf(activity)

        findButton(root, "⇧")!!.performClick()
        assertNotNull("Q after shift", findButton(root, "Q"))
        assertNull("lowercase q hidden while shifted", findButton(root, "q"))

        // One-shot shift: tapping a letter commits (null-safe, no service in
        // tests) and drops shift back to lowercase.
        findButton(root, "Q")!!.performClick()
        assertNotNull("q back after one shifted char", findButton(root, "q"))
        assertNull("Q gone after reset", findButton(root, "Q"))
    }

    @Test
    fun `shift toggles off when tapped twice`() {
        val activity = launch()
        val root = rootOf(activity)
        findButton(root, "⇧")!!.performClick()
        findButton(root, "⇧")!!.performClick()
        assertNotNull(findButton(root, "q"))
    }

    @Test
    fun `mode button cycles styles and persists them`() {
        val activity = launch()
        val root = rootOf(activity)
        val modeButton = findButton(root, "PRO")
        assertNotNull("mode button starts on PRO", modeButton)

        modeButton!!.performClick()
        assertEquals(TextStyles.Mode.BUBBLY, PttModeStore.load(activity))
        assertNotEquals("PRO", modeButton.text.toString())

        modeButton.performClick()
        assertEquals(TextStyles.Mode.CASUAL, PttModeStore.load(activity))
        assertEquals("☕ casual", modeButton.text.toString())
    }

    @Test
    fun `pins row shows default snippets plus the add button`() {
        val activity = launch()
        val root = rootOf(activity)
        assertNotNull(findButton(root, "hello"))
        assertNotNull(findButton(root, "thanks"))
        assertNotNull(findButton(root, "email"))
        assertNotNull(findButton(root, activity.getString(R.string.keyboard_pin_add)))
    }

    @Test
    fun `add pin with empty clipboard shows a toast and adds nothing`() {
        val activity = launch()
        val root = rootOf(activity)
        findButton(root, activity.getString(R.string.keyboard_pin_add))!!.performClick()
        assertEquals(
            activity.getString(R.string.keyboard_pin_empty_clipboard),
            ShadowToast.getTextOfLatestToast()
        )
        // Row still shows the 3 defaults + add button; no phantom pin added.
        assertNull(findButton(root, "Unpinned"))
    }
}
