package com.motoeye.remote.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoeye.remote.AppGraph
import com.motoeye.remote.ble.ConnectionState
import com.motoeye.remote.ble.DeviceScanner
import com.motoeye.remote.ble.DiscoveredDevice
import com.motoeye.remote.data.SettingsRepository
import com.motoeye.remote.service.RemoteForegroundService
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Device selection screen: scan for MotoEye HUD, pair, connect.
 * Once connected (or previously paired + auto-connect), navigates to
 * the remote screen.
 */
@Composable
fun DeviceSelectScreen(
    onConnected: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val connectionState by AppGraph.connection.connectionState.collectAsState()
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
    }

    val devices = remember { mutableStateListOf<DiscoveredDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var savedAddress by remember { mutableStateOf<String?>(null) }
    var savedName by remember { mutableStateOf<String?>(null) }

    fun startScan() {
        scanning = true
        devices.clear()
        scope.launch {
            DeviceScanner(context).scan(10_000)
                .catch { scanning = false }
                .collect { list ->
                    devices.clear()
                    devices.addAll(list)
                }
            scanning = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> if (grants.values.all { it }) startScan() }

    fun ensurePermissions(onGranted: () -> Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) onGranted()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    // Load saved device + auto-connect
    LaunchedEffect(Unit) {
        settings.settings.collect { s ->
            savedAddress = s.pairedDeviceAddress
            savedName = s.pairedDeviceName
        }
    }

    // Auto-connect to saved device once
    LaunchedEffect(savedAddress) {
        if (savedAddress != null && connectionState == ConnectionState.DISCONNECTED) {
            AppGraph.connection.connect(savedAddress!!)
        }
    }

    // Navigate to remote when connected
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            onConnected()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        // Header: title + settings access (available even when not connected)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MotoEye Remote",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "v$appVersion",
                    color = Color(0xFF757575),
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color(0xFFE0E0E0)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Connect to your MotoEye E6 HUD",
            color = Color(0xFF9E9E9E),
            fontSize = 15.sp
        )
        Spacer(Modifier.height(24.dp))

        // Connection status
        StatusPill(connectionState)

        Spacer(Modifier.height(24.dp))

        if (savedAddress != null && connectionState == ConnectionState.DISCONNECTED) {
            Text(
                text = "Saved device: ${savedName ?: savedAddress}",
                color = Color(0xFF9E9E9E),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                ensurePermissions {
                    AppGraph.connection.connect(savedAddress!!)
                }
            }) {
                Text("Reconnect")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    settings.clearPairedDevice()
                    savedAddress = null
                }
            }) {
                Text("Forget device")
            }
            Spacer(Modifier.height(16.dp))
        }

        // Scan button
        OutlinedButton(onClick = {
            ensurePermissions { startScan() }
        }, enabled = !scanning) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("Scanning…")
            } else {
                Text(if (devices.isEmpty()) "Scan for devices" else "Scan again")
            }
        }

        Spacer(Modifier.height(16.dp))

        // Device list
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(devices) { device ->
                DeviceRow(device = device) {
                    scope.launch {
                        settings.savePairedDevice(device.address, device.name)
                        savedAddress = device.address
                        savedName = device.name
                    }
                    AppGraph.connection.connect(device.address)
                }
            }
            if (devices.isEmpty() && !scanning) {
                item {
                    Text(
                        text = "No devices found. Make sure the HUD is on.",
                        color = Color(0xFF616161),
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(state: ConnectionState) {
    val (text, color) = when (state) {
        ConnectionState.CONNECTED -> "Connected" to Color(0xFF4CAF50)
        ConnectionState.CONNECTING,
        ConnectionState.DISCOVERING,
        ConnectionState.HANDSHAKING -> "Connecting…" to Color(0xFFFF9800)
        ConnectionState.RECONNECTING -> "Reconnecting…" to Color(0xFFFF9800)
        ConnectionState.SCANNING -> "Scanning…" to Color(0xFF2196F3)
        ConnectionState.DISCONNECTED -> "Disconnected" to Color(0xFFF44336)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(text = text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = device.name,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    text = device.address,
                    color = Color(0xFF9E9E9E),
                    fontSize = 13.sp
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${device.rssi} dBm",
                    color = Color(0xFF616161),
                    fontSize = 13.sp
                )
            }
        }
    }
}
