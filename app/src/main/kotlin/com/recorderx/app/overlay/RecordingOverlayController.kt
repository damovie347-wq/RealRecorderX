package com.recorderx.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.recorderx.app.R

/**
 * A small always-on-top control bubble that stays out of the recording itself.
 *
 * The mechanism: this is a *separate* window added via WindowManager with
 * FLAG_SECURE set. FLAG_SECURE tells the platform to exclude this window's
 * content wherever the screen is mirrored to a non-secure surface --
 * screenshots, casting, and (what matters here) MediaProjection's VirtualDisplay
 * output. It is never part of MainActivity's view hierarchy or any view that
 * could end up composited into the capture surface, so there's no per-frame
 * visibility toggling or timing to get right -- the platform simply omits it.
 * This is the same approach Samsung's own screen recorder UI relies on.
 *
 * Verify the exact rendering (fully excluded vs. blacked-out) on your target
 * OEM skins if you extend this bubble -- it's platform-guaranteed to keep the
 * *content* private, but keeping the bubble itself small and unobtrusive is
 * good defense-in-depth regardless.
 */
class RecordingOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartLpX = 0
    private var dragStartLpY = 0

    fun isShown(): Boolean = overlayView != null

    fun show(onTogglePauseResume: () -> Unit, onStop: () -> Unit) {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_RecorderX_Overlay)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_recording_controls, null)

        view.findViewById<ImageButton>(R.id.btnPauseResume).setOnClickListener { onTogglePauseResume() }
        view.findViewById<ImageButton>(R.id.btnStop).setOnClickListener { onStop() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 220
        }

        // Dragging is handled on the root container's empty background area only --
        // touches that land on a button are consumed by that button first and never
        // reach this listener, so tap-to-act and drag-to-move coexist without any
        // manual hit-test bookkeeping.
        view.setOnTouchListener { _, event -> handleDrag(event, lp) }

        try {
            windowManager.addView(view, lp)
        } catch (e: SecurityException) {
            // Permission was revoked between MainActivity's check and now -- recording
            // itself still proceeds without the bubble; the notification's Stop
            // action remains a reliable fallback control.
            return
        }
        overlayView = view
        layoutParams = lp
    }

    fun setElapsedText(text: String) {
        overlayView?.findViewById<TextView>(R.id.txtElapsed)?.text = text
    }

    fun setPaused(paused: Boolean) {
        val icon = if (paused) R.drawable.ic_play else R.drawable.ic_pause
        overlayView?.findViewById<ImageButton>(R.id.btnPauseResume)?.setImageDrawable(
            ContextCompat.getDrawable(context, icon)
        )
    }

    fun hide() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached -- nothing to do.
        }
        overlayView = null
        layoutParams = null
    }

    private fun handleDrag(event: MotionEvent, lp: WindowManager.LayoutParams): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartLpX = lp.x
                dragStartLpY = lp.y
                true
            }
            MotionEvent.ACTION_MOVE -> {
                // Gravity is TOP|END, so lp.x is an offset from the *right* edge:
                // moving the finger right should shrink that offset.
                val dx = (event.rawX - dragStartRawX).toInt()
                val dy = (event.rawY - dragStartRawY).toInt()
                lp.x = dragStartLpX - dx
                lp.y = dragStartLpY + dy
                val view = overlayView
                if (view != null) {
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (e: IllegalArgumentException) {
                        // View was detached mid-gesture (e.g. recording stopped) -- ignore.
                    }
                }
                true
            }
            else -> false
        }
    }
}
