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
class TextStylesTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val neutralCasualEmojis = listOf("👋", "☕", "😊", "✌️", "🙌", "🤙", "😎", "✨", "💯", "🔥", "🫡")

    /** TextStyles.setSkinTone mutates shared state; every tone-dependent test
     *  sets the tone explicitly and restores the DARK default afterwards. */
    @After fun restoreTone() { TextStyles.setSkinTone(EmojiSkinTone.DARK) }

    private fun clearPrefs(name: String) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun clearAllStores() {
        clearPrefs("ai_controller_ptt_mode")
        clearPrefs("ai_controller_skin_tone")
        clearPrefs("ai_controller_keyboard")
    }

    // ---- Mode plumbing ----

    @Test fun `parseMode handles names case-insensitively`() {
        assertEquals(TextStyles.Mode.BUBBLY, TextStyles.parseMode("bubbly"))
        assertEquals(TextStyles.Mode.CASUAL, TextStyles.parseMode("CASUAL"))
        assertEquals(TextStyles.Mode.BOLD, TextStyles.parseMode("Bold"))
        assertEquals(TextStyles.Mode.BIG, TextStyles.parseMode("big"))
    }

    @Test fun `parseMode falls back to PRO`() {
        assertEquals(TextStyles.Mode.PRO, TextStyles.parseMode(null))
        assertEquals(TextStyles.Mode.PRO, TextStyles.parseMode(""))
        assertEquals(TextStyles.Mode.PRO, TextStyles.parseMode("fancy"))
    }

    @Test fun `next cycles through all modes and wraps`() {
        assertEquals(TextStyles.Mode.BUBBLY, TextStyles.next(TextStyles.Mode.PRO))
        assertEquals(TextStyles.Mode.CASUAL, TextStyles.next(TextStyles.Mode.BUBBLY))
        assertEquals(TextStyles.Mode.BOLD, TextStyles.next(TextStyles.Mode.CASUAL))
        assertEquals(TextStyles.Mode.BIG, TextStyles.next(TextStyles.Mode.BOLD))
        assertEquals(TextStyles.Mode.PRO, TextStyles.next(TextStyles.Mode.BIG))
    }

    // ---- Char transforms ----

    @Test fun `PRO returns the transcript untouched`() {
        TextStyles.setSkinTone(EmojiSkinTone.DARK)
        assertEquals("I am happy", TextStyles.transform("I am happy", TextStyles.Mode.PRO))
    }

    @Test fun `toBold maps to the Mathematical Bold block`() {
        assertEquals("𝐚", TextStyles.toBold("a"))
        assertEquals("𝐙", TextStyles.toBold("Z"))
        assertEquals("𝐚1!", TextStyles.toBold("a1!"))
        assertEquals("", TextStyles.toBold(""))
    }

    @Test fun `toCursive maps to the Bold Script block`() {
        assertEquals("𝓪", TextStyles.toCursive("a"))
        assertEquals("𝓩", TextStyles.toCursive("Z"))
    }

    @Test fun `toOldEnglish uses the contiguous Bold Fraktur block`() {
        assertEquals("𝖆", TextStyles.toOldEnglish("a"))
        // plain Fraktur (U+1D504) has legacy gaps for C/H/I/R/Z; the Bold
        // Fraktur block used here has none
        assertEquals("𝕮", TextStyles.toOldEnglish("C"))
        assertEquals("𝕳", TextStyles.toOldEnglish("H"))
    }

    @Test fun `astral emoji passes through the char maps whole`() {
        assertEquals("😊", TextStyles.toBold("😊"))
        assertEquals("👋🏿", TextStyles.toCursive("👋🏿"))
    }

    // ---- Keyword emoji insertion ----

    @Test fun `keyword emoji is appended with the neutral tone`() {
        TextStyles.setSkinTone(EmojiSkinTone.NEUTRAL)
        assertEquals(
            TextStyles.toCursive("i am happy 😊"),
            TextStyles.transform("i am happy", TextStyles.Mode.BUBBLY)
        )
    }

    @Test fun `longest phrase wins the keyword match`() {
        TextStyles.setSkinTone(EmojiSkinTone.NEUTRAL)
        // "good morning" (12 chars) beats "good" (4 chars)
        assertEquals(
            TextStyles.toBold("say good morning to me 🌅"),
            TextStyles.transform("say good morning to me", TextStyles.Mode.BOLD)
        )
    }

    @Test fun `text without a keyword is only style-mapped`() {
        TextStyles.setSkinTone(EmojiSkinTone.NEUTRAL)
        assertEquals(TextStyles.toBold("xyz"), TextStyles.transform("xyz", TextStyles.Mode.BOLD))
    }

    // ---- Skin tone ----

    @Test fun `neutral tone leaves hand emoji unmodified`() {
        TextStyles.setSkinTone(EmojiSkinTone.NEUTRAL)
        assertEquals(TextStyles.toBold("bye 👋"), TextStyles.transform("bye", TextStyles.Mode.BOLD))
    }

    @Test fun `dark tone appends the modifier exactly once per hand emoji`() {
        TextStyles.setSkinTone(EmojiSkinTone.DARK)
        assertEquals(TextStyles.toBold("bye 👋🏿"), TextStyles.transform("bye", TextStyles.Mode.BOLD))
    }

    @Test fun `light tone appends its own modifier`() {
        TextStyles.setSkinTone(EmojiSkinTone.LIGHT)
        assertEquals(TextStyles.toBold("bye 👋🏻"), TextStyles.transform("bye", TextStyles.Mode.BOLD))
    }

    @Test fun `tone change rebuilds the emoji tables`() {
        TextStyles.setSkinTone(EmojiSkinTone.DARK)
        val dark = TextStyles.transform("thanks", TextStyles.Mode.BOLD)
        TextStyles.setSkinTone(EmojiSkinTone.NEUTRAL)
        val neutral = TextStyles.transform("thanks", TextStyles.Mode.BOLD)
        assertTrue(dark.contains("🙏🏿"))
        assertTrue(neutral.contains("🙏"))
        assertFalse(neutral.contains("🏿"))
    }

    @Test fun `skin tone enum carries modifier and label`() {
        assertNull(EmojiSkinTone.NEUTRAL.modifier)
        assertEquals("🏿", EmojiSkinTone.DARK.modifier)
        assertEquals("✌🏿  Dark (default)", EmojiSkinTone.DARK.label)
        assertEquals(EmojiSkinTone.DARK, TextStyles.currentSkinTone())
    }

    // ---- CASUAL ----

    @Test fun `casual lowercases and keeps the keyword emoji without a boost`() {
        TextStyles.setSkinTone(EmojiSkinTone.NEUTRAL)
        assertEquals("happy 😊", TextStyles.transform("HAPPY", TextStyles.Mode.CASUAL))
    }

    @Test fun `casual without a keyword appends a random casual emoji`() {
        TextStyles.setSkinTone(EmojiSkinTone.NEUTRAL)
        val result = TextStyles.transform("xyz", TextStyles.Mode.CASUAL)
        assertTrue(result.startsWith("xyz "))
        val appended = result.removePrefix("xyz ")
        assertTrue("unexpected casual emoji: $appended", appended in neutralCasualEmojis)
    }

    // ---- PttModeStore ----

    @Test fun `ptt mode defaults to PRO`() {
        assertEquals(TextStyles.Mode.PRO, PttModeStore.load(context))
    }

    @Test fun `ptt mode save and load roundtrip`() {
        PttModeStore.save(context, TextStyles.Mode.BOLD)
        assertEquals(TextStyles.Mode.BOLD, PttModeStore.load(context))
        PttModeStore.save(context, TextStyles.Mode.CASUAL)
        assertEquals(TextStyles.Mode.CASUAL, PttModeStore.load(context))
    }

    @Test fun `ptt mode falls back to PRO on garbage`() {
        context.getSharedPreferences("ai_controller_ptt_mode", Context.MODE_PRIVATE)
            .edit().putString("mode", "fancy").apply()
        assertEquals(TextStyles.Mode.PRO, PttModeStore.load(context))
    }

    // ---- SkinToneStore ----

    @Test fun `skin tone defaults to DARK`() {
        assertEquals(EmojiSkinTone.DARK, SkinToneStore.load(context))
    }

    @Test fun `skin tone save and load roundtrip`() {
        SkinToneStore.save(context, EmojiSkinTone.LIGHT)
        assertEquals(EmojiSkinTone.LIGHT, SkinToneStore.load(context))
    }

    @Test fun `skin tone load is case-insensitive and falls back to DARK`() {
        context.getSharedPreferences("ai_controller_skin_tone", Context.MODE_PRIVATE)
            .edit().putString("tone", "medium").apply()
        assertEquals(EmojiSkinTone.MEDIUM, SkinToneStore.load(context))
        context.getSharedPreferences("ai_controller_skin_tone", Context.MODE_PRIVATE)
            .edit().putString("tone", "bogus").apply()
        assertEquals(EmojiSkinTone.DARK, SkinToneStore.load(context))
    }

    // ---- KeyboardFontSizeStore ----

    @Test fun `font size defaults to 18sp`() {
        assertEquals(KeyboardFontSizeStore.DEFAULT_SP, KeyboardFontSizeStore.load(context))
    }

    @Test fun `font size save and load roundtrip`() {
        KeyboardFontSizeStore.save(context, 26f)
        assertEquals(26f, KeyboardFontSizeStore.load(context))
    }

    @Test fun `larger steps up and saturates at the max`() {
        assertEquals(22f, KeyboardFontSizeStore.larger(18f))
        assertEquals(22f, KeyboardFontSizeStore.larger(20f))
        assertEquals(34f, KeyboardFontSizeStore.larger(34f))
    }

    @Test fun `smaller steps down and saturates at the min`() {
        assertEquals(14f, KeyboardFontSizeStore.smaller(18f))
        assertEquals(18f, KeyboardFontSizeStore.smaller(20f))
        assertEquals(14f, KeyboardFontSizeStore.smaller(14f))
    }

    @Test fun `transform BIG renders old english`() {
        val out = TextStyles.transform("Hi", TextStyles.Mode.BIG)
        assertTrue(out.isNotBlank())
        assertNotEquals("Hi", out)
    }
}
