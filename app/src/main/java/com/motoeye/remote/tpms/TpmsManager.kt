package com.motoeye.remote.tpms

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TpmsSensor(
    val id: String = "",
    val position: Int = 0,  // 0=LF, 1=LR, 2=RF, 3=RR
    val pressure: Float = -1f,    // Raw value
    val temperature: Float = -1f, // Celsius
    val voltage: Float = -1f,     // Volts
    val rssi: Int = 0
) {
    val batteryPercent: Int
        get() = when {
            voltage >= 2.9f -> 100
            voltage < 2.55f -> 0
            else -> (((voltage - 2.55f) / 0.35f) * 100).toInt()
        }

    companion object {
        const val POSITION_LEFT_FRONT = 0
        const val POSITION_LEFT_REAR = 1
        const val POSITION_RIGHT_FRONT = 2
        const val POSITION_RIGHT_REAR = 3
    }
}

data class TpmsState(
    val isActive: Boolean = false,
    val vehicleType: Int = 0,  // 0=motorcycle, 1=unicycle, 2=trike, 3=reverse trike, 4=ATV
    val vehicleName: String = "",
    val leftFrontAddr: String = "",
    val leftRearAddr: String = "",
    val rightFrontAddr: String = "",
    val rightRearAddr: String = "",
    val sensors: List<TpmsSensor> = emptyList(),
    val pressureUnit: Int = 0,  // 0=Bar, 1=Psi, 2=KPa
    val temperatureUnit: Int = 0,  // 0=Celsius, 1=Fahrenheit
    val maxPressure: Double = 0.0,
    val minPressure: Double = 0.0,
    val maxTemperature: Double = 0.0,
    val minVoltage: Double = 0.0,
    val lastUpdateTime: Long = 0
) {
    /** Number of sensor addresses paired on the HUD (from vehicle config) */
    val pairedSensorCount: Int
        get() = listOf(leftFrontAddr, leftRearAddr, rightFrontAddr, rightRearAddr)
            .count { it.isNotBlank() }
}

/**
 * Manages TPMS data from the MotoEye device.
 * Receives TPMS JSON via BLE notify characteristic.
 */
class TpmsManager {

    private val TAG = "TpmsManager"

    private val _state = MutableStateFlow(TpmsState())
    val state: StateFlow<TpmsState> = _state.asStateFlow()

    /** Parse TPMS JSON from HUD notify characteristic */
    fun handleTpData(json: String) {
        try {
            // Check if this is a TPMS message (type 1032)
            if (!json.contains("\"type\":1032") && !json.contains("\"type\": 1032")) {
                return
            }

            val isSuccess = extractInt(json, "isSuccess") == 1
            if (!isSuccess) return

            // Parse TPMS array
            val tpmsArray = extractArray(json, "tpms") ?: return
            val sensors = parseSensors(tpmsArray)

            // Parse unit and threshold info
            val presUnit = extractInt(json, "pU") ?: 0
            val tempUnit = extractInt(json, "tU") ?: 0
            val pMax = extractDouble(json, "pMax") ?: 0.0
            val pMin = extractDouble(json, "pMin") ?: 0.0
            val tMax = extractDouble(json, "tMax") ?: 0.0
            val vMin = extractDouble(json, "vMin") ?: 0.0

            _state.value = _state.value.copy(
                isActive = true,
                sensors = sensors,
                pressureUnit = presUnit,
                temperatureUnit = tempUnit,
                maxPressure = pMax,
                minPressure = pMin,
                maxTemperature = tMax,
                minVoltage = vMin,
                lastUpdateTime = System.currentTimeMillis()
            )

            Log.i(TAG, "TPMS data updated: ${sensors.size} sensors")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing TPMS data: ${e.message}")
        }
    }

    /** Parse TPMS vehicle info (type 1031) */
    fun handleVehicleData(json: String) {
        try {
            if (!json.contains("\"type\":1031") && !json.contains("\"type\": 1031")) {
                return
            }

            val isSuccess = extractInt(json, "isSuccess") == 1
            if (!isSuccess) return

            val vType = extractInt(json, "vType") ?: 0
            val name = extractString(json, "name") ?: ""

            _state.value = _state.value.copy(
                vehicleType = vType,
                vehicleName = name,
                leftFrontAddr = extractString(json, "lf") ?: "",
                leftRearAddr = extractString(json, "lr") ?: "",
                rightFrontAddr = extractString(json, "rf") ?: "",
                rightRearAddr = extractString(json, "rr") ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing vehicle data: ${e.message}")
        }
    }

    private fun parseSensors(arrayContent: String): List<TpmsSensor> {
        val sensors = mutableListOf<TpmsSensor>()
        // Split by }{ to get individual sensor objects
        val objects = arrayContent.split("},").map { it.trim().trim('{', '}') }

        for (obj in objects) {
            val id = extractString(obj, "id") ?: continue
            val position = extractInt(obj, "position") ?: 0
            val pressure = extractFloat(obj, "pressure") ?: -1f
            val temperature = extractFloat(obj, "temperature") ?: -1f
            val voltage = extractFloat(obj, "voltage") ?: -1f
            val rssi = extractInt(obj, "rssi") ?: 0

            sensors.add(TpmsSensor(
                id = id,
                position = position,
                pressure = pressure,
                temperature = temperature,
                voltage = voltage,
                rssi = rssi
            ))
        }
        return sensors
    }

    fun getSensorByPosition(position: Int): TpmsSensor? {
        return _state.value.sensors.find { it.position == position }
    }

    fun isAlertActive(): Boolean {
        val s = _state.value
        if (!s.isActive) return false

        return s.sensors.any { sensor ->
            (s.maxPressure > 0 && sensor.pressure >= s.maxPressure) ||
            (s.minPressure > 0 && sensor.pressure in 1f..s.minPressure.toFloat()) ||
            (s.maxTemperature > 0 && sensor.temperature >= s.maxTemperature) ||
            (s.minVoltage > 0 && sensor.voltage in 0.1f..s.minVoltage.toFloat())
        }
    }

    fun reset() {
        _state.value = TpmsState()
    }

    // Simple JSON extraction helpers (avoids external JSON library dependency)
    private fun extractString(json: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractInt(json: String, key: String): Int? {
        val regex = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractFloat(json: String, key: String): Float? {
        val regex = Regex("\"$key\"\\s*:\\s*(-?[\\d.]+)")
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractDouble(json: String, key: String): Double? {
        val regex = Regex("\"$key\"\\s*:\\s*(-?[\\d.]+)")
        return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractArray(json: String, key: String): String? {
        val start = json.indexOf("\"$key\"")
        if (start < 0) return null
        val arrayStart = json.indexOf('[', start)
        if (arrayStart < 0) return null
        val arrayEnd = json.indexOf(']', arrayStart)
        if (arrayEnd < 0) return null
        return json.substring(arrayStart + 1, arrayEnd)
    }
}
