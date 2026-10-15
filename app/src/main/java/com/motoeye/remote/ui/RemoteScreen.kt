package com.motoeye.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoeye.remote.AppGraph
import com.motoeye.remote.ble.ConnectionState
import com.motoeye.remote.ble.Protocol
import com.motoeye.remote.accessibility.AutoControlService
import com.motoeye.remote.tpms.TpmsManager

private val ButtonBg = Color(0xFF2A2A2A)
private val ButtonPressed = Color(0xFF404040)
private val DpadBg = Color(0xFF1A1A2E)
private val DpadCenter = Color(0xFF16213E)
private val RecordRed = Color(0xFFE53935)
private val CameraBlue = Color(0xFF42A5F5)
private val VoicePurple = Color(0xFFAB47BC)
private val ConnectedGreen = Color(0xFF4CAF50)
private val DisconnectedRed = Color(0xFFF44336)

@Composable
fun RemoteScreen(
    onOpenSettings: () -> Unit,
    onOpenDeviceSelect: () -> Unit
) {
    val context = LocalContext.current
    val connectionState by AppGraph.connection.connectionState.collectAsState()
    val tpmsState by AppGraph.tpms.state.collectAsState()
    val mediaPlaying by AppGraph.media.isPlaying.collectAsState()
    var showTpms by remember { mutableStateOf(false) }
    var showMacroSheet by remember { mutableStateOf(false) }

    // HID channel readiness (camera/record live only when attached)
    val hidReady by AppGraph.phoneHid.isAttached.collectAsState()
    val hudConnected = connectionState == ConnectionState.CONNECTED

    // Settings-driven behavior
    val settings = remember { com.motoeye.remote.data.SettingsRepository(context) }
    var vibrationEnabled by remember { mutableStateOf(true) }
    var showTpmsButton by remember { mutableStateOf(true) }
    var showRecordButton by remember { mutableStateOf(true) }
    val tpmsSensors by AppGraph.tpmsBle.sensors.collectAsState()
    val deviceStatus by AppGraph.connection.deviceStatus.collectAsState()
    val macroList by AppGraph.macroDb.macroDao().observeAll().collectAsState(initial = emptyList())
    val macroCount = macroList.size
    LaunchedEffect(Unit) {
        settings.settings.collect {
            vibrationEnabled = it.vibrationEnabled
            showTpmsButton = it.showTpms
            showRecordButton = it.showRecordButton
        }
    }
    var isRecording by remember { mutableStateOf(false) }
    val toggleRecord: () -> Unit = {
        // Physical remote semantics: hold the camera key (F2) ~1.1s.
        // Threshold measured live: 900ms does nothing, 1200ms toggles.
        // Short F2 press = rear view toggle.
        if (AppGraph.phoneHid.isConnected()) {
            AppGraph.phoneHid.sendUsage(0x3B, 1100L)
        } else {
            // Legacy fallback while the HID link is down
            AppGraph.connection.sendKeyClick(Protocol.Keys.RECORD)
        }
        isRecording = !isRecording
    }
    // Direct-vibrator haptics: View.performHapticFeedback is suppressed by
    // Samsung's system-haptics switch; raw vibrator always works (VIBRATE perm).
    val vibrator = remember {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    }
    val pressHaptic: (Boolean) -> Unit = { pressed ->
        if (pressed && vibrationEnabled) {
            try {
                vibrator?.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        25, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp)
    ) {
        // Top status bar
        StatusBar(
            connectionState = connectionState,
            tpmsActive = tpmsState.isActive,
            hudBattery = deviceStatus.batteryLevel,
            onSettings = onOpenSettings,
            onDevice = onOpenDeviceSelect
        )

        // Main control area — corner shortcuts + centered D-pad + BACK
        // below it + volume keys in the bottom corners. Works in both
        // orientations (sizes scale for landscape).
        val isLandscape = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp)
        ) {
            val cornerSize = (if (isLandscape) 86.dp else 104.dp)
            val volSize = (if (isLandscape) 70.dp else 92.dp)
            val dpadSize = (if (isLandscape) 195.dp else 250.dp)
            val backSize = (if (isLandscape) 56.dp else 72.dp)

            // CAM top-left (square, rounded, big)
            TapButton(
                label = "CAM",
                icon = Icons.Default.Camera,
                color = CameraBlue,
                onClick = {
                    pressHaptic(true)
                    AppGraph.sendCameraTrigger()
                },
                size = cornerSize,
                enabled = hidReady && hudConnected,
                disabledLabel = if (!hudConnected) "OFF" else "HID…",
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.TopStart)
            )

            // REC top-right (square, rounded, big); when record is hidden,
            // BACK takes the top-right corner at the same size instead
            if (showRecordButton) {
                RecordButton(
                    isRecording = isRecording,
                    onToggle = toggleRecord,
                    onHaptic = pressHaptic,
                    label = if (isRecording) "STOP" else "REC",
                    size = cornerSize,
                    enabled = hidReady && hudConnected,
                    disabledLabel = if (!hudConnected) "OFF" else "HID…",
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            } else {
                HoldButton(
                    label = "BACK",
                    icon = Icons.AutoMirrored.Filled.Undo,
                    color = ButtonBg,
                    onPress = { pressHaptic(it); AppGraph.sendReflectiveKey(0x29, Protocol.Keys.BACK, it) },
                    size = cornerSize,
                    enabled = hudConnected,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }

            // Center: D-pad, and BACK beneath it when record owns the corner
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Dpad(onHaptic = pressHaptic, sizeDp = dpadSize, enabled = hudConnected)
                if (showRecordButton) {
                    Spacer(Modifier.height(if (isLandscape) 4.dp else 16.dp))
                    HoldButton(
                        label = "BACK",
                        icon = Icons.AutoMirrored.Filled.Undo,
                        color = ButtonBg,
                        onPress = { pressHaptic(it); AppGraph.sendReflectiveKey(0x29, Protocol.Keys.BACK, it) },
                        size = backSize,
                        enabled = hudConnected
                    )
                }
            }

            // Volume keys: large circles in the bottom corners
            Column(
                modifier = Modifier.align(Alignment.BottomStart),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (macroCount > 0) {
                    TapButton(
                        label = "",
                        icon = Icons.Default.DirectionsRun,
                        color = Color(0xFF1F3A5F),
                        onClick = { showMacroSheet = true },
                        size = if (isLandscape) 50.dp else 56.dp,
                        enabled = hudConnected,
                        disabledLabel = "",
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                }
                HoldButton(
                    label = "",
                    icon = Icons.Default.Add,
                    color = ButtonBg,
                    onPress = { pressHaptic(it); AppGraph.sendReflectiveKey(0x52, Protocol.Keys.DPAD_UP, it) },
                    size = volSize,
                    minHoldMs = 2000L,
                    enabled = hudConnected
                )
            }
            Column(
                modifier = Modifier.align(Alignment.BottomEnd),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showTpmsButton) {
                    val best = tpmsSensors.values.maxByOrNull { it.lastSeen }
                    Column(
                        modifier = Modifier.padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (best != null) {
                            Text(
                                "%.1f psi".format(best.pressurePsi),
                                color = Color(0xFF66BB6A),
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "%d°C · %d%%".format(best.temperatureC, best.batteryPct),
                                color = Color(0xFF9E9E9E),
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                "TPMS —",
                                color = Color(0xFF757575),
                                fontSize = 13.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                HoldButton(
                    label = "",
                    icon = Icons.Default.Remove,
                    color = ButtonBg,
                    onPress = { pressHaptic(it); AppGraph.sendReflectiveKey(0x51, Protocol.Keys.DPAD_DOWN, it) },
                    size = volSize,
                    minHoldMs = 2000L,
                    enabled = hudConnected
                )
            }
        }

        val tpmsSummaryLabel: String = run {
            val best = tpmsSensors.values.maxByOrNull { it.lastSeen }
            if (best != null) {
                "%.1f/%d".format(best.pressurePsi, best.temperatureC)
            } else "TPMS"
        }

        // Bottom bar: AA + media + TPMS
        BottomBar(
            mediaPlaying = mediaPlaying,
            showTpmsButton = showTpmsButton,
            onPlayPause = { AppGraph.media.playPause() },
            onNext = { AppGraph.media.next() },
            onPrev = { AppGraph.media.previous() },
            onExitAa = {
                if (!AutoControlService.exitAndroidAutoBestEffort()) {
                    // Fallback: no accessibility service — send back
                    AppGraph.keySender.click(Protocol.Keys.BACK)
                }
            },
            onTpms = { showTpms = !showTpms },
            tpmsActive = tpmsState.isActive,
            tpmsSummary = tpmsSummaryLabel
        )
    }

    // Macro sheet modal
    if (showMacroSheet) {
        MacroSheet(onClose = { showMacroSheet = false })
    }

    // TPMS overlay
    if (showTpms) {
        TpmsOverlay(state = tpmsState, onClose = { showTpms = false })
    }
}

@Composable
private fun TapButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    enabled: Boolean = true,
    disabledLabel: String = "HID…",
    shape: androidx.compose.ui.graphics.Shape = CircleShape
) {
    var pressed by remember { mutableStateOf(false) }
    val effEnabled by rememberUpdatedState(enabled)

    // While the HID channel is attaching, keep the button visible-but-dim:
    // pure alpha over black reads as "broken", so give a waiting tint.
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(
                    when {
                        pressed -> ButtonPressed
                        effEnabled -> color
                        else -> color.copy(alpha = 0.45f)
                    }
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = effEnabled
                            try{ awaitRelease() } finally { pressed = false }
                        },
                        onTap = { if (effEnabled) onClick() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White.copy(alpha = if (effEnabled) 1f else 0.5f),
                modifier = Modifier.size(size * 0.45f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (effEnabled) label else disabledLabel,
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    onToggle: () -> Unit,
    onHaptic: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    enabled: Boolean = true,
    disabledLabel: String = "HID…",
    shape: androidx.compose.ui.graphics.Shape = CircleShape
) {
    var pressed by remember { mutableStateOf(false) }
    val effEnabled by rememberUpdatedState(enabled)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(
                    when {
                        pressed -> ButtonPressed
                        effEnabled && isRecording -> RecordRed
                        effEnabled -> RecordRed.copy(alpha = 0.35f)
                        !effEnabled && isRecording -> RecordRed.copy(alpha = 0.55f)
                        else -> RecordRed.copy(alpha = 0.18f)
                    }
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = effEnabled
                            if (effEnabled) onHaptic(true)
                            try {
                                awaitRelease()
                            } finally {
                                pressed = false
                            }
                        },
                        onTap = { if (effEnabled) onToggle() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Recording dot indicator
            Box(
                modifier = Modifier
                    .size(size * 0.38f)
                    .clip(CircleShape)
                    .background(if (isRecording) Color.White else RecordRed)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (effEnabled) label else disabledLabel,
            color = if (isRecording) RecordRed else Color(0xFF9E9E9E),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}


@Composable
private fun StatusBar(
    connectionState: ConnectionState,
    tpmsActive: Boolean,
    hudBattery: Int = -1,
    onSettings: () -> Unit,
    onDevice: () -> Unit
) {
    val statusText = when (connectionState) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.CONNECTING,
        ConnectionState.DISCOVERING,
        ConnectionState.HANDSHAKING -> "Connecting…"
        ConnectionState.RECONNECTING -> "Reconnecting…"
        ConnectionState.SCANNING -> "Scanning…"
        ConnectionState.DISCONNECTED -> "Disconnected"
    }
    val statusColor = if (connectionState == ConnectionState.CONNECTED) ConnectedGreen else DisconnectedRed

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onDevice)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusText,
                color = Color(0xFFE0E0E0),
                fontSize = 14.sp
            )
            if (hudBattery in 0..100) {
                Spacer(Modifier.width(12.dp))
                if (connectionState == ConnectionState.CONNECTED) {
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = "HUD battery",
                        tint = ConnectedGreen,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = "$hudBattery%",
                    color = Color(0xFFBDBDBD),
                    fontSize = 13.sp
                )
            }
            if (tpmsActive) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Default.TireRepair,
                    contentDescription = "TPMS active",
                    tint = Color(0xFF66BB6A),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFFE0E0E0))
            }
        }
    }
}


