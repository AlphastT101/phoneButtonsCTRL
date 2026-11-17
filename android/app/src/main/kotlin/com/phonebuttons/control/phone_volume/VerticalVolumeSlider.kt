package com.phonebuttons.control.phone_volume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Minimal vertical volume slider: a thin rounded line fill, with a short
 * horizontal dash as the handle — no circle. The dash marks the current
 * position on the line.
 */
class VerticalVolumeSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var max: Int = 15

    var value: Int = 0
        set(v) {
            val clamped = v.coerceIn(0, max)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var onStepChanged: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackWidth = 4 * density
    private val pad = 10 * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30363D")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0883E")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0F6FC")
        setShadowLayer(6 * density, 0f, 1 * density, Color.parseColor("#80000000"))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val top = pad
        val bottom = h - pad
        val half = trackWidth / 2f

        canvas.drawRoundRect(cx - half, top, cx + half, bottom, half, half, trackPaint)

        val frac = if (max == 0) 0f else value.toFloat() / max.toFloat()
        val filledTop = bottom - frac * (bottom - top)
        if (frac > 0f) {
            canvas.drawRoundRect(cx - half, filledTop, cx + half, bottom, half, half, fillPaint)
        }

        // Straight-line handle (dash) with margins from the track's ends, no circle.
        val handleW = 18 * density
        val handleH = 5 * density
        val handleLeft = cx - handleW / 2f
        val radius = handleH / 2f
        canvas.drawRoundRect(
            RectF(handleLeft, filledTop - radius, handleLeft + handleW, filledTop + radius),
            radius, radius, handlePaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val top = pad
                val bottom = height - pad
                val frac = 1f - ((event.y - top) / (bottom - top)).coerceIn(0f, 1f)
                val newValue = (frac * max).roundToInt()
                if (newValue != value) {
                    value = newValue
                    onStepChanged?.invoke(newValue)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                return true
            }
        }
        return true
    }
}
