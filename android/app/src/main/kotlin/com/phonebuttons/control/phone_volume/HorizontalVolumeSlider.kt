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
 * System-style horizontal volume bar: a full-width pill with a blue fill up to
 * the current position and a thin vertical handle at the boundary.
 *
 * Dragging follows the finger continuously. Tapping somewhere on the bar glides
 * the handle to that position instead of teleporting. All bar animations use
 * ease-in-out; the window resize animations use ease-out.
 */
class HorizontalVolumeSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onValueChanged: ((Int) -> Unit)? = null

    var max: Int = 15
        set(v) {
            field = v.coerceAtLeast(1)
            if (!isDragging) syncFraction()
        }

    private var currentValue = 0
    val value: Int get() = currentValue

    var isDragging: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private val horizontalPad = 3f * density
    private val handleWidth = 12f * density
    private val handleOvershoot = 5f * density
    private val gapHalf = 24f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var fraction = 0f
    private var moveAnimator: ValueAnimator? = null
    private var glideAnimator: ValueAnimator? = null
    private var glideCanceled = false
    private var downX = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#292933")
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#71D2FF")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#71D2FF")
    }

    init {
        isFocusable = true
        contentDescription = "Volume"
        setWillNotDraw(false)
    }

    fun setValue(value: Int, animate: Boolean) {
        val clamped = value.coerceIn(0, max)
        if (isDragging || isGliding) return
        val target = clamped.toFloat() / max.toFloat()
        if (clamped == currentValue && abs(fraction - target) < 0.001f) return
        currentValue = clamped
        moveAnimator?.cancel()
        if (!animate || width == 0 || !isShown) {
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
        val left = horizontalPad
        val right = width - horizontalPad
        if (right <= left) return

        val top = 0f
        val bottom = height.toFloat()
        val radius = bottom / 2f

        // Two separate pills with a gap around the handle, as in the system UI.
        val handleX = left + (right - left) * fraction
        val blueEnd = (handleX - gapHalf).coerceIn(left, right)
        val greyStart = (handleX + gapHalf).coerceIn(left, right)
        if (fraction > 0.001f) {
            canvas.drawRoundRect(left, top, blueEnd, bottom, radius, radius, activePaint)
        }
        if (fraction < 0.999f) {
            canvas.drawRoundRect(greyStart, top, right, bottom, radius, radius, trackPaint)
        }

        // Thin vertical handle, slightly taller than the bar.
        val cy = (top + bottom) / 2f
        canvas.drawRoundRect(
            handleX - handleWidth / 2f,
            cy - bottom / 2f - handleOvershoot,
            handleX + handleWidth / 2f,
            cy + bottom / 2f + handleOvershoot,
            handleWidth / 2f,
            handleWidth / 2f,
            handlePaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                moveAnimator?.cancel()
                glideAnimator?.cancel()
                isDragging = true
                downX = event.x
                val targetValue = (fractionForX(event.x) * max).roundToInt().coerceIn(0, max)
                val target = targetValue.toFloat() / max.toFloat()
                if (abs(target - fraction) > 0.01f) glideTo(target) else updateFromTouch(event.x, false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    if (isGliding && abs(event.x - downX) <= touchSlop) return true
                    if (isGliding) {
                        glideCanceled = true
                        glideAnimator?.cancel()
                        glideAnimator = null
                    }
                    updateFromTouch(event.x, true)
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

    private fun fractionForX(x: Float): Float {
        val left = horizontalPad
        val right = width - horizontalPad
        if (right <= left) return fraction
        return ((x - left) / (right - left)).coerceIn(0f, 1f)
    }

    private fun updateFromTouch(x: Float, haptic: Boolean) {
        fraction = fractionForX(x)
        emitValue(fraction, haptic)
        invalidate()
    }

    private fun emitValue(f: Float, haptic: Boolean) {
        val newValue = (f * max).roundToInt().coerceIn(0, max)
        if (newValue != currentValue) {
            currentValue = newValue
            if (haptic) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onValueChanged?.invoke(newValue)
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
