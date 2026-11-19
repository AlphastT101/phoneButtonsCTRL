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
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Material 3 / Pixel style horizontal slider.
 *
 * The knob follows the finger continuously (no step snapping while dragging), while the
 * underlying stream value is quantized to the audio stream's real steps. Programmatic
 * changes (hardware keys, mute links) glide to their new position instead of teleporting.
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

    var muted: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var isDragging: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private val trackHeight = 10 * density
    private val thumbRadius = 9 * density
    private val horizontalPad = 12 * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var fraction = 0f
    private var thumbScale = 1f
    private var moveAnimator: ValueAnimator? = null
    private var glideAnimator: ValueAnimator? = null
    private var glideCanceled = false
    private var thumbAnimator: ValueAnimator? = null
    private var downX = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3C4043")
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8C7FA")
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D3E3FD")
    }
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.parseColor("#1E1F22")
    }
    private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D000000")
    }

    init {
        isFocusable = true
        contentDescription = "Volume"
    }

    fun setValue(value: Int, animate: Boolean) {
        val clamped = value.coerceIn(0, max)
        if (isDragging || isGliding) return
        val target = clamped.toFloat() / max.toFloat()
        if (clamped == currentValue && kotlin.math.abs(fraction - target) < 0.001f) return
        currentValue = clamped
        moveAnimator?.cancel()
        if (!animate || width == 0 || !isShown) {
            fraction = target
            invalidate()
            return
        }
        moveAnimator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 190L
            interpolator = DecelerateInterpolator()
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

        val cy = height / 2f
        val top = cy - trackHeight / 2f
        val bottom = cy + trackHeight / 2f
        val radius = trackHeight / 2f

        canvas.drawRoundRect(left, top, right, bottom, radius, radius, trackPaint)

        val thumbX = left + (right - left) * fraction
        if (fraction > 0.001f) {
            canvas.drawRoundRect(left, top, thumbX, bottom, radius, radius, activePaint)
        }

        val r = thumbRadius * thumbScale
        canvas.drawCircle(thumbX, cy + 1 * density, r + 3 * density, thumbShadowPaint)
        canvas.drawCircle(thumbX, cy, r + 2 * density, thumbRingPaint)
        canvas.drawCircle(thumbX, cy, r, thumbPaint)
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
                animateThumb(1.22f)
                val targetValue = (fractionForX(event.x) * max).roundToInt().coerceIn(0, max)
                val target = targetValue.toFloat() / max.toFloat()
                if (abs(target - fraction) > 0.01f) {
                    glideTo(target)
                } else {
                    updateFromTouch(event.x, haptic = false)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    // Ignore finger jitter while a tap glide is running; only take over
                    // once the finger actually travels past the touch slop.
                    if (isGliding && abs(event.x - downX) <= touchSlop) return true
                    if (isGliding) {
                        glideCanceled = true
                        glideAnimator?.cancel()
                        glideAnimator = null
                    }
                    updateFromTouch(event.x, haptic = true)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    animateThumb(1f)
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

    /**
     * Tap-to-position: glide the thumb to the tapped spot instead of teleporting,
     * then commit the resulting volume in one update when the glide finishes.
     */
    private fun glideTo(target: Float) {
        glideCanceled = false
        glideAnimator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
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
                    if (!glideCanceled) {
                        emitValue(fraction, haptic = true)
                    }
                }
            })
            start()
        }
    }

    private fun animateThumb(to: Float) {
        thumbAnimator?.cancel()
        thumbAnimator = ValueAnimator.ofFloat(thumbScale, to).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                thumbScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        moveAnimator?.cancel()
        glideAnimator?.cancel()
        glideAnimator = null
        thumbAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
