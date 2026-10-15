package com.motoeye.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoeye.remote.tpms.TpmsSensor
import com.motoeye.remote.tpms.TpmsState

/**
 * Full-screen TPMS overlay showing the 4 tire sensors with
 * pressure, temperature, battery and signal.
 */
@Composable
fun TpmsOverlay(state: TpmsState, onClose: () -> Unit) {
    // Guaranteed scan flow while the user is looking at TPMS
    DisposableEffect(Unit) {
        com.motoeye.remote.AppGraph.tpmsBle.setActive(true)
        onDispose { com.motoeye.remote.AppGraph.tpmsBle.setActive(false) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .padding(24.dp)
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "TPMS",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            // ---- Direct BLE sensors (Motoeye "VD" advertisements) ----
            val bleSensors by com.motoeye.remote.AppGraph.tpmsBle.sensors.collectAsState()
            if (bleSensors.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Direct BLE sensors", color = Color(0xFF66BB6A), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    bleSensors.values.sortedByDescending { it.rssi }.forEach { s ->
                        val ageSec = (System.currentTimeMillis() - s.lastSeen) / 1000
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF142233))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${formatPressure(s.pressurePsi, 1)}  ${formatTemperature(s.temperatureC.toFloat(), 0)}",
                                    color = Color.White,
                                    fontSize = 18.sp
                                )
                                Text(
                                    text = "${s.address.takeLast(5)} • ${s.batteryPct}% (298mv-format ${(s.batteryV)}V) • rssi ${s.rssi} • age ${ageSec}s"
                                        .replace("(298mv-format ", "("),
                                    color = Color(0xFF9E9E9E),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            if (!state.isActive) {
                if (bleSensors.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No sensor packets yet — sensors sleep and transmit on pressure change / wheel movement",
                        color = Color(0xFF9E9E9E),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "HUD data: firmware too old (needs ≥ 2512121814)",
                    color = Color(0xFF616161),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = {
                        com.motoeye.remote.AppGraph.connection.enableTpmReporting(true)
                    }) { Text("HUD retry", color = Color(0xFF66BB6A)) }

                    androidx.compose.material3.OutlinedButton(onClick = {
                        com.motoeye.remote.AppGraph.tpmsBle.stop()
                        com.motoeye.remote.AppGraph.tpmsBle.start()
                    }) { Text("Rescan sensors", color = Color(0xFF42A5F5)) }
                }
            } else {
                Spacer(Modifier.height(16.dp))

                // 2x2 grid of sensors: LF RF / LR RR
                val lf = state.getSensorOrNull(TpmsSensor.POSITION_LEFT_FRONT)
                val lr = state.getSensorOrNull(TpmsSensor.POSITION_LEFT_REAR)
                val rf = state.getSensorOrNull(TpmsSensor.POSITION_RIGHT_FRONT)
                val rr = state.getSensorOrNull(TpmsSensor.POSITION_RIGHT_REAR)

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SensorCard("Left Front", lf, state, Modifier.weight(1f))
                        SensorCard("Right Front", rf, state, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SensorCard("Left Rear", lr, state, Modifier.weight(1f))
                        SensorCard("Right Rear", rr, state, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun TpmsState.getSensorOrNull(position: Int): TpmsSensor? =
    sensors.find { it.position == position }

@Composable
private fun SensorCard(
    label: String,
    sensor: TpmsSensor?,
    state: TpmsState,
    modifier: Modifier = Modifier
) {
    val pressure = sensor?.pressure ?: -1f
    val alert = sensor != null && (
        (state.maxPressure > 0 && pressure >= state.maxPressure) ||
            (state.minPressure > 0 && pressure in 0.1f..state.minPressure.toFloat())
        )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (alert) Color(0x33F44336) else Color(0xFF1E1E1E))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, color = Color(0xFF9E9E9E), fontSize = 13.sp)

        Spacer(Modifier.height(8.dp))

        if (sensor == null) {
            Text(text = "—", color = Color(0xFF616161), fontSize = 32.sp)
        } else {
            Text(
                text = formatPressure(pressure, state.pressureUnit),
                color = if (alert) Color(0xFFF44336) else Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatItem("Temp", formatTemperature(sensor.temperature, state.temperatureUnit))
                StatItem("Bat", "${sensor.batteryPercent}%")
                StatItem("RSSI", "${sensor.rssi}dBm")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = Color(0xFFE0E0E0), fontSize = 13.sp)
        Text(text = label, color = Color(0xFF757575), fontSize = 10.sp)
    }
}

private fun formatPressure(raw: Float, unit: Int): String {
    // Pressure arrives already in the HUD's configured unit
    return when (unit) {
        1 -> String.format("%.1f psi", raw)
        2 -> String.format("%.0f kPa", raw)
        else -> String.format("%.2f bar", raw)
    }
}

private fun formatTemperature(celsius: Float, unit: Int): String {
    return if (unit == 1) {
        String.format("%.0f°F", celsius * 9 / 5 + 32)
    } else {
        String.format("%.0f°C", celsius)
    }
}
