package com.ai.controller

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast

/**
 * Floating on-screen keyboard, drawn directly by the accessibility service via
 * [WindowManager] — the same technique [CursorOverlay]/[LegendOverlay] already
 * use on this device. This is the real Android analogue of ai-controller's
 * slide_keyboard.py: a self-drawn floating panel with its own D-pad-independent
 * key grid, not an OS-registered input method.
 *
 * Two earlier attempts are still in the tree but no longer wired to the
 * controller's Kbd button:
 *  - [com.ai.controller.ui.KeyboardActivity]: a fullscreen Activity, which
 *    steals window focus from whatever field you were typing into.
 *  - [com.ai.controller.keyboard.AIInputMethodService]: a real system IME.
 *    Live-tested 2026-09-18: `switchToInputMethod()` and `setShowMode(AUTO)`
 *    both report success, but `dumpsys input_method`'s `mInputShown` stayed
 *    `false` except for one fleeting, unreproducible instant — the framework
 *    never reliably surfaces the input view even when every binding step
 *    succeeds. Not something worth chasing further versus this approach,
 *    which never depends on that arbitration at all.
 *
 * [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] (same flag CursorOverlay
 * uses) keeps this window from ever taking input focus, so the real target
 * field stays focused underneath and [ControllerAccessibilityService]'s
 * existing typeCharacter/typeText/backspaceOnce (which read
 * `rootInActiveWindow.findFocus`) keep working completely unmodified.
 */
