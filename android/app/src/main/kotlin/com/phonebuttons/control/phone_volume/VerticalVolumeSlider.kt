package com.phonebuttons.control.phone_volume

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * System-style vertical volume bar used by the compact (media-only) modal.
 *
 * A wide grey pill with a blue fill growing from the bottom and a thin
 * horizontal handle at the boundary. Value increases downward: top = 0,
 * bottom = max. Dragging follows the finger; a tap glides the handle with
 * ease-in-out instead of teleporting.
 */
class VerticalVolumeSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var max: Int = 15
        set(v) {
            field = v.coerceAtLeast(1)
            if (!isDragging) syncFraction()
        }

    private var currentValue = 0
    val value: Int get() = currentValue

    var onStepChanged: ((Int) -> Unit)? = null

    var isDragging: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private val trackWidth = 30f * density
    private val handleHeight = 12f * density
    private val handleOvershoot = 5f * density
    private val gapHalf = 16f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var fraction = 0f
    private var moveAnimator: ValueAnimator? = null
    private var glideAnimator: ValueAnimator? = null
    private var glideCanceled = false
    private var downY = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#343440")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#71D2FF")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#71D2FF")
    }

    init {
        isFocusable = true
        contentDescription = "Media volume"
        setWillNotDraw(false)
    }

    /** Direct, non-animated assignment (used when views are first built). */
    fun setValueImmediate(value: Int) {
        val clamped = value.coerceIn(0, max)
        currentValue = clamped
        moveAnimator?.cancel()
        glideAnimator?.cancel()
        glideAnimator = null
        fraction = clamped.toFloat() / max.toFloat()
        invalidate()
    }

    /** Programmatic change: glides with ease-in-out. */
    fun setValue(value: Int, animate: Boolean) {
        val clamped = value.coerceIn(0, max)
        if (isDragging || isGliding) return
        val target = clamped.toFloat() / max.toFloat()
        if (clamped == currentValue && abs(fraction - target) < 0.001f) return
        currentValue = clamped
        moveAnimator?.cancel()
        if (!animate || height == 0 || !isShown) {
            fraction = target
            invalidate()
            return
        }
        moveAnimator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 200L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun syncFraction() {
        fraction = currentValue.toFloat() / max.toFloat()
        invalidate()
    }

    private val isGliding: Boolean get() = glideAnimator != null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isDragging) syncFraction()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val top = 0f
        val bottom = height.toFloat()
        if (bottom <= top) return
        val half = trackWidth / 2f
        val radius = half

        // Two separate pills with a gap around the handle.
        val handleY = top + (bottom - top) * fraction
        val greyEnd = (handleY - gapHalf).coerceIn(top, bottom)
        val blueStart = (handleY + gapHalf).coerceIn(top, bottom)
        if (fraction < 0.999f) {
            canvas.drawRoundRect(cx - half, top, cx + half, greyEnd, radius, radius, trackPaint)
        }
        if (fraction > 0.001f) {
            canvas.drawRoundRect(cx - half, blueStart, cx + half, bottom, radius, radius, fillPaint)
        }

        // Thin horizontal handle, slightly wider than the bar.
        canvas.drawRoundRect(
            cx - half - handleOvershoot,
            handleY - handleHeight / 2f,
            cx + half + handleOvershoot,
            handleY + handleHeight / 2f,
            handleHeight / 2f,
            handleHeight / 2f,
            handlePaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                moveAnimator?.cancel()
                glideAnimator?.cancel()
                isDragging = true
                downY = event.y
                val targetValue = (fractionForY(event.y) * max).roundToInt().coerceIn(0, max)
                val target = targetValue.toFloat() / max.toFloat()
                if (abs(target - fraction) > 0.01f) glideTo(target) else updateFromTouch(event.y, false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    if (isGliding && abs(event.y - downY) <= touchSlop) return true
                    if (isGliding) {
                        glideCanceled = true
                        glideAnimator?.cancel()
                        glideAnimator = null
                    }
                    updateFromTouch(event.y, true)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    if (event.actionMasked == MotionEvent.ACTION_UP && !isGliding) performClick()
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun fractionForY(y: Float): Float {
        val top = 0f
        val bottom = height.toFloat()
        if (bottom <= top) return fraction
        return ((y - top) / (bottom - top)).coerceIn(0f, 1f)
    }

    private fun updateFromTouch(y: Float, haptic: Boolean) {
        fraction = fractionForY(y)
        emitValue(fraction, haptic)
        invalidate()
    }

    private fun emitValue(f: Float, haptic: Boolean) {
        val newValue = (f * max).roundToInt().coerceIn(0, max)
        if (newValue != currentValue) {
            currentValue = newValue
            if (haptic) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onStepChanged?.invoke(newValue)
        }
    }

    private fun glideTo(target: Float) {
        glideCanceled = false
        glideAnimator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 200L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    glideCanceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    glideAnimator = null
                    if (!glideCanceled) emitValue(fraction, true)
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        moveAnimator?.cancel()
        glideAnimator?.cancel()
        glideAnimator = null
        super.onDetachedFromWindow()
    }
}