@Composable
private fun RightButtonColumn(
    onHaptic: (Boolean) -> Unit,
    buttonSize: Dp = 72.dp,
    spacing: Dp = 16.dp
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        HoldButton(
            label = "BACK",
            icon = Icons.AutoMirrored.Filled.Undo,
            color = ButtonBg,
            onPress = { onHaptic(it); AppGraph.sendReflectiveKey(0x29, Protocol.Keys.BACK, it) },
            size = buttonSize
        )
        HoldButton(
            label = "VOL+",
            icon = Icons.Default.Add,
            color = ButtonBg,
            onPress = { onHaptic(it); AppGraph.sendReflectiveKey(0x52, Protocol.Keys.DPAD_UP, it) },
            size = buttonSize,
            minHoldMs = 2000L
        )
        HoldButton(
            label = "VOL−",
            icon = Icons.Default.Remove,
            color = ButtonBg,
            onPress = { onHaptic(it); AppGraph.sendReflectiveKey(0x51, Protocol.Keys.DPAD_DOWN, it) },
            size = buttonSize,
            minHoldMs = 2000L
        )
    }
}

@Composable
private fun HoldButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onPress: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    minHoldMs: Long = 0,
    enabled: Boolean = true
) {
    var pressed by remember { mutableStateOf(false) }
    val effEnabled by rememberUpdatedState(enabled)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(
                    when {
                        pressed -> ButtonPressed
                        effEnabled -> color
                        else -> color.copy(alpha = 0.45f)
                    }
                )
                .pointerInput(minHoldMs) {
                    detectTapGestures(
                        onPress = {
                            if (!effEnabled) {
                                // disabled: swallow the hold, no haptic, no send
                                pressed = false
                                tryAwaitRelease()
                                return@detectTapGestures
                            }
                            pressed = true
                            onPress(true)
                            val downTime = System.currentTimeMillis()
                            try {
                                if (minHoldMs > 0) {
                                    // Enforce a minimum virtual hold so the HUD
                                    // registers a long-press even on a quick tap
                                    val earlyRelease =
                                        kotlinx.coroutines.withTimeoutOrNull(minHoldMs) {
                                            tryAwaitRelease()
                                        } == true
                                    if (earlyRelease) {
                                        val remaining = minHoldMs - (System.currentTimeMillis() - downTime)
                                        if (remaining > 0) kotlinx.coroutines.delay(remaining)
                                    } else {
                                        // Minimum hold reached, user still holding —
                                        // wait for the real release
                                        tryAwaitRelease()
                                    }
                                } else {
                                    tryAwaitRelease()
                                }
                            } finally {
                                pressed = false
                                onPress(false)
                            }
                        },
                        onTap = {
                            // Quick tap: send press+release pair
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White.copy(alpha = if (effEnabled) 1f else 0.5f),
            modifier = Modifier.size(32.dp)
        )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Dpad(onHaptic: (Boolean) -> Unit, sizeDp: Dp = 250.dp, enabled: Boolean = true) {
    // Long-press center = voice assistant (configurable trigger)
    var centerPressed by remember { mutableStateOf(false) }
    var centerLongPress by remember { mutableStateOf(false) }
    val effEnabled by rememberUpdatedState(enabled)
    val dpadAlpha = if (effEnabled) 1f else 0.45f

    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(DpadBg, Color(0xFF0D1025))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Up
        DpadArrow(
            size = sizeDp * 0.22f,
            icon = Icons.Default.KeyboardArrowUp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = sizeDp * 0.046f),
            onPress = { onHaptic(it); AppGraph.sendReflectiveKey(0x52, Protocol.Keys.DPAD_UP, it) },
            enabled = effEnabled
        )
        // Down
        DpadArrow(
            size = sizeDp * 0.22f,
            icon = Icons.Default.KeyboardArrowDown,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = sizeDp * 0.046f),
            onPress = { onHaptic(it); AppGraph.sendReflectiveKey(0x51, Protocol.Keys.DPAD_DOWN, it) },
            enabled = effEnabled
        )
        // Left
        DpadArrow(
            size = sizeDp * 0.22f,
            icon = Icons.Default.KeyboardArrowLeft,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = sizeDp * 0.046f),
            onPress = { onHaptic(it); AppGraph.sendReflectiveKey(0x50, Protocol.Keys.DPAD_LEFT, it) },
            enabled = effEnabled
        )
        // Right
        DpadArrow(
            size = sizeDp * 0.22f,
            icon = Icons.Default.KeyboardArrowRight,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = sizeDp * 0.046f),
            onPress = { onHaptic(it); AppGraph.sendReflectiveKey(0x4F, Protocol.Keys.DPAD_RIGHT, it) },
            enabled = effEnabled
        )

        // Center OK button (long-press → voice)
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(if (centerPressed) ButtonPressed else DpadCenter.copy(alpha = dpadAlpha))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            if (!effEnabled) {
                                tryAwaitRelease()
                                return@detectTapGestures
                            }
                            centerPressed = true
                            centerLongPress = false
                            onHaptic(true)
                            AppGraph.sendReflectiveKey(0x28, Protocol.Keys.ENTER, true)
                            val downTime = System.currentTimeMillis()
                            try {
                                while (true) {
                                    val released = kotlinx.coroutines.withTimeoutOrNull(50) {
                                        tryAwaitRelease()
                                    }
                                    if (released != null) break // released
                                    if (!centerLongPress &&
                                        System.currentTimeMillis() - downTime >= 500
                                    ) {
                                        centerLongPress = true
                                        // Release ENTER, trigger voice instead
                                        AppGraph.sendReflectiveKey(0x28, Protocol.Keys.ENTER, false)
                                        AppGraph.connection.sendKeyEvent(Protocol.Keys.HEADSETHOOK, true)
                                    }
                                }
                            } finally {
                                if (centerLongPress) {
                                    AppGraph.connection.sendKeyEvent(Protocol.Keys.HEADSETHOOK, false)
                                } else {
                                    AppGraph.sendReflectiveKey(0x28, Protocol.Keys.ENTER, false)
                                }
                                centerPressed = false
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "OK",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DpadArrow(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onPress: (Boolean) -> Unit,
    size: Dp = 56.dp,
    enabled: Boolean = true
) {
    var pressed by remember { mutableStateOf(false) }
    val effEnabled by rememberUpdatedState(enabled)

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    pressed -> ButtonPressed
                    effEnabled -> Color(0xFF0F3460)
                    else -> Color(0xFF0F3460).copy(alpha = 0.45f)
                }
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!effEnabled) {
                            tryAwaitRelease()
                            return@detectTapGestures
                        }
                        pressed = true
                        onPress(true)
                        try {
                            awaitRelease()
                        } finally {
                            pressed = false
                            onPress(false)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (effEnabled) 1f else 0.5f),
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun BottomBar(
    mediaPlaying: Boolean,
    showTpmsButton: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onExitAa: () -> Unit,
    onTpms: () -> Unit,
    tpmsActive: Boolean,
    tpmsSummary: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavBarButton(Icons.Default.Home, "Exit AA", onExitAa)
        NavBarButton(Icons.Default.SkipPrevious, "Prev", onPrev)
        NavBarButton(
            if (mediaPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            if (mediaPlaying) "Pause" else "Play",
            onPlayPause
        )
        NavBarButton(Icons.Default.SkipNext, "Next", onNext)
        if (showTpmsButton) {
            NavBarButton(Icons.Default.TireRepair, tpmsSummary, onTpms, highlight = tpmsActive)
        }
    }
}

