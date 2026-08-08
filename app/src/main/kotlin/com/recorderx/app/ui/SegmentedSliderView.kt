package com.recorderx.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.recorderx.app.R
import kotlin.math.roundToInt

/**
 * A long horizontal pill slider with N discrete stops, drawn entirely with
 * Canvas primitives (no bitmaps, no nested view hierarchy, no third-party
 * slider library). Every setting screen in RecorderX -- including the 0-200%
 * level sliders, which just use a finer step count -- is built from this one
 * class, which keeps the whole settings UI to a single, well-exercised
 * drawing and touch-handling path instead of N slightly-different widgets.
 */
class SegmentedSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var steps: Int = 3
        set(value) {
            field = value.coerceAtLeast(2)
            selectedIndex = selectedIndex.coerceIn(0, field - 1)
            invalidate()
        }

    var selectedIndex: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, steps - 1)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /** Fired only on an actual value change, not on every touch move within
     * the same step -- keeps settings persistence from writing on every pixel
     * of a drag. */
    var onIndexChanged: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackHeight = 10f * density
    private val thumbRadius = 14f * density
    private val tickRadius = 2f * density
    private val touchSlop = 4f * density

    private val trackPaintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.track_background)
        style = Paint.Style.FILL
    }
    private val trackPaintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_yellow)
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.thumb_color)
        style = Paint.Style.FILL
    }
    private val tickOnFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tick_on_fill)
    }
    private val tickOnTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tick_on_track)
    }

    private val trackRect = RectF()
    private var lastDownX = 0f
    private var draggedPastSlop = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minHeight = (resources.getDimension(R.dimen.slider_touch_height)).roundToInt()
        val height = resolveSize(minHeight, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val usableLeft = paddingLeft + thumbRadius
        val usableRight = width - paddingRight - thumbRadius
        if (usableRight <= usableLeft) return

        val centerY = height / 2f
        trackRect.set(usableLeft, centerY - trackHeight / 2f, usableRight, centerY + trackHeight / 2f)
        canvas.drawRoundRect(trackRect, trackHeight / 2f, trackHeight / 2f, trackPaintBg)

        val fraction = selectedIndex.toFloat() / (steps - 1)
        val thumbX = usableLeft + (usableRight - usableLeft) * fraction

        val fillRect = RectF(usableLeft, trackRect.top, thumbX.coerceAtLeast(usableLeft), trackRect.bottom)
        if (fillRect.width() > 0f) {
            canvas.drawRoundRect(fillRect, trackHeight / 2f, trackHeight / 2f, trackPaintFill)
        }

        for (i in 0 until steps) {
            val x = usableLeft + (usableRight - usableLeft) * (i.toFloat() / (steps - 1))
            val paint = if (x <= thumbX + 0.5f) tickOnFillPaint else tickOnTrackPaint
            canvas.drawCircle(x, centerY, tickRadius, paint)
        }

        canvas.drawCircle(thumbX, centerY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastDownX = event.x
                draggedPastSlop = false
                updateFromTouchX(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!draggedPastSlop && kotlin.math.abs(event.x - lastDownX) > touchSlop) {
                    draggedPastSlop = true
                }
                updateFromTouchX(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                updateFromTouchX(event.x)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouchX(x: Float) {
        val usableLeft = paddingLeft + thumbRadius
        val usableRight = width - paddingRight - thumbRadius
        if (usableRight <= usableLeft) return
        val fraction = ((x - usableLeft) / (usableRight - usableLeft)).coerceIn(0f, 1f)
        val newIndex = (fraction * (steps - 1)).roundToInt().coerceIn(0, steps - 1)
        if (newIndex != selectedIndex) {
            selectedIndex = newIndex
            onIndexChanged?.invoke(newIndex)
        }
    }
}
