package com.ai.controller

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the shared per-key style logic extracted from
 * FloatingKeyboardOverlay and keyboard/AIInputMethodService (which duplicated it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardStyleLogicTest {

    @Test
    fun `styleChar passes keys through in PRO mode`() {
        assertEquals("a", styleChar("a", TextStyles.Mode.PRO))
        assertEquals("Z", styleChar("Z", TextStyles.Mode.PRO))
        assertEquals(",", styleChar(",", TextStyles.Mode.PRO))
    }

    @Test
    fun `styleChar lowercases in CASUAL mode`() {
        assertEquals("q", styleChar("Q", TextStyles.Mode.CASUAL))
        assertEquals("a", styleChar("a", TextStyles.Mode.CASUAL))
    }

    @Test
    fun `styleChar maps through TextStyles for decorative modes`() {
        assertEquals(TextStyles.toBold("a"), styleChar("a", TextStyles.Mode.BOLD))
        assertEquals(TextStyles.toCursive("a"), styleChar("a", TextStyles.Mode.BUBBLY))
        assertEquals(TextStyles.toOldEnglish("a"), styleChar("a", TextStyles.Mode.BIG))
    }

    @Test
    fun `styleChar decorative modes differ from the plain key`() {
        assertNotEquals("a", styleChar("a", TextStyles.Mode.BOLD))
        assertNotEquals("a", styleChar("a", TextStyles.Mode.BUBBLY))
        assertNotEquals("a", styleChar("a", TextStyles.Mode.BIG))
    }

    @Test
    fun `styleLabel covers every mode`() {
        assertEquals("PRO", styleLabel(TextStyles.Mode.PRO))
        assertEquals("☕ casual", styleLabel(TextStyles.Mode.CASUAL))
        assertEquals(TextStyles.toBold("Bold"), styleLabel(TextStyles.Mode.BOLD))
        assertEquals(TextStyles.toOldEnglish("Old-E"), styleLabel(TextStyles.Mode.BIG))
        val bubbly = styleLabel(TextStyles.Mode.BUBBLY)
        assertTrue("bubbly label should start with sparkle: $bubbly", bubbly.startsWith("✨ "))
        assertEquals("✨ " + TextStyles.toCursive("Cursive"), bubbly)
    }

    @Test
    fun `styleLabel values are all distinct`() {
        val labels = TextStyles.Mode.values().map { styleLabel(it) }.toSet()
        assertEquals(TextStyles.Mode.values().size, labels.size)
    }
}
