package com.ai.controller

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.ai.controller.models.ControllerInput
import com.ai.controller.models.ControllerProfile

/**
 * Small floating text bubble showing the current button legend near the
 * cursor — the Android analogue of controller-legend.py's HUD. Read-only
 * chrome: touch-transparent and never focusable, so it never steals input
 * from the app underneath (same footprint bug class as CursorOverlay/A1).
 */
class LegendOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    fun show() {
        if (attached) return
        val textView = TextView(context).apply {
            setTextColor(Color.parseColor("#FF6A00"))
            textSize = 11f
            setPadding(18, 8, 18, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B80D0D12"))
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#FF6A00"))
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            windowManager.addView(textView, lp)
            view = textView
            params = lp
            attached = true
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Failed to attach legend overlay", e)
        }
    }

    fun hide() {
        if (!attached) return
        attached = false
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Legend overlay already detached", e)
            }
        }
        view = null
        params = null
    }

    fun setVisible(visible: Boolean) {
        view?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun isShowing(): Boolean = attached

    /** Repositions near [cursor] and refreshes the displayed legend text. */
    fun update(cursor: PointF, legendText: String) {
        val v = view ?: return
        val p = params ?: return
        if (v.text != legendText) v.text = legendText
        p.x = (cursor.x + OFFSET_X).toInt()
        p.y = (cursor.y + OFFSET_Y).toInt()
        try {
            windowManager.updateViewLayout(v, p)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Legend overlay update failed, view not attached", e)
        }
    }

    companion object {
        private const val TAG = "LegendOverlay"
        private const val OFFSET_X = 48
        private const val OFFSET_Y = 12

        // Mirrors controller-legend.py's LEGEND_SLOT_ORDER on Linux exactly:
        // A, B, X, Y, LB, RB, LT, RT, ⧉(Select/View), ☰(Start), LS(stick-click-L), RS(stick-click-R).
        private val SLOT_ORDER = listOf(
            ControllerInput.BUTTON_A to "A",
            ControllerInput.BUTTON_B to "B",
            ControllerInput.BUTTON_X to "X",
            ControllerInput.BUTTON_Y to "Y",
            ControllerInput.BUTTON_L1 to "LB",
            ControllerInput.BUTTON_R1 to "RB",
            ControllerInput.TRIGGER_L2 to "LT",
            ControllerInput.TRIGGER_R2 to "RT",
            ControllerInput.BUTTON_SELECT to "⧉",
            ControllerInput.BUTTON_START to "☰",
            ControllerInput.BUTTON_THUMBL to "LS",
            ControllerInput.BUTTON_THUMBR to "RS"
        )

        /**
         * Builds the Linux-matching legend string. Uses each action's [ButtonAction.label]
         * (the literal desktop legend text, e.g. "Click"/"Bksp") when set, falling back to
         * the enum-name form only for slots that never got a parity label. Entries are
         * grouped two-per-line so the bubble stays narrow on a tablet.
         */
        fun legendTextFor(profile: ControllerProfile): String = entriesFor(profile)
            .chunked(4)
            .joinToString("\n") { row -> row.joinToString("   ") { it.first + ":" + it.second } }

        private fun entriesFor(profile: ControllerProfile): List<Pair<String, String>> =
            SLOT_ORDER.map { (input, label) ->
                val action = profile.mappings[input]
                val text = action?.label?.takeIf { it.isNotBlank() }
                    ?: action?.type?.name?.lowercase()?.replace('_', ' ')
                    ?: "none"
                label to text
            }
    }
}
