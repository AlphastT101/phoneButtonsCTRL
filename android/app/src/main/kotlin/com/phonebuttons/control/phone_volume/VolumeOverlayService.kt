package com.phonebuttons.control.phone_volume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import kotlin.math.hypot

/**
 * Always-on edge overlay that replaces broken hardware volume keys:
 *  - Collapsed: small round button on the left or right screen edge (slightly transparent).
 *  - Expanded: card with a vertical volume slider, full opacity, dimmed scrim behind.
 * Tap outside the card (scrim) or the ✕ button collapses it.
 * After a few idle seconds the expanded card fades to slightly transparent.
 */
class VolumeOverlayService : android.app.Service() {

    private lateinit var wm: WindowManager
    private lateinit var audio: android.media.AudioManager
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null

    private var screenW = 1080
    private var screenH = 2400

    private var scrimView: View? = null
    private var scrimLp: WindowManager.LayoutParams? = null
    private var rootView: FrameLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var collapsedView: ImageView? = null
    private var expandedView: LinearLayout? = null
    private var percentText: TextView? = null
    private var slider: VerticalVolumeSlider? = null

    private var isExpanded = false
    private var onRightSide = true
    private var attached = false

    private val density get() = resources.displayMetrics.density
    private fun Int.dp() = (this * density).toInt()

    companion object {
        const val PREF = "volume_overlay_prefs"
        const val KEY_ENABLED = "overlay_enabled"
        const val KEY_EDGE = "overlay_edge" // 0 = left, 1 = right
        const val KEY_Y = "overlay_y"
        const val CHANNEL_ID = "volume_overlay_channel"
        const val NOTIFICATION_ID = 7001
        private const val BUTTON_DP = 26
        private const val BUTTON_HEIGHT_DP = 84
        private const val PANEL_DP = 96
        private const val FADE_DELAY_MS = 3500L
        private const val EXPANDED_ALPHA = 1f
        private const val COLLAPSED_ALPHA = 0.6f
        private const val COLLAPSED_IDLE_ALPHA = 0.35f
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
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

        // Scrim window: always attached, but untouchable + invisible while collapsed.
        scrimView = View(this).apply {
            setBackgroundColor(Color.parseColor("#4D000000"))
            alpha = 0f
            setOnClickListener { collapse() }
        }
        scrimLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        addWindowSafe(scrimView!!, scrimLp!!)

        // Collapsed edge tab (flush with the screen border, flat side against it)
        collapsedView = ImageView(this).apply {
            setImageResource(tabChevron())
            setBackgroundResource(tabBackground())
            scaleType = ImageView.ScaleType.CENTER
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
            alpha = COLLAPSED_ALPHA
            contentDescription = "Volume control"
        }
        attachDrag(collapsedView!!)

        // Expanded card
        expandedView = buildExpandedPanel().apply {
            visibility = View.GONE
            alpha = 0f
        }
        attachDrag(expandedView!!)

        rootView = FrameLayout(this).apply {
            addView(collapsedView, FrameLayout.LayoutParams(BUTTON_DP.dp(), BUTTON_HEIGHT_DP.dp()))
            addView(expandedView, FrameLayout.LayoutParams(PANEL_DP.dp(), WRAP_CONTENT))
        }

        val startY = coerceY(prefs.getInt(KEY_Y, screenH / 3), BUTTON_HEIGHT_DP.dp())
        panelLp = WindowManager.LayoutParams(
            BUTTON_DP.dp(),
            WRAP_CONTENT,
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

    private fun buildExpandedPanel(): LinearLayout {
        val horizontal = 10.dp()
        val vertical = 12.dp()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_volume)
            layoutParams = LinearLayout.LayoutParams(20.dp(), 20.dp())
        }
        val spacer = android.widget.Space(this)
        val iconLp = LinearLayout.LayoutParams(0, 1, 1f)
        spacer.layoutParams = iconLp

        val closeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(R.drawable.overlay_button_bg)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
            contentDescription = "Close"
            setOnClickListener { collapse() }
        }
        header.addView(icon)
        header.addView(spacer)
        header.addView(closeBtn, LinearLayout.LayoutParams(34.dp(), 34.dp()))

