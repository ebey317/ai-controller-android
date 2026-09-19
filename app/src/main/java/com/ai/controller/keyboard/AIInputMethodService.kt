package com.ai.controller.keyboard
import android.util.Log

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.ai.controller.ControllerAccessibilityService
import com.ai.controller.EmojiSkinTone
import com.ai.controller.KeyboardFontSizeStore
import com.ai.controller.PinStore
import com.ai.controller.PttModeStore
import com.ai.controller.SkinToneStore
import com.ai.controller.TextStyles

/**
 * Real Android keyboard overlay — the IME analogue of ai-controller's slide_keyboard.py,
 * and the replacement for [com.ai.controller.ui.KeyboardActivity] in the controller flow
 * (that Activity is Android-15-background-launch-blocked and, worse, is fundamentally
 * the wrong shape: a fullscreen Activity steals focus from the field you're typing
 * into, whereas an InputMethodService overlays it like any other keyboard).
 *
 * Text goes through [android.view.inputmethod.InputConnection] (commitText /
 * deleteSurroundingText), not the accessibility-node injection ControllerAccessibilityService
 * uses for voice dictation — this is a normal IME with normal IME semantics. Style
 * transforms reuse [TextStyles] directly: per-character keys go through the character
 * maps (toCursive/toBold/toOldEnglish) since the whole-utterance keyword-emoji/casual
 * pipeline in [TextStyles.transform] only makes sense applied to a whole phrase, which
 * is exactly what pins are — so pins go through [TextStyles.transform] itself.
 */
class AIInputMethodService : InputMethodService() {

    private var shiftOn = false
    private lateinit var keyGrid: LinearLayout
    private lateinit var pinsRow: LinearLayout
    private lateinit var styleButtons: Map<TextStyles.Mode, Button>
    private var cachedMode: TextStyles.Mode = TextStyles.Mode.PRO
    private var fontSizeSp: Float = KeyboardFontSizeStore.DEFAULT_SP
    private var allButtons: MutableList<Button> = mutableListOf()
    private var currentEditorInfo: android.view.inputmethod.EditorInfo? = null

