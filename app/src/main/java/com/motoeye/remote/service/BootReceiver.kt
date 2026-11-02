package com.motoeye.remote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.motoeye.remote.ble.HudLink
import com.motoeye.remote.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts the remote service after device reboot if a device was
 * previously paired, auto-reconnect is enabled and the HUD's headset link is
 * already up (otherwise [HudLinkReceiver] connects when the HUD powers on).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(context)
                val address = settings.pairedDeviceAddress()
                if (address != null && settings.autoReconnect() && HudLink.isUp(context)) {
                    RemoteForegroundService.startConnect(context, address)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