        val maxVolume = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        slider = VerticalVolumeSlider(this).apply {
            max = maxVolume
            value = current
            onStepChanged = { v ->
                try {
                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0)
                } catch (_: SecurityException) {
                    // Never crash the overlay on restricted audio profiles.
                }
                refreshPercent()
            }
        }
        val sliderLp = LinearLayout.LayoutParams(56.dp(), 208.dp()).apply {
            gravity = Gravity.CENTER
            topMargin = 8.dp()
            bottomMargin = 2.dp()
        }

        percentText = TextView(this).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#F0883E"))
            gravity = Gravity.CENTER
        }
        refreshPercent(current, maxVolume)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, vertical, horizontal, vertical)
            setBackgroundResource(R.drawable.overlay_card_bg)
            addView(header, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 38.dp(),
            ))
            addView(slider, sliderLp)
            addView(percentText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT,
            ))
        }
    }

    private fun refreshPercent(value: Int = slider?.value ?: 0, max: Int = slider?.max ?: 15) {
        val pct = if (max <= 0) 0 else (value * 100f / max).toInt()
        percentText?.text = "$pct%"
    }

    // ---------------------------------------------------------- expand / collapse

    private fun expand() {
        if (isExpanded) return
        isExpanded = true
        val root = rootView ?: return
        val lp = panelLp ?: return
        val w = PANEL_DP.dp()
        lp.width = w
        lp.x = edgeX(w)
        lp.y = coerceY(lp.y, EXPANDED_HEIGHT_PX())
        updateWindowSafe(root, lp)

        // Scrim on while expanded
        scrimLp?.let { slp ->
            slp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            scrimView?.let { updateWindowSafe(it, slp) }
        }
        scrimView?.animate()?.alpha(1f)?.setDuration(220)?.withEndAction(null)?.start()

        collapsedView?.let { btn ->
            btn.animate().alpha(0f).setDuration(140).withEndAction {
                btn.visibility = View.GONE
                btn.alpha = COLLAPSED_ALPHA
            }.start()
        }
        expandedView?.let { card ->
            card.visibility = View.VISIBLE
            card.alpha = 0f
            card.translationX = if (onRightSide) 64.dp().toFloat() else -64.dp().toFloat()
            card.animate()
                .alpha(EXPANDED_ALPHA)
                .translationX(0f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
        pokeAlpha()
        // Expanded card never fades — restore full opacity explicitly.
        expandedView?.alpha = EXPANDED_ALPHA
    }

    private fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        val root = rootView ?: return
        val lp = panelLp ?: return
        cancelFade()

        scrimView?.animate()?.alpha(0f)?.setDuration(200)?.start()
        scrimLp?.let { slp ->
            slp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            scrimView?.let { updateWindowSafe(it, slp) }
        }

        expandedView?.let { card ->
            card.animate()
                .alpha(0f)
                .translationX(if (onRightSide) 56.dp().toFloat() else -56.dp().toFloat())
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { card.visibility = View.GONE }
                .start()
        }

        val w = BUTTON_DP.dp()
        lp.width = w
        lp.x = edgeX(w)
        lp.y = coerceY(lp.y, BUTTON_HEIGHT_DP.dp())
        updateWindowSafe(root, lp)

        collapsedView?.let { btn ->
            btn.visibility = View.VISIBLE
            btn.alpha = 0f
            btn.animate().alpha(COLLAPSED_ALPHA).setDuration(200).start()
        }
        scheduleFade()
        persistGeometry()
    }

    // ---------------------------------------------------------- drag & snap

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartLpX = 0
    private var dragStartLpY = 0
    private var dragged = false

    private fun attachDrag(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
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
                    if (!dragged && hypot(dx, dy) > 8 * density) {
                        dragged = true
                        pokeAlpha()
                    }
                    if (dragged) {
                        val viewW = if (isExpanded) PANEL_DP.dp() else BUTTON_DP.dp()
                        val viewH = if (isExpanded) currentCardHeight() else BUTTON_HEIGHT_DP.dp()
                        panelLp?.let { lp ->
                            lp.x = (dragStartLpX + dx).toInt().coerceIn(0, screenW - viewW)
                            lp.y = (dragStartLpY + dy).toInt().coerceIn(0, screenH - viewH)
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
        val viewW = if (isExpanded) PANEL_DP.dp() else BUTTON_DP.dp()
        val lp = panelLp ?: return
        val root = rootView ?: return
        val targetX = if (lp.x + viewW / 2 < screenW / 2) 0 else screenW - viewW
        onRightSide = targetX != 0
        collapsedView?.apply {
            setImageResource(tabChevron())
            setBackgroundResource(tabBackground())
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
        return if (measured > 0) measured else 300.dp()
    }

    private fun EXPANDED_HEIGHT_PX(): Int = currentCardHeight().coerceAtLeast(200.dp())

    private fun edgeX(viewW: Int): Int = if (onRightSide) screenW - viewW else 0

    private fun tabBackground(): Int =
        if (onRightSide) R.drawable.overlay_tab_bg_right else R.drawable.overlay_tab_bg_left

    private fun tabChevron(): Int =
        if (onRightSide) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right

    private fun coerceY(y: Int, viewH: Int): Int = y.coerceIn(0, (screenH - viewH).coerceAtLeast(0))

    // ---------------------------------------------------------- idle fade

    private fun pokeAlpha() {
        cancelFade()
        expandedView?.let { if (it.visibility == View.VISIBLE) it.alpha = EXPANDED_ALPHA }
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
            .setContentText("Floating volume control is active — tap it on the screen edge")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        cancelFade()
        try { scrimView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { rootView?.let { wm.removeView(it) } } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }
}
