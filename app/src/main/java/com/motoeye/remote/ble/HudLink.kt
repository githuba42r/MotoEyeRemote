package com.motoeye.remote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.motoeye.remote.data.SettingsRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The HUD is dual-mode: besides the BLE control link (named "<id>_LE") it
 * pairs with the phone as a classic Bluetooth headset (named "<id>", e.g.
 * E6-286406AA / E6-286406AA_LE) and opens that headset link itself when it
 * powers on. Automatic connects key off that link: no scanning for a HUD
 * that is switched off.
 */
object HudLink {

    private const val TAG = "HudLink"
    private const val LE_SUFFIX = "_LE"

    /** Classic (headset) device name for the saved BLE HUD name. */
    fun classicName(leName: String?): String? =
        leName?.takeIf { it.isNotBlank() }?.removeSuffix(LE_SUFFIX)

    /** Whether [device] is the classic side of the saved HUD. */
    @SuppressLint("MissingPermission")
    suspend fun isHud(context: Context, device: BluetoothDevice): Boolean {
        val name = runCatching { device.name }.getOrNull() ?: return false
        if (name.endsWith(LE_SUFFIX)) return false
        return name == classicName(savedLeName(context))
    }

    /** Whether the saved HUD's headset link is up right now. */
    @SuppressLint("MissingPermission")
    suspend fun isUp(context: Context): Boolean {
        val classic = classicName(savedLeName(context)) ?: return false
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        val hud = runCatching { adapter.bondedDevices }.getOrNull()
            ?.firstOrNull { runCatching { it.name }.getOrNull() == classic }
            ?: return false
        for (profile in intArrayOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)) {
            if (profileState(context, profile, hud) == BluetoothProfile.STATE_CONNECTED) return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun savedLeName(context: Context): String? {
        val settings = SettingsRepository(context)
        val address = settings.pairedDeviceAddress() ?: return null
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val live = runCatching { manager.adapter?.getRemoteDevice(address)?.name }.getOrNull()
        return live ?: settings.pairedDeviceName()
    }

    @SuppressLint("MissingPermission")
    private suspend fun profileState(context: Context, profile: Int, device: BluetoothDevice): Int {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return BluetoothProfile.STATE_DISCONNECTED
        return withTimeoutOrNull(2000) {
            suspendCancellableCoroutine { cont ->
                val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val state = runCatching { proxy.getConnectionState(device) }
                            .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                        adapter.closeProfileProxy(p, proxy)
                        if (cont.isActive) cont.resume(state)
                    }

                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
                if (!ok && cont.isActive) cont.resume(BluetoothProfile.STATE_DISCONNECTED)
            }
        } ?: run {
            Log.w(TAG, "profile $profile proxy timed out")
            BluetoothProfile.STATE_DISCONNECTED
        }
    }
}
