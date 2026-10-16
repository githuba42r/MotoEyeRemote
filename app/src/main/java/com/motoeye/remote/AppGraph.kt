package com.motoeye.remote

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.motoeye.remote.ble.BleConnectionManager
import com.motoeye.remote.ble.ConnectionState
import com.motoeye.remote.ble.KeyEventSender
import com.motoeye.remote.ble.Protocol
import com.motoeye.remote.macro.MacroDatabase
import com.motoeye.remote.macro.MacroEngine
import com.motoeye.remote.media.MediaController
import com.motoeye.remote.tpms.TpmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-level singleton holding shared managers.
 * Initialized lazily on first access from service or UI.
 */
@SuppressLint("StaticFieldLeak")
object AppGraph {

    private const val TAG = "AppGraph"

    /** A/B result: staggered HID attach; delay now user-tunable (0.2.12) */
    private const val HID_ATTACH_ENABLED = true

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context

    lateinit var connection: BleConnectionManager
        private set
    lateinit var keySender: KeyEventSender
        private set
    lateinit var tpms: TpmsManager
        private set
    lateinit var media: MediaController
        private set
    lateinit var macroDb: MacroDatabase
        private set
    lateinit var macroEngine: MacroEngine
        private set
    lateinit var phoneHid: com.motoeye.remote.hid.PhoneHidDevice
        private set
    lateinit var tpmsBle: com.motoeye.remote.tpms.TpmsBleMonitor
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            connection = BleConnectionManager(appContext)
            keySender = KeyEventSender(connection)
            tpms = TpmsManager()
            media = MediaController(appContext)
            macroDb = MacroDatabase.get(appContext)
            macroEngine = MacroEngine(appContext)
            phoneHid = com.motoeye.remote.hid.PhoneHidDevice(appContext)
            tpmsBle = com.motoeye.remote.tpms.TpmsBleMonitor(appContext)

            // Direct BLE TPMS sensor reading (sensors advertise independently)
            tpmsBle.start()

            // Start the HID-keyboard profile early; it needs the system to
            // register the app before the HUD connection can be established
            phoneHid.start()

            // Wire TPMS message routing
            connection.onMessageReceived = { json ->
                tpms.handleTpData(json)
                tpms.handleVehicleData(json)
            }
            // Drop the HID retry loop when the HUD link is intentionally gone
            connection.onDisconnected = {
                phoneHid.stopTrying()
            }
            // 0.2.6: staggered attach — the A/B test showed simultaneous
            // link establishment causes supervision-timeout drops, so wait
            // for the Vidoar link to quiet down first (user-tunable, 0.2.12).
            connection.onConnected = { address ->
                if (HID_ATTACH_ENABLED) {
                    appScope.launch {
                        val delayMs = runCatching {
                            com.motoeye.remote.data.SettingsRepository(appContext)
                                .hidAttachDelaySeconds() * 1000L
                        }.getOrDefault(5000L)
                        if (delayMs > 0) {
                            Log.i(TAG, "HID attach in ${delayMs / 1000}s")
                            kotlinx.coroutines.delay(delayMs)
                        }
                        Log.i(TAG, "HID attach starting now")
                        phoneHid.connect(address)
                    }
                }
            }

            // Seed builtin macros (honours user deletions)
            appScope.launch {
                runCatching {
                    MacroEngine.seedBuiltins(
                        macroDb.macroDao(),
                        com.motoeye.remote.data.SettingsRepository(appContext)
                    )
                }
            }

            // App-level auto-connect fallback: if a saved HUD exists and the
            // link is still down after the UI settles, connect regardless of
            // which screen the user is on.
            appScope.launch {
                val saved = com.motoeye.remote.data.SettingsRepository(appContext)
                    .pairedDeviceAddress()
                if (saved != null) {
                    kotlinx.coroutines.delay(4000)
                    Log.i(TAG, "Auto-connect check: saved=$saved state=${connection.connectionState.value}")
                    if (connection.connectionState.value == ConnectionState.DISCONNECTED) {
                        connection.connect(saved)
                    }
                }
            }

            initialized = true
        }
    }

    /**
     * Send a key press/release preferring the HID-keyboard (system) path —
     * identical to the physical remote — falling back to the Vidoar channel
     * when the HUD HID link isn't attached.
     */
    fun sendReflectiveKey(hidUsage: Int, vidoarKeyCode: Int, pressed: Boolean) {
        if (phoneHid.isConnected()) {
            if (pressed) phoneHid.sendKeyDown(hidUsage) else phoneHid.sendKeyUp(hidUsage)
        } else {
            connection.sendKeyEvent(vidoarKeyCode, pressed)
        }
    }

    /** CAM button: short F2 via the HID keyboard (same as the physical
     *  remote's CAM button), the only proven-working channel. */
    fun sendCameraTrigger() {
        phoneHid.triggerRearView()
    }

    /**
     * Debug passthrough for adb experiments:
     *   raw:<json>         — send an arbitrary framed JSON command
     *   key:<keyCode>      — HID key click
     *   keyhold:<keyCode>:<ms> — HID press, hold, release
     */
    fun executeDebugCommand(cmd: String) {
        when {
            cmd.startsWith("raw:") ->
                connection.sendJsonCommand(cmd.removePrefix("raw:").trim())
            cmd.startsWith("keyhold:") -> {
                val parts = cmd.removePrefix("keyhold:").trim().split(":")
                val code = parts.getOrNull(0)?.toIntOrNull() ?: return
                val ms = parts.getOrNull(1)?.toLongOrNull() ?: 600L
                keySender.hold(code, ms)
            }
            cmd.startsWith("key:") -> {
                cmd.removePrefix("key:").trim().toIntOrNull()?.let { keySender.click(it) }
            }
            cmd.startsWith("hidk:") -> {
                // HID-keyboard-channel experiment: hidk:<usageHex>[:<holdMs>]
                val parts = cmd.removePrefix("hidk:").trim().split(":")
                val usage = parts.getOrNull(0)?.toIntOrNull(16) ?: return
                val ms = parts.getOrNull(1)?.toLongOrNull() ?: 60L
                phoneHid.sendUsage(usage, ms)
            }
            cmd == "hidprobe" -> {
                // Read the physical remote's HID Report Map to identify button codes
                appScope.launch {
                    val result = com.motoeye.remote.hid.HidRemoteProbe.probe(
                        appContext, "D5:DE:A3:74:B1:66"
                    )
                    android.util.Log.i("HidRemoteProbe", "PROBE RESULT: $result")
                }
            }
            cmd.startsWith("svcprobe") -> {
                // Full GATT service dump of a bonded peer (default: the HUD)
                val addr = cmd.removePrefix("svcprobe:").trim()
                    .ifEmpty { "98:26:AD:C3:C7:13" }
                appScope.launch {
                    val result = com.motoeye.remote.hid.SvcDumpProbe.dump(appContext, addr)
                    android.util.Log.i("SvcDumpProbe", "SVC DUMP $addr:\n$result")
                }
            }
            cmd.startsWith("adsprobe") -> {
                // Passive BLE advertisement capture (TPMS sensor hunting).
                // Optional rssi floor: adsprobe:-80
                val floor = cmd.removePrefix("adsprobe:").trim().toIntOrNull() ?: -99
                appScope.launch {
                    val n = com.motoeye.remote.hid.RawAdvProbe.probe(appContext, floor)
                    android.util.Log.i("RawAdvProbe", "adsprobe complete: $n devices")
                }
            }
        }
    }

    val isInitialized: Boolean get() = initialized
}
