package com.motoeye.remote.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Diagnostic: passive BLE advertisement capture. Logs the full payload
 * (manufacturer data, service data, name, RSSI) of every nearby device so
 * TPMS sensor advertisement formats can be identified. Optionally filters
 * by RSSI to isolate nearby sensors.
 *
 * Triggered via debug broadcast command "adsprobe" (or "adsprobe:-80" to
 * require stronger than -80 dBm).
 */
@SuppressLint("MissingPermission")
object RawAdvProbe {

    private const val TAG = "RawAdvProbe"

    suspend fun probe(context: Context, rssiFloor: Int = -99): Int {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = try { adapter.bluetoothLeScanner } catch (_: Exception) { null }
            ?: return -1
        val count = AtomicInteger(0)

        // No service filter — capture everything; TPMS sensors typically
        // advertise without a name.
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.rssi < rssiFloor) return
                if (count.incrementAndGet() > 400) return  // keep log sane
                val d = result.device
                val record = result.scanRecord
                val sb = StringBuilder()
                sb.append("ADV address=${d?.address} rssi=${result.rssi} name=${record?.deviceName}")
                result.scanRecord?.advertiseFlags?.let { sb.append(" flags=0x%02x".format(it)) }
                // Raw bytes of the whole advertisement
                result.scanRecord?.bytes?.let { raw ->
                    sb.append(" raw=${raw.joinToString("") { "%02x".format(it) }}")
                }
                // Manufacturer data sections (SparseArray<ByteArray>)
                val msd = record?.manufacturerSpecificData
                if (msd != null) {
                    for (i in 0 until msd.size()) {
                        val id = msd.keyAt(i)
                        val data = msd.valueAt(i)
                        sb.append(" msr[0x%04x]=%s".format(
                            id, data?.joinToString("") { "%02x".format(it) } ?: "?"
                        ))
                    }
                }
                // Service data sections
                for ((uuid, data) in record?.serviceData ?: emptyMap()) {
                    sb.append(" svcData[$uuid]=${data?.joinToString("") { "%02x".format(it) }}")
                }
                val uuids = record?.serviceUuids?.joinToString { "$it" }
                if (!uuids.isNullOrBlank()) sb.append(" svcUuids=$uuids")
                Log.i(TAG, sb.toString())
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "adsprobe scan failed: $errorCode")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        Log.i(TAG, "adsprobe starting (RSSI floor $rssiFloor, up to 5x12s windows)")
        try {
            var total = 0
            for (attempt in 1..5) {
                scanner.startScan(emptyList<ScanFilter>(), settings, cb)
                kotlinx.coroutines.delay(12_000)
                try { scanner.stopScan(cb) } catch (_: Exception) {}
                total = count.get()
                Log.i(TAG, "adsprobe window $attempt done: $total results so far")
                kotlinx.coroutines.delay(2000)
                if (total > 0) break
            }
            val c = count.get()
            Log.i(TAG, "adsprobe done: $c results logged")
            return c
        } catch (e: Exception) {
            Log.e(TAG, "adsprobe error: ${e.message}")
            return -2
        }
    }
}
