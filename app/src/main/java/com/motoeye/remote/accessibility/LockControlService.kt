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
 * without unlocking the phone. Visible only while the keyguard is up AND the
 * user enables "Lock screen controls" in Settings.
 */
class LockControlService : AccessibilityService() {

    private val TAG = "LockControlService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private var overlayEnabled = false

    private val settings by lazy { SettingsRepository(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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
                main.post { syncOverlay() }
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
        AppGraph.connection != null && AppGraph.phoneHid != null
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

    private fun keyButton(label: String, sizeDp: Int, textSize: Float = 13f): TextView =
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

        // D-pad cluster centred on screen (184dp wide, 192dp tall grid)
        val clusterLeft = (screenW - dp(184)) / 2
        val clusterTop = (screenH - dp(192)) / 2
        fun addClustered(view: View, relX: Int, relY: Int) =
            addWindow(view, clusterLeft + dp(relX), clusterTop + dp(relY), 56)

        // D-pad
        addClustered(holdKey("▲", 0xFF0F3460.toInt(), 56, 0x52, Protocol.Keys.DPAD_UP), 64, 0)
        addClustered(holdKey("◀", 0xFF0F3460.toInt(), 56, 0x50, Protocol.Keys.DPAD_LEFT), 0, 68)
        addClustered(holdKey("OK", 0xFF1B5E20.toInt(), 56, 0x28, Protocol.Keys.ENTER), 64, 68)
        addClustered(holdKey("▶", 0xFF0F3460.toInt(), 56, 0x4F, Protocol.Keys.DPAD_RIGHT), 128, 68)
        addClustered(holdKey("▼", 0xFF0F3460.toInt(), 56, 0x51, Protocol.Keys.DPAD_DOWN), 64, 136)

        // Big (2x) CAM and BACK below the D-pad, pushed to the screen edges
        val bigY = clusterTop + dp(192) + dp(14)
        val margin = 18
        addWindow(camButton(112), dp(margin), bigY, 112)
        addWindow(backButton(112), screenW - dp(margin) - dp(112), bigY, 112)

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
        Log.i(TAG, "Lock-screen pad hidden")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        detachOverlay()
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
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
