package com.ai.controller

import android.speech.tts.TextToSpeech
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

/**
 * Crew B — Robolectric tests for [VoiceManager].
 *
 * The platform TTS engine is shadowed ([ShadowTextToSpeech]); the async
 * OnInitListener is fired manually through the shadow, which mirrors what the
 * real engine does on its binder thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceManagerTest {

    private fun manager() = VoiceManager(RuntimeEnvironment.getApplication())

    private fun engineOf(vm: VoiceManager): TextToSpeech {
        val field = VoiceManager::class.java.getDeclaredField("tts")
        field.isAccessible = true
        return field.get(vm) as TextToSpeech
    }

    private fun shadowOf(vm: VoiceManager): ShadowTextToSpeech =
        Shadows.shadowOf(engineOf(vm))

    private fun fireInit(vm: VoiceManager, status: Int) {
        shadowOf(vm).onInitListener.onInit(status)
    }

    private fun pendingOf(vm: VoiceManager): ArrayDeque<String> {
        val field = VoiceManager::class.java.getDeclaredField("pendingUtterances")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(vm) as ArrayDeque<String> // kotlin.collections.ArrayDeque
    }

    // ── Packs ────────────────────────────────────────────────────────────

    @Test
    fun `packs list contains aria and joe with distinct presets`() {
        val packs = manager().packs()
        assertEquals(2, packs.size)
        assertEquals(listOf("aria", "joe"), packs.map { it.id })
        assertEquals("Aria", packs[0].displayName)
        assertEquals("Joe", packs[1].displayName)
        assertTrue(packs[0].pitch > packs[1].pitch) // brighter vs flatter preset
        assertEquals(Locale.US, packs[0].locale)
    }

    @Test
    fun `default active pack is aria`() {
        val vm = manager()
        assertEquals("aria", vm.activePackId())
        assertEquals("aria", vm.activePack().id)
    }

    @Test
    fun `setActivePack with valid id persists`() {
        val vm = manager()
        vm.setActivePack("joe")
        assertEquals("joe", vm.activePackId())
        assertEquals(0.85f, vm.activePack().pitch, 0.0001f)
        // A fresh manager on the same app context reads the persisted value.
        assertEquals("joe", manager().activePackId())
    }

    @Test
    fun `setActivePack with unknown id is ignored`() {
        val vm = manager()
        vm.setActivePack("joe")
        vm.setActivePack("does-not-exist")
        assertEquals("joe", vm.activePackId())
    }

    @Test
    fun `nextPack cycles aria to joe and wraps back to aria`() {
        val vm = manager()
        val first = vm.nextPack()
        assertEquals("joe", first.id)
        assertEquals("joe", vm.activePackId())
        val second = vm.nextPack()
        assertEquals("aria", second.id)
        assertEquals("aria", vm.activePackId())
    }

    // ── speak() ──────────────────────────────────────────────────────────

    @Test
    fun `speak with blank text is dropped without touching the queue`() {
        val vm = manager()
        vm.speak("")
        vm.speak("   ")
        assertTrue(pendingOf(vm).isEmpty())
        fireInit(vm, TextToSpeech.SUCCESS)
        assertNull(shadowOf(vm).lastSpokenText)
    }

    @Test
    fun `speak before init queues and drains in order on success`() {
        val vm = manager()
        vm.speak("one")
        vm.speak("two")
        vm.speak("three")
        assertEquals(3, pendingOf(vm).size) // queued, engine not ready yet
        fireInit(vm, TextToSpeech.SUCCESS)
        assertTrue(pendingOf(vm).isEmpty())
        assertEquals(listOf("one", "two", "three"), shadowOf(vm).spokenTextList)
    }

    @Test
    fun `speak after successful init goes straight to the engine`() {
        val vm = manager()
        fireInit(vm, TextToSpeech.SUCCESS)
        vm.speak("hello")
        assertEquals("hello", shadowOf(vm).lastSpokenText)
        assertTrue(pendingOf(vm).isEmpty())
    }

    @Test
    fun `init failure drops backlog and refuses new utterances`() {
        val vm = manager()
        vm.speak("queued-before-failure")
        fireInit(vm, TextToSpeech.ERROR)
        assertTrue(pendingOf(vm).isEmpty()) // backlog dropped, not drained
        vm.speak("after-failure")
        assertTrue(pendingOf(vm).isEmpty()) // refused: engine will never init
        assertTrue(shadowOf(vm).spokenTextList.isEmpty()) // nothing reached TTS
    }

    @Test
    fun `stopSpeaking is safe before and after init`() {
        val vm = manager()
        vm.stopSpeaking() // engine exists but not initialized: no crash
        fireInit(vm, TextToSpeech.SUCCESS)
        vm.speak("hello")
        vm.stopSpeaking()
        assertTrue(shadowOf(vm).isStopped)
    }

    // ── shutdown() ───────────────────────────────────────────────────────

    @Test
    fun `shutdown stops engine clears backlog and blocks further speech`() {
        val vm = manager()
        vm.speak("queued")
        fireInit(vm, TextToSpeech.SUCCESS)
        vm.speak("spoken")
        val shadow = shadowOf(vm)
        assertEquals("spoken", shadow.lastSpokenText)

        vm.shutdown()
        assertTrue(shadow.isShutdown)

        vm.speak("after-shutdown")
        // ready=false and tts=null: nothing reaches the (dead) engine...
        assertEquals("spoken", shadow.lastSpokenText)
        // ...and the utterance is queued, not silently executed
        assertEquals(1, pendingOf(vm).size)
    }

    @Test
    fun `shutdown before init is safe and idempotent`() {
        val vm = manager()
        vm.speak("queued")
        vm.shutdown()
        vm.shutdown()
        assertTrue(pendingOf(vm).isEmpty())
    }
}
