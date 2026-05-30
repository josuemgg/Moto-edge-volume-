package com.edgevolume

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.*
import android.view.*
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat

class EdgeVolumeService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager

    private var overlayView: View? = null
    private var fillView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    // Gesture tracking
    private var touchStartY = 0f
    private var touchStartRawY = 0f
    private var touchStartRawX = 0f
    private var lastStepY = 0f
    private var lastVolumeChangeMs = 0L
    private var isDraggingPosition = false
    private var initialParamY = 0

    // Config
    private val STEP_PX get() = 38f * resources.displayMetrics.density / 3f
    private val THROTTLE_MS = 110L
    private val CHANNEL_ID = "ev_channel"
    private val NOTIF_ID = 1

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        buildOverlay()
    }

    // ─── Build the edge overlay ──────────────────────────────────────────────

    private fun buildOverlay() {
        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val onRight = prefs.getString("side", "right") != "left"

        val widthPx  = (18 * density).toInt()   // thin strip — sits on the curved edge
        val heightPx = (210 * density).toInt()  // tall enough for comfortable swipes

        // ── Outer container (pill, semi-transparent) ──
        val container = FrameLayout(this)
        val r = 14f * density
        val cornerRadii = if (onRight)
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)   // rounded left side
        else
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)   // rounded right side

        container.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadii = cornerRadii
            setColor(Color.argb(90, 60, 100, 220))
        }

        // ── Fill bar (shows current volume level) ──
        val fill = View(this)
        fill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val fr = 12f * density
            this.cornerRadii = if (onRight)
                floatArrayOf(0f, 0f, 0f, 0f, fr, fr, fr, fr)
            else
                floatArrayOf(0f, 0f, 0f, 0f, fr, fr, fr, fr)
            colors = intArrayOf(
                Color.argb(220, 80, 160, 255),
                Color.argb(180, 40, 100, 200)
            )
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }

        container.addView(fill, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.BOTTOM })

        fillView = fill
        overlayView = container

        // ── Window params ──
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        val params = WindowManager.LayoutParams(
            widthPx, heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (onRight) Gravity.END or Gravity.CENTER_VERTICAL
                      else         Gravity.START or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }

        overlayParams = params
        attachTouchListener(container, params)
        windowManager.addView(container, params)
        syncFillToVolume(animated = false)
    }

    // ─── Touch handling ───────────────────────────────────────────────────────

    private fun attachTouchListener(view: View, params: WindowManager.LayoutParams) {

        view.setOnTouchListener { _, event ->
            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    touchStartY     = event.rawY
                    lastStepY       = event.rawY
                    touchStartRawY  = event.rawY
                    touchStartRawX  = event.rawX
                    initialParamY   = params.y
                    isDraggingPosition = false

                    // Highlight active
                    (view.background as? GradientDrawable)
                        ?.setColor(Color.argb(170, 70, 130, 255))
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.rawX - touchStartRawX)
                    val dy = event.rawY - lastStepY
                    val totalDy = Math.abs(event.rawY - touchStartRawY)

                    // If horizontal movement detected → reposition the widget
                    if (dx > 20f && !isDraggingPosition && totalDy < 15f) {
                        isDraggingPosition = true
                    }

                    if (isDraggingPosition) {
                        // Move the strip up/down on the edge
                        params.y = initialParamY + (event.rawY - touchStartRawY).toInt()
                        windowManager.updateViewLayout(view, params)
                    } else {
                        // ── Volume gesture ──
                        val now = System.currentTimeMillis()
                        if (now - lastVolumeChangeMs >= THROTTLE_MS) {
                            when {
                                dy < -STEP_PX -> {   // swipe UP → louder
                                    changeVolume(AudioManager.ADJUST_RAISE)
                                    lastStepY = event.rawY
                                    lastVolumeChangeMs = now
                                }
                                dy > STEP_PX -> {    // swipe DOWN → quieter
                                    changeVolume(AudioManager.ADJUST_LOWER)
                                    lastStepY = event.rawY
                                    lastVolumeChangeMs = now
                                }
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingPosition = false
                    // Restore normal colour
                    (view.background as? GradientDrawable)
                        ?.setColor(Color.argb(90, 60, 100, 220))
                    syncFillToVolume(animated = true)
                    true
                }

                else -> false
            }
        }
    }

    // ─── Volume helpers ───────────────────────────────────────────────────────

    private fun changeVolume(direction: Int) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
        vibrate()
        syncFillToVolume(animated = false)
    }

    /** Resize the fill bar to reflect current media volume */
    private fun syncFillToVolume(animated: Boolean) {
        val fill   = fillView ?: return
        val parent = overlayView ?: return

        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val ratio = cur.toFloat() / max.toFloat()

        parent.post {
            val totalH = parent.height
            val targetH = (totalH * ratio).toInt().coerceAtLeast(0)
            val lp = fill.layoutParams
            if (animated) {
                val from = lp.height
                val animator = android.animation.ValueAnimator.ofInt(from, targetH).apply {
                    duration = 120
                    addUpdateListener {
                        lp.height = it.animatedValue as Int
                        fill.layoutParams = lp
                    }
                }
                animator.start()
            } else {
                lp.height = targetH
                fill.layoutParams = lp
            }
        }
    }

    // ─── Haptic feedback ─────────────────────────────────────────────────────

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        }
    }

    // ─── Notification & lifecycle ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Edge Volume", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Control de volumen por borde de pantalla"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Edge Volume activo")
            .setContentText("Desliza el borde ↑ subir  ↓ bajar volumen")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { runCatching { windowManager.removeView(it) } }
    }
}
