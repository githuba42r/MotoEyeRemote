package com.motoeye.remote.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    DISCOVERING,
    HANDSHAKING,
    CONNECTED,
    RECONNECTING
}

data class DeviceStatus(
    val protocolVersion: Int = 5,
    val deviceName: String = "",
    val batteryLevel: Int = -1,
    val isRecording: Boolean = false,
    val isCameraOn: Boolean = false,
    val connectCount: Int = 0
)

@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) {

    private val TAG = "BleConnectionManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var hidCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    private var savedAddress: String? = null
    private var reconnectAttempt = 0
    private var isReconnecting = false
    private var isHandshakeDone = false

    // Disconnect callback
    var onDisconnected: (() -> Unit)? = null
    var onMessageReceived: ((String) -> Unit)? = null
    var onConnected: ((String) -> Unit)? = null

    fun connect(address: String) {
        Log.i(TAG, "connect() requested for $address")
        savedAddress = address
        reconnectAttempt = 0
        isReconnecting = false
        reconnectScheduled = false
        stopWatch()
        ensureBondThenConnect(address)
    }

    /**
     * Ensure an ENCRYPTED LE bond exists before connecting. The HUD's
     * services tolerate unencrypted links, but the bond record analysis
     * (le_linkkey_known=F) showed we were riding an unsecured link — likely
     * behind the project's mystery disconnects.
     */
    private fun ensureBondThenConnect(address: String) {
        scope.launch {
            try {
                val device = try {
                    adapter.getRemoteDevice(address)
                } catch (e: Exception) {
                    Log.e(TAG, "bad address $address"); return@launch
                }
                _connectionState.value = ConnectionState.CONNECTING
                Log.i(TAG, "Bond check: state=${device.bondState}")
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    // state must be cleared before doConnect to avoid the
                    // "already connecting" early-return
                    _connectionState.value = ConnectionState.DISCONNECTED
                    doConnect(address, autoConnect = false)
                    return@launch
                }
                Log.i(TAG, "No LE bond with $address — creating bond for encrypted link")
                try {
                    device.createBond()
                } catch (e: Exception) {
                    Log.e(TAG, "createBond failed: ${e.message}")
                }
                val start = System.currentTimeMillis()
                while (device.bondState != BluetoothDevice.BOND_BONDED &&
                    System.currentTimeMillis() - start < 25_000
                ) {
                    delay(500)
                    if (device.bondState == BluetoothDevice.BOND_NONE) {
                        Log.e(TAG, "Bonding rejected/failed")
                        break
                    }
                }
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    doConnect(address, autoConnect = false)
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    scheduleReconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureBondThenConnect error: ${e.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun doConnect(address: String, autoConnect: Boolean = false) {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.DISCOVERING
        ) return

        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "Connecting to $address (autoConnect=$autoConnect)")

        try {
            val device = adapter.getRemoteDevice(address)
            gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "Connection state: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.DISCOVERING
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from ${gatt.device.address} (reconnecting=$isReconnecting)")
                    cleanupGatt()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    isHandshakeDone = false
                    onDisconnected?.invoke()
                    // Always continue the reconnect cycle while a saved device
                    // exists (scheduleReconnect owns the state machine)
                    if (savedAddress != null) {
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                _connectionState.value = ConnectionState.DISCONNECTED
                return
            }

            val service = gatt.getService(Protocol.SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Service ${Protocol.SERVICE_UUID} not found")
                _connectionState.value = ConnectionState.DISCONNECTED
                return
            }

            writeCharacteristic = service.getCharacteristic(Protocol.WRITE_UUID)
            notifyCharacteristic = service.getCharacteristic(Protocol.NOTIFY_UUID)
            readCharacteristic = service.getCharacteristic(Protocol.READ_UUID)
            hidCharacteristic = service.getCharacteristic(Protocol.INPUT_DEV_UUID)

            // Set write types
            writeCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            hidCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            // Enable notifications on notify characteristic
            notifyCharacteristic?.let { char ->
                gatt.setCharacteristicNotification(char, true)
                val descriptor = char.getDescriptor(Protocol.CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }

            _connectionState.value = ConnectionState.HANDSHAKING
            // Perform handshake after a short delay
            scope.launch {
                delay(200)
                performHandshake()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == Protocol.CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "CCCD write successful, notifications enabled")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { value ->
                onNotifyPacket(value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onNotifyPacket(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                characteristic.value?.let { value ->
                    if (characteristic.uuid == Protocol.READ_UUID) {
                        handleDeviceStatus(value)
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == Protocol.READ_UUID) {
                    handleDeviceStatus(value)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: status=$status, uuid=${characteristic.uuid}")
            }
        }
    }

    private fun performHandshake() {
        scope.launch {
            try {
                // Step 1: Read device status
                readDeviceStatus()
                delay(300)

                // Step 2: Send ping
                val ping = Protocol.buildPing()
                sendFramedCommand(ping)
                delay(200)

                // Step 3: Send device info
                val appVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
                } catch (_: Exception) { "0.1.0" }
                currentVersion = appVersion
                val deviceInfo = Protocol.buildDeviceInfo(appVersion)
                sendFramedCommand(deviceInfo)

                isHandshakeDone = true
                reconnectAttempt = 0
                isReconnecting = false
                reconnectScheduled = false
                stopWatch()
                _connectionState.value = ConnectionState.CONNECTED
                onConnected?.invoke(savedAddress ?: "")
                startInfoPoll()
                Log.i(TAG, "Handshake complete, connected")

                // Read device status again after handshake
                delay(500)
                readDeviceStatus()

                // Kick off TPMS: request vehicle config (1031 — its success
                // response auto-triggers enable), then also enable data
                // reporting (1032) directly as belt-and-braces, matching how
                // the MotoEye app's TPMS page behaves.
                delay(800)
                requestTpmVehicle()
                delay(1500)
                enableTpmReporting(true)
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed: ${e.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    // ---- Receive reassembly (port of MessageReceiver02) ----
    // HUD frames responses like the phone frames requests: a header packet
    // [count&0x7F, len_lo, len_hi, 0, 0] (no payload), then body packets
    // [index|0x80, <=19 payload bytes]. JSON is complete only when all
    // body packets have been collected.
    private var recvIsSending = false
    private var recvLength = 0
    private var recvMsg: ByteArray? = null
    private var recvNowLength = 0

    private fun resetRecv() {
        recvIsSending = false
        recvLength = 0
        recvMsg = null
        recvNowLength = 0
    }

    private fun onNotifyPacket(bArr: ByteArray) {
        if (bArr.isEmpty()) return
        val isBody = (bArr[0].toInt() and 0x80) != 0

        if (!recvIsSending) {
            if (isBody) {
                // Orphan body packet with no header — drop and reset
                Log.w(TAG, "Orphan body packet, reset")
                resetRecv()
                return
            }
            recvLength = (bArr[1].toInt() and 0xFF) or ((bArr[2].toInt() and 0xFF) shl 8)
            recvMsg = ByteArray(recvLength)
            recvIsSending = true
            recvNowLength = 0
            Log.d(TAG, "Header packet: count=${bArr[0].toInt() and 0x7F}, length=$recvLength")
            return
        }

        // Body packet
        val msg = recvMsg
        if (!isBody || msg == null) {
            Log.w(TAG, "Unexpected header mid-transfer, reset")
            resetRecv()
            return
        }
        val index = bArr[0].toInt() and 0x7F
        val offset = index * 19
        val payloadLen = bArr.size - 1
        if (offset + payloadLen > msg.size) {
            Log.e(TAG, "Body packet overflow (idx=$index, len=$payloadLen), reset")
            resetRecv()
            return
        }
        System.arraycopy(bArr, 1, msg, offset, payloadLen)
        recvNowLength = offset + payloadLen
        Log.d(TAG, "Body packet: idx=$index, now=$recvNowLength/${msg.size}")

        if (recvNowLength >= recvLength) {
            val json = String(msg, 0, recvLength, Charsets.UTF_8)
            resetRecv()
            handleJson(json)
        }
    }

    /** Handle a complete JSON message from the HUD */
    private fun handleJson(json: String) {
        _lastMessage.value = json
        Log.i(TAG, "RX JSON: $json")
        onMessageReceived?.invoke(json)

        try {
            val typeMatch = Regex("\"type\"\\s*:\\s*(\\d+)").find(json)
            val type = typeMatch?.groupValues?.get(1)?.toIntOrNull() ?: return

            when (type) {
                1000 -> parseDeviceInfo(json)
                1031 -> {
                    // TPMS vehicle response — if success, enable data reporting
                    val success = extractInt(json, "isSuccess") == 1
                    if (success) {
                        Log.i(TAG, "TPMS vehicle OK — enabling data reporting")
                        enableTpmReporting(true)
                    } else {
                        Log.w(TAG, "TPMS vehicle request failed: $json")
                    }
                }
                1032 -> Log.d(TAG, "TPMS message: $json")
                1 -> Log.d(TAG, "Ping ack received")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON ($json): ${e.message}")
        }
    }

    private fun extractInt(json: String, key: String): Int? {
        return Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * The HUD's own battery percentage arrives inside the type-1000
     * device-info reply (field "power", % — subagent-verified against the
     * decompile: HostFragment renders it as "NN%"). The HUD never pushes
     * it spontaneously, so poll the device info on a cadence while
     * connected. Battery moves slowly — 45s is plenty.
     */
    private fun startInfoPoll() {
        infoPollJob?.cancel()
        infoPollJob = scope.launch {
            while (isActive && isConnected()) {
                try {
                    sendFramedCommand(Protocol.buildPing())      // keepalive
                    sendFramedCommand(Protocol.buildDeviceInfo(currentVersion))
                } catch (_: Exception) {}
                delay(45_000)
            }
        }
    }
    private var infoPollJob: kotlinx.coroutines.Job? = null
    @Volatile private var currentVersion: String = "0.4.7"


    private fun parseDeviceInfo(data: String) {
        try {
            val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(data)
            val powerMatch = Regex("\"power\"\\s*:\\s*\"?(\\d+)\"?").find(data)
            val ctStateMatch = Regex("\"ctState\"\\s*:\\s*(\\d+)").find(data)
            val snMatch = Regex("\"sn\"\\s*:\\s*\"([^\"]+)\"").find(data)

            _deviceStatus.value = _deviceStatus.value.copy(
                deviceName = nameMatch?.groupValues?.get(1) ?: "",
                batteryLevel = powerMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1,
                connectCount = ctStateMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing device info: ${e.message}")
        }
    }

    private fun handleDeviceStatus(value: ByteArray) {
        // Device status from read characteristic
        if (value.size >= 2) {
            val protocolVersion = value[1].toInt() and 0xFF
            _deviceStatus.value = _deviceStatus.value.copy(protocolVersion = protocolVersion)
            Log.i(TAG, "Device protocol version: $protocolVersion")
        }
    }

    fun sendKeyEvent(keyCode: Int, pressed: Boolean) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send key event: not connected")
            return
        }

        val protocol = _deviceStatus.value.protocolVersion
        if (protocol >= 5) {
            // HID mode (protocol v5+)
            sendHidData(Protocol.buildHidKeyEvent(keyCode, pressed))
        } else {
            // JSON mode (protocol < 4)
            if (pressed) {
                // Only send on press for JSON mode (no release events)
                sendFramedCommand(Protocol.buildKeyClickJson(keyCode))
            }
        }
    }

    fun sendKeyClick(keyCode: Int) {
        sendKeyEvent(keyCode, true)
        // For HID mode, also send release
        if (_deviceStatus.value.protocolVersion >= 5) {
            scope.launch {
                delay(50)
                sendKeyEvent(keyCode, false)
            }
        }
    }

    private fun sendHidData(data: ByteArray) {
        val char = hidCharacteristic ?: run {
            Log.e(TAG, "HID characteristic not found")
            return
        }
        char.value = data
        gatt?.writeCharacteristic(char)
    }

    private fun sendFramedCommand(json: String) {
        val packets = Protocol.frameCommand(json)
        val char = writeCharacteristic ?: run {
            Log.e(TAG, "Write characteristic not found")
            return
        }

        scope.launch {
            Log.i(TAG, "Sending framed command (${packets.size} packets): $json")
            for (packet in packets) {
                char.value = packet
                val ok = gatt?.writeCharacteristic(char) ?: false
                if (!ok) Log.e(TAG, "Write returned false for packet of ${packet.size}B")
                delay(20) // Small delay between packets
            }
        }
    }

    private fun readDeviceStatus() {
        val char = readCharacteristic ?: return
        gatt?.readCharacteristic(char)
    }

    fun sendJsonCommand(json: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send command: not connected")
            return
        }
        sendFramedCommand(json)
    }

    fun requestTpmVehicle() {
        sendJsonCommand(Protocol.buildTpmVehicleRequest())
    }

    fun enableTpmReporting(enable: Boolean) {
        sendJsonCommand(Protocol.buildTpmDataReport(enable))
    }

    // ---- Auto-reconnect: quick retry, then periodic presence checks ----
    @Volatile private var reconnectScheduled = false
    private var presenceJob: kotlinx.coroutines.Job? = null

    private fun scheduleReconnect() {
        if (savedAddress == null) return
        if (reconnectScheduled) return
        reconnectScheduled = true

        scope.launch {
            val autoReconnect = runCatching {
                com.motoeye.remote.data.SettingsRepository(context).autoReconnect()
            }.getOrDefault(true)
            reconnectScheduled = false
            if (!autoReconnect) {
                isReconnecting = false
                _connectionState.value = ConnectionState.DISCONNECTED
                return@launch
            }
            isReconnecting = true
            _connectionState.value = ConnectionState.RECONNECTING
            reconnectAttempt++
            if (reconnectAttempt == 1) {
                // The HUD likely just dropped — try a direct reconnect quickly.
                // If this fails, DISCONNECTED fires and re-enters here (attempt
                // 2+), which switches to presence-watch mode.
                delay(1000)
                Log.i(TAG, "Reconnect attempt $reconnectAttempt (direct)")
                savedAddress?.let { doConnect(it, autoConnect = true) }
            } else if (presenceJob?.isActive != true) {
                Log.i(TAG, "Reconnect attempt $reconnectAttempt (presence watch)")
                startPresenceWatch()
            }
        }
    }

    /**
     * Presence watch: repeatedly check whether the registered HUD has come
     * back, then attach. Checks are bounded and gentle:
     *  1. cheap system check — is the HUD in this phone's bonded (paired)
     *     device list;
     *  2. a short ~1.5s filtered BLE scan burst for its address.
     * Interval grows from 3s up to 15s. No continuous scanning.
     */
    private fun startPresenceWatch() {
        val job = presenceJob
        if (job?.isActive == true) return
        presenceJob = scope.launch {
            var intervalMs = 3000L
            val startedAt = System.currentTimeMillis()
            while (isActive && isReconnecting && savedAddress != null) {
                // Give up after 2 minutes of failed attempts: report a plain
                // DISCONNECTED (→ notification hidden, UI returns to the
                // device-rescan screen).
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed > 120_000) {
                    Log.w(TAG, "Reconnection given up after ${(elapsed / 1000)}s")
                    isReconnecting = false
                    _connectionState.value = ConnectionState.DISCONNECTED
                    return@launch
                }
                val visible = presenceCheck()
                if (visible) {
                    Log.i(TAG, "Registered HUD visible — attaching")
                    _connectionState.value = ConnectionState.CONNECTING
                    doConnect(savedAddress!!, autoConnect = true)
                    return@launch
                }
                delay(intervalMs)
                intervalMs = (intervalMs + 2000).coerceAtMost(15000L)
            }
        }
    }

    /** One bounded presence check: bond-state + short filtered scan burst */
    private suspend fun presenceCheck(): Boolean {
        val addr = savedAddress ?: return false

        // 1) Cheap check: is the saved HUD among this phone's paired devices?
        val bonded = try {
            adapter.bondedDevices?.any { it.address.equals(addr, ignoreCase = true) }
        } catch (_: Exception) { null }
        if (bonded == false) {
            Log.w(TAG, "Saved HUD $addr is not in this phone's paired devices")
        }

        // 2) Short scan burst filtered to the HUD's address
        val bleScanner = try { adapter.bluetoothLeScanner } catch (_: Exception) { null }
        if (bleScanner == null) {
            Log.w(TAG, "BLE scanner unavailable (radio off?)")
            return false
        }
        return suspendCancellableCoroutine { cont ->
            var done = false
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (done) return
                    done = true
                    try { bleScanner.stopScan(this) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(
                        result.device?.address.equals(addr, ignoreCase = true)
                    )
                }
                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Presence scan failed: $errorCode")
                    if (done) return
                    done = true
                    if (cont.isActive) cont.resume(false)
                }
            }
            val filter = ScanFilter.Builder().setDeviceAddress(addr).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            try {
                bleScanner.startScan(listOf(filter), settings, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Presence scan start error: ${e.message}")
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
            // Timeout: HUD not advertising within 1.5s
            scope.launch {
                delay(1500)
                if (!done) {
                    done = true
                    try { bleScanner.stopScan(callback) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
    }

    private fun stopWatch() {
        presenceJob?.cancel()
        presenceJob = null
    }

    fun disconnect() {
        isReconnecting = false
        reconnectScheduled = false
        stopWatch()
        savedAddress = null
        cleanupGatt()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun cleanupGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        readCharacteristic = null
        hidCharacteristic = null
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
}
