package com.ai.controller

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * On-device TTS voice packs — the Android analogue of ai-controller's
 * voice_manager.py plus the Piper/Edge-TTS voice pair in voice_bridge.py.
 * Android has no bundled Piper model and the project must not reach any
 * network endpoint besides Groq Whisper, so both packs here are presets
 * (locale/pitch/rate) over the platform TextToSpeech engine — fully
 * offline-capable — rather than a network TTS call.
 */
class VoiceManager(context: Context) {

    data class VoicePack(
        val id: String,
        val displayName: String,
        val locale: Locale,
        val pitch: Float,
        val rate: Float
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingUtterances = ArrayDeque<String>()

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                applyActivePack()
                // QUEUE_ADD, not the live-call QUEUE_FLUSH default — draining the
                // backlog must preserve order instead of each item flushing (and
                // discarding) the ones queued before it.
                while (pendingUtterances.isNotEmpty()) {
                    speakInternal(pendingUtterances.removeFirst(), TextToSpeech.QUEUE_ADD)
                }
            } else {
                Log.w(TAG, "TextToSpeech engine failed to initialize (status=$status)")
            }
        }
    }

    fun packs(): List<VoicePack> = PACKS

    fun activePackId(): String = prefs.getString(KEY_ACTIVE_PACK, PACKS.first().id) ?: PACKS.first().id

    fun setActivePack(id: String) {
        if (PACKS.none { it.id == id }) return
        prefs.edit().putString(KEY_ACTIVE_PACK, id).apply()
        applyActivePack()
    }

    /** Cycles to the next voice pack and returns it, mirroring voice_toggle.toggle(). */
    fun nextPack(): VoicePack {
        val ids = PACKS.map { it.id }
        val idx = ids.indexOf(activePackId())
        val next = PACKS[(idx + 1) % PACKS.size]
        setActivePack(next.id)
        return next
    }

    fun activePack(): VoicePack = PACKS.firstOrNull { it.id == activePackId() } ?: PACKS.first()

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            pendingUtterances.addLast(text)
            return
        }
        speakInternal(text)
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    private fun speakInternal(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        tts?.speak(text, queueMode, null, "ai_controller_${System.currentTimeMillis()}")
    }

    private fun applyActivePack() {
        val pack = activePack()
        val engine = tts ?: return
        val result = engine.setLanguage(pack.locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Locale ${pack.locale} unsupported by device TTS engine; using engine default")
        }
        engine.setPitch(pack.pitch)
        engine.setSpeechRate(pack.rate)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "VoiceManager"
        private const val PREFS_NAME = "ai_controller_voice"
        private const val KEY_ACTIVE_PACK = "active_pack"

        // Two presets, mirroring the desktop's aria/joe pair — a brighter, higher
        // pitched voice and a flatter, lower one — built from the platform engine
        // instead of a network call or a bundled Piper model.
        private val PACKS = listOf(
            VoicePack("aria", "Aria", Locale.US, pitch = 1.05f, rate = 1.0f),
            VoicePack("joe", "Joe", Locale.US, pitch = 0.85f, rate = 0.95f)
        )
    }
}
