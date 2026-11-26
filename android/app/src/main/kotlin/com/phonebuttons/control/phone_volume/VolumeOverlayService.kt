package com.phonebuttons.control.phone_volume

import android.animation.ArgbEvaluator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Floating edge control that replaces broken hardware volume keys.
 *
 * TAB   — small blue half-pill flush with the screen edge.
 * MINI  — compact media-only modal: ring/vibrate button, vertical media bar,
 *         and a "more" button (reference image 2).
 * FULL  — full-width modal with five labelled horizontal bars (reference image 1).
 *
 * State changes animate the overlay window itself with an ease-out resize;
 * the modal contents fade in quickly once the resize finishes. Slider motion
 * uses ease-in-out.
 */
class VolumeOverlayService : android.app.Service() {

    private lateinit var wm: WindowManager
    private lateinit var audio: AudioManager
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null

    private var screenW = 1080
    private var screenH = 2400

    private var scrimView: View? = null
    private var scrimLp: WindowManager.LayoutParams? = null
    private var rootView: FrameLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null

    private var tabView: ImageView? = null
    private var surface: FrameLayout? = null
    private var surfaceDrawable: GradientDrawable? = null
    private var surfaceColor = TAB_BLUE
    private var surfaceRadius = 14f
    private var surfaceMargins = IntArray(4)

    private var miniContent: LinearLayout? = null
    private var miniSlider: VerticalVolumeSlider? = null
    private var miniRingerIcon: ImageView? = null
    private var fullContent: LinearLayout? = null

    private val rows = LinkedHashMap<Int, VolumeRow>()
    private val lastVolume = HashMap<Int, Int>()
    private var callMuted = false
    private var enforceGuard = false

    private enum class OverlayState { TAB, MINI, FULL }
    private var state = OverlayState.TAB
    private var onRightSide = true
    private var attached = false

    private var transition: ValueAnimator? = null
    private var transitionGeneration = 0

