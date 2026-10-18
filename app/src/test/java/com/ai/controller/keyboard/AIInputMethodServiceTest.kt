package com.ai.controller.keyboard

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.Button
import com.ai.controller.KeyboardFontSizeStore
import com.ai.controller.PttModeStore
import com.ai.controller.TextStyles
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

private class RecordingInputConnection(target: InputConnection) : InputConnectionWrapper(target, true) {
    val committed = mutableListOf<String>()
    val deleted = mutableListOf<Pair<Int, Int>>()
    var editorAction: Int? = null

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        committed.add(text.toString())
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        deleted.add(beforeLength to afterLength)
        return true
    }

    override fun performEditorAction(editorActionCode: Int): Boolean {
        editorAction = editorActionCode
        return true
    }
}

private class TestIme : AIInputMethodService() {
    var connection: InputConnection? = null
    override fun getCurrentInputConnection(): InputConnection? = connection
}

/**
 * Tests for AIInputMethodService: fullscreen policy, the built key grid, styled
 * commits through a recording InputConnection, shift behavior, the enter-key
 * editor-action fallback, pinned snippets, and font-size persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AIInputMethodServiceTest {

    private lateinit var appContext: Context
    private lateinit var ime: TestIme
    private lateinit var ic: RecordingInputConnection
    private lateinit var inputView: View

    @Before
    fun setUp() {
        appContext = RuntimeEnvironment.getApplication()
        ime = Robolectric.buildService(TestIme::class.java).create().get()
        ic = RecordingInputConnection(BaseInputConnection(View(appContext), false))
        ime.connection = ic
        PttModeStore.save(appContext, TextStyles.Mode.PRO)
        inputView = ime.onCreateInputView()
    }

    private fun allButtons(): List<Button> {
        val out = mutableListOf<Button>()
        fun walk(v: View) {
            if (v is Button) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(inputView)
        return out
    }

    private fun buttonWithText(text: String): Button? =
        allButtons().firstOrNull { it.text.toString() == text }

    @Test
    fun `never uses fullscreen mode`() {
        assertFalse(ime.onEvaluateFullscreenMode())
    }

    @Test
    fun `input view has the full alphabet plus punctuation`() {
        val texts = allButtons().map { it.text.toString() }.toSet()
        for (ch in 'a'..'z') assertTrue("missing key $ch", texts.contains(ch.toString()))
        for (sym in listOf(",", ".", "?", "!", "'", "-", "/", ":", ";", "@")) {
            assertTrue("missing punctuation $sym", texts.contains(sym))
        }
    }

    @Test
    fun `letter key commits the plain character in PRO mode`() {
        buttonWithText("b")?.performClick() ?: error("b key not found")
        assertEquals(listOf("b"), ic.committed)
    }

    @Test
    fun `letter key commits styled text in BOLD mode`() {
        PttModeStore.save(appContext, TextStyles.Mode.BOLD)
        inputView = ime.onCreateInputView()
        buttonWithText("b")?.performClick() ?: error("b key not found")
        assertEquals(listOf(TextStyles.toBold("b")), ic.committed)
    }

    @Test
    fun `letter key lowercases in CASUAL mode`() {
        PttModeStore.save(appContext, TextStyles.Mode.CASUAL)
        inputView = ime.onCreateInputView()
        buttonWithText("⇧")?.performClick() ?: error("shift not found")
        buttonWithText("B")?.performClick() ?: error("B key not found")
        assertEquals(listOf("b"), ic.committed)
    }

    @Test
    fun `shift uppercases and clears after one keypress`() {
        buttonWithText("⇧")?.performClick() ?: error("shift not found")
        assertNotNull(buttonWithText("Q"))
        buttonWithText("Q")?.performClick() ?: error("Q key not found")
        assertEquals(listOf("Q"), ic.committed)
        assertNotNull(buttonWithText("q"))
    }

    @Test
    fun `space commits a space and backspace deletes`() {
        buttonWithText("space")?.performClick() ?: error("space not found")
        buttonWithText("⌫")?.performClick() ?: error("backspace not found")
        assertEquals(listOf(" "), ic.committed)
        assertEquals(listOf(1 to 0), ic.deleted)
    }

    @Test
    fun `enter performs the editor action when the field supports one`() {
        ime.onStartInputView(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_DONE }, false)
        buttonWithText("⏎")?.performClick() ?: error("enter not found")
        assertEquals(EditorInfo.IME_ACTION_DONE, ic.editorAction)
        assertTrue("no newline should be committed when the action runs", ic.committed.isEmpty())
    }

    @Test
    fun `enter falls back to newline when the field has no action`() {
        ime.onStartInputView(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_NONE }, false)
        buttonWithText("⏎")?.performClick() ?: error("enter not found")
        assertNull(ic.editorAction)
        assertEquals(listOf("\n"), ic.committed)
    }

    @Test
    fun `pin button commits the pin text`() {
        // default pins ship a "hello" -> "Hello! " entry; PRO mode passes text through.
        buttonWithText("hello")?.performClick() ?: error("hello pin not found")
        assertEquals(listOf("Hello! "), ic.committed)
    }

    @Test
    fun `font size buttons persist the new step`() {
        val before = KeyboardFontSizeStore.load(ime)
        buttonWithText("A+")?.performClick() ?: error("A+ not found")
        assertEquals(KeyboardFontSizeStore.larger(before), KeyboardFontSizeStore.load(ime), 0.001f)
    }
}
