package com.ai.controller

import android.Manifest
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import com.ai.controller.models.ButtonAction
import com.ai.controller.models.ControllerInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.io.File

/**
 * Push-to-talk voice flow: consent/permission gates, the AudioRecord-backed
 * recording coroutine (start/stop/cancel), the Groq transcription error paths,
 * and temp-file cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceRecordingTest {

    private lateinit var service: ControllerAccessibilityService
    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()

    private fun grantMic() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun cacheWavs(prefix: String): List<File> =
        service.cacheDir.listFiles { f -> f.name.startsWith(prefix) }?.toList() ?: emptyList()

    @Before
    fun setUp() {
        grantMic()
        silenceMicrophone()
        ConsentManager.setGranted(app, true)
        service = connectTestService()
        service.primePttDebounce()
    }

    @After
    fun tearDown() {
        ConsentManager.setGranted(app, false)
        ShadowAudioRecord.clearSource()
        service.onDestroy()
    }

    @Test
    fun `startVoiceRecording is a no-op when already recording`() {
        service.isRecording = true
        service.startVoiceRecording()
        assertNull(service.audioRecord)
        service.isRecording = false
    }

    @Test
    fun `startVoiceRecording requires consent`() {
        ConsentManager.setGranted(app, false)
        service.startVoiceRecording()
        assertFalse(service.isRecording)
        assertNull(service.audioRecord)
        assertFalse(service.pttController.isHeld)
    }

    @Test
    fun `startVoiceRecording requires the record-audio permission`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        service.startVoiceRecording()
        assertFalse(service.isRecording)
        assertNull(service.audioRecord)
        grantMic()
    }

    @Test
    fun `ptt down starts recording and ptt up stops it`() {
        service.pttDownFromKeyboard()
        waitForMain { service.isRecording }
        assertNotNull(service.audioRecord)

        service.pttUpFromKeyboard()
        waitForMain { service.voiceRecordJob?.isCompleted == true }
        assertFalse(service.isRecording)
        assertNull(service.audioRecord)
        // Transcription fails without an API key; the temp wav must still be gone.
        assertTrue(cacheWavs("voice_prompt_").isEmpty())
    }

    @Test
    fun `double ptt down cancels the in-flight recording`() {
        service.pttDownFromKeyboard()
        waitForMain { service.isRecording }
        service.pttDownFromKeyboard() // second down while held -> onCancel
        waitForMain { service.voiceRecordJob?.isCompleted == true }
        assertFalse(service.isRecording)
        assertFalse(service.pttController.isHeld)
    }

    @Test
    fun `stopVoiceRecording is a no-op when idle`() {
        service.stopVoiceRecording(cancel = false)
        service.stopVoiceRecording(cancel = true)
    }

    @Test
    fun `processRecording deletes the temp wav when transcription fails`() {
        runBlocking { service.processRecording(ByteArray(160), 16000) }
        assertTrue(cacheWavs("voice_prompt_").isEmpty())
    }

    @Test
    fun `transcribeBytesIfConsented returns empty without consent`() {
        ConsentManager.setGranted(app, false)
        val result = runBlocking { service.transcribeBytesIfConsented(byteArrayOf(1, 2, 3)) }
        assertEquals("", result)
    }

    @Test
    fun `transcribeBytesIfConsented cleans up the temp file on failure`() {
        val result = runBlocking { service.transcribeBytesIfConsented(byteArrayOf(1, 2, 3)) }
        assertEquals("", result) // no API key -> IllegalStateException -> caught
        assertTrue(cacheWavs("bridge_voice_").isEmpty())
    }

    @Test
    fun `hardStopVoiceRecording is safe when idle`() {
        service.hardStopVoiceRecording()
        assertFalse(service.isRecording)
    }

    @Test
    fun `digital voice-trigger button drives the PTT edges`() {
        service.profile.mappings[ControllerInput.BUTTON_A] = ButtonAction.voiceTrigger()
        val downAt = SystemClock.uptimeMillis()
        service.callOnKeyEvent(KeyEvent(downAt, downAt, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0))
        waitForMain { service.isRecording }
        assertTrue(service.pttController.isHeld)

        val upAt = downAt + 100
        service.callOnKeyEvent(KeyEvent(upAt, upAt, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 0))
        waitForMain { service.voiceRecordJob?.isCompleted == true }
        assertFalse(service.isRecording)
        assertFalse(service.pttController.isHeld)
    }

    @Test
    fun `consentGranted reflects the consent store`() {
        assertTrue(service.consentGranted())
        ConsentManager.setGranted(app, false)
        assertFalse(service.consentGranted())
    }

    @Test
    fun `reloadActiveProfile cancels a held PTT recording`() {
        service.pttDownFromKeyboard()
        waitForMain { service.isRecording }
        assertTrue(service.pttController.isHeld)
        service.reloadActiveProfile()
        waitForMain { service.voiceRecordJob?.isCompleted == true }
        // The recording is cancelled (never transcribed); the PTT edge state
        // itself is owned by the controller, not the reload path.
        assertFalse(service.isRecording)
    }

    @Test
    fun `recording auto-cancels after the maximum duration`() {
        service.startVoiceRecording()
        waitForMain { service.isRecording }
        // Advance the frozen shadow clock past the 30s safety net.
        idleMain(31_000)
        waitForMain { service.voiceRecordJob?.isCompleted == true }
        assertFalse(service.isRecording)
    }
}
