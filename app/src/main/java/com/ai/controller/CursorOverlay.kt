package com.ai.controller

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView

/**
 * A small always-on-top cursor rendered via [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 * This overlay type requires no extra runtime permission when hosted by an
 * AccessibilityService, unlike SYSTEM_ALERT_WINDOW.
 *
 * The stick reports a target delta each tick; [render] runs on a 60fps handler
 * loop and lerps the on-screen position toward that target so movement reads
 * as smooth motion instead of discrete jumps.
 */
class CursorOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var cursorView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attached = false

    private val position = PointF(0f, 0f)
    private val target = PointF(0f, 0f)
    private var screenWidth = 0
    private var screenHeight = 0

    private val renderLoop = object : Runnable {
        override fun run() {
            if (!attached) return
            interpolate()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    fun show() {
        if (attached) return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        position.set(screenWidth / 2f, screenHeight / 2f)
        target.set(position.x, position.y)

        val view = ImageView(context).apply {
            setImageResource(R.drawable.cursor)
        }
        val params = WindowManager.LayoutParams(
            CURSOR_SIZE_PX,
            CURSOR_SIZE_PX,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x.toInt()
            y = position.y.toInt()
        }

        try {
            windowManager.addView(view, params)
            cursorView = view
            layoutParams = params
            attached = true
            setTouchTransparent(true)
            handler.post(renderLoop)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Failed to attach cursor overlay", e)
        }
    }

    /**
     * Ensures the cursor's footprint never intercepts touches meant for the
     * app underneath (bug A1: the cursor overlay was found blocking touch
     * under its footprint). FLAG_NOT_TOUCHABLE alone routes touches through
     * to whatever is beneath the window; FLAG_NOT_FOCUSABLE additionally
     * keeps the overlay from ever becoming the input-focused window. Both
     * are applied at view-attach time above; this setter exists so the flag
     * can be re-asserted explicitly (e.g. after a layout-param rebuild) and
     * verified in tests without relying on constructor-time ordering.
     */
    fun setTouchTransparent(transparent: Boolean) {
        val params = layoutParams ?: return
        val view = cursorView ?: return
        params.flags = if (transparent) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "setTouchTransparent update failed, view not attached", e)
        }
    }

    fun hide() {
        if (!attached) return
        attached = false
        handler.removeCallbacks(renderLoop)
        cursorView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Cursor view already detached", e)
            }
        }
        cursorView = null
        layoutParams = null
    }

    /** Re-adds the same view (position/state untouched) so it stacks above whatever
     * else has been added to the window manager since — same-type overlay windows
     * layer in add order, so a window added after this one (e.g. the on-screen
     * keyboard) otherwise buries the cursor with no visual sign it still exists. */
    fun raise() {
        if (!attached) return
        val view = cursorView ?: return
        val params = layoutParams ?: return
        try {
            windowManager.removeView(view)
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // OCR finding: was IllegalArgumentException only, but removeView() can throw
            // IllegalStateException (view not attached) and addView() can throw
            // WindowManager.BadTokenException — this runs every time the keyboard opens
            // (startCustomKeyboard calls it unconditionally), so an uncaught instance of
            // either would crash the whole accessibility service on essentially any
            // keyboard-open press, not just this one call.
            attached = false
            cursorView = null
            layoutParams = null
            Log.w(TAG, "raise failed, cursor overlay detached", e)
        }
    }

    /** Nudges the movement target by a stick-derived delta, in pixels per tick. */
    fun applyDelta(dx: Float, dy: Float) {
        if (!attached) return
        target.x = (target.x + dx).coerceIn(0f, screenWidth.toFloat())
        target.y = (target.y + dy).coerceIn(0f, screenHeight.toFloat())
    }

    /** Absolute screen-space position of the cursor, used for gesture dispatch. */
    fun getPosition(): PointF = PointF(position.x, position.y)

    fun isShowing(): Boolean = attached

    private fun interpolate() {
        position.x += (target.x - position.x) * LERP_FACTOR
        position.y += (target.y - position.y) * LERP_FACTOR

        val params = layoutParams ?: return
        val view = cursorView ?: return
        params.x = (position.x - CURSOR_SIZE_PX / 2f).toInt()
        params.y = (position.y - CURSOR_SIZE_PX / 2f).toInt()
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cursor view update failed, view not attached", e)
        }
    }

    companion object {
        private const val TAG = "CursorOverlay"
        private const val FRAME_INTERVAL_MS = 16L
        private const val LERP_FACTOR = 0.35f
        private const val CURSOR_SIZE_PX = 48
    }
}
