package com.phonebuttons.control.phone_volume

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
import android.graphics.Typeface
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Always-on edge overlay that replaces broken hardware volume keys.
 *
 * Collapsed: a small blue half-pill flush with the screen edge.
 * Expanded: a Material 3 / Pixel-style sound panel with ring modes and five
 * continuously-draggable volume streams. The card uses window blur on devices
 * that support cross-window blur (Android 12+), and is fully opaque when it is not
 * available so nothing underneath shows through.
 */
class VolumeOverlayService : android.app.Service() {

    private lateinit var wm: WindowManager
    private lateinit var audio: AudioManager
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null

    private var screenW = 1080
    private var screenH = 2400
    private var panelWidthPx = 0

    private var scrimView: View? = null
    private var scrimLp: WindowManager.LayoutParams? = null
    private var rootView: FrameLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var collapsedView: ImageView? = null
    private var expandedView: LinearLayout? = null

    private val rows = LinkedHashMap<Int, VolumeRow>()
    private val modeChips = LinkedHashMap<Int, ModeChip>()
    private val lastVolume = HashMap<Int, Int>()
    private var callMuted = false
    private var enforceGuard = false

    private var isExpanded = false
    private var onRightSide = true
    private var attached = false

    private val density get() = resources.displayMetrics.density
    private fun Int.dp() = (this * density).toInt()

    // Pixel / Material 3 dark palette with Pixel blue.
    private val blue = Color.parseColor("#A8C7FA")
    private val onBlue = Color.parseColor("#00315D")
    private val textPrimary = Color.parseColor("#E3E3E3")
    private val textSecondary = Color.parseColor("#9AA0A6")
    private val iconIdle = Color.parseColor("#C4C7C5")
    private val scrimColor = Color.parseColor("#66000000")
    private val rippleLight = Color.parseColor("#33FFFFFF")
    private val rippleDark = Color.parseColor("#4000315D")

    private data class StreamDef(val stream: Int, val iconRes: Int, val label: String)
    private data class RingerDef(val mode: Int, val label: String, val iconRes: Int)
    private class VolumeRow(
        val def: StreamDef,
        val slider: HorizontalVolumeSlider,
        val icon: ImageView,
        val label: TextView,
        val percent: TextView,
        val iconButton: FrameLayout,
    )
    private class ModeChip(
        val container: LinearLayout,
        val icon: ImageView,
        val label: TextView,
    )

    private val streams = listOf(
        StreamDef(AudioManager.STREAM_MUSIC, R.drawable.ic_media, "Media"),
        StreamDef(AudioManager.STREAM_VOICE_CALL, R.drawable.ic_call, "Call"),
        StreamDef(AudioManager.STREAM_RING, R.drawable.ic_ring, "Ring"),
        StreamDef(AudioManager.STREAM_NOTIFICATION, R.drawable.ic_notif, "Notifications"),
        StreamDef(AudioManager.STREAM_ALARM, R.drawable.ic_alarm, "Alarm"),
    )

    private val ringerModes = listOf(
        RingerDef(AudioManager.RINGER_MODE_NORMAL, "Ring", R.drawable.ic_ring_mode),
        RingerDef(AudioManager.RINGER_MODE_VIBRATE, "Vibrate", R.drawable.ic_vibrate),
    )

    companion object {
        const val PREF = "volume_overlay_prefs"
        const val KEY_ENABLED = "overlay_enabled"
        const val KEY_EDGE = "overlay_edge" // 0 = left, 1 = right
        const val KEY_Y = "overlay_y"
        const val CHANNEL_ID = "volume_overlay_channel"
        const val NOTIFICATION_ID = 7001
        private const val BUTTON_DP = 24
        private const val BUTTON_HEIGHT_DP = 76
        private const val PANEL_DP = 340
        private const val EDGE_MARGIN_DP = 8
        private const val FADE_DELAY_MS = 3500L
        private const val COLLAPSED_ALPHA = 0.85f
        private const val COLLAPSED_IDLE_ALPHA = 0.30f
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
        refreshModeChips()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!attached && Settings.canDrawOverlays(this)) attachViews()
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    // ---------------------------------------------------------- views

