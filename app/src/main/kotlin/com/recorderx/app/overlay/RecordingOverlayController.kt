package com.recorderx.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
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
 * A small always-on-top control bubble for pause/resume/stop while recording.
 *
 * ## Why this isn't (and can't be) `FLAG_SECURE`
 *
 * An earlier version of this bubble used `WindowManager.LayoutParams.FLAG_SECURE`,
 * reasoning that it's the platform mechanism for excluding a window from
 * screen-mirroring destinations. That reasoning has a real gap: `FLAG_SECURE`
 * is a *privacy* primitive, not a *compositing* one -- its documented,
 * standard behavior in a capture (screenshot, cast, or a `MediaProjection`
 * `VirtualDisplay` like the one this app itself creates) is to render the
 * secure window's bounds as a solid black shape, not to cleanly omit it and
 * reveal whatever is underneath. That's exactly right for its actual purpose
 * (make sure a banking PIN pad can never leak into a capture, screen-mirror,
 * or Recents thumbnail, full stop) and exactly wrong for this one (a small
 * piece of *this app's own* chrome that should just not be part of the
 * recording, without covering the content behind it in black). The visible
 * black dot/rectangle this generated in recordings -- "siyah bir nokta" --
 * was that mechanism working as documented, not a bug in the platform.
 *
 * There is no public API that lets a normal (non-system, non-privileged) app
 * make its own overlay simultaneously (a) visible live on screen and
 * (b) cleanly excluded -- not blacked out, not just small -- from that same
 * app's own `MediaProjection` recording. Samsung's own recorder can do this
 * because it's a privileged system component with capture-pipeline access a
 * third-party APK is never granted. Mainstream third-party recorders (XRecorder,
 * ADV Screen Recorder, etc.) don't fake this either -- they ship the same
 * two mitigations below, because that combination is the actual, honest
 * ceiling for what a normal app can do here.
 *
 * ## What this does instead
 *
 * 1. No `FLAG_SECURE` -- so there is no black shape, ever, under any OEM
 *    compositor. The window is a completely ordinary, small, translucent
 *    overlay (see [setExpanded] / `overlay_recording_controls.xml`): a
 *    ~16dp dot at rest, far smaller and far less visually intrusive than
 *    either the old dark 40dp collapsed circle or a `FLAG_SECURE` black box
 *    would have been if it *does* end up in a frame.
 * 2. A genuine **hide** action (the eye icon on the expanded row) that fully
 *    detaches this window from `WindowManager` -- not shrunk, not
 *    transparent, actually removed -- for whenever a completely clean frame
 *    matters more than having the controls reachable on-screen. `RecordingService`
 *    adds a "Show controls" action to the persistent recording notification
 *    while hidden this way, since the bubble obviously can't offer its own
 *    "bring me back" tap target once it's gone.
 * 3. An automatic idle fade: after [IDLE_FADE_DELAY_MS] with no touch, the
 *    bubble eases down to [IDLE_ALPHA] opacity and stays there until touched
 *    again. It's still technically part of every frame captured while idle,
 *    but far less visually intrusive than sitting at full opacity for the
 *    entire recording when in practice the user only actually touches it
 *    twice (pause, stop) -- a real, if partial, reduction in how much of the
 *    recording it's actually noticeable in, on top of (1) and (2) above.
 */
class RecordingOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val tapSlopPx = ViewConfiguration.get(context).scaledTouchSlop
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var expanded = false

    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeOutRunnable = Runnable { rootView?.animate()?.alpha(IDLE_ALPHA)?.setDuration(250)?.start() }

    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartLpX = 0
    private var dragStartLpY = 0
    private var dragMoved = false

    fun isShown(): Boolean = rootView != null

    fun show(onTogglePauseResume: () -> Unit, onStop: () -> Unit, onHide: () -> Unit) {
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_RecorderX_Overlay)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_recording_controls, null)

        val collapsedDot = view.findViewById<FrameLayout>(R.id.collapsedDot)
        val expandedRow = view.findViewById<View>(R.id.expandedRow)

        view.findViewById<ImageButton>(R.id.btnPauseResume).setOnClickListener { onTogglePauseResume() }
        view.findViewById<ImageButton>(R.id.btnStop).setOnClickListener { onStop() }
        view.findViewById<ImageButton>(R.id.btnHide).setOnClickListener { onHide() }
        view.findViewById<ImageButton>(R.id.btnCollapse).setOnClickListener { setExpanded(false) }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
        scheduleIdleFade()
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

    /** Fully detaches the bubble window. Also what a plain "recording
     * stopped" teardown uses -- from the platform's point of view a
     * user-requested hide and an end-of-session teardown are the same
     * operation, just with different callers. */
    fun hide() {
        val view = rootView ?: return
        fadeHandler.removeCallbacks(fadeOutRunnable)
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached -- nothing to do.
        }
        rootView = null
        layoutParams = null
    }

    /** Cancels any pending fade-out and restores full opacity immediately (a
     * touch is direct evidence the user is looking right at it), then
     * re-arms the countdown from zero. Called on every touch event, not just
     * taps, so an in-progress drag never fades out from under the finger. */
    private fun scheduleIdleFade() {
        fadeHandler.removeCallbacks(fadeOutRunnable)
        rootView?.animate()?.alpha(1f)?.setDuration(120)?.start()
        fadeHandler.postDelayed(fadeOutRunnable, IDLE_FADE_DELAY_MS)
    }

    private fun handleTouch(event: MotionEvent, lp: WindowManager.LayoutParams, onTap: () -> Unit): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) scheduleIdleFade()
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

    companion object {
        private const val IDLE_FADE_DELAY_MS = 2_500L
        private const val IDLE_ALPHA = 0.32f
    }
}
