package com.ai.controller.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ai.controller.ControllerAccessibilityService
import com.ai.controller.PttModeStore
import com.ai.controller.R
import com.ai.controller.TextStyles
import org.json.JSONArray
import org.json.JSONObject

/**
 * Floating-keyboard replacement, built as a regular Activity rather than an
 * IME: the Android analogue of ai-controller's slide_keyboard.py. Every key
 * routes through ControllerAccessibilityService's accessibility-node text
 * injection (see typeCharacter/typeText/backspaceOnce) instead of an X11
 * xdotool call, since that's the only text-injection path available to a
 * standard AccessibilityService without INJECT_EVENTS.
 *
 * Ships the same style toggle as the desktop keyboard (PRO/BUBBLY/CASUAL/
 * BOLD/BIG, see [TextStyles]) plus 5 pinned-snippet slots (vs. the desktop's
 * 7 — chosen to fit comfortably in one row on a phone-width grid) persisted
 * across launches.
 */
class KeyboardActivity : AppCompatActivity() {

    private lateinit var pinsRow: LinearLayout
    private lateinit var modeButton: Button
    private var shiftOn = false
    private lateinit var keyGrid: GridLayout

    private val rowsLower = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.keyboard_title)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D12"))
            setPadding(24, 24, 24, 24)
        }

        root.addView(buildModeAndVoiceRow())
        pinsRow = buildPinsRow()
        root.addView(pinsRow)
        keyGrid = buildKeyGrid()
        root.addView(keyGrid)
        root.addView(buildBottomRow())

        setContentView(root)
    }

    // ── Mode row ─────────────────────────────────────────────────────────

    private fun buildModeAndVoiceRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, 16)
        }
        modeButton = styledButton(modeLabel(PttModeStore.load(this))).apply {
            setOnClickListener { onModeCycle() }
        }
        row.addView(modeButton)
        return row
    }

    private fun modeLabel(mode: TextStyles.Mode): String = when (mode) {
        TextStyles.Mode.PRO -> "PRO"
        TextStyles.Mode.BUBBLY -> "✨ " + TextStyles.toCursive("Cursive")
        TextStyles.Mode.CASUAL -> "☕ casual"
        TextStyles.Mode.BOLD -> TextStyles.toBold("Bold")
        TextStyles.Mode.BIG -> TextStyles.toOldEnglish("Old-E")
    }

    private fun onModeCycle() {
        val next = TextStyles.next(PttModeStore.load(this))
        PttModeStore.save(this, next)
        modeButton.text = modeLabel(next)
    }

    // ── Pinned snippets (5 slots) ────────────────────────────────────────

    private fun buildPinsRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, 16)
        }
        refreshPinsRow(row)
        return row
    }

    private fun refreshPinsRow(row: LinearLayout) {
        row.removeAllViews()
        val pins = loadPins()
        for (index in 0 until pins.length()) {
            val pin = pins.getJSONObject(index)
            val label = pin.optString("label", "?").take(10)
            val btn = pinButton(label).apply {
                setOnClickListener {
                    ControllerAccessibilityService.instance?.typeText(pin.optString("text", ""))
                }
                setOnLongClickListener {
                    removePin(index)
                    refreshPinsRow(row)
                    Toast.makeText(this@KeyboardActivity, "Unpinned $label", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            row.addView(btn)
        }
        if (pins.length() < PIN_SLOTS) {
            row.addView(pinButton(getString(R.string.keyboard_pin_add)).apply {
                setOnClickListener { onPinAdd(row) }
            })
        }
    }

    private fun onPinAdd(row: LinearLayout) {
        val pins = loadPins()
        if (pins.length() >= PIN_SLOTS) {
            Toast.makeText(this, R.string.keyboard_pin_full, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.keyboard_pin_empty_clipboard, Toast.LENGTH_SHORT).show()
            return
        }
        val label = if (text.length <= 10) text else text.take(9) + "…"
        val pin = JSONObject().put("label", label).put("text", text)
        pins.put(pin)
        savePins(pins)
        refreshPinsRow(row)
    }

    private fun loadPins(): JSONArray {
        val raw = prefs().getString(KEY_PINS, null) ?: return defaultPins()
        return runCatching { JSONArray(raw) }.getOrDefault(defaultPins())
    }

    private fun savePins(pins: JSONArray) {
        prefs().edit().putString(KEY_PINS, pins.toString()).apply()
    }

    private fun removePin(index: Int) {
        val pins = loadPins()
        val kept = JSONArray()
        for (i in 0 until pins.length()) if (i != index) kept.put(pins.get(i))
        savePins(kept)
    }

    private fun defaultPins(): JSONArray = JSONArray().apply {
        put(JSONObject().put("label", "hello").put("text", "Hello! "))
        put(JSONObject().put("label", "thanks").put("text", "Thank you! "))
        put(JSONObject().put("label", "email").put("text", "@"))
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    // ── QWERTY key grid ──────────────────────────────────────────────────

    private fun buildKeyGrid(): GridLayout {
        val grid = GridLayout(this).apply {
            columnCount = 10
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        populateKeyGrid(grid)
        return grid
    }

    private fun populateKeyGrid(grid: GridLayout) {
        grid.removeAllViews()
        for (row in rowsLower) {
            for (key in row) {
                val label = if (shiftOn) key.uppercase() else key
                grid.addView(styledButton(label).apply {
                    layoutParams = GridLayout.LayoutParams().apply { width = 90; height = 90 }
                    setOnClickListener {
                        ControllerAccessibilityService.instance?.typeCharacter(label)
                        if (shiftOn) {
                            shiftOn = false
                            populateKeyGrid(grid)
                        }
                    }
                })
            }
        }
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 16, 0, 0)
        }
        row.addView(styledButton("⇧").apply {
            setOnClickListener {
                shiftOn = !shiftOn
                populateKeyGrid(keyGrid)
            }
        })
        row.addView(styledButton("space").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { ControllerAccessibilityService.instance?.typeCharacter(" ") }
        })
        row.addView(styledButton("⌫").apply {
            setOnClickListener { ControllerAccessibilityService.instance?.backspaceOnce() }
        })
        row.addView(styledButton("⏎").apply {
            setOnClickListener { ControllerAccessibilityService.instance?.typeCharacter("\n") }
        })
        return row
    }

    // ── Shared styling ───────────────────────────────────────────────────

    private fun styledButton(label: String): Button = Button(this).apply {
        text = label
        setTextColor(Color.parseColor("#E8E8E8"))
        setBackgroundColor(Color.parseColor("#23232B"))
        setPadding(12, 12, 12, 12)
    }

    private fun pinButton(label: String): Button = styledButton(label).apply {
        setTextColor(Color.parseColor("#3DDC97"))
        setBackgroundColor(Color.parseColor("#0F2A24"))
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    companion object {
        private const val PREFS_NAME = "ai_controller_keyboard"
        private const val KEY_PINS = "pinned_snippets"
        private const val PIN_SLOTS = 5
    }
}