    private fun attachViews() {
        if (attached) return
        attached = true

        scrimView = View(this).apply {
            setBackgroundColor(scrimColor)
            alpha = 0f
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
            // WindowManager caps non-touchable overlay windows at 0.8 alpha.
            alpha = 0.79f
        }
        addWindowSafe(scrimView!!, scrimLp!!)

        collapsedView = ImageView(this).apply {
            setImageResource(tabChevron())
            setBackgroundResource(tabBackground())
            imageTintList = ColorStateList.valueOf(onBlue)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
            alpha = COLLAPSED_ALPHA
            contentDescription = "Open sound controls"
        }
        attachDrag(collapsedView!!)

        panelWidthPx = min(PANEL_DP.dp(), screenW - 2 * EDGE_MARGIN_DP.dp())
        expandedView = buildPanel().apply {
            visibility = View.GONE
            alpha = 0f
        }
        applyCardSurface()
        attachDrag(expandedView!!)

        rootView = FrameLayout(this).apply {
            addView(collapsedView, FrameLayout.LayoutParams(BUTTON_DP.dp(), BUTTON_HEIGHT_DP.dp()))
            addView(expandedView, FrameLayout.LayoutParams(panelWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val startY = coerceY(prefs.getInt(KEY_Y, screenH / 3), BUTTON_HEIGHT_DP.dp())
        panelLp = WindowManager.LayoutParams(
            BUTTON_DP.dp(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = edgeX(BUTTON_DP.dp())
            y = startY
        }
        addWindowSafe(rootView!!, panelLp!!)
        scheduleFade()
    }

    private fun buildPanel(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 10.dp(), 18.dp(), 14.dp())
            setBackgroundResource(R.drawable.overlay_card_bg)
            clipToOutline = true
        }

        // Sheet-style grabber.
        val grabWrap = FrameLayout(this)
        grabWrap.addView(
            View(this).apply { background = roundedDrawable(Color.parseColor("#5F6368"), 2) },
            FrameLayout.LayoutParams(34.dp(), 4.dp(), Gravity.CENTER),
        )
        card.addView(grabWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12.dp()))

        // Header.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Sound & vibration"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(iconIdle)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            background = roundedDrawable(Color.TRANSPARENT, 18, rippleLight)
            contentDescription = "Close"
            setOnClickListener { collapse() }
        }, LinearLayout.LayoutParams(36.dp(), 36.dp()))
        card.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 42.dp(),
        ).apply { topMargin = 4.dp() })

        // Ring mode segmented control.
        card.addView(buildModeSelector(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 44.dp(),
        ).apply { topMargin = 12.dp() })

        // Stream rows.
        var first = true
        for (def in streams) {
            card.addView(buildRow(def), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = if (first) 12.dp() else 2.dp() })
            first = false
        }

        refreshModeChips()
        return card
    }

    private fun buildModeSelector(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(3.dp(), 3.dp(), 3.dp(), 3.dp())
            background = roundedDrawable(Color.parseColor("#2A2B2E"), 22)
        }
        ringerModes.forEachIndexed { index, def ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = roundedDrawable(Color.TRANSPARENT, 19, rippleLight)
                setOnClickListener { selectRingerMode(def.mode) }
            }
            val icon = ImageView(this).apply {
                setImageResource(def.iconRes)
                imageTintList = ColorStateList.valueOf(iconIdle)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            chip.addView(icon, LinearLayout.LayoutParams(15.dp(), 15.dp()).apply {
                marginEnd = 6.dp()
            })
            val label = TextView(this).apply {
                text = def.label
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(iconIdle)
                includeFontPadding = false
            }
            chip.addView(label)
            container.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = if (index == 0) 0 else 2.dp()
            })
            modeChips[def.mode] = ModeChip(chip, icon, label)
        }
        return container
    }

    private fun buildRow(def: StreamDef): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 2.dp(), 0, 2.dp())
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconButton = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            background = roundedDrawable(Color.TRANSPARENT, 13, rippleLight)
            setOnClickListener { toggleMute(def.stream) }
        }
        val icon = ImageView(this).apply {
            setImageResource(def.iconRes)
            imageTintList = ColorStateList.valueOf(iconIdle)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(3.dp(), 3.dp(), 3.dp(), 3.dp())
        }
        iconButton.addView(icon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        top.addView(iconButton, LinearLayout.LayoutParams(26.dp(), 26.dp()))

        val label = TextView(this).apply {
            text = def.label
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            includeFontPadding = false
        }
        top.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = 8.dp() })

        top.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))

        val percent = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textSecondary)
            includeFontPadding = false
            gravity = Gravity.END
        }
        top.addView(percent)

        val max = try { audio.getStreamMaxVolume(def.stream) } catch (_: Exception) { 15 }
        val slider = HorizontalVolumeSlider(this).apply {
            this.max = max.coerceAtLeast(1)
            setValue(displayVolume(def.stream), false)
            contentDescription = "${def.label} volume"
            onValueChanged = { value -> onSliderValue(def.stream, value) }
        }

        row.addView(top, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        row.addView(slider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 38.dp(),
        ).apply { topMargin = 1.dp() })

        rows[def.stream] = VolumeRow(def, slider, icon, label, percent, iconButton)
        refreshRow(def.stream, animate = false)
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
        val max = getMaxVolume(stream)
        val target = value.coerceIn(1, max)
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
            // The row reads "Silent" because of the ringer mode; tapping it returns to Ring.
            selectRingerMode(AudioManager.RINGER_MODE_NORMAL)
            return
        }
        if (isStreamMuted(stream)) {
            val restore = lastVolume[stream] ?: ((getMaxVolume(stream) + 1) / 2).coerceAtLeast(1)
            applyVolume(stream, restore)
        } else {
            muteStream(stream)
        }
        expandedView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        refreshRow(stream, animate = true)
        if (stream == AudioManager.STREAM_VOICE_CALL) refreshRow(AudioManager.STREAM_NOTIFICATION, animate = true)
    }

    private fun onSliderValue(stream: Int, value: Int) {
        if (isRingerSilent() && (stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION)) {
            try {
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            } catch (_: Exception) {
            }
            refreshModeChips()
        }
        if (stream == AudioManager.STREAM_NOTIFICATION && isCallMuted()) {
            enforceCallNotifLink()
            refreshRow(stream, animate = false)
            return
        }
        if (value <= 0) {
            muteStream(stream)
        } else {
            applyVolume(stream, value)
        }
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
            rows[AudioManager.STREAM_NOTIFICATION]?.let { refreshRow(AudioManager.STREAM_NOTIFICATION, animate = true) }
        } finally {
            enforceGuard = false
        }
    }

    private fun selectRingerMode(mode: Int) {
        try {
            audio.ringerMode = mode
        } catch (e: Exception) {
            android.util.Log.w("VolumeButtons", "ringer mode change to $mode failed", e)
        }
        expandedView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        handler.postDelayed({
            refreshModeChips()
            refreshRow(AudioManager.STREAM_RING, animate = true)
            refreshRow(AudioManager.STREAM_NOTIFICATION, animate = true)
        }, 120)
    }

    private fun refreshRow(stream: Int, animate: Boolean) {
        val row = rows[stream] ?: return
        val silent = isRingerSilent() &&
            (stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION)
        val lockedByCall = stream == AudioManager.STREAM_NOTIFICATION && isCallMuted()
        val muted = silent || lockedByCall || isStreamMuted(stream)
        val visible = if (muted) 0 else getVolume(stream)

        if (!row.slider.isDragging) {
            row.slider.setValue(visible, animate)
        }
        row.slider.muted = muted
        row.slider.isEnabled = !lockedByCall
        row.slider.alpha = if (lockedByCall) 0.45f else 1f

        row.percent.text = when {
            lockedByCall -> "Muted by call"
            silent -> "Silent"
            muted -> "Muted"
            else -> "${(getVolume(stream) * 100f / getMaxVolume(stream)).roundToInt()}%"
        }
        row.percent.setTextColor(if (muted) blue else textSecondary)
        row.icon.imageTintList = ColorStateList.valueOf(if (muted) blue else iconIdle)
        row.label.setTextColor(if (lockedByCall) textSecondary else textPrimary)
        row.iconButton.contentDescription = if (muted) "Unmute ${row.def.label}" else "Mute ${row.def.label}"
    }

    private fun updateAllRows(animate: Boolean) {
        for (stream in rows.keys) refreshRow(stream, animate)
    }

    private fun refreshModeChips() {
        val current = try {
            audio.ringerMode
        } catch (_: Exception) {
            AudioManager.RINGER_MODE_NORMAL
        }
        for ((mode, chip) in modeChips) {
            val selected = mode == current
            chip.container.background = if (selected) {
                roundedDrawable(blue, 19, rippleDark)
            } else {
                roundedDrawable(Color.TRANSPARENT, 19, rippleLight)
            }
            val fg = if (selected) onBlue else iconIdle
            chip.icon.imageTintList = ColorStateList.valueOf(fg)
            chip.label.setTextColor(fg)
            chip.container.contentDescription = chip.label.text.toString()
        }
    }

    // ---------------------------------------------------------- expand / collapse

    private fun expand() {
        if (isExpanded) return
        isExpanded = true
        android.util.Log.d("VolumeButtons", "expand edge=${if (onRightSide) "right" else "left"} y=${panelLp?.y}")
        cancelFade()
        refreshModeChips()
        updateAllRows(animate = false)
        enforceCallNotifLink()

        val root = rootView ?: return
        val lp = panelLp ?: return
        val w = panelWidthPx
        lp.width = w
        lp.x = expandedEdgeX(w)
        lp.y = coerceY(lp.y, currentCardHeight(), EDGE_MARGIN_DP.dp())
        applyBlur(true)
        updateWindowSafe(root, lp)

        expandedView?.post {
            val h = expandedView?.height ?: 0
            if (h > 0) {
                val y = coerceY(lp.y, h, EDGE_MARGIN_DP.dp())
                if (y != lp.y) {
                    lp.y = y
                    rootView?.let { updateWindowSafe(it, lp) }
                }
            }
        }

        scrimLp?.let { slp ->
            slp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            slp.alpha = 1f
            scrimView?.let { updateWindowSafe(it, slp) }
        }
        scrimView?.animate()?.alpha(1f)?.setDuration(200)?.start()

        collapsedView?.let { btn ->
            btn.animate().alpha(0f).setDuration(120).withEndAction {
                btn.visibility = View.GONE
                btn.alpha = COLLAPSED_ALPHA
            }.start()
        }

        expandedView?.let { card ->
            card.visibility = View.VISIBLE
            card.alpha = 0f
            card.translationX = if (onRightSide) 26.dp().toFloat() else -26.dp().toFloat()
            card.scaleX = 0.97f
            card.scaleY = 0.97f
            card.pivotX = if (onRightSide) w.toFloat() else 0f
            card.pivotY = 0f
            card.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    private fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        android.util.Log.d("VolumeButtons", "collapse")
        cancelFade()

        scrimView?.animate()?.alpha(0f)?.setDuration(170)?.start()
        scrimLp?.let { slp ->
            slp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            slp.alpha = 0.79f
            scrimView?.let { updateWindowSafe(it, slp) }
        }

        expandedView?.let { card ->
            card.animate()
                .alpha(0f)
                .translationX(if (onRightSide) 22.dp().toFloat() else -22.dp().toFloat())
                .scaleX(0.98f)
                .scaleY(0.98f)
                .setDuration(150)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    card.visibility = View.GONE
                    card.alpha = 1f
                    card.translationX = 0f
                    card.scaleX = 1f
                    card.scaleY = 1f
                    applyBlur(false)
                    val root = rootView
                    val lp = panelLp
                    if (root != null && lp != null) {
                        val w = BUTTON_DP.dp()
                        lp.width = w
                        lp.x = edgeX(w)
                        lp.y = coerceY(lp.y, BUTTON_HEIGHT_DP.dp())
                        updateWindowSafe(root, lp)
                    }
                    collapsedView?.let { btn ->
                        btn.setImageResource(tabChevron())
                        btn.setBackgroundResource(tabBackground())
                        btn.visibility = View.VISIBLE
                        btn.alpha = 0f
                        btn.animate().alpha(COLLAPSED_ALPHA).setDuration(170).start()
                    }
                    persistGeometry()
                    scheduleFade()
                }
                .start()
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
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartLpX = panelLp?.x ?: 0
                    dragStartLpY = panelLp?.y ?: 0
                    dragged = false
                    v.isPressed = true
                    pokeAlpha()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!dragged && hypot(dx, dy) > 8 * density) {
                        dragged = true
                        pokeAlpha()
                    }
                    if (dragged) {
                        val viewW = if (isExpanded) panelWidthPx else BUTTON_DP.dp()
                        val viewH = if (isExpanded) currentCardHeight() else BUTTON_HEIGHT_DP.dp()
                        panelLp?.let { lp ->
                            lp.x = (dragStartLpX + dx).toInt().coerceIn(0, (screenW - viewW).coerceAtLeast(0))
                            lp.y = (dragStartLpY + dy).toInt().coerceIn(0, (screenH - viewH).coerceAtLeast(0))
                            rootView?.let { updateWindowSafe(it, lp) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (dragged) {
                        snapToNearestEdge()
                        dragged = false
                        scheduleFade()
                    } else if (v is ImageView && !isExpanded) {
                        expand()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun snapToNearestEdge() {
        val viewW = if (isExpanded) panelWidthPx else BUTTON_DP.dp()
        val lp = panelLp ?: return
        val root = rootView ?: return
        val margin = if (isExpanded) EDGE_MARGIN_DP.dp() else 0
        val targetX = if (lp.x + viewW / 2 < screenW / 2) {
            margin
        } else {
            (screenW - viewW - margin).coerceAtLeast(0)
        }
        android.util.Log.d("VolumeButtons", "snap from=(${lp.x},${lp.y}) to=$targetX")
        onRightSide = targetX > screenW / 2
        collapsedView?.apply {
            setImageResource(tabChevron())
            setBackgroundResource(tabBackground())
        }
        if (isExpanded) {
            val y = coerceY(lp.y, currentCardHeight(), margin)
            lp.y = y
        }
        val animator = android.animation.ValueAnimator.ofInt(lp.x, targetX)
        animator.duration = 220
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { va ->
            lp.x = va.animatedValue as Int
            updateWindowSafe(root, lp)
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                persistGeometry()
            }
        })
        animator.start()
    }

    private fun persistGeometry() {
        panelLp?.let { lp ->
            prefs.edit()
                .putInt(KEY_EDGE, if (onRightSide) 1 else 0)
                .putInt(KEY_Y, lp.y)
                .apply()
        }
    }

    private fun currentCardHeight(): Int {
        val measured = expandedView?.height ?: 0
        return if (measured > 0) measured else 500.dp()
    }

    private fun edgeX(viewW: Int): Int = if (onRightSide) screenW - viewW else 0

    private fun expandedEdgeX(viewW: Int): Int {
        val margin = EDGE_MARGIN_DP.dp()
        return if (onRightSide) (screenW - viewW - margin).coerceAtLeast(0) else margin
    }

    private fun tabBackground(): Int =
        if (onRightSide) R.drawable.overlay_tab_bg_right else R.drawable.overlay_tab_bg_left

    private fun tabChevron(): Int =
        if (onRightSide) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right

    private fun coerceY(y: Int, viewH: Int, margin: Int = 0): Int {
        val minY = margin.coerceAtLeast(0)
        val maxY = (screenH - viewH - margin).coerceAtLeast(minY)
        return y.coerceIn(minY, maxY)
    }

    // ---------------------------------------------------------- idle fade

    private fun pokeAlpha() {
        cancelFade()
        expandedView?.let { if (it.visibility == View.VISIBLE) it.alpha = 1f }
        collapsedView?.let { if (it.visibility == View.VISIBLE) it.alpha = COLLAPSED_ALPHA }
    }

    private fun scheduleFade() {
        cancelFade()
        if (isExpanded) return
        fadeRunnable = Runnable {
            collapsedView?.animate()?.alpha(COLLAPSED_IDLE_ALPHA)?.setDuration(450)?.start()
        }
        handler.postDelayed(fadeRunnable!!, FADE_DELAY_MS)
    }

    private fun cancelFade() {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
    }

    // ---------------------------------------------------------- blur

    private fun crossWindowBlurAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            wm.isCrossWindowBlurEnabled
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyCardSurface() {
        // With blur we can afford a hint of translucency; without it the card must stay readable.
        val alpha = if (crossWindowBlurAvailable()) 0xF2 else 0xFF
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f * density
            setColor((alpha shl 24) or 0x1E1F22)
            setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#1FFFFFFF"))
        }
        expandedView?.background = bg
    }

    private fun applyBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val lp = panelLp ?: return
        try {
            if (enabled && wm.isCrossWindowBlurEnabled) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.setBlurBehindRadius(28.dp())
            } else {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                lp.setBlurBehindRadius(0)
            }
        } catch (_: Throwable) {
            // Blur is a visual bonus; the translucent card still looks right without it.
        }
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
        refreshModeChips()
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
            // Observers are a convenience; sliders still work without them.
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

    // ---------------------------------------------------------- window helpers

    private fun addWindowSafe(view: View, lp: WindowManager.LayoutParams): Boolean {
        return try {
            wm.addView(view, lp)
            true
        } catch (se: SecurityException) {
            stopSelf()
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun updateWindowSafe(view: View, lp: WindowManager.LayoutParams): Boolean {
        return try {
            wm.updateViewLayout(view, lp)
            true
        } catch (se: SecurityException) {
            stopSelf()
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    // ---------------------------------------------------------- foreground notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Floating volume button",
            NotificationManager.IMPORTANCE_LOW,
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
            .setContentText("Floating sound controls are active — tap the edge tab")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        cancelFade()
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
