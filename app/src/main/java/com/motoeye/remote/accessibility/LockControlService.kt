package com.motoeye.remote.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.TextView
import com.motoeye.remote.AppGraph
import com.motoeye.remote.ble.ConnectionState
import com.motoeye.remote.ble.Protocol
import com.motoeye.remote.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Lock-screen remote: an accessibility overlay (TYPE_ACCESSIBILITY_OVERLAY,
 * the only trusted window kind that can sit above the keyguard) with the
 * D-pad, the rear-view camera button and BACK — enough to operate the HUD
 * without unlocking the phone — plus, optionally, a media row (prev /
 * play-pause / next) above the D-pad. Visible only while the keyguard is up,
 * the HUD is connected AND the user enables "Lock screen controls" in Settings.
 */
class LockControlService : AccessibilityService() {

    private val TAG = "LockControlService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private var overlayEnabled = false
    private var mediaEnabled = false
    private var playPauseView: TextView? = null

    private val settings by lazy { SettingsRepository(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // The system can rebind this service in a fresh process (after a
        // reboot, or once the app process was killed) before the UI or the
        // foreground service ever ran, so set the shared managers up here.
        AppGraph.init(this)
        Log.i(TAG, "Lock-screen pad service connected")

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }

        scope.launch {
            settings.settings.collect {
                overlayEnabled = it.showLockScreenControls
                val mediaChanged = it.showLockScreenMedia != mediaEnabled
                mediaEnabled = it.showLockScreenMedia
                main.post {
                    // Rebuild a visible pad so the media row appears/disappears
                    if (mediaChanged) detachOverlay()
                    syncOverlay()
                }
            }
        }
        // Show/hide the pad as the HUD link comes and goes
        scope.launch {
            AppGraph.connection.connectionState.collect {
                main.post { syncOverlay() }
            }
        }
        scope.launch {
            AppGraph.media.isPlaying.collect { playing ->
                main.post { playPauseView?.text = playPauseLabel(playing) }
            }
        }
        main.post { syncOverlay() }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            main.post { syncOverlay() }
        }
    }

    private fun keyguardUp(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun ready(): Boolean = try {
        AppGraph.connection.connectionState.value == ConnectionState.CONNECTED
    } catch (e: Exception) {
        false
    }

    private fun syncOverlay() {
        val want = overlayEnabled && keyguardUp() && ready()
        if (want && overlayViews.isEmpty()) attachOverlay()
        if (!want && overlayViews.isNotEmpty()) detachOverlay()
    }

    // --------------------------------------------------------------- overlay

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).roundToInt()

    private fun buttonBg(idleColor: Int, pressed: Boolean, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(android.content.res.ColorStateList.valueOf(
                if (pressed) 0xFF3A3A60.toInt() else idleColor
            ))
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun keyButton(label: String, sizeDp: Int, textSize: Float = 16f): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    /**
     * Round corner for the key-press button. down = press-down / up = release.
     */
    private fun holdKey(text: String, color: Int, sizeDp: Int, hidUsage: Int, keyCode: Int): TextView {
        val radius = sizeDp / 2
        val tv = keyButton(text, sizeDp)
        var pressed = false
        tv.background = buttonBg(color, false, radius)
        tv.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    press(hidUsage, keyCode, true)
                    view.background = buttonBg(color, true, radius)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pressed) {
                        press(hidUsage, keyCode, false)
                        view.background = buttonBg(color, false, radius)
                        pressed = false
                    }
                    true
                }
                else -> false
            }
        }
        return tv
    }

    /** CAM button is a click (rear-view toggle), never a hold. */
    private fun camButton(sizeDp: Int = 64): TextView {
        val radius = sizeDp / 5
        val tv = keyButton("CAM", sizeDp, textSize = 17f)
        tv.background = buttonBg(0xFF2A4D7F.toInt(), false, radius)
        tv.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (ready()) AppGraph.sendCameraTrigger()
        }
        return tv
    }

    /** BACK button: press/release like the remote. */
    private fun backButton(sizeDp: Int = 64): TextView {
        val radius = sizeDp / 2
        val tv = keyButton("↩", sizeDp, textSize = 20f)
        var pressed = false
        tv.background = buttonBg(0xFF333333.toInt(), false, radius)
        tv.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    press(0x29, Protocol.Keys.BACK, true)
                    view.background = buttonBg(0xFF333333.toInt(), true, radius)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pressed) {
                        press(0x29, Protocol.Keys.BACK, false)
                        view.background = buttonBg(0xFF333333.toInt(), false, radius)
                        pressed = false
                    }
                    true
                }
                else -> false
            }
        }
        return tv
    }

    private fun playPauseLabel(playing: Boolean) = if (playing) "❚❚" else "▶"

    /** Media transport button: a click on the phone's player, no HUD link involved. */
    private fun mediaButton(label: String, sizeDp: Int, action: () -> Unit): TextView {
        val radius = sizeDp / 2
        val tv = keyButton(label, sizeDp, textSize = 20f)
        tv.background = buttonBg(0xFF4A2A6A.toInt(), false, radius)
        tv.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.background = buttonBg(0xFF4A2A6A.toInt(), true, radius)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.background = buttonBg(0xFF4A2A6A.toInt(), false, radius)
            }
            false   // let the click listener fire
        }
        tv.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
        return tv
    }

    private fun press(hidUsage: Int, keyCode: Int, down: Boolean) {
        if (!ready()) return
        AppGraph.sendReflectiveKey(hidUsage, keyCode, down)
    }

    private fun spacer(d: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(d), dp(d))
    }

    private fun panelView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            setColor(android.content.res.ColorStateList.valueOf(0xE6121018.toInt()))
            cornerRadius = dp(14).toFloat()
        }
        gravity = Gravity.CENTER
    }

    /**
     * Each button is its own small overlay window (no full-screen container),
     * so everything that is NOT a button stays touch-pass-through to the
     * lockscreen (swipe-to-unlock etc. keeps working). The D-pad cluster is
     * centred on screen; the big (2x) CAM and BACK buttons sit below it,
     * pushed to the left/right screen edges for gloved reach.
     */
    private val overlayViews = mutableListOf<Pair<View, WindowManager.LayoutParams>>()

    private fun addWindow(view: View, xPx: Int, yPx: Int, sizeDp: Int) {
        val pad = WindowManager.LayoutParams(
            dp(sizeDp), dp(sizeDp),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        pad.gravity = Gravity.TOP or Gravity.START
        pad.x = xPx
        pad.y = yPx
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, pad)
            overlayViews.add(view to pad)
        } catch (e: Exception) {
            Log.e(TAG, "overlay add failed: ${e.message}")
        }
    }

    private fun attachOverlay() {
        if (overlayViews.isNotEmpty()) return

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        // With the media row the whole pad shrinks so the extra row fits below
        // the lock-screen clock/widgets; the pad's bottom edge stays put.
        val scale = if (mediaEnabled) MEDIA_SCALE else 1f
        fun sc(v: Int) = (v * scale).roundToInt()
        val dpad = sc(DPAD)
        val gap = sc(GAP)
        val big = sc(BIG)
        val bigGap = sc(BIG_GAP)
        val media = sc(MEDIA)

        // D-pad cluster: 3x3 grid of dpad buttons, gap apart, centred
        // horizontally (and vertically for the full-size pad)
        val pitch = dpad + gap
        val cluster = 3 * dpad + 2 * gap
        val clusterLeft = (screenW - dp(cluster)) / 2
        val fullBottom = (screenH + dp(3 * DPAD + 2 * GAP)) / 2 + dp(BIG_GAP) + dp(BIG)
        val clusterTop =
            if (mediaEnabled) fullBottom - dp(big) - dp(bigGap) - dp(cluster)
            else (screenH - dp(cluster)) / 2
        fun addClustered(view: View, col: Int, row: Int) =
            addWindow(view, clusterLeft + dp(col * pitch), clusterTop + dp(row * pitch), dpad)

        // D-pad
        addClustered(holdKey("▲", 0xFF0F3460.toInt(), dpad, 0x52, Protocol.Keys.DPAD_UP), 1, 0)
        addClustered(holdKey("◀", 0xFF0F3460.toInt(), dpad, 0x50, Protocol.Keys.DPAD_LEFT), 0, 1)
        addClustered(holdKey("OK", 0xFF1B5E20.toInt(), dpad, 0x28, Protocol.Keys.ENTER), 1, 1)
        addClustered(holdKey("▶", 0xFF0F3460.toInt(), dpad, 0x4F, Protocol.Keys.DPAD_RIGHT), 2, 1)
        addClustered(holdKey("▼", 0xFF0F3460.toInt(), dpad, 0x51, Protocol.Keys.DPAD_DOWN), 1, 2)

        // Optional media row above the D-pad, spaced like the D-pad columns
        if (mediaEnabled) {
            val mediaY = clusterTop - dp(gap) - dp(media)
            fun addMedia(view: View, col: Int) = addWindow(
                view, clusterLeft + dp(col * pitch) + (dp(dpad) - dp(media)) / 2, mediaY, media
            )
            addMedia(mediaButton("⏮", media) { AppGraph.media.previous() }, 0)
            val playPause = mediaButton(playPauseLabel(AppGraph.media.isPlaying.value), media) {
                AppGraph.media.playPause()
            }
            playPauseView = playPause
            addMedia(playPause, 1)
            addMedia(mediaButton("⏭", media) { AppGraph.media.next() }, 2)
        }

        // Big CAM and BACK below the D-pad, pushed to the screen edges
        val bigY = clusterTop + dp(cluster) + dp(bigGap)
        val margin = 18
        addWindow(camButton(big), dp(margin), bigY, big)
        addWindow(backButton(big), screenW - dp(margin) - dp(big), bigY, big)

        Log.i(TAG, "Lock-screen pad shown (${overlayViews.size} windows)")
    }

    private fun detachOverlay() {
        if (overlayViews.isEmpty()) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        for ((view, _) in overlayViews) {
            try {
                manager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "overlay remove failed: ${e.message}")
            }
        }
        overlayViews.clear()
        playPauseView = null
        Log.i(TAG, "Lock-screen pad hidden")
    }

    /** Keyguard show/hide raises window-state events — re-check on each. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) syncOverlay()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        detachOverlay()
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        /** Lock-screen pad sizes (dp): D-pad button, gap between them, CAM/BACK,
         *  D-pad-to-CAM/BACK gap, media row */
        private const val DPAD = 72
        private const val GAP = 18
        private const val BIG = 100
        private const val BIG_GAP = 14
        private const val MEDIA = 64
        /** Whole-pad scale while the media row is shown (fits below the clock widgets) */
        private const val MEDIA_SCALE = 0.78f

        @Volatile
        var instance: LockControlService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val fullyQualified = LockControlService::class.java.name  // com.motoeye.remote.accessibility.LockControlService
            return enabled.split(':').any {
                it.equals("${context.packageName}/$fullyQualified", ignoreCase = true) ||
                    it.endsWith("/$fullyQualified")
            }
        }
    }
}