@Composable
private fun NavBarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    highlight: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlight) Color(0xFF66BB6A) else Color(0xFFE0E0E0),
                modifier = Modifier.size(28.dp)
            )
        }
        Text(text = label, color = Color(0xFF9E9E9E), fontSize = 10.sp)
    }
}

/**
 * Modal sheet listing all macros with one-tap RUN and a small close button.
 * Shows the engine's running state and auto-closes when the last macro ends.
 */
@Composable
private fun MacroSheet(onClose: () -> Unit) {
    val macroList by AppGraph.macroDb.macroDao().observeAll().collectAsState(initial = emptyList())
    val runningId by AppGraph.macroEngine.runningId.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { }) // swallow taps
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xFF161616))
                .padding(16.dp)
        ) {
            // Header with small close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Macros",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF333333))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White,
                        modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(10.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 360.dp)
            ) {
                items(macroList.sortedBy { it.sortOrder }) { macro ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF232323))
                            .clickable(enabled = runningId == null) {
                                onClose()  // return to the main screen; a repeating toasts announces progress
                                AppGraph.macroEngine.execute(macro)
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = macro.name,
                                color = if (macro.id == runningId) Color(0xFF66BB6A) else Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${macro.steps.size} steps" +
                                    (if (macro.id == runningId) " · running…" else ""),
                                color = if (macro.id == runningId) Color(0xFF66BB6A) else Color(0xFF9E9E9E),
                                fontSize = 11.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Run",
                            tint = if (runningId == null) Color(0xFF66BB6A) else Color(0xFF555555),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                if (macroList.isEmpty()) {
                    item {
                        Text(
                            "No macros — create them in Settings → Macros",
                            color = Color(0xFF9E9E9E),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}
