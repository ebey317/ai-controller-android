package com.ai.controller

import android.graphics.PointF
import android.graphics.Rect
import com.ai.controller.models.SwipeDirection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for the pure helpers extracted from ControllerAccessibilityService:
 * swipe end-point math (dispatchSwipe), D-pad hat edge detection
 * (handleGenericMotion), stick-scroll direction (maybeDispatchStickScroll), WAV
 * packing (writeWavToCache), caret index/range math (caretIndex/caretRange), and
 * the FieldIdentity structural fingerprint (clearOnFreshFocus).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceLogicTest {

    // ── swipeEndPoint ─────────────────────────────────────────────────────

    @Test
    fun `swipeEndPoint moves up by distance`() {
        val end = swipeEndPoint(PointF(500f, 500f), SwipeDirection.UP, 300f, 1080, 2400)
        assertEquals(500f, end.x, 0.001f)
        assertEquals(200f, end.y, 0.001f)
    }

    @Test
    fun `swipeEndPoint moves down by distance`() {
        val end = swipeEndPoint(PointF(500f, 500f), SwipeDirection.DOWN, 300f, 1080, 2400)
        assertEquals(500f, end.x, 0.001f)
        assertEquals(800f, end.y, 0.001f)
    }

    @Test
    fun `swipeEndPoint moves left by distance`() {
        val end = swipeEndPoint(PointF(500f, 500f), SwipeDirection.LEFT, 300f, 1080, 2400)
        assertEquals(200f, end.x, 0.001f)
        assertEquals(500f, end.y, 0.001f)
    }

    @Test
    fun `swipeEndPoint moves right by distance`() {
        val end = swipeEndPoint(PointF(500f, 500f), SwipeDirection.RIGHT, 300f, 1080, 2400)
        assertEquals(800f, end.x, 0.001f)
        assertEquals(500f, end.y, 0.001f)
    }

    @Test
    fun `swipeEndPoint clamps to screen edges`() {
        val top = swipeEndPoint(PointF(100f, 50f), SwipeDirection.UP, 300f, 1080, 2400)
        assertEquals(0f, top.y, 0.001f)
        val left = swipeEndPoint(PointF(50f, 500f), SwipeDirection.LEFT, 300f, 1080, 2400)
        assertEquals(0f, left.x, 0.001f)
        val bottom = swipeEndPoint(PointF(500f, 2300f), SwipeDirection.DOWN, 300f, 1080, 2400)
        assertEquals(2400f, bottom.y, 0.001f)
        val right = swipeEndPoint(PointF(1000f, 500f), SwipeDirection.RIGHT, 300f, 1080, 2400)
        assertEquals(1080f, right.x, 0.001f)
    }

    // ── detectHatEdges ────────────────────────────────────────────────────

    @Test
    fun `hat edges detect left rising edge`() {
        val e = detectHatEdges(-1f, 0f)
        assertTrue(e.leftRising)
        assertFalse(e.rightRising)
        assertFalse(e.leftFalling)
        assertFalse(e.rightFalling)
    }

    @Test
    fun `hat edges detect right rising edge`() {
        val e = detectHatEdges(1f, 0f)
        assertTrue(e.rightRising)
        assertFalse(e.leftRising)
        assertFalse(e.leftFalling)
        assertFalse(e.rightFalling)
    }

    @Test
    fun `hat edges detect falling edges`() {
        val leftRelease = detectHatEdges(0f, -1f)
        assertTrue(leftRelease.leftFalling)
        assertFalse(leftRelease.leftRising)
        val rightRelease = detectHatEdges(0f, 1f)
        assertTrue(rightRelease.rightFalling)
        assertFalse(rightRelease.rightRising)
    }

    @Test
    fun `hat edges never fire on the NaN-initialized first tick`() {
        // lastHatX starts as NaN so a D-pad already held at service start
        // never registers a spurious rising edge.
        val heldLeft = detectHatEdges(-1f, Float.NaN)
        assertFalse(heldLeft.leftRising)
        assertFalse(heldLeft.leftFalling)
        val heldRight = detectHatEdges(1f, Float.NaN)
        assertFalse(heldRight.rightRising)
        assertFalse(heldRight.rightFalling)
    }

    @Test
    fun `hat edges do not refire while held`() {
        val stillHeld = detectHatEdges(-1f, -1f)
        assertFalse(stillHeld.leftRising)
        assertFalse(stillHeld.leftFalling)
    }

    @Test
    fun `hat edges respect the half-deflection boundary`() {
        assertFalse(detectHatEdges(-0.5f, 0f).leftRising)
        assertFalse(detectHatEdges(0.5f, 0f).rightRising)
        assertTrue(detectHatEdges(-0.51f, 0f).leftRising)
    }

    // ── stickScrollDirection ──────────────────────────────────────────────

    @Test
    fun `stick scroll direction follows sign of Y`() {
        assertEquals(SwipeDirection.UP, stickScrollDirection(-0.7f))
        assertEquals(SwipeDirection.DOWN, stickScrollDirection(0.7f))
        assertEquals(SwipeDirection.DOWN, stickScrollDirection(0f))
    }

    // ── pcmToWavBytes ─────────────────────────────────────────────────────

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(offset).toInt()

    @Test
    fun `wav bytes have a valid 44-byte header`() {
        val pcm = ByteArray(3200) { (it % 256).toByte() }
        val wav = pcmToWavBytes(pcm, 16000)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVEfmt ", String(wav, 8, 8, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size + 36, leInt(wav, 4)) // RIFF chunk size
        assertEquals(16, leShort(wav, 16)) // fmt chunk size
        assertEquals(1, leShort(wav, 20)) // PCM format
        assertEquals(1, leShort(wav, 22)) // mono
        assertEquals(16000, leInt(wav, 24)) // sample rate
        assertEquals(16000 * 2, leInt(wav, 28)) // byte rate
        assertEquals(2, leShort(wav, 32)) // block align
        assertEquals(16, leShort(wav, 34)) // bits per sample
        assertEquals(pcm.size, leInt(wav, 40)) // data chunk size
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun `wav bytes encode the sample rate in little endian`() {
        val wav = pcmToWavBytes(ByteArray(100), 44100)
        assertEquals(44100, leInt(wav, 24))
        assertEquals(44100 * 2, leInt(wav, 28))
    }

    @Test
    fun `wav bytes handle empty pcm`() {
        val wav = pcmToWavBytes(ByteArray(0), 16000)
        assertEquals(44, wav.size)
        assertEquals(36, leInt(wav, 4))
        assertEquals(0, leInt(wav, 40))
    }

    // ── clampSelectionIndex / selectionRange ──────────────────────────────

    @Test
    fun `clampSelectionIndex passes through in-range values`() {
        assertEquals(3, clampSelectionIndex(3, 10))
        assertEquals(0, clampSelectionIndex(0, 10))
        assertEquals(10, clampSelectionIndex(10, 10))
    }

    @Test
    fun `clampSelectionIndex falls back to end when out of range`() {
        assertEquals(10, clampSelectionIndex(-1, 10))
        assertEquals(10, clampSelectionIndex(11, 10))
    }

    @Test
    fun `selectionRange keeps valid ranges and orders them`() {
        assertEquals(2 to 5, selectionRange(2, 5, 10))
        assertEquals(2 to 5, selectionRange(5, 2, 10)) // reversed selection normalized
        assertEquals(4 to 4, selectionRange(4, 4, 10)) // collapsed caret
    }

    @Test
    fun `selectionRange collapses to end when invalid`() {
        assertEquals(10 to 10, selectionRange(-1, 5, 10))
        assertEquals(10 to 10, selectionRange(2, 99, 10))
        assertEquals(10 to 10, selectionRange(-1, -1, 10))
    }

    // ── FieldIdentity ─────────────────────────────────────────────────────

    @Test
    fun `FieldIdentity compares structurally`() {
        val a = ControllerAccessibilityService.FieldIdentity(1, "com.example", "id/field", Rect(0, 0, 100, 50))
        val b = ControllerAccessibilityService.FieldIdentity(1, "com.example", "id/field", Rect(0, 0, 100, 50))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `FieldIdentity distinguishes different fields`() {
        val a = ControllerAccessibilityService.FieldIdentity(1, "com.example", "id/field", Rect(0, 0, 100, 50))
        assertNotEquals(a, a.copy(windowId = 2))
        assertNotEquals(a, a.copy(packageName = "com.other"))
        assertNotEquals(a, a.copy(viewIdResourceName = null))
        assertNotEquals(a, a.copy(boundsInScreen = Rect(0, 0, 100, 60)))
    }
}