class FloatingKeyboardOverlay(private val service: ControllerAccessibilityService) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var dragging = false
    private var lastX: Int? = null
    private var lastY: Int? = null
    private var fontSizeSp: Float = KeyboardFontSizeStore.DEFAULT_SP
    private var shiftOn = false
    private lateinit var pinsRow: LinearLayout
    private lateinit var keyGrid: LinearLayout
    private lateinit var dragHandle: Button
    private lateinit var styleButtons: Map<TextStyles.Mode, Button>

    private val rowsLower = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )

    /** Reported live 2026-09-19: "no slashes, no commas, no colons — full keyboard."
     * Letters-only was never a full keyboard; this is the punctuation a real one has,
     * on its own row so it doesn't crowd the QWERTY rows or need a shift-layer toggle. */
    private val punctuationRow = listOf(",", ".", "?", "!", "'", "-", "/", ":", ";", "@")

    fun isShowing(): Boolean = rootView != null

    fun toggle() {
        if (isShowing()) hide() else show()
    }

    /**
     * Finds the clickable view under (screenX, screenY) and clicks it directly, in code —
     * bypassing [android.accessibilityservice.AccessibilityService.dispatchGesture] entirely.
     * Live-tested 2026-09-18: a dispatched gesture at the cursor's exact on-screen position,
     * centered precisely on a key, produced nothing — no character typed, no log activity —
     * even though the same coordinate hit fine via a real touch (finger or `adb shell input
     * tap`). This looks like a platform restriction on a service dispatching a gesture to a
     * window it owns itself; direct hit-testing sidesteps the question entirely by never
     * going through the gesture pipeline for this window at all.
     */
    fun handleTapAt(screenX: Float, screenY: Float): Boolean {
        val root = rootView ?: return false
        val target = findClickableViewAt(root, screenX, screenY)
        val label = (target as? Button)?.text?.toString()
        val bounds = target?.let { screenBounds(it) }
        Log.d(TAG, "handleTapAt(${screenX.toInt()}, ${screenY.toInt()}) -> hit='${label ?: "NOTHING"}' bounds=$bounds")
        if (target == null) return false
        target.performClick()
        return true
    }

    private fun findClickableViewAt(view: View, x: Float, y: Float): View? {
        if (view.visibility != View.VISIBLE) return null
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val hit = findClickableViewAt(view.getChildAt(i), x, y)
                if (hit != null) return hit
            }
        }
        if (!view.isClickable) return null
        return if (screenBounds(view).contains(x.toInt(), y.toInt())) view else null
    }

    /**
     * True screen-space bounds of [view]. Reported live 2026-09-18 as "I press one letter
     * and it prints something different": the previous implementation used
     * getGlobalVisibleRect(), whose rect is relative to this view's own WINDOW, not the
     * screen. The cursor position being hit-tested against it is screen-absolute, so every
     * lookup was off by exactly this window's offset (x=24, y≈screenHeight/8) — landing
     * roughly a row down and over from whatever key the cursor was actually sitting on.
     * getLocationOnScreen() is the screen-absolute equivalent and has no such skew.
     */
    private fun screenBounds(view: View): Rect {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    /**
     * Small floating panel (like slide_keyboard.py), positioned so it doesn't bury
     * whatever field you're typing into — not a full-width bar docked over the
     * bottom of the screen, which is exactly where most apps put their own input
     * field. Position is remembered across show/hide within one keyboard-service
     * lifetime (not persisted to disk yet) via [lastX]/[lastY].
     */
    fun show() {
        if (isShowing()) return
        TextStyles.setSkinTone(SkinToneStore.load(service))
        fontSizeSp = KeyboardFontSizeStore.load(service)
        cachedMode = PttModeStore.load(service)
        val root = buildContent()
        val metrics = service.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX ?: 24
            y = lastY ?: (metrics.heightPixels / 8)
        }
        try {
            windowManager.addView(root, params)
            rootView = root
            windowParams = params
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Failed to attach floating keyboard", e)
        }
    }

    fun hide() {
        val view = rootView ?: return
        windowParams?.let { lastX = it.x; lastY = it.y }
        rootView = null
        windowParams = null
        dragging = false
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Floating keyboard already detached", e)
        }
    }

    fun isDragging(): Boolean = dragging

    /** Moves the whole panel by a D-pad-nudge-sized delta — same input the cursor
     * itself uses (see ControllerAccessibilityService.handleKeyEventAction), just
     * redirected to the window position instead while [dragging] is true. */
    fun nudgePosition(dx: Int, dy: Int) {
        val params = windowParams ?: run { Log.w(TAG, "nudgePosition($dx,$dy): no windowParams"); return }
        val view = rootView ?: run { Log.w(TAG, "nudgePosition($dx,$dy): no rootView"); return }
        val metrics = service.resources.displayMetrics
        // OCR finding, confirmed live 2026-09-19 as "I have to move it, then turn it off
        // and back on for it to stick": this clamped x/y to [0, screenWidth/Height], never
        // subtracting the panel's OWN width/height — so dragging right/down could push most
        // of the panel off-screen while x/y kept climbing toward the raw screen edge. It
        // looked stopped (nothing visible left to move); it wasn't, the numbers were just
        // past what's on screen. Toggling the keyboard off/on didn't "fix" the position —
        // show() just re-renders at whatever x/y already is, which happened to look right
        // because the clamp starts fresh each time rather than compounding drift.
        val maxX = (metrics.widthPixels - view.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - view.height).coerceAtLeast(0)
        params.x = (params.x + dx).coerceIn(0, maxX)
        params.y = (params.y + dy).coerceIn(0, maxY)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            // OCR finding: was IllegalArgumentException only. addView-adjacent calls can
            // also throw IllegalStateException/BadTokenException if the window's gone —
            // uncaught, either kills the whole accessibility service, not just this call.
            Log.w(TAG, "nudgePosition failed, view not attached", e)
        }
    }

    // ── Layout (ported from AIInputMethodService.onCreateInputView) ────────

    private fun buildContent(): View {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D12"))
            setPadding(6, 6, 6, 6)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(buildTopRow())
        root.addView(buildStyleAndToneRow())
        pinsRow = buildPinsRow()
        refreshPinsRow()
        root.addView(pinsRow)
        keyGrid = buildKeyGrid()
        root.addView(keyGrid)
        root.addView(buildBottomRow())
        return root
    }

    /** Top row: the drag handle plus a user-driven font-size control. Reported live
     * 2026-09-19: a fixed text size isn't the fix — "we need to be able to allow the
     * consumer to choose the size that they want... it doesn't need to be predetermined."
     * A+/A- cycle [KeyboardFontSizeStore.STEPS_SP] and persist the choice; only this panel's
     * own text is affected for now ("let's wait for a second before we put it on everything"). */
    private fun buildTopRow(): LinearLayout {
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        dragHandle = styledButton(dragHandleLabel()).apply {
            setBackgroundColor(Color.parseColor("#1A1A22"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                dragging = !dragging
                Log.d(TAG, "drag handle clicked -> dragging=$dragging")
                dragHandle.text = dragHandleLabel()
                dragHandle.setBackgroundColor(Color.parseColor(if (dragging) "#3DDC97" else "#1A1A22"))
            }
        }
        row.addView(dragHandle)
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

    private fun dragHandleLabel(): String = if (dragging) "✋ D-pad to move — click to drop" else "✥ Move"

    private fun changeFontSize(sp: Float) {
        if (sp == fontSizeSp) return
        fontSizeSp = sp
        KeyboardFontSizeStore.save(service, sp)
        rebuildContent()
    }

    /** Swaps the panel's content view in place, keeping its current window position — the
     * only way to apply a font-size change to every button, short of rebuilding each one
     * individually (fragile — new rows like the punctuation strip would need updating too
     * every time). show()/hide() would also reset the remembered [lastX]/[lastY]. */
    private fun rebuildContent() {
        val params = windowParams ?: return
        val oldView = rootView ?: return
        val newRoot = buildContent()
        try {
            windowManager.removeView(oldView)
            windowManager.addView(newRoot, params)
            rootView = newRoot
        } catch (e: Exception) {
            Log.e(TAG, "rebuildContent failed", e)
        }
    }

    private fun buildStyleAndToneRow(): HorizontalScrollView {
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = mutableMapOf<TextStyles.Mode, Button>()
        for (mode in TextStyles.Mode.values()) {
            val btn = styledButton(styleLabel(mode)).apply {
                setOnClickListener {
                    PttModeStore.save(service, mode)
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
        return HorizontalScrollView(service).apply { addView(row) }
    }

    // Matches the Linux desktop's own _mode_display_label exactly (slide_keyboard.py) —
    // each label is rendered in the actual style it applies, so the font itself says
    // what the mode does. Reported live 2026-09-18: labeling these "Bubbly"/"Big" (the
    // internal TextStyles.Mode names, kept for storage compatibility) instead of what
    // they actually render as read as wrong/confusing — there is no separate "big" or
    // "bubbly" look, just Cursive and Old English, same as the desktop always called them.
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
        val list = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A22"))
            setPadding(12, 12, 12, 12)
        }
        val popup = PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            // The popup is its own separate window and needs TYPE_ACCESSIBILITY_OVERLAY
            // too, or it can't attach on top of an already-overlay-hosted anchor.
            windowLayoutType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        for (tone in EmojiSkinTone.entries) {
            list.addView(styledButton(tone.label).apply {
                setOnClickListener {
                    SkinToneStore.save(service, tone)
                    TextStyles.setSkinTone(tone)
                    popup.dismiss()
                }
            })
        }
        popup.showAsDropDown(anchor)
    }

    // ── Pinned snippets ──────────────────────────────────────────────────

    private fun buildPinsRow(): LinearLayout =
        LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }

    private fun refreshPinsRow() {
        val row = pinsRow
        row.removeAllViews()
        val pins = PinStore.load(service)
        for (index in 0 until pins.length()) {
            val pin = pins.getJSONObject(index)
            val label = pin.optString("label", "?").take(10)
            row.addView(pinButton(label).apply {
                setOnClickListener { service.typeText(TextStyles.transform(pin.optString("text", ""), currentMode())) }
                setOnLongClickListener {
                    PinStore.removeAt(service, index)
                    refreshPinsRow()
                    Toast.makeText(service, "Unpinned $label", Toast.LENGTH_SHORT).show()
                    true
                }
            })
        }
    }

    // ── QWERTY grid ──────────────────────────────────────────────────────

    /**
     * Real QWERTY: one horizontal row per letter row, so q-p / a-l / z-m land where a
     * keyboard actually puts them. The previous version poured all three rows into a
     * single 10-column GridLayout, which silently wrapped "z" onto the end of the middle
     * row — part of why aiming at a key hit something else.
     */
    private fun buildKeyGrid(): LinearLayout {
        val grid = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        populateKeyGrid(grid)
        return grid
    }

    private fun populateKeyGrid(grid: LinearLayout) {
        grid.removeAllViews()
        for (row in rowsLower) {
            val rowView = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) {
                val label = if (shiftOn) key.uppercase() else key
                rowView.addView(keyButton(label).apply {
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
        val punctRow = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        for (symbol in punctuationRow) {
            punctRow.addView(keyButton(symbol).apply {
                setOnClickListener { commitStyledChar(symbol) }
            })
        }
        grid.addView(punctRow)
    }

    /**
     * A key whose real touch bounds equal its drawn cell. Reported live 2026-09-18 as
     * "I press one letter and it prints something different": a plain Button carries
     * Android's default minWidth (64dp) / minHeight (48dp), which at this device's density
     * exceeds the KEY_PX cell it was being given — so neighbouring keys physically
     * overlapped, and a hit-test at a point covered by two keys resolved to whichever the
     * traversal reached first, not the one under the cursor. Zeroing the minimums (and the
     * button's internal padding) makes bounds == what you see.
     */
    private fun keyButton(label: String): Button = styledButton(label).apply {
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(KEY_PX, KEY_PX)
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(styledButton("⇧").apply {
            setOnClickListener {
                shiftOn = !shiftOn
                populateKeyGrid(keyGrid)
            }
        })
        row.addView(styledButton("space").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { service.typeCharacter(" ") }
        })
        row.addView(styledButton("⌫").apply {
            setOnClickListener { service.backspaceOnce() }
        })
        row.addView(styledButton("⏎").apply {
            setOnClickListener { service.pressEnter() }
        })
        row.addView(voiceButton())
        return row
    }

    /** Hold-to-talk mic key, routed through the exact same PttController edges as a
     * controller trigger press — see AIInputMethodService's original for the same pattern. */
    private fun voiceButton(): Button = styledButton("🎤").apply {
        setTextColor(Color.parseColor("#3DDC97"))
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { service.pttDownFromKeyboard(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { service.pttUpFromKeyboard(); true }
                else -> false
            }
        }
    }

    // ── Styled commit helpers ────────────────────────────────────────────

    /** OCR finding: currentMode() previously re-read SharedPreferences on every single
     * keystroke via commitStyledChar — real main-thread disk I/O per character typed.
     * Cached, loaded once in show() and updated in place whenever a style button changes
     * it, so a keystroke reads one field instead of hitting disk. */
    private var cachedMode: TextStyles.Mode = TextStyles.Mode.PRO
    private fun currentMode(): TextStyles.Mode = cachedMode

    private fun commitStyledChar(ch: String) {
        val styled = when (currentMode()) {
            TextStyles.Mode.BUBBLY -> TextStyles.toCursive(ch)
            TextStyles.Mode.BOLD -> TextStyles.toBold(ch)
            TextStyles.Mode.BIG -> TextStyles.toOldEnglish(ch)
            TextStyles.Mode.CASUAL -> ch.lowercase()
            TextStyles.Mode.PRO -> ch
        }
        service.typeCharacter(styled)
    }

    // ── Shared styling ───────────────────────────────────────────────────

    private fun styledButton(label: String): Button = Button(service).apply {
        text = label
        setTextColor(Color.parseColor("#E8E8E8"))
        setBackgroundColor(Color.parseColor("#23232B"))
        setPadding(4, 4, 4, 4)
        // Reported live 2026-09-19: "I can't see this" — shrinking the keys (KEY_PX)
        // to make the panel smaller isn't the same as shrinking the text; text needs to
        // stay readable independent of how compact the cells are.
        textSize = fontSizeSp
        isAllCaps = false
        // Android's default Button minWidth/minHeight (64dp/48dp) are larger than the
        // cells this keyboard lays out, which made adjacent controls overlap and hit-test
        // to the wrong one. Every button here sizes to exactly what it draws.
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
    }

    private fun pinButton(label: String): Button = styledButton(label).apply {
        setTextColor(Color.parseColor("#3DDC97"))
        setBackgroundColor(Color.parseColor("#0F2A24"))
        gravity = Gravity.CENTER
    }

    companion object {
        private const val TAG = "FloatingKeyboardOverlay"
        /** Square key cell, in px. Touch bounds match this exactly (see keyButton). */
        /** Reported live 2026-09-19: "can we make it smaller?" — down from 80. */
        private const val KEY_PX = 56
    }
}