    private val density get() = resources.displayMetrics.density
    private fun Int.dp() = (this * density).toInt()
    /** Convert a coordinate from the 1080px-wide reference images to this display. */
    private fun ref(px: Int): Int = (px * density / 2.7f).roundToInt()
    private fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).roundToInt()
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // Reference palette (sampled from the two screenshots).
    private val teal = Color.parseColor("#003547")
    private val blue = Color.parseColor("#71D2FF")
    private val labelColor = Color.parseColor("#B0B0B0")
    private val scrimColor = Color.TRANSPARENT
    private val rippleLight = Color.parseColor("#33FFFFFF")

    private data class StreamDef(val stream: Int, val iconRes: Int, val label: String)
    private class VolumeRow(
        val def: StreamDef,
        val slider: HorizontalVolumeSlider,
        val icon: ImageView,
    )

    private data class Target(
        val state: OverlayState,
        val w: Int,
        val h: Int,
        val x: Int,
        val y: Int,
        val margins: IntArray,
        val radius: Float,
        val color: Int,
        val alpha: Float,
    )

    private val streams = listOf(
        StreamDef(AudioManager.STREAM_MUSIC, R.drawable.ic_media, "Media"),
        StreamDef(AudioManager.STREAM_VOICE_CALL, R.drawable.ic_call, "Call"),
        StreamDef(AudioManager.STREAM_RING, R.drawable.ic_ring, "Ring"),
        StreamDef(AudioManager.STREAM_NOTIFICATION, R.drawable.ic_notif, "Notification"),
        StreamDef(AudioManager.STREAM_ALARM, R.drawable.ic_alarm, "Alarm"),
    )

    companion object {
        const val PREF = "volume_overlay_prefs"
        const val KEY_ENABLED = "overlay_enabled"
        const val KEY_EDGE = "overlay_edge" // 0 = left, 1 = right
        const val KEY_Y = "overlay_y"
        const val CHANNEL_ID = "volume_overlay_channel"
        const val NOTIFICATION_ID = 7001
        private const val TAB_W_DP = 24
        private const val TAB_H_DP = 76
        private const val MINI_W_REF = 238
        private const val MINI_H_REF = 1079
        private const val FULL_W_REF = 918
        private const val FULL_H_REF = 950
        private const val EDGE_MARGIN_DP = 8
        private const val FADE_DELAY_MS = 3500L
        private const val COLLAPSED_ALPHA = 0.85f
        private const val COLLAPSED_IDLE_ALPHA = 0.30f
        private const val TAB_BLUE = 0xFFA8C7FA.toInt()
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createNotificationChannel()
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        screenW = resources.displayMetrics.widthPixels
        screenH = resources.displayMetrics.heightPixels
        onRightSide = prefs.getInt(KEY_EDGE, 1) == 1
        attachViews()
        registerObservers()
        enforceCallNotifLink()
        updateAllRows(animate = false)
        refreshMiniRinger()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!attached && Settings.canDrawOverlays(this)) attachViews()
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    // ---------------------------------------------------------- view setup

    private fun attachViews() {
        if (attached) return
        attached = true

        // Small tab window — touchable only while collapsed.
        tabView = ImageView(this).apply {
            setImageResource(tabChevron())
            setBackgroundResource(tabBackground())
            imageTintList = ColorStateList.valueOf(Color.parseColor("#00315D"))
            scaleType = ImageView.ScaleType.CENTER
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
            alpha = COLLAPSED_ALPHA
            contentDescription = "Open sound controls"
        }
        attachDrag(tabView!!)

        rootView = FrameLayout(this).apply {
            clipChildren = false
            addView(tabView, FrameLayout.LayoutParams(TAB_W_DP.dp(), TAB_H_DP.dp()))
        }
        val startY = coerceY(prefs.getInt(KEY_Y, screenH / 3), TAB_H_DP.dp())
        panelLp = WindowManager.LayoutParams(
            TAB_W_DP.dp(),
            TAB_H_DP.dp(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = edgeX(TAB_W_DP.dp())
            y = startY
        }
        addWindowSafe(rootView!!, panelLp!!)

        // Full-screen modal window. Window size never changes; only the card
        // view inside it animates, which is GPU-accelerated and lag-free.
        scrimView = FrameLayout(this).apply {
            setBackgroundColor(scrimColor)
            clipChildren = false
            setOnClickListener { collapse() }
        }
        scrimLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.79f
        }
        addWindowSafe(scrimView!!, scrimLp!!)

        surfaceDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = surfaceRadius * density
            setColor(TAB_BLUE)
        }
        surface = FrameLayout(this).apply {
            background = surfaceDrawable
            alpha = 0f
            clipChildren = false
        }

        miniContent = buildMini()
        fullContent = buildFull()
        surface!!.addView(miniContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        surface!!.addView(fullContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        hideContent(miniContent!!)
        hideContent(fullContent!!)

        val initial = geometry(OverlayState.TAB, panelLp!!.y + TAB_H_DP.dp() / 2).rect()
        (scrimView as FrameLayout).addView(
            surface,
            FrameLayout.LayoutParams(initial[2], initial[3], Gravity.TOP or Gravity.START).apply {
                leftMargin = initial[0]
                topMargin = initial[1]
            },
        )
        scheduleFade()
    }

    /** Compact modal: ring/vibrate circle, vertical media bar, more button. */
    private fun buildMini(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ref(40), ref(24), ref(40), ref(20))
            clipChildren = false
        }

        // Small blue dot above the controls.
        card.addView(View(this).apply {
            background = ovalDrawable(blue)
        }, LinearLayout.LayoutParams(ref(10), ref(10)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Ring / vibrate circle.
        val ringer = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            background = ovalRipple(blue)
            contentDescription = "Switch ring and vibrate"
            setOnClickListener { toggleMiniRinger() }
        }
        miniRingerIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(ref(24), ref(24), ref(24), ref(24))
            imageTintList = ColorStateList.valueOf(teal)
        }
        ringer.addView(miniRingerIcon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        card.addView(ringer, LinearLayout.LayoutParams(ref(96), ref(96)).apply {
            topMargin = ref(12)
        })

        // Vertical media bar.
        val slider = VerticalVolumeSlider(this).apply {
            max = getMaxVolume(AudioManager.STREAM_MUSIC)
            setValueImmediate(displayVolume(AudioManager.STREAM_MUSIC))
            contentDescription = "Media volume"
            onStepChanged = { value -> onSliderValue(AudioManager.STREAM_MUSIC, value) }
        }
        miniSlider = slider
        card.addView(slider, LinearLayout.LayoutParams(ref(108), ref(600)).apply {
            topMargin = ref(32)
        })

        card.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))

        // "More" button opens the full panel.
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tune)
            imageTintList = ColorStateList.valueOf(blue)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "All sound controls"
            isClickable = true
            isFocusable = true
            background = roundedDrawable(Color.TRANSPARENT, 20, rippleLight)
            setOnClickListener { showFull() }
        }, LinearLayout.LayoutParams(ref(56), ref(56)).apply {
            bottomMargin = ref(16)
        })

        return card
    }

    /** Full modal: five labelled bars, each with its stream icon inside. */
    private fun buildFull(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ref(48), ref(48), ref(48), ref(60))
            clipChildren = false
        }
        for (def in streams) {
            card.addView(buildFullRow(def), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = ref(30)
            })
        }
        return card
    }

    private fun buildFullRow(def: StreamDef): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }

        row.addView(TextView(this).apply {
            text = def.label
            textSize = 14f
            setTextColor(labelColor)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ref(44)))

        val bar = FrameLayout(this).apply {
            clipChildren = false
        }
        val max = getMaxVolume(def.stream)
        val slider = HorizontalVolumeSlider(this).apply {
            this.max = max
            setValue(displayVolume(def.stream), false)
            contentDescription = "${def.label} volume"
            onValueChanged = { value -> onSliderValue(def.stream, value) }
        }
        bar.addView(slider, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ref(84),
        ))

        val iconButton = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            background = roundedDrawable(Color.TRANSPARENT, 14, rippleLight)
            contentDescription = "Mute ${def.label}"
            setOnClickListener { toggleMute(def.stream) }
        }
        val icon = ImageView(this).apply {
            setImageResource(def.iconRes)
            imageTintList = ColorStateList.valueOf(blue)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(ref(8), ref(8), ref(8), ref(8))
        }
        iconButton.addView(icon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        bar.addView(iconButton, FrameLayout.LayoutParams(ref(44), ref(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = ref(36)
        })

        row.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ref(84),
        ).apply { topMargin = ref(12) })

        rows[def.stream] = VolumeRow(def, slider, icon)
        return row
    }

    // ---------------------------------------------------------- stream state

    private fun getVolume(stream: Int): Int = try {
        audio.getStreamVolume(stream)
    } catch (_: Exception) {
        0
    }

    private fun getMaxVolume(stream: Int): Int = try {
        audio.getStreamMaxVolume(stream).coerceAtLeast(1)
    } catch (_: Exception) {
        15
    }

    private fun flagMuted(stream: Int): Boolean = try {
        audio.isStreamMute(stream)
    } catch (_: Exception) {
        false
    }

    private fun isRingerSilent(): Boolean = try {
        audio.ringerMode == AudioManager.RINGER_MODE_SILENT
    } catch (_: Exception) {
        false
    }

    private fun isCallMuted(): Boolean =
        callMuted || getVolume(AudioManager.STREAM_VOICE_CALL) == 0 ||
            flagMuted(AudioManager.STREAM_VOICE_CALL)

    private fun isStreamMuted(stream: Int): Boolean {
        if (stream == AudioManager.STREAM_VOICE_CALL) return isCallMuted()
        return getVolume(stream) == 0 || flagMuted(stream)
    }

    private fun displayVolume(stream: Int): Int {
        if (isRingerSilent() && (stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION)) {
            return 0
        }
        if (isStreamMuted(stream)) return 0
        return getVolume(stream)
    }

    private fun rememberVolume(stream: Int) {
        val v = getVolume(stream)
        if (v > 0) lastVolume[stream] = v
    }

    private fun applyVolume(stream: Int, value: Int) {
        val target = value.coerceIn(1, getMaxVolume(stream))
        try {
            audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {
        }
        try {
            audio.setStreamVolume(stream, target, 0)
        } catch (_: Exception) {
        }
        lastVolume[stream] = target
        if (stream == AudioManager.STREAM_VOICE_CALL) callMuted = false
    }

    private fun muteStream(stream: Int, remember: Boolean = true) {
        if (remember) rememberVolume(stream)
        if (stream == AudioManager.STREAM_VOICE_CALL) callMuted = true
        try {
            audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
        } catch (_: Exception) {
        }
        try {
            audio.setStreamVolume(stream, 0, 0)
        } catch (_: Exception) {
        }
        if (stream == AudioManager.STREAM_VOICE_CALL) enforceCallNotifLink()
    }

    private fun toggleMute(stream: Int) {
        if (stream == AudioManager.STREAM_NOTIFICATION && isCallMuted()) return
        if (isRingerSilent() && (stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION)) {
            selectRingerMode(AudioManager.RINGER_MODE_NORMAL)
            return
        }
        if (isStreamMuted(stream)) {
            val restore = lastVolume[stream] ?: ((getMaxVolume(stream) + 1) / 2).coerceAtLeast(1)
            applyVolume(stream, restore)
        } else {
            muteStream(stream)
        }
        rootView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        refreshRow(stream, animate = true)
        if (stream == AudioManager.STREAM_VOICE_CALL) refreshRow(AudioManager.STREAM_NOTIFICATION, animate = true)
    }

    private fun onSliderValue(stream: Int, value: Int) {
        if (isRingerSilent() && (stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION)) {
            try {
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            } catch (_: Exception) {
            }
        }
        if (stream == AudioManager.STREAM_NOTIFICATION && isCallMuted()) {
            enforceCallNotifLink()
            refreshRow(stream, animate = false)
            return
        }
        if (value <= 0) muteStream(stream) else applyVolume(stream, value)
        if (stream == AudioManager.STREAM_VOICE_CALL) {
            enforceCallNotifLink()
            refreshRow(AudioManager.STREAM_NOTIFICATION, animate = false)
        }
        refreshRow(stream, animate = false)
    }

    private fun enforceCallNotifLink() {
        if (enforceGuard) return
        if (!isCallMuted()) return
        if (isStreamMuted(AudioManager.STREAM_NOTIFICATION)) return
        enforceGuard = true
        try {
            muteStream(AudioManager.STREAM_NOTIFICATION)
            refreshRow(AudioManager.STREAM_NOTIFICATION, animate = true)
        } finally {
            enforceGuard = false
        }
    }

    private fun toggleMiniRinger() {
        val vibrate = try {
            audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE
        } catch (_: Exception) {
            false
        }
        selectRingerMode(if (vibrate) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_VIBRATE)
    }

    private fun selectRingerMode(mode: Int) {
        try {
            audio.ringerMode = mode
        } catch (e: Exception) {
            android.util.Log.w("VolumeButtons", "ringer mode change to $mode failed", e)
        }
        rootView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        handler.postDelayed({
            refreshMiniRinger()
            refreshRow(AudioManager.STREAM_RING, animate = true)
            refreshRow(AudioManager.STREAM_NOTIFICATION, animate = true)
        }, 120)
    }

    private fun refreshMiniRinger() {
        val vibrate = try {
            audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE
        } catch (_: Exception) {
            false
        }
        miniRingerIcon?.setImageResource(if (vibrate) R.drawable.ic_vibrate else R.drawable.ic_ring)
        miniRingerIcon?.contentDescription =
            if (vibrate) "Vibrate, switch to ring" else "Ring, switch to vibrate"
    }

    private fun refreshRow(stream: Int, animate: Boolean) {
        val row = rows[stream] ?: return
        val silent = isRingerSilent() &&
            (stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION)
        val lockedByCall = stream == AudioManager.STREAM_NOTIFICATION && isCallMuted()
        val muted = silent || lockedByCall || isStreamMuted(stream)
        val visible = if (muted) 0 else getVolume(stream)

        if (!row.slider.isDragging) row.slider.setValue(visible, animate)
        row.icon.alpha = if (muted) 0.45f else 1f
        row.iconButtonDescription(row, muted)

        if (stream == AudioManager.STREAM_MUSIC && miniSlider?.isDragging != true) {
            miniSlider?.max = getMaxVolume(stream)
            miniSlider?.setValue(visible, animate)
        }
    }

    private fun VolumeRow.iconButtonDescription(row: VolumeRow, muted: Boolean) {
        (rows[row.def.stream] ?: return).icon.contentDescription =
            if (muted) "Unmute ${row.def.label}" else "Mute ${row.def.label}"
    }

    private fun updateAllRows(animate: Boolean) {
        for (stream in rows.keys) refreshRow(stream, animate)
    }

    // ---------------------------------------------------------- geometry

    private fun edgeX(w: Int): Int = if (onRightSide) screenW - w else 0

    private fun coerceY(y: Int, h: Int, margin: Int = 0): Int {
        val minY = margin.coerceAtLeast(0)
        val maxY = (screenH - h - margin).coerceAtLeast(minY)
        return y.coerceIn(minY, maxY)
    }

    private fun geometry(target: OverlayState, centerY: Int): Target = when (target) {
        OverlayState.TAB -> {
            val w = TAB_W_DP.dp()
            val h = TAB_H_DP.dp()
            Target(
                target, w, h, edgeX(w), coerceY(centerY - h / 2, h),
                intArrayOf(0, 0, 0, 0), 14f, TAB_BLUE, 0f,
            )
        }
        OverlayState.MINI -> {
            val w = ref(MINI_W_REF)
            val h = ref(MINI_H_REF)
            val side = ref(42)
            Target(
                target, w, h, edgeX(w), coerceY(centerY - h / 2, h, EDGE_MARGIN_DP.dp()),
                intArrayOf(side, side, ref(48), ref(24)), 28f, teal, 1f,
            )
        }
        OverlayState.FULL -> {
            val w = ref(FULL_W_REF)
            val h = ref(FULL_H_REF)
            val margin = EDGE_MARGIN_DP.dp()
            val x = if (onRightSide) (screenW - w - margin).coerceAtLeast(0) else margin
            Target(
                target, w, h, x, coerceY(centerY - h / 2, h, margin),
                intArrayOf(0, 0, 0, 0), 28f, teal, 1f,
            )
        }
    }

    private fun openWidth(): Int = when (state) {
        OverlayState.FULL -> ref(FULL_W_REF)
        OverlayState.MINI -> ref(MINI_W_REF)
        OverlayState.TAB -> TAB_W_DP.dp()
    }

    private fun openHeight(): Int = when (state) {
        OverlayState.FULL -> ref(FULL_H_REF)
        OverlayState.MINI -> ref(MINI_H_REF)
        OverlayState.TAB -> TAB_H_DP.dp()
    }

    private fun Target.rect(): IntArray = intArrayOf(
        x + margins[0],
        y + margins[2],
        w - margins[0] - margins[1],
        h - margins[2] - margins[3],
    )

    /**
     * Ease-out card resize inside the fixed full-screen window. No window
     * layout changes happen per frame, so this stays smooth at 60fps.
     */
    private fun transitionTo(
        target: OverlayState,
        fadeIn: View?,
        onEnd: (() -> Unit)? = null,
    ) {
        val surf = surface ?: return
        val drawable = surfaceDrawable ?: return
        val slp = surf.layoutParams as FrameLayout.LayoutParams

        transition?.cancel()
        val gen = ++transitionGeneration
        val centerY = slp.topMargin + slp.height / 2
        val to = geometry(target, centerY)
        state = target

        val fromRect = intArrayOf(slp.leftMargin, slp.topMargin, slp.width, slp.height)
        val toRect = to.rect()
        val fromRadius = surfaceRadius
        val fromColor = surfaceColor
        val fromAlpha = surf.alpha
        val evaluator = ArgbEvaluator()

        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                slp.leftMargin = lerp(fromRect[0], toRect[0], t)
                slp.topMargin = lerp(fromRect[1], toRect[1], t)
                slp.width = lerp(fromRect[2], toRect[2], t)
                slp.height = lerp(fromRect[3], toRect[3], t)
                surf.layoutParams = slp

                surfaceRadius = lerp(fromRadius, to.radius, t)
                drawable.cornerRadius = surfaceRadius * density
                surfaceColor = evaluator.evaluate(t, fromColor, to.color) as Int
                drawable.setColor(surfaceColor)
                surf.alpha = lerp(fromAlpha, to.alpha, t)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (gen != transitionGeneration) return
                    transition = null
                    fadeIn?.let { revealContent(it) }
                    panelLp?.let { lp ->
                        lp.x = toRect[0]
                        lp.y = toRect[1]
                    }
                    persistGeometry()
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    // ---------------------------------------------------------- state changes

    private fun showMini() {
        if (state == OverlayState.MINI) return
        val fromFull = state == OverlayState.FULL
        cancelFade()
        refreshMiniRinger()
        updateAllRows(animate = false)
        openModalWindow()
        if (fromFull) fadeOut(fullContent)
        transitionTo(OverlayState.MINI, miniContent)
    }

    private fun showFull() {
        if (state == OverlayState.FULL) return
        cancelFade()
        refreshMiniRinger()
        updateAllRows(animate = false)
        enforceCallNotifLink()
        openModalWindow()
        fadeOut(miniContent)
        transitionTo(OverlayState.FULL, fullContent)
    }

    private fun collapse() {
        if (state == OverlayState.TAB) return
        cancelFade()
        val closing = if (state == OverlayState.FULL) fullContent else miniContent
        fadeOut(closing)
        val slp = surface?.layoutParams as? FrameLayout.LayoutParams
        val centerY = if (slp != null) slp.topMargin + slp.height / 2 else screenH / 2
        transitionTo(OverlayState.TAB, null) {
            closeModalWindow(centerY)
            tabView?.let { btn -> btn.alpha = COLLAPSED_ALPHA }
            scheduleFade()
        }
    }

    private fun revealContent(view: View?) {
        view ?: return
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(120)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun fadeOut(view: View?) {
        view ?: return
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) return
        view.animate().alpha(0f).setDuration(100)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    private fun hideContent(view: View) {
        view.visibility = View.GONE
        view.alpha = 0f
    }

    private fun openModalWindow() {
        scrimLp?.let { lp ->
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            lp.alpha = 1f
            scrimView?.let { updateWindowSafe(it, lp) }
        }
        rootView?.let { rv ->
            panelLp?.let { lp ->
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                updateWindowSafe(rv, lp)
            }
        }
        tabView?.alpha = 0f
    }

    private fun closeModalWindow(centerY: Int) {
        val tab = geometry(OverlayState.TAB, centerY)
        panelLp?.let { lp ->
            lp.x = tab.x
            lp.y = tab.y
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            rootView?.let { updateWindowSafe(it, lp) }
        }
        tabView?.alpha = COLLAPSED_ALPHA
        scrimLp?.let { lp ->
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            lp.alpha = 0.79f
            scrimView?.let { updateWindowSafe(it, lp) }
        }
    }

    // ---------------------------------------------------------- drag & snap

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartLpX = 0
    private var dragStartLpY = 0
    private var dragged = false

    private fun attachDrag(view: View) {
        view.setOnTouchListener { v, event ->
            if (transition?.isRunning == true) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartLpX = panelLp?.x ?: 0
                    dragStartLpY = panelLp?.y ?: 0
                    dragged = false
                    pokeAlpha()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!dragged && hypot(dx, dy) > 8 * density) dragged = true
                    if (dragged) {
                        val viewW = openWidth()
                        val viewH = openHeight()
                        panelLp?.let { lp ->
                            lp.x = (dragStartLpX + dx).toInt().coerceIn(0, (screenW - viewW).coerceAtLeast(0))
                            lp.y = (dragStartLpY + dy).toInt().coerceIn(0, (screenH - viewH).coerceAtLeast(0))
                            rootView?.let { updateWindowSafe(it, lp) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) {
                        snapToNearestEdge()
                        dragged = false
                        scheduleFade()
                    } else if (v === tabView && state == OverlayState.TAB) {
                        showMini()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun snapToNearestEdge() {
        val viewW = openWidth()
        val lp = panelLp ?: return
        val root = rootView ?: return
        val margin = if (state == OverlayState.TAB) 0 else EDGE_MARGIN_DP.dp()
        val targetX = if (lp.x + viewW / 2 < screenW / 2) {
            margin
        } else {
            (screenW - viewW - margin).coerceAtLeast(0)
        }
        onRightSide = targetX > screenW / 2
        tabView?.apply {
            setImageResource(tabChevron())
            setBackgroundResource(tabBackground())
        }
        if (state != OverlayState.TAB) {
            lp.y = coerceY(lp.y, openHeight(), margin)
        }
        ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.x = it.animatedValue as Int
                updateWindowSafe(root, lp)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = persistGeometry()
            })
            start()
        }
    }

    private fun persistGeometry() {
        panelLp?.let { lp ->
            prefs.edit()
                .putInt(KEY_EDGE, if (onRightSide) 1 else 0)
                .putInt(KEY_Y, lp.y)
                .apply()
        }
    }

    private fun tabBackground(): Int =
        if (onRightSide) R.drawable.overlay_tab_bg_right else R.drawable.overlay_tab_bg_left

    private fun tabChevron(): Int =
        if (onRightSide) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right

    // ---------------------------------------------------------- idle fade

    private fun pokeAlpha() {
        cancelFade()
        tabView?.let { if (it.visibility == View.VISIBLE) it.alpha = COLLAPSED_ALPHA }
    }

    private fun scheduleFade() {
        cancelFade()
        if (state != OverlayState.TAB) return
        fadeRunnable = Runnable {
            tabView?.animate()?.alpha(COLLAPSED_IDLE_ALPHA)?.setDuration(450)?.start()
        }
        handler.postDelayed(fadeRunnable!!, FADE_DELAY_MS)
    }

    private fun cancelFade() {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
    }

    // ---------------------------------------------------------- observers

    private val volumeSync = Runnable {
        if (enforceGuard) return@Runnable
        enforceCallNotifLink()
        updateAllRows(animate = true)
    }

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(volumeSync)
            handler.postDelayed(volumeSync, 80)
        }
    }

    private val ringerSync = Runnable {
        refreshMiniRinger()
        refreshRow(AudioManager.STREAM_RING, animate = true)
        refreshRow(AudioManager.STREAM_NOTIFICATION, animate = true)
    }

    private val ringerObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(ringerSync)
            handler.postDelayed(ringerSync, 120)
        }
    }

    private fun registerObservers() {
        try {
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.MODE_RINGER), false, ringerObserver,
            )
        } catch (_: Exception) {
        }
    }

    private fun unregisterObservers() {
        try { contentResolver.unregisterContentObserver(volumeObserver) } catch (_: Exception) {}
        try { contentResolver.unregisterContentObserver(ringerObserver) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------- helpers

    private fun roundedDrawable(color: Int, radiusDp: Int, rippleColor: Int? = null): android.graphics.drawable.Drawable {
        val radius = radiusDp * density
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
        if (rippleColor == null) return content
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun ovalDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun ovalRipple(color: Int): RippleDrawable {
        val content = ovalDrawable(color)
        val mask = ovalDrawable(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(rippleLight), content, mask)
    }

    // ---------------------------------------------------------- window helpers

    private fun addWindowSafe(view: View, lp: WindowManager.LayoutParams): Boolean = try {
        wm.addView(view, lp)
        true
    } catch (se: SecurityException) {
        stopSelf()
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun updateWindowSafe(view: View, lp: WindowManager.LayoutParams): Boolean = try {
        wm.updateViewLayout(view, lp)
        true
    } catch (se: SecurityException) {
        stopSelf()
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    // ---------------------------------------------------------- notification (hidden)

    private fun createNotificationChannel() {
        // IMPORTANCE_NONE keeps the foreground service alive without showing a
        // notification in the shade or status bar.
        // The channel may already exist from an older build with a visible
        // importance; delete it first so the hidden channel actually applies.
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .deleteNotificationChannel(CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Floating volume button",
            NotificationManager.IMPORTANCE_NONE,
        ).apply {
            description = "Keeps the floating volume button alive"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).let { intent ->
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume)
            .setContentTitle("Volume Buttons")
            .setContentText("Floating sound controls are active")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        cancelFade()
        transition?.cancel()
        unregisterObservers()
        handler.removeCallbacks(volumeSync)
        handler.removeCallbacks(ringerSync)
        try { scrimView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { rootView?.let { wm.removeView(it) } } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }
}
