package com.motoeye.remote.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.motoeye.remote.AppGraph
import com.motoeye.remote.ble.ConnectionState
import com.motoeye.remote.ble.HudLink
import com.motoeye.remote.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Auto-connect trigger: the HUD opens its classic (headset) link to the phone
 * when it powers on. ACL_CONNECTED is exempt from the implicit-broadcast
 * limits, so this fires even when the app isn't running.
 */
class HudLinkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device: BluetoothDevice = (
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }) ?: return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!HudLink.isHud(context, device)) return@launch
                val settings = SettingsRepository(context)
                val address = settings.pairedDeviceAddress() ?: return@launch
                if (!settings.autoReconnect()) return@launch
                AppGraph.init(context)
                // The HUD just powered on: start a fresh connect unless one is
                // already through to service discovery / handshake. This also
                // replaces a reconnect cycle that is still waiting on a stale
                // attempt from before the HUD went off.
                val state = AppGraph.connection.connectionState.value
                if (state == ConnectionState.CONNECTED ||
                    state == ConnectionState.DISCOVERING ||
                    state == ConnectionState.HANDSHAKING
                ) return@launch
                Log.i(TAG, "HUD headset link up ($state) — connecting to $address")
                RemoteForegroundService.startConnect(context, address)
            } catch (e: Exception) {
                Log.e(TAG, "auto-connect failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "HudLinkReceiver"
    }
}