    private val rowsLower = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )

    private val punctuationRow = listOf(",", ".", "?", "!", "'", "-", "/", ":", ";", "@")

    override fun onCreate() {
        super.onCreate()
        TextStyles.setSkinTone(SkinToneStore.load(this))
    }

    override fun onEvaluateFullscreenMode(): Boolean = false // overlay, never fullscreen

    override fun onCreateInputView(): View {
        Log.d("AIInputMethodService", "onCreateInputView")
        cachedMode = PttModeStore.load(this)
        fontSizeSp = KeyboardFontSizeStore.load(this)
        allButtons.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D12"))
            setPadding(16, 16, 16, 16)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        root.addView(buildFontSizeRow())
        root.addView(buildStyleAndToneRow())
        pinsRow = buildPinsRow()
        refreshPinsRow()
        root.addView(pinsRow)
        keyGrid = buildKeyGrid()
        root.addView(keyGrid)
        root.addView(buildBottomRow())

        return root
    }

    override fun onWindowShown() {
        super.onWindowShown()
        Log.d("AIInputMethodService", "onWindowShown")
    }

    private fun buildFontSizeRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.setPadding(0, 0, 0, 16)
        row.addView(styledButton("A-").apply {
            setBackgroundColor(Color.parseColor("#1A1A22"))
            setOnClickListener { changeFontSize(KeyboardFontSizeStore.smaller(fontSizeSp)) }
        })
        row.addView(styledButton("A+").apply {
            setBackgroundColor(Color.parseColor("#1A1A22"))
            setOnClickListener { changeFontSize(KeyboardFontSizeStore.larger(fontSizeSp)) }
        })
        return row
    }

    private fun buildStyleAndToneRow(): HorizontalScrollView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = mutableMapOf<TextStyles.Mode, Button>()
        for (mode in TextStyles.Mode.values()) {
            val btn = styledButton(styleLabel(mode)).apply {
                setOnClickListener {
                    PttModeStore.save(this@AIInputMethodService, mode)
                    cachedMode = mode
                    refreshStyleButtons()
                }
            }
            buttons[mode] = btn
            row.addView(btn)
        }
        styleButtons = buttons
        row.addView(styledButton("🎨").apply {
            setOnClickListener { showSkinTonePopup(it) }
        })
        refreshStyleButtons()
        return HorizontalScrollView(this).apply { addView(row) }
    }

    private fun styleLabel(mode: TextStyles.Mode): String = when (mode) {
        TextStyles.Mode.PRO -> "PRO"
        TextStyles.Mode.BUBBLY -> "✨ " + TextStyles.toCursive("Cursive")
        TextStyles.Mode.CASUAL -> "☕ casual"
        TextStyles.Mode.BOLD -> TextStyles.toBold("Bold")
        TextStyles.Mode.BIG -> TextStyles.toOldEnglish("Old-E")
    }

    private fun refreshStyleButtons() {
        val active = cachedMode
        for ((mode, btn) in styleButtons) {
            btn.setBackgroundColor(Color.parseColor(if (mode == active) "#3DDC97" else "#23232B"))
            btn.setTextColor(Color.parseColor(if (mode == active) "#0D0D12" else "#E8E8E8"))
        }
    }

    private fun showSkinTonePopup(anchor: View) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A22"))
            setPadding(12, 12, 12, 12)
        }
        val popup = PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        for (tone in EmojiSkinTone.entries) {
            list.addView(styledButton(tone.label).apply {
                setOnClickListener {
                    SkinToneStore.save(this@AIInputMethodService, tone)
                    TextStyles.setSkinTone(tone)
                    popup.dismiss()
                }
            })
        }
        popup.showAsDropDown(anchor)
    }

    

    // ── Pinned snippets ──────────────────────────────────────────────────

    private fun buildPinsRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        return row
    }

    private fun refreshPinsRow() {
        val row = pinsRow
        row.removeAllViews()
        val pins = PinStore.load(this)
        for (index in 0 until pins.length()) {
            val pin = pins.getJSONObject(index)
            val label = pin.optString("label", "?").take(10)
            row.addView(pinButton(label).apply {
                setOnClickListener { commitStyledText(pin.optString("text", "")) }
                setOnLongClickListener {
                    PinStore.removeAt(this@AIInputMethodService, index)
                    refreshPinsRow()
                    true
                }
            })
        }
    }

    // ── QWERTY grid ──────────────────────────────────────────────────────

    private fun buildKeyGrid(): LinearLayout {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        populateKeyGrid(grid)
        return grid
    }

    private fun populateKeyGrid(grid: LinearLayout) {
        grid.removeAllViews()
        for (row in rowsLower) {
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) {
                val label = if (shiftOn) key.uppercase() else key
                rowView.addView(styledButton(label).apply {
                    layoutParams = LinearLayout.LayoutParams(80, 80)
                    setOnClickListener {
                        commitStyledChar(label)
                        if (shiftOn) {
                            shiftOn = false
                            populateKeyGrid(grid)
                        }
                    }
                })
            }
            grid.addView(rowView)
        }
        val punctRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (symbol in punctuationRow) {
            punctRow.addView(styledButton(symbol).apply {
                layoutParams = LinearLayout.LayoutParams(80, 80)
                setOnClickListener { commitStyledChar(symbol) }
            })
        }
        grid.addView(punctRow)
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(styledButton("⇧").apply {
            setOnClickListener {
                shiftOn = !shiftOn
                populateKeyGrid(keyGrid)
            }
        })
        row.addView(styledButton("space").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        })
        row.addView(styledButton("⌫").apply {
            setOnClickListener { currentInputConnection?.deleteSurroundingText(1, 0) }
        })
        row.addView(styledButton("⏎").apply {
            setOnClickListener {
                val ic = currentInputConnection ?: return@setOnClickListener
                val ei = currentEditorInfo
                val actionId = ei?.let { it.imeOptions and EditorInfo.IME_MASK_ACTION } ?: EditorInfo.IME_ACTION_DONE
                val supportsAction = actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED
                if (supportsAction && ic.performEditorAction(actionId)) {
                    return@setOnClickListener
                }
                ic.commitText("\n", 1)
            }
        })
        row.addView(voiceButton())
        return row
    }

    /** Hold-to-talk mic key: routes through the exact same PttController edges as a
     * controller trigger (ControllerAccessibilityService.pttDownFromKeyboard/Up),
     * so recording/consent/transcription/debug-overlay behavior is identical to the
     * gamepad path — the service still owns the mic, Groq call, and text injection. */
    private fun voiceButton(): Button = styledButton("🎤").apply {
        setTextColor(Color.parseColor("#3DDC97"))
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ControllerAccessibilityService.instance?.pttDownFromKeyboard()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ControllerAccessibilityService.instance?.pttUpFromKeyboard()
                    true
                }
                else -> false
            }
        }
    }

    // ── Styled commit helpers ────────────────────────────────────────────

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d("AIInputMethodService", "onStartInputView restarting=$restarting")
        currentEditorInfo = info
        cachedMode = PttModeStore.load(this)
        refreshPinsRow()
    }

    private fun currentMode(): TextStyles.Mode = cachedMode

    private fun commitStyledChar(ch: String) {
        val ic = currentInputConnection ?: return
        val styled = when (currentMode()) {
            TextStyles.Mode.BUBBLY -> TextStyles.toCursive(ch)
            TextStyles.Mode.BOLD -> TextStyles.toBold(ch)
            TextStyles.Mode.BIG -> TextStyles.toOldEnglish(ch)
            TextStyles.Mode.CASUAL -> ch.lowercase()
            TextStyles.Mode.PRO -> ch
        }
        ic.commitText(styled, 1)
    }

    /** Pins commit through the full [TextStyles.transform] pipeline (keyword emoji +
     * casual boost + character mapping) since a pin is a whole phrase, not one key. */
    private fun commitStyledText(text: String) {
        currentInputConnection?.commitText(TextStyles.transform(text, currentMode()), 1)
    }

    private fun changeFontSize(sp: Float) {
        if (sp == fontSizeSp) return
        fontSizeSp = sp
        KeyboardFontSizeStore.save(this, sp)
        for (btn in allButtons) {
            btn.textSize = sp
        }
    }

    // ── Shared styling ───────────────────────────────────────────────────

    private fun styledButton(label: String): Button = Button(this).apply {
        text = label
        setTextColor(Color.parseColor("#E8E8E8"))
        setBackgroundColor(Color.parseColor("#23232B"))
        setPadding(10, 10, 10, 10)
        isAllCaps = false
        textSize = fontSizeSp
        allButtons.add(this)
    }

    private fun pinButton(label: String): Button = styledButton(label).apply {
        setTextColor(Color.parseColor("#3DDC97"))
        setBackgroundColor(Color.parseColor("#0F2A24"))
        gravity = Gravity.CENTER
    }
}
