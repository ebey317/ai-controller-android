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
 *
 * Thread safety: the TTS init callback can run on a binder thread while
 * speak()/shutdown() run on the main thread. All [ready]/[pendingUtterances]
 * access goes through [lock]; [initFailed] is @Volatile so a failed engine
 * stops queue growth instead of leaking memory utterance by utterance.
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

    private val lock = Any()
    private var ready = false
    private val pendingUtterances = ArrayDeque<String>()

    /** Set once when the engine reports failure; stops unbounded queue growth. */
    @Volatile
    private var initFailed = false

    init {
        tts = TextToSpeech(appContext) { status ->
            synchronized(lock) {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    applyActivePackLocked()
                    // QUEUE_ADD, not the live-call QUEUE_FLUSH default — draining the
                    // backlog must preserve order instead of each item flushing (and
                    // discarding) the ones queued before it.
                    while (pendingUtterances.isNotEmpty()) {
                        speakInternal(pendingUtterances.removeFirst(), TextToSpeech.QUEUE_ADD)
                    }
                } else {
                    initFailed = true
                    pendingUtterances.clear()
                    Log.w(TAG, "TextToSpeech engine failed to initialize (status=$status); " +
                            "dropping backlog and refusing new utterances")
                }
            }
        }
    }

    fun packs(): List<VoicePack> = PACKS

    fun activePackId(): String = prefs.getString(KEY_ACTIVE_PACK, PACKS.first().id) ?: PACKS.first().id

    fun setActivePack(id: String) {
        if (PACKS.none { it.id == id }) return
        prefs.edit().putString(KEY_ACTIVE_PACK, id).apply()
        synchronized(lock) { applyActivePackLocked() }
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
        synchronized(lock) {
            if (initFailed) {
                Log.w(TAG, "speak() dropped: TTS engine failed to initialize")
                return
            }
            if (!ready) {
                // Bounded backlog: a never-initializing engine must not grow
                // memory without limit. Drop the oldest when full.
                if (pendingUtterances.size >= MAX_PENDING) {
                    pendingUtterances.removeFirst()
                    Log.w(TAG, "speak() backlog full ($MAX_PENDING); dropping oldest utterance")
                }
                pendingUtterances.addLast(text)
                return
            }
            speakInternal(text)
        }
    }

    fun stopSpeaking() {
        synchronized(lock) { tts?.stop() }
    }

    private fun speakInternal(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        val engine = tts ?: return
        val utteranceId = "ai_controller_${System.currentTimeMillis()}"
        val result = engine.speak(text, queueMode, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "TextToSpeech.speak() returned ERROR for utterance $utteranceId " +
                    "(engine may be missing data or disconnected)")
        }
    }

    /** Must be called with [lock] held. */
    private fun applyActivePackLocked() {
        val pack = activePack()
        val engine = tts ?: return
        if (!trySetLanguage(engine, pack.locale)) {
            // Requested locale unusable — fall back to guaranteed options before
            // giving up, or the voice may be silent on an engine default.
            if (!trySetLanguage(engine, Locale.US)) {
                trySetLanguage(engine, Locale.getDefault())
            }
        }
        engine.setPitch(pack.pitch)
        engine.setSpeechRate(pack.rate)
    }

    /** Returns true if the engine accepted a usable language. */
    private fun trySetLanguage(engine: TextToSpeech, locale: Locale): Boolean {
        val result = engine.setLanguage(locale)
        return when (result) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> true
            else -> {
                Log.w(TAG, "Locale $locale unsupported by device TTS engine " +
                        "(result=$result); trying fallback")
                false
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            tts?.stop()
            tts?.shutdown()
            tts = null
            // Full reset: after shutdown, ready=false so speak() queues nothing
            // into a dead engine, and the backlog is dropped, not drained into null.
            ready = false
            initFailed = false
            pendingUtterances.clear()
        }
    }

    companion object {
        private const val TAG = "VoiceManager"
        private const val PREFS_NAME = "ai_controller_voice"
        private const val KEY_ACTIVE_PACK = "active_pack"

        /** Backlog cap while the engine is still initializing. */
        private const val MAX_PENDING = 20

        // Two presets, mirroring the desktop's aria/joe pair — a brighter, higher
        // pitched voice and a flatter, lower one — built from the platform engine
        // instead of a network call or a bundled Piper model.
        private val PACKS = listOf(
            VoicePack("aria", "Aria", Locale.US, pitch = 1.05f, rate = 1.0f),
            VoicePack("joe", "Joe", Locale.US, pitch = 0.85f, rate = 0.95f)
        )
    }
}
