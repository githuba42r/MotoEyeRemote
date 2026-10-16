package com.motoeye.remote.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.motoeye.remote.AppGraph
import com.motoeye.remote.R
import com.motoeye.remote.RemoteApp
import com.motoeye.remote.ble.ConnectionState
import com.motoeye.remote.ble.Protocol
import com.motoeye.remote.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service keeping the BLE connection alive with a persistent
 * notification providing connection status and quick-action buttons.
 */
class RemoteForegroundService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundActive = false

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_NOT_STICKY
                startAsForeground()
                AppGraph.connection.connect(address)
            }
            ACTION_DISCONNECT -> {
                AppGraph.connection.disconnect()
                stopSelf()
            }
            ACTION_KEY -> {
                val keyCode = intent.getIntExtra(EXTRA_KEY_CODE, -1)
                when (keyCode) {
                    132 -> AppGraph.sendCameraTrigger()
                    else -> if (keyCode >= 0) AppGraph.keySender.click(keyCode)
                }
            }
            else -> {
                startAsForeground()
                // Process restarted by the system (START_STICKY): revive the
                // connection to the saved HUD — previously a process death
                // silently ended auto-reconnect until the app was reopened.
                scope.launch {
                    kotlinx.coroutines.delay(3000)
                    val saved = com.motoeye.remote.data.SettingsRepository(this@RemoteForegroundService)
                        .pairedDeviceAddress()
                    val state = AppGraph.connection.connectionState.value
                    if (saved != null && state == ConnectionState.DISCONNECTED) {
                        AppGraph.connection.connect(saved)
                    }
                }
            }
        }

        observeConnection()
        acquireWakeLock()
        return START_STICKY
    }

    private fun startAsForeground() {
        if (foregroundActive) return
        val notification = buildNotification(getString(R.string.connecting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundActive = true
    }

    private fun hideForeground() {
        if (!foregroundActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundActive = false
    }

    private fun observeConnection() {
        scope.launch {
            AppGraph.connection.connectionState.collect { state ->
                updateNotification(state)
            }
        }
    }

    /**
     * The notification exists while the HUD is connected OR while a
     * reconnection attempt is in flight ("short time after disconnection").
     * Hidden once the presence watch gives up (state DISCONNECTED).
     */
    private fun updateNotification(state: ConnectionState) {
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val text = when (state) {
            ConnectionState.CONNECTED -> getString(R.string.connected)
            ConnectionState.RECONNECTING -> "Reconnecting…"
            ConnectionState.CONNECTING,
            ConnectionState.DISCOVERING,
            ConnectionState.HANDSHAKING -> getString(R.string.connecting)
            else -> null   // DISCONNECTED / SCANNING → hide
        }
        if (text != null) {
            startAsForeground()
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } else {
            hideForeground()
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun actionIntent(action: String, keyCode: Int = -1): PendingIntent {
            val intent = Intent(this, RemoteForegroundService::class.java).apply {
                this.action = action
                if (keyCode >= 0) putExtra(EXTRA_KEY_CODE, keyCode)
            }
            return PendingIntent.getService(
                this, keyCode + 1000, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val builder = NotificationCompat.Builder(this, RemoteApp.CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            // Connected/reconnecting notification: status + rear camera + back
            .addAction(0, getString(R.string.action_camera), actionIntent(ACTION_KEY, 132))
            .addAction(0, "Back", actionIntent(ACTION_KEY, Protocol.Keys.BACK))
            .addAction(0, getString(R.string.disconnect), actionIntent(ACTION_DISCONNECT))

        return builder.build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        scope.launch {
            val enabled = com.motoeye.remote.data.SettingsRepository(this@RemoteForegroundService)
                .settings.first().wakeLockEnabled
            if (!enabled) return@launch
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoEye:Remote").apply {
                setReferenceCounted(false)
                if (!isHeld) acquire()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CONNECT = "com.motoeye.remote.CONNECT"
        const val ACTION_DISCONNECT = "com.motoeye.remote.DISCONNECT"
        const val ACTION_KEY = "com.motoeye.remote.KEY"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_KEY_CODE = "keyCode"
        private const val NOTIFICATION_ID = 1001

        fun startConnect(context: Context, address: String) {
            val intent = Intent(context, RemoteForegroundService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_ADDRESS, address)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun sendKey(context: Context, keyCode: Int) {
            val intent = Intent(context, RemoteForegroundService::class.java).apply {
                action = ACTION_KEY
                putExtra(EXTRA_KEY_CODE, keyCode)
            }
            context.startService(intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, RemoteForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}
