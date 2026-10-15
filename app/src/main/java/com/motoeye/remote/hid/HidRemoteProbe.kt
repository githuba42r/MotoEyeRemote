package com.motoeye.remote.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-off diagnostic: connect to the physical HID remote as a GATT client
 * and read its HID Report Map (0x2A4B) so we can see which HID usage code
 * each button emits (esp. the CAM button).
 *
 * Triggered via debug broadcast command "hidprobe".
 * Target by known address first, then fall back to a scan for anything
 * advertising the HID profile service (0x1812).
 */
@SuppressLint("MissingPermission")
object HidRemoteProbe {

    private const val TAG = "HidRemoteProbe"

    private val HID_SERVICE_UUID = java.util.UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
    private val REPORT_MAP_UUID = java.util.UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")

    suspend fun probe(context: Context, knownAddress: String?): String {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        // 1) try known address directly (remote may be idle/advertising)
        if (knownAddress != null) {
            try {
                val dev = adapter.getRemoteDevice(knownAddress)
                val map = readReportMap(context, dev)
                if (map != null) return "ADDRESS HIT $knownAddress\n$map"
            } catch (e: Exception) {
                Log.w(TAG, "direct probe to $knownAddress failed: ${e.message}")
            }
        }

        // 2) unfiltered scan — report EVERYTHING seen, prefer HC-* / known addr
        val scanner = try { adapter.bluetoothLeScanner } catch (_: Exception) { null }
            ?: return "scanner unavailable"
        val seen = linkedMapOf<String, ScanResult>()
        val found = CompletableDeferred<BluetoothDevice?>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device?.name ?: "?"
                val addr = result.device?.address ?: "?"
                seen.putIfAbsent("$addr ($name, rssi=${result.rssi})", result)
                if (found.isCompleted) return
                if (name.startsWith("HC") || knownAddress?.equals(addr, true) == true) {
                    found.complete(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Pairing-mode scan FAILED: errorCode=$errorCode")
            }
        }
        val t0 = System.currentTimeMillis()
        Log.i(TAG, "Unfiltered scan started")
        try {
            scanner.startScan(emptyList(), settings, cb)
        } catch (e: Exception) {
            Log.e(TAG, "scan start exception", e)
            return "scan start failed: ${e.message}"
        }
        val dev = withTimeoutOrNull(10000) { found.await() }
        try { scanner.stopScan(cb) } catch (_: Exception) {}
        Log.i(TAG, "Unfiltered scan done in ${System.currentTimeMillis() - t0}ms, ${seen.size} devices seen")
        Log.i(TAG, "Scan results in pairing mode: " + seen.keys.joinToString(" | "))
        if (dev == null) {
            return "NO MATCH FOUND. Devices seen: ${seen.keys.joinToString(" | ")}"
        }
        val map = readReportMap(context, dev)
        if (map == null) return "connected to ${dev.address} but report map read failed"
        return "FOUND ${dev.address} (${dev.name})\n$map"
    }

    /** Connect, discover 0x1812, read 0x2A4B report map, disconnect. */
    @SuppressLint("MissingPermission")
    private suspend fun readReportMap(context: Context, device: BluetoothDevice): String? {
        var gattRef: BluetoothGatt? = null
        return try {
            val connected = CompletableDeferred<Boolean>()
            val over = CompletableDeferred<String?>()

            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (!over.isCompleted) over.complete(null)
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        if (!over.isCompleted) over.complete(null)
                        return
                    }
                    val hid = g.getService(HID_SERVICE_UUID)
                    if (hid == null) {
                        Log.i(TAG, "No HID service; services: " +
                            g.services.joinToString { it.uuid.toString() })
                        if (!over.isCompleted) over.complete(null)
                        return
                    }
                    Log.i(TAG, "HID chars: " + hid.characteristics.joinToString { it.uuid.toString() })
                    val reportMap = hid.getCharacteristic(REPORT_MAP_UUID)
                    if (reportMap == null) {
                        if (!over.isCompleted) over.complete(null)
                        return
                    }
                    connected.complete(true)
                    g.readCharacteristic(reportMap)
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (characteristic.uuid == REPORT_MAP_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                        characteristic.value?.let { v ->
                            if (!over.isCompleted) {
                                over.complete("REPORT MAP (${v.size} bytes): " + v.joinToString(" ") {
                                    String.format("%02x", it)
                                })
                            }
                        }
                    }
                    // trigger next MTU chunk read if long
                    else if (status == BluetoothGatt.GATT_SUCCESS &&
                        over.isCompleted && g.services.firstOrNull {
                            it.uuid == HID_SERVICE_UUID
                        }?.getCharacteristic(REPORT_MAP_UUID) != null
                    ) {
                        // no-op
                    }
                }

                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) {
                    if (characteristic.uuid == REPORT_MAP_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                        if (!over.isCompleted) {
                            over.complete("REPORT MAP (${value.size} bytes): " + value.joinToString(" ") {
                                String.format("%02x", it)
                            })
                        }
                    }
                }
            }

            gattRef = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            val connectedOk = withTimeoutOrNull(10000) { connected.await() }
            if (connectedOk != true) {
                Log.w(TAG, "connect/discover timed out to ${device.address}")
            }
            val result = withTimeoutOrNull(8000) { over.await() }
            result
        } finally {
            try {
                gattRef?.disconnect()
                gattRef?.close()
            } catch (_: Exception) {}
        }
    }
}
