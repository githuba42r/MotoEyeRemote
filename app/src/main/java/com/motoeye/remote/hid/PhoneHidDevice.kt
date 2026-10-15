package com.motoeye.remote.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Registers the phone as a BLE HID keyboard with the HUD via the standard
 * BluetoothHidDevice (HOGP) profile. Events sent through this channel take
 * the same path as the physical remote's, so keycodes map identically
 * (e.g. KEYCODE_F2 / usage 0x3B = rear view camera, exactly like the
 * remote's CAM button, which our tests proved emits F2).
 */
@SuppressLint("MissingPermission")
class PhoneHidDevice(private val context: Context) {

    private val TAG = "PhoneHidDevice"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private val _isAttached = MutableStateFlow(false)
    val isAttached: StateFlow<Boolean> = _isAttached.asStateFlow()

    private val _connectedDeviceAddr = MutableStateFlow<String?>(null)
    val connectedDeviceAddr: StateFlow<String?> = _connectedDeviceAddr.asStateFlow()

    private var hidProfile: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null

    // HID keyboard usage codes (keyboard usage page, same values our tests
    // showed from the physical remote)
    object Usage {
        const val ESCAPE = 0x29        // Back
        const val ENTER = 0x28         // OK / center
        const val ARROW_RIGHT = 0x4F   // DPAD right
        const val ARROW_LEFT = 0x50    // DPAD left
        const val ARROW_DOWN = 0x51    // DPAD down (remote Vol−)
        const val ARROW_UP = 0x52      // DPAD up (remote Vol+)
        const val F1 = 0x3A            // MacBook: PTT (vendor 131 equivalent)
        const val F2 = 0x3B            // Remote CAM button — rear view camera
    }

    companion object {
        private fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()

        // Standard 6-KRO keyboard descriptor WITH Report ID 1:
        // report = [id=1, modifier, k0..k5]
        private val KEYBOARD_DESCRIPTOR = bytes(
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x06,       // Usage (Keyboard)
            0xA1, 0x01,       // Collection (Application)
            0x85, 0x01,       //   Report ID 1
            0x05, 0x07,       //   Usage Page (Keyboard)
            0x19, 0xE0, 0x29, 0xE7, //   Usage Min/Max (LeftCtrl..RightGUI)
            0x15, 0x00, 0x25, 0x01, //   Logical 0..1
            0x75, 0x01, 0x95, 0x08, //   Report Size 1, Count 8
            0x81, 0x02,       //   Input (Data,Var,Abs) — modifier byte
            0x75, 0x08, 0x95, 0x06, //   Report Size 8, Count 6
            0x15, 0x00, 0x25, 0x65, //   Logical 0..101
            0x05, 0x07,       //   Usage Page (Keyboard)
            0x19, 0x00, 0x29, 0x65, //   Usage Min 0, Max 0x65
            0x81, 0x00,       //   Input (Data,Array) — six key slots
            0xC0              // End Collection
        )
        private const val REPORT_ID = 1
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidProfile = proxy as BluetoothHidDevice
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidProfile = null
                _isRegistered.value = false
            }
        }
    }

    private fun registerHidApp() {
        val sdp = android.bluetooth.BluetoothHidDeviceAppSdpSettings(
            "MotoEye Remote", "Full remote control keyboard", "MotoEye",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD, KEYBOARD_DESCRIPTOR
        )
        val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                Log.i(TAG, "HID app registered=$registered plugged=${pluggedDevice?.address}")
                _isRegistered.value = registered
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                Log.i(TAG, "HID connection state=$state to ${device.address}")
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevice = device
                    _connectedDeviceAddr.value = device.address
                    _isAttached.value = true
                    _isRegistered.value = true
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    // Flag the failure path accordingly: the link is gone,
                    _isAttached.value = false
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                        _connectedDeviceAddr.value = null
                    }
                    // Transient HOGP attach failures are common — keep trying
                    if (wantConnected) startRetryLoop()
                }
            }
        }
        try {
            hidProfile?.registerApp(
                sdp, null, null,
                kotlinx.coroutines.Dispatchers.IO.asExecutor(), callback
            )
        } catch (e: Exception) {
            Log.e(TAG, "registerApp failed: ${e.message}")
        }
    }

    fun start() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (!_isRegistered.value) adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    /**
     * Connect to the HUD as a HID keyboard device (same peer as the BLE bond).
     */
    @SuppressLint("MissingPermission")
    @Volatile private var wantConnected = false
    @Volatile private var peerAddress: String? = null
    private var retryJob: kotlinx.coroutines.Job? = null

    /**
     * Attach to the HUD as an HID keyboard device and keep trying until it
     * works (HOGP attaches fail transiently after app restarts / link
     * churn — auto-retry closes that gap).
     */
    fun connect(address: String) {
        wantConnected = true
        peerAddress = address
        startRetryLoop()
    }

    /** Stop wanting the link (user disconnects / HUD gone entirely) */
    fun stopTrying() {
        wantConnected = false
        retryJob?.cancel()
        retryJob = null
    }

    private fun startRetryLoop() {
        val job = retryJob
        if (job?.isActive == true) return
        retryJob = scope.launch {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            var attempt = 0
            while (wantConnected && connectedDevice == null) {
                val profile = hidProfile
                val addr = peerAddress
                if (profile == null) {
                    // Wait for profile registration (up to ~10 s per cycle)
                    if (attempt % 2 == 0) Log.i(TAG, "HID profile not registered yet, waiting")
                    delay(2000)
                    continue
                }
                if (addr == null) break
                attempt++
                Log.i(TAG, "HID attach attempt $attempt to $addr")
                try {
                    profile.connect(adapter.getRemoteDevice(addr))
                } catch (e: Exception) {
                    Log.e(TAG, "HID connect failed: ${e.message}")
                }
                // Give the stack up to 8 s to establish, then clean up and pause
                var waited = 0L
                while (wantConnected && connectedDevice == null && waited < 8000) {
                    delay(300); waited += 300
                }
                if (connectedDevice == null) {
                    try { profile.disconnect(adapter.getRemoteDevice(addr)) } catch (_: Exception) {}
                    Log.w(TAG, "HID attach attempt $attempt did not connect — retrying")
                    delay(3000)
                }
            }
            if (connectedDevice != null) Log.i(TAG, "HID attach success after $attempt attempt(s)")
        }
    }

    private fun connectInternal(adapter: BluetoothAdapter, device: BluetoothDevice) {
        try {
            hidProfile?.connect(device)
            Log.i(TAG, "HID connect requested to ${device.address}")
        } catch (e: Exception) {
            Log.e(TAG, "HID connect failed: ${e.message}")
        }
    }

    /**
     * Send a keyboard usage press+release through the system HID path.
     * holdMs emulates a real key hold (0 = instant click).
     */
    fun sendUsage(usage: Int, holdMs: Long = 60L) {
        sendKeyDown(usage)
        scope.launch {
            if (holdMs > 0) delay(holdMs)
            sendKeyUp(usage)
        }
    }

    /** Press-and-hold a usage (release with sendKeyUp) */
    fun sendKeyDown(usage: Int) {
        val dev = connectedDevice
        val profile = hidProfile
        if (dev == null || profile == null) {
            Log.w(TAG, "HID usage $usage DOWN dropped — link not attached")
            return
        }
        val press = byteArrayOf(
            REPORT_ID.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0
        )
        scope.launch {
            try {
                profile.sendReport(dev, REPORT_ID, press)
                Log.d(TAG, "HID usage $usage DOWN")
            } catch (e: Exception) {
                Log.e(TAG, "sendReport failed: ${e.message}")
            }
        }
    }

    /** Release a previously held usage */
    fun sendKeyUp(usage: Int) {
        val dev = connectedDevice
        val profile = hidProfile
        if (dev == null || profile == null) {
            Log.w(TAG, "HID usage $usage UP dropped — link not attached")
            return
        }
        val release = byteArrayOf(
            REPORT_ID.toByte(), 0, 0, 0, 0, 0, 0, 0
        )
        scope.launch {
            try {
                profile.sendReport(dev, REPORT_ID, release)
                Log.d(TAG, "HID usage $usage UP")
            } catch (e: Exception) {
                Log.e(TAG, "sendReport failed: ${e.message}")
            }
        }
    }

    /** True when the HUD is attached as our HID host (system path usable) */
    fun isConnected(): Boolean = connectedDevice != null

    /** Convenience: the remote-CAM equivalent (F2 → rear view camera) */
    fun triggerRearView() = sendUsage(Usage.F2)
}
