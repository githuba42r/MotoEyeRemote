package com.motoeye.remote.tpms

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reads Motoeye BLE TPMS sensors directly from their advertisements
 * (bypassing the HUD relay, whose 1031/1032 handlers need newer firmware).
 *
 * Format (reverse-engineered and validated live 2026-10-15):
 *   advertisement local name = "VD"
 *   manufacturer data company id 0x3000, 4 bytes:
 *     b0 = battery raw  → volts = b0 * 0.01 + 1.22 (3.0V = 100%)
 *     b1 = temperature raw → °C = b1 - 50
 *     b2..b3 = absolute pressure kPa big-endian
 *              gauge kPa = abs - 100;  psi = gauge * 0.145037
 *   Example live packet: B9:42:FB:00:02:A6 msr b0490136 → 2.98V, 23.0°C, 30.45 psi
 *
 * Sensors sleep between transmissions — they advertise on pressure change
 * and periodic wakes, so scanning is duty-cycled and data is cached.
 */
@SuppressLint("MissingPermission")
class TpmsBleMonitor(private val context: Context) {

    private val TAG = "TpmsBleMonitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class SensorLive(
        val address: String,
        val batteryV: Float,
        val batteryPct: Int,
        val temperatureC: Int,
        val pressurePsi: Float,
        val pressureKpa: Float,
        val rssi: Int,
        val lastSeen: Long
    )

    private val _sensors = MutableStateFlow<Map<String, SensorLive>>(emptyMap())
    val sensors: StateFlow<Map<String, SensorLive>> = _sensors.asStateFlow()

    @Volatile private var scanning = false
    private var wanted = false
    @Volatile private var active = false   // user is actively viewing TPMS

    fun start() {
        if (wanted) return
        wanted = true
        scope.launch {
            while (isActive && wanted) {
                if (active) {
                    // Continuous windows while the user watches TPMS
                    scanWindow()
                    delay(1_000)
                } else {
                    scanWindow()
                    delay(25_000) // duty cycle: 12s scan + 25s idle
                }
            }
        }
    }

    /** Call when TPMS becomes visible (overlay opened) — guaranteed scan flow */
    fun setActive(on: Boolean) {
        active = on
    }

    fun stop() {
        wanted = false
        active = false
        scanning = false
    }

    @SuppressLint("MissingPermission")
    private fun scanWindow() {
        if (scanning) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = try { adapter.bluetoothLeScanner } catch (_: Exception) { null }
            ?: return
        scanning = true
        val filter = ScanFilter.Builder()
            .setDeviceName("VD")
            .setManufacturerData(0x3000, byteArrayOf())  // must carry 0x3000 msr
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val name = record.deviceName ?: return
                if (!name.uppercase().startsWith("VD")) return
                val payload = record.manufacturerSpecificData?.get(0x3000) ?: return
                if (payload.size < 4) return
                parseAndStore(result, payload)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "TPMS scan failed: $errorCode")
            }
        }
        try {
            scanner.startScan(listOf(filter), settings, cb)
        } catch (e: Exception) {
            Log.e(TAG, "scan start error: ${e.message}")
            scanning = false
            return
        }
        scope.launch {
            delay(12_000)
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            scanning = false
        }
    }

    private fun parseAndStore(result: ScanResult, payload: ByteArray) {
        val batteryRaw = payload[0].toInt() and 0xFF
        val tempRaw = payload[1].toInt() and 0xFF
        val presRaw = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)

        val volts = batteryRaw * 0.01f + 1.22f
        val pct = ((volts - 2.0f) / 1.0f * 100f).toInt().coerceIn(0, 100) // 2.0V=0%, 3.0V=100%
        val tempC = tempRaw - 50
        val kPaGauge = (presRaw - 100).coerceAtLeast(0).toFloat()
        val psi = kPaGauge * 0.145037f   // 210 kPa gauge → 30.46 psi (verified live)
        val addr = result.device?.address ?: return

        val sensor = SensorLive(
            address = addr,
            batteryV = volts,
            batteryPct = pct,
            temperatureC = tempC,
            pressurePsi = psi,
            pressureKpa = kPaGauge,
            rssi = result.rssi,
            lastSeen = System.currentTimeMillis()
        )
        // prune stale sensors (not seen for > 10 min)
        val now = sensor.lastSeen
        val updated = (_sensors.value + (addr to sensor)).filterValues {
            now - it.lastSeen < 1_800_000
        }
        _sensors.value = updated
        Log.i(TAG, "RaedLive VDSensor $addr psi=%.1f temp=%d°C bat=%d%% (%.2fV) rssi=%d"
            .format(psi, tempC, pct, volts, result.rssi))
    }
}
