package com.motoeye.remote.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.motoeye.remote.ui.MainActivity

/**
 * Accessibility service used to exit Android Auto projection by injecting
 * a HOME key event. Android Auto cannot be exited via BLE key events
 * (the HUD cannot launch arbitrary device apps), so the phone-side app
 * injects HOME (or BACK) globally when the user presses "Exit AA".
 *
 * Also usable to bring the remote app back to foreground.
 */
class AutoControlService : AccessibilityService() {

    private val TAG = "AutoControlService"

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AutoControl service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /**
     * Perform a global action (AccessibilityService has no generic key
     * injection API without the system INJECT_EVENTS permission).
     */
    fun performGlobal(actionId: Int): Boolean {
        return try {
            val ok = performGlobalAction(actionId)
            Log.i(TAG, "Global action $actionId: $ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Global action failed: ${e.message}")
            false
        }
    }

    /** Exit Android Auto projection by sending HOME (global action, API 26+) */
    fun exitAndroidAuto(): Boolean = performGlobal(AccessibilityService.GLOBAL_ACTION_HOME)

    /** Navigate back globally (API 30+) */
    fun pressBack(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            performGlobal(AccessibilityService.GLOBAL_ACTION_BACK)
        } else {
            false
        }

    /** Bring the remote app to the foreground */
    fun openRemoteApp() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open remote app: ${e.message}")
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AutoControlService? = null
            private set

        val isEnabled: Boolean get() = instance != null

        /** Best-effort AA exit: use accessibility if enabled, else fail gracefully */
        fun exitAndroidAutoBestEffort(): Boolean {
            return instance?.exitAndroidAuto() ?: false
        }
    }
}
