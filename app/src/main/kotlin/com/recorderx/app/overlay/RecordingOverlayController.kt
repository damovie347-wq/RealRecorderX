package com.recorderx.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.recorderx.app.R
import kotlin.math.abs

/**
 * A small always-on-top control bubble that stays out of the recording itself.
 *
 * The mechanism: this is a *separate* window added via WindowManager with
 * FLAG_SECURE set. FLAG_SECURE tells the platform to exclude this window's
 * content wherever the screen is mirrored to a non-secure destination --
 * screenshots, casting, and (what matters here) MediaProjection's VirtualDisplay
 * output. It is never part of MainActivity's view hierarchy or any view that
 * could end up composited into the capture surface, so there's no per-frame
 * visibility toggling or timing to get right -- the platform simply omits it.
 * This is the same approach Samsung's own screen recorder UI relies on.
 *
 * On some OEM skins FLAG_SECURE content renders as a solid black shape in the
 * capture rather than being omitted outright -- the *content* (icons, text,
 * timer) is still guaranteed private either way, but a visible black shape is
 * an aesthetic problem a platform flag can't fully solve from here. The
 * bubble therefore starts **collapsed to a small 40dp dot** by default (see
 * [setExpanded]) and only grows to the full control row while the user is
 * actively using it -- both because a large control row genuinely gets in
 * the way of whatever's being recorded, and because it keeps a worst-case
 * black-shape render small instead of a large rectangle sitting on screen
 * for the whole session.
 */
class RecordingOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val tapSlopPx = ViewConfiguration.get(context).scaledTouchSlop
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var expanded = false

    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartLpX = 0
    private var dragStartLpY = 0
    private var dragMoved = false

    fun isShown(): Boolean = rootView != null

    fun show(onTogglePauseResume: () -> Unit, onStop: () -> Unit) {
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_RecorderX_Overlay)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_recording_controls, null)

        val collapsedDot = view.findViewById<FrameLayout>(R.id.collapsedDot)
        val expandedRow = view.findViewById<View>(R.id.expandedRow)

        view.findViewById<ImageButton>(R.id.btnPauseResume).setOnClickListener { onTogglePauseResume() }
        view.findViewById<ImageButton>(R.id.btnStop).setOnClickListener { onStop() }
        view.findViewById<ImageButton>(R.id.btnCollapse).setOnClickListener { setExpanded(false) }

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

        // One shared drag handler for both the collapsed dot and the expanded
        // row's own background: distinguishes a tap (expand, or collapse via
        // the dedicated button) from a drag (reposition) by total movement.
        collapsedDot.setOnTouchListener { _, event -> handleTouch(event, lp) { setExpanded(true) } }
        expandedRow.setOnTouchListener { _, event -> handleTouch(event, lp) { /* tapping the row background does nothing */ } }

        try {
            windowManager.addView(view, lp)
        } catch (e: SecurityException) {
            // Permission was revoked between MainActivity's check and now -- recording
            // itself still proceeds without the bubble; the notification's Stop
            // action remains a reliable fallback control.
            return
        }
        rootView = view
        layoutParams = lp
        expanded = false
    }

    /** Toggling GONE<->VISIBLE on whichever row isn't showing naturally
     * shrinks/grows the window's own WRAP_CONTENT size; updateViewLayout
     * forces WindowManager to actually re-measure and reposition for it. */
    private fun setExpanded(value: Boolean) {
        val view = rootView ?: return
        val lp = layoutParams ?: return
        expanded = value
        view.findViewById<View>(R.id.collapsedDot).visibility = if (value) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.expandedRow).visibility = if (value) View.VISIBLE else View.GONE
        safeUpdateLayout(view, lp)
    }

    fun setElapsedText(text: String) {
        rootView?.findViewById<TextView>(R.id.txtElapsed)?.text = text
    }

    fun setPaused(paused: Boolean) {
        val icon = if (paused) R.drawable.ic_play else R.drawable.ic_pause
        rootView?.findViewById<ImageButton>(R.id.btnPauseResume)?.setImageDrawable(
            ContextCompat.getDrawable(context, icon)
        )
    }

    fun hide() {
        val view = rootView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached -- nothing to do.
        }
        rootView = null
        layoutParams = null
    }

    private fun handleTouch(event: MotionEvent, lp: WindowManager.LayoutParams, onTap: () -> Unit): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragDownRawX = event.rawX
                dragDownRawY = event.rawY
                dragStartLpX = lp.x
                dragStartLpY = lp.y
                dragMoved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                // Gravity is TOP|END, so lp.x is an offset from the *right* edge:
                // moving the finger right should shrink that offset.
                val dx = (event.rawX - dragDownRawX).toInt()
                val dy = (event.rawY - dragDownRawY).toInt()
                if (!dragMoved && (abs(dx) > tapSlopPx || abs(dy) > tapSlopPx)) {
                    dragMoved = true
                }
                if (dragMoved) {
                    lp.x = dragStartLpX - dx
                    lp.y = dragStartLpY + dy
                    rootView?.let { safeUpdateLayout(it, lp) }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragMoved) onTap()
                true
            }
            else -> false
        }
    }

    private fun safeUpdateLayout(view: View, lp: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: IllegalArgumentException) {
            // View was detached mid-gesture (e.g. recording stopped) -- ignore.
        }
    }
}
