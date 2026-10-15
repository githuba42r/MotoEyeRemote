package com.motoeye.remote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class RemoteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        registerScreenStateLogger()
    }

    /**
     * Diagnostics: log screen on/off transitions so BLE drop timestamps can
     * be correlated with Samsung's screen-off BLE throttling.
     */
    private fun registerScreenStateLogger() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: android.content.Context?,
                intent: android.content.Intent?
            ) {
                android.util.Log.i("ScreenState", "screen ${intent?.action?.substringAfterLast('.')}")
            }
        }
        registerReceiver(receiver, filter)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Foreground service channel (low importance, no sound)
            val foregroundChannel = NotificationChannel(
                CHANNEL_FOREGROUND,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(foregroundChannel)

            // TPMS alerts channel
            val tpmsChannel = NotificationChannel(
                CHANNEL_TPMS,
                "TPMS Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Tire pressure and temperature alerts"
            }
            manager.createNotificationChannel(tpmsChannel)
        }
    }

    companion object {
        const val CHANNEL_FOREGROUND = "remote_status"
        const val CHANNEL_TPMS = "tpms_alerts"
    }
}
