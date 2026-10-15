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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
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

    // HID channel readiness (camera/record live only when attached)
    val hidReady by AppGraph.phoneHid.isAttached.collectAsState()

    // Settings-driven behavior
    val settings = remember { com.motoeye.remote.data.SettingsRepository(context) }
    var vibrationEnabled by remember { mutableStateOf(true) }
    var showTpmsButton by remember { mutableStateOf(true) }
    var showRecordButton by remember { mutableStateOf(true) }
    var recordAtTop by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        settings.settings.collect {
            vibrationEnabled = it.vibrationEnabled
            showTpmsButton = it.showTpms
            showRecordButton = it.showRecordButton
            recordAtTop = it.recordAtTop
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
    val haptic = LocalHapticFeedback.current
    val pressHaptic: (Boolean) -> Unit = { pressed ->
        if (pressed && vibrationEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
            showRecordChip = showRecordButton && recordAtTop,
            isRecording = isRecording,
            onToggleRecord = toggleRecord,
            onSettings = onOpenSettings,
            onDevice = onOpenDeviceSelect
        )

        // Main control area — layout adapts to orientation
        val orientation = LocalConfiguration.current.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LeftButtonColumn(
                    onHaptic = pressHaptic,
                    buttonSize = 60.dp,
                    spacing = 16.dp,
                    showRecord = showRecordButton && !recordAtTop,
                    isRecording = isRecording,
                    onToggleRecord = toggleRecord,
                    hidReady = hidReady
                )
                Dpad(onHaptic = pressHaptic)
                RightButtonColumn(
                    onHaptic = pressHaptic,
                    buttonSize = 60.dp,
                    spacing = 16.dp
                )
            }
        } else {
            // Portrait: D-pad on top, buttons in two rows below
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Dpad(onHaptic = pressHaptic)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TapButton(
                        label = "CAM",
                        icon = Icons.Default.Camera,
                        color = CameraBlue,
                        onClick = {
                            pressHaptic(true)
                            AppGraph.sendCameraTrigger()
                        },
                        enabled = hidReady
                    )
                    if (showRecordButton && !recordAtTop) {
                        RecordButton(
                            isRecording = isRecording,
                            onToggle = toggleRecord,
                            onHaptic = pressHaptic,
                            label = if (isRecording) "STOP" else "REC",
                            enabled = hidReady
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    HoldButton(
                        label = "BACK",
                        icon = Icons.Default.ArrowBack,
                        color = ButtonBg,
                        onPress = { pressHaptic(it); AppGraph.sendReflectiveKey(0x29, Protocol.Keys.BACK, it) }
                    )
                    HoldButton(
                        label = "VOL+",
                        icon = Icons.Default.Add,
                        color = ButtonBg,
                        // Physical remote: long-press UP shows HUD volume overlay + auto-repeat
                        onPress = { pressHaptic(it); AppGraph.sendReflectiveKey(0x52, Protocol.Keys.DPAD_UP, it) },
                        minHoldMs = 2000L
                    )
                    HoldButton(
                        label = "VOL−",
                        icon = Icons.Default.Remove,
                        color = ButtonBg,
                        onPress = { pressHaptic(it); AppGraph.sendReflectiveKey(0x51, Protocol.Keys.DPAD_DOWN, it) },
                        minHoldMs = 2000L
                    )
                }
            }
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
            tpmsActive = tpmsState.isActive
        )
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
    size: Dp = 76.dp,
    enabled: Boolean = true
) {
    var pressed by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (pressed) ButtonPressed else color)
                .alpha(if (enabled) 1f else 0.35f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            try{ awaitRelease() } finally { pressed = false }
                        },
                        onTap = { if (enabled) onClick() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(size * 0.45f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (enabled) label else "HID…",
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
    size: Dp = 76.dp,
    enabled: Boolean = true
) {
    var pressed by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .alpha(if (enabled) 1f else 0.35f)
                .background(
                    when {
                        pressed -> ButtonPressed
                        isRecording -> RecordRed
                        else -> RecordRed.copy(alpha = 0.35f)
                    }
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            onHaptic(true)
                            try {
                                awaitRelease()
                            } finally {
                                pressed = false
                            }
                        },
                        onTap = { if (enabled) onToggle() }
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
            text = if (enabled) label else "HID…",
            color = if (isRecording) RecordRed else Color(0xFF9E9E9E),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RecordChip(
    isRecording: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (isRecording) RecordRed else RecordRed.copy(alpha = 0.35f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (isRecording) Color.White else RecordRed)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (isRecording) "REC" else "OFF",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StatusBar(
    connectionState: ConnectionState,
    tpmsActive: Boolean,
    showRecordChip: Boolean = false,
    isRecording: Boolean = false,
    onToggleRecord: () -> Unit = {},
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
            if (showRecordChip) {
                RecordChip(isRecording = isRecording, onToggle = onToggleRecord)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFFE0E0E0))
            }
        }
    }
}

@Composable
private fun LeftButtonColumn(
    onHaptic: (Boolean) -> Unit,
    buttonSize: Dp = 72.dp,
    spacing: Dp = 16.dp,
    showRecord: Boolean = true,
    isRecording: Boolean = false,
    onToggleRecord: () -> Unit = {},
    hidReady: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        TapButton(
            label = "CAM",
            icon = Icons.Default.Camera,
            color = CameraBlue,
            onClick = { AppGraph.sendCameraTrigger() },
            size = buttonSize,
            enabled = hidReady
        )
        if (showRecord) {
            RecordButton(
                isRecording = isRecording,
                onToggle = onToggleRecord,
                onHaptic = onHaptic,
                label = if (isRecording) "STOP" else "REC",
                size = buttonSize,
                enabled = hidReady
            )
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
            icon = Icons.Default.ArrowBack,
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
    size: Dp = 76.dp,
    minHoldMs: Long = 0
) {
    var pressed by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (pressed) ButtonPressed else color)
                .pointerInput(minHoldMs) {
                    detectTapGestures(
                        onPress = {
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
                tint = Color.White,
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
private fun Dpad(onHaptic: (Boolean) -> Unit) {
    // Long-press center = voice assistant (configurable trigger)
    var centerPressed by remember { mutableStateOf(false) }
    var centerLongPress by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(260.dp)
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
            icon = Icons.Default.KeyboardArrowUp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            onPress = { onHaptic(it); AppGraph.sendReflectiveKey(0x52, Protocol.Keys.DPAD_UP, it) }
        )
        // Down
        DpadArrow(
            icon = Icons.Default.KeyboardArrowDown,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
            onPress = { onHaptic(it); AppGraph.sendReflectiveKey(0x51, Protocol.Keys.DPAD_DOWN, it) }
        )
        // Left
        DpadArrow(
            icon = Icons.Default.KeyboardArrowLeft,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp),
            onPress = { onHaptic(it); AppGraph.sendReflectiveKey(0x50, Protocol.Keys.DPAD_LEFT, it) }
        )
        // Right
        DpadArrow(
            icon = Icons.Default.KeyboardArrowRight,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            onPress = { onHaptic(it); AppGraph.sendReflectiveKey(0x4F, Protocol.Keys.DPAD_RIGHT, it) }
        )

        // Center OK button (long-press → voice)
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(if (centerPressed) ButtonPressed else DpadCenter)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
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
    onPress: (Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) ButtonPressed else Color(0xFF0F3460))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
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
            tint = Color.White,
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
    tpmsActive: Boolean
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
            NavBarButton(Icons.Default.TireRepair, "TPMS", onTpms, highlight = tpmsActive)
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
