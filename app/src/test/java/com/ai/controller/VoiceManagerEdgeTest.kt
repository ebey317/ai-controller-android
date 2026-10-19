package com.ai.controller

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Final-push coverage for [VoiceManager]: the bounded-backlog drop-oldest
 * branch, the TextToSpeech.ERROR result branch, and the
 * LANG_COUNTRY_VAR_AVAILABLE acceptance branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceManagerEdgeTest {

    private fun manager() = VoiceManager(RuntimeEnvironment.getApplication())

    private fun setTts(vm: VoiceManager, tts: TextToSpeech) {
        VoiceManager::class.java.getDeclaredField("tts").apply { isAccessible = true }.set(vm, tts)
    }

    private fun setReady(vm: VoiceManager, ready: Boolean) {
        VoiceManager::class.java.getDeclaredField("ready").apply { isAccessible = true }
            .setBoolean(vm, ready)
    }

    private fun maxPending(): Int =
        VoiceManager::class.java.getDeclaredField("MAX_PENDING").apply { isAccessible = true }
            .getInt(null)

    private fun pendingOf(vm: VoiceManager): ArrayDeque<String> {
        val field = VoiceManager::class.java.getDeclaredField("pendingUtterances")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(vm) as ArrayDeque<String>
    }

    /** Fake engine with scripted speak/setLanguage results. */
    private class FakeTts(context: Context) : TextToSpeech(context, null) {
        var speakResult: Int = TextToSpeech.SUCCESS
        var languageResult: Int = TextToSpeech.LANG_AVAILABLE

        override fun speak(text: CharSequence?, queueMode: Int, params: Bundle?, utteranceId: String?): Int =
            speakResult

        override fun setLanguage(locale: Locale?): Int = languageResult
    }

    @Test
    fun `speak drops the oldest utterance when the backlog is full`() {
        val vm = manager() // engine init never fired: not ready, not failed
        val max = maxPending()
        repeat(max + 1) { vm.speak("utterance $it") }
        val pending = pendingOf(vm)
        assertEquals(max, pending.size)
        assertEquals("utterance 1", pending.first())
        assertEquals("utterance $max", pending.last())
    }

    @Test
    fun `speak tolerates an engine ERROR result`() {
        val vm = manager()
        val fake = FakeTts(RuntimeEnvironment.getApplication())
        fake.speakResult = TextToSpeech.ERROR
        setTts(vm, fake)
        setReady(vm, true)
        vm.speak("hello") // must not throw; the ERROR branch logs
    }

    @Test
    fun `active pack applies when the engine reports LANG_COUNTRY_VAR_AVAILABLE`() {
        val vm = manager()
        val fake = FakeTts(RuntimeEnvironment.getApplication())
        fake.languageResult = TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        setTts(vm, fake)
        val m = VoiceManager::class.java.getDeclaredMethod("applyActivePackLocked")
            .apply { isAccessible = true }
        m.invoke(vm) // must not throw
    }
}
