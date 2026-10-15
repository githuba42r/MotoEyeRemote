package com.motoeye.remote.ble

import java.util.UUID

/**
 * BLE protocol constants and packet builders for the MotoEye E6 device.
 * Reverse-engineered from the MotoEye APK (com.vidoar.motohud).
 */
object Protocol {

    // GATT UUIDs
    val SERVICE_UUID: UUID = UUID.fromString("0000fe86-0000-1000-8000-00805f9b34fb")
    val WRITE_UUID: UUID = UUID.fromString("00000012-b100-0000-0000-100000000003")
    val NOTIFY_UUID: UUID = UUID.fromString("00000011-b100-0000-0000-100000000003")
    val READ_UUID: UUID = UUID.fromString("00000013-b100-0000-0000-100000000003")
    val INPUT_DEV_UUID: UUID = UUID.fromString("00000014-b100-0000-0000-100000000003")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Key codes (Android KeyEvent constants)
    object Keys {
        const val BACK = 4
        const val CAMERA = 132       // Opens DVR on current HUD firmware
        const val CAMERA_KEY = 27    // Android KEYCODE_CAMERA — rear camera candidate
        const val DPAD_UP = 19
        const val DPAD_DOWN = 20
        const val DPAD_LEFT = 21
        const val DPAD_RIGHT = 22
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val ENTER = 66
        const val HEADSETHOOK = 131  // PTT / voice trigger
        const val RECORD_M3 = 133    // Record (M3/M3S firmware)
        const val RECORD = 134       // Record / DVR (other firmware)
        const val MEDIA_PLAY_PAUSE = 85
        const val MEDIA_NEXT = 87
        const val MEDIA_PREVIOUS = 88
        const val MEDIA_PLAY = 126
        const val MEDIA_PAUSE = 127
    }

    // Command types for JSON protocol
    object CommandType {
        const val PING = 1
        const val DEVICE_INFO = 1000
        const val KEY_CLICK = 8001
        const val KEY_EVENT = 8002
        const val TPM_VEHICLE = 1031
        const val TPM_DATA = 1032
        const val REARVIEW_MIRROR = 1029   // CMD_REARVIEW_MIRROR — display rear camera
        const val CAMERA_KEY = 1030        // CMD_CMAERA_KEY — camera key trigger
        const val CONTROLLER_SCAN = 2001
        const val CONTROLLER_BOND = 2002
        const val CONTROLLER_INFO = 2003
    }

    // HID packet: [0x00, pressed, keycode_lo, keycode_hi, 0x00, 0x00]
    fun buildHidKeyEvent(keyCode: Int, pressed: Boolean): ByteArray {
        return byteArrayOf(
            0x00,
            if (pressed) 0x01 else 0x00,
            (keyCode and 0xFF).toByte(),
            ((keyCode shr 8) and 0xFF).toByte(),
            0x00,
            0x00
        )
    }

    // JSON command: {"type":<int>,"content":{...}}
    fun buildJsonCommand(type: Int, content: String? = null): String {
        return if (content != null) {
            """{"type":$type,"content":$content}"""
        } else {
            """{"type":$type}"""
        }
    }

    // Frame a JSON string into BLE packets (header + body chunks)
    // Header: [pkgCount & 0x7F, len_lo, len_hi, 0, 0]
    // Body: [pkgIndex | 0x80, up to 19 payload bytes]
    fun frameCommand(json: String): List<ByteArray> {
        val payload = json.toByteArray(Charsets.UTF_8)
        val packets = mutableListOf<ByteArray>()

        // Calculate packet count (19 bytes per body packet)
        val dataLen = payload.size
        val pkgCount = if (dataLen % 19 == 0) dataLen / 19 else (dataLen / 19) + 1

        // Header packet
        val header = byteArrayOf(
            (pkgCount and 0x7F).toByte(),
            (dataLen and 0xFF).toByte(),
            ((dataLen shr 8) and 0xFF).toByte(),
            0x00,
            0x00
        )
        packets.add(header)

        // Body packets
        for (i in 0 until pkgCount) {
            val offset = i * 19
            val remaining = dataLen - offset
            val chunkLen = minOf(19, remaining)
            val packet = ByteArray(1 + chunkLen)
            packet[0] = (i or 0x80).toByte()
            System.arraycopy(payload, offset, packet, 1, chunkLen)
            packets.add(packet)
        }

        return packets
    }

    // Device info JSON for handshake
    fun buildDeviceInfo(appVersion: String, language: String = "en"): String {
        val content = """{"code":"android","ptype":"android","uid":"","proVer":5,"appVer":"$appVersion","lan":"$language"}"""
        return buildJsonCommand(CommandType.DEVICE_INFO, content)
    }

    // Ping command
    fun buildPing(): String = buildJsonCommand(CommandType.PING)

    // TPMS commands
    fun buildTpmVehicleRequest(): String = buildJsonCommand(CommandType.TPM_VEHICLE)

    fun buildTpmDataReport(enable: Boolean): String {
        val isOn = if (enable) "1" else "0"
        return buildJsonCommand(CommandType.TPM_DATA, """{"isOn":"$isOn"}""")
    }

    // Key click command (JSON protocol, for protocol < 4)
    fun buildKeyClickJson(keyCode: Int): String {
        return buildJsonCommand(CommandType.KEY_CLICK, """{"key":$keyCode}""")
    }

    // Key event command (JSON protocol, for protocol < 4)
    fun buildKeyEventJson(keyCode: Int, isUp: Boolean): String {
        val up = if (isUp) 1 else 0
        return buildJsonCommand(CommandType.KEY_EVENT, """{"key":$keyCode,"isUp":$up}""")
    }

    // Rear-view camera display trigger (HUD shows the rear camera feed)
    fun buildRearviewCommand(): String = buildJsonCommand(CommandType.REARVIEW_MIRROR)

    // Camera key trigger (same key the physical remote's CAM button maps to)
    fun buildCameraKeyCommand(): String = buildJsonCommand(CommandType.CAMERA_KEY)
}
