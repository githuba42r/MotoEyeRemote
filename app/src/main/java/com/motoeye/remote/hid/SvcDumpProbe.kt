package com.motoeye.remote.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Diagnostic: full GATT service discovery dump of an already-bonded peer
 * (the HUD), listing every service + characteristic so we can find
 * non-Vidoar services exposed by side-loaded apps (e.g. a TPMS app).
 *
 * Triggered via debug broadcast command "svcprobe".
 */
@SuppressLint("MissingPermission")
object SvcDumpProbe {

    private const val TAG = "SvcDumpProbe"

    suspend fun dump(context: Context, address: String): String {
        var gattRef: BluetoothGatt? = null
        return try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager).adapter
            if (!adapter.isEnabled) return "bluetooth off"
            val device = adapter.getRemoteDevice(address)
            val done = CompletableDeferred<StringBuilder?>()

            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                        BluetoothProfile.STATE_DISCONNECTED ->
                            if (!done.isCompleted) done.complete(null)
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        if (!done.isCompleted) done.complete(null)
                        return
                    }
                    val sb = StringBuilder()
                    for (svc in g.services) {
                        sb.appendLine("SVC ${svc.uuid} instId=${svc.instanceId}")
                        for (ch in svc.characteristics) {
                            val props = listOfNotNull(
                                if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) "R" else null,
                                if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                    (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) "W" else null,
                                if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) "N" else null
                            ).joinToString("")
                            sb.appendLine("  CHAR ${ch.uuid} props=$props")
                        }
                    }
                    if (!done.isCompleted) done.complete(sb)
                    // politely disconnect after dump
                    g.disconnect()
                }
            }

            gattRef = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            val sb = withTimeoutOrNull(15_000) { done.await() }
            if (sb == null) "svcprobe: connect/discover timed out or no services"
            else sb.toString()
        } catch (e: Exception) {
            "svcprobe error: ${e.message}"
        } finally {
            try {
                gattRef?.disconnect()
                gattRef?.close()
            } catch (_: Exception) {}
        }
    }
}
