package com.motoeye.remote.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

class DeviceScanner(context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private val scanner = adapter.bluetoothLeScanner

    @SuppressLint("MissingPermission")
    fun scan(durationMs: Long = 10000): Flow<List<DiscoveredDevice>> = callbackFlow {
        val devices = mutableMapOf<String, DiscoveredDevice>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = device.name ?: return
                // Filter for MotoEye devices (name contains "MotoEye" or service UUID fe86)
                val isMotoEye = name.contains("MotoEye", ignoreCase = true) ||
                        name.contains("Moto", ignoreCase = true) ||
                        result.scanRecord?.serviceUuids?.any { it.uuid == Protocol.SERVICE_UUID } == true

                if (isMotoEye) {
                    devices[device.address] = DiscoveredDevice(
                        name = name,
                        address = device.address,
                        rssi = result.rssi
                    )
                    trySend(devices.values.sortedByDescending { it.rssi })
                }
            }

            override fun onScanFailed(errorCode: Int) {
                // Close flow on scan failure
                close()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, callback)

        // Auto-stop after duration
        kotlinx.coroutines.delay(durationMs)
        scanner.stopScan(callback)
        close()

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {}
        }
    }
}
