package com.ai.controller

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Floating "Last input / last action / PTT state" readout. Exists because toasts
 * from an AccessibilityService are unreliable to observe on a tablet that's driven
 * entirely by a controller with no visible notification shade — this overlay is
 * the one debug signal that survives that. Toggled by MainActivity's Debug switch
 * (persisted in ai_controller_prefs), independent of the button legend overlay.
 */
class DebugOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    fun show() {
        if (attached) return
        val textView = TextView(context).apply {
            setTextColor(Color.parseColor("#3DDC97"))
            textSize = 11f
            setPadding(20, 12, 20, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D80D0D12"))
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#3DDC97"))
            }
            text = "Debug overlay ready"
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 24
            y = 24
        }

        try {
            windowManager.addView(textView, lp)
            view = textView
            params = lp
            attached = true
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Failed to attach debug overlay", e)
        }
    }

    fun hide() {
        if (!attached) return
        attached = false
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Debug overlay already detached", e)
            }
        }
        view = null
        params = null
    }

    fun isShowing(): Boolean = attached

    fun update(text: String) {
        val v = view ?: return
        v.text = text
    }

    companion object {
        private const val TAG = "DebugOverlay"
    }
}
