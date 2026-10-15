package com.motoeye.remote.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoeye.remote.accessibility.AutoControlService
import com.motoeye.remote.data.SettingsRepository
import com.motoeye.remote.media.MediaSessionListener
import com.motoeye.remote.voice.VoiceShortcuts
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val appSettings by settings.settings.collectAsState(
        initial = null
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TopAppBar(
            title = { Text("Settings", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black,
                titleContentColor = Color.White
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            val s = appSettings

            SectionTitle("Connection")

            ToggleRow(
                title = "Auto reconnect",
                subtitle = "Reconnect to the HUD automatically",
                checked = s?.autoReconnect ?: true,
                onCheckedChange = { scope.launch { settings.setAutoReconnect(it) } }
            )

            ToggleRow(
                title = "Keep awake (wake lock)",
                subtitle = "Prevent sleep while connected",
                checked = s?.wakeLockEnabled ?: true,
                onCheckedChange = { scope.launch { settings.setWakeLock(it) } }
            )

            Spacer(Modifier.height(8.dp))
            SectionTitle("Controls")

            ToggleRow(
                title = "Long-press OK for voice",
                subtitle = "Hold center button ≥500ms to trigger Assistant",
                checked = s?.longPressVoice ?: true,
                onCheckedChange = { scope.launch { settings.setLongPressVoice(it) } }
            )

            ToggleRow(
                title = "Haptic feedback",
                subtitle = "Vibrate on button press",
                checked = s?.vibrationEnabled ?: true,
                onCheckedChange = { scope.launch { settings.setVibration(it) } }
            )

            ToggleRow(
                title = "Legacy record keycode",
                subtitle = "Use keycode 133 instead of 134 (M3/M3S firmware)",
                checked = (s?.recordKeyCode ?: 134) == 133,
                onCheckedChange = {
                    scope.launch { settings.setRecordKeyCode(if (it) 133 else 134) }
                }
            )

            Spacer(Modifier.height(8.dp))
            SectionTitle("Display")

            ToggleRow(
                title = "Show TPMS button",
                subtitle = "Tire pressure overlay on remote screen",
                checked = s?.showTpms ?: true,
                onCheckedChange = { scope.launch { settings.setShowTpms(it) } }
            )

            ToggleRow(
                title = "Show record button",
                subtitle = "Recording toggle on remote screen",
                checked = s?.showRecordButton ?: true,
                onCheckedChange = { scope.launch { settings.setShowRecordButton(it) } }
            )

            ToggleRow(
                title = "Record button at top",
                subtitle = "Rarely used — tucks it into the status bar",
                checked = s?.recordAtTop ?: false,
                onCheckedChange = { scope.launch { settings.setRecordAtTop(it) } }
            )

            ChoiceRow(
                title = "Camera button action",
                subtitle = "F2 keyboard = the physical remote's CAM button",
                options = listOf(
                    "f2hid" to "F2 keyboard",
                    "cam27" to "Camera 27",
                    "camkey" to "Camera key",
                    "mirror" to "Rear camera",
                    "hid" to "DVR key"
                ),
                selected = s?.cameraTrigger ?: "f2hid",
                onSelect = { scope.launch { settings.setCameraTrigger(it) } }
            )

            Spacer(Modifier.height(8.dp))
            SectionTitle("Permissions & Services")

            LinkRow(
                title = "Android Auto control",
                subtitle = if (AutoControlService.isEnabled) "Enabled" else "Enable accessibility service",
                accent = if (AutoControlService.isEnabled) Color(0xFF4CAF50) else Color(0xFFFF9800),
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            )

            LinkRow(
                title = "Media controls",
                subtitle = if (isNotificationAccessEnabled(context)) "Enabled" else "Grant notification access",
                accent = if (isNotificationAccessEnabled(context)) Color(0xFF4CAF50) else Color(0xFFFF9800),
                onClick = {
                    context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            )

            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val batteryExempt = remember {
                runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
                    .getOrDefault(false)
            }

            LinkRow(
                title = "Battery optimization",
                subtitle = if (batteryExempt) "Exempt — background reconnect stays alive"
                           else "Allow \"ignore battery optimizations\" for reliable reconnect",
                accent = if (batteryExempt) Color(0xFF4CAF50) else Color(0xFFFF9800),
                onClick = {
                    if (!batteryExempt) {
                        // Direct system dialog (requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            // Fallback: generic optimization list
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    } else {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
            )

            LinkRow(
                title = "Voice commands",
                subtitle = "Manage Assistant shortcuts",
                accent = Color(0xFFAB47BC),
                onClick = { VoiceShortcuts.registerAll(context) }
            )

            Spacer(Modifier.height(8.dp))
            SectionTitle("Advanced")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(16.dp)
            ) {
                Text(
                    text = "Camera channel startup delay",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "How long after the Bluetooth link is up the app waits before attaching the \"keyboard\" channel that powers the CAM and REC buttons. Lower = camera works sooner after connecting; higher = more stable link. Default 5s.",
                    color = Color(0xFF9E9E9E),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${s?.hidAttachDelaySeconds ?: 5}s",
                        color = Color(0xFF2196F3),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    androidx.compose.material3.Slider(
                        value = (s?.hidAttachDelaySeconds ?: 5).toFloat(),
                        onValueChange = { v -> scope.launch { settings.setHidAttachDelaySeconds(v.toInt()) } },
                        valueRange = 0f..30f,
                        steps = 29
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "HUD-attached status indicators turn green when ready",
                    color = Color(0xFF616161),
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            SectionTitle("About")

            val appVersion = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "??" }
            }

            Text(
                text = "MotoEye Remote v$appVersion\nProtocol reverse-engineered from MotoEye APK v1.0.2604141705",
                color = Color(0xFF757575),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun isNotificationAccessEnabled(context: Context): Boolean {
    val cn = ComponentName(context, MediaSessionListener::class.java)
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return flat.contains(cn.flattenToString())
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = Color(0xFF2196F3),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, color = Color(0xFF9E9E9E), fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .padding(16.dp)
    ) {
        Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(text = subtitle, color = Color(0xFF9E9E9E), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Text(
                    text = if (isSelected) "● $label" else "○ $label",
                    color = if (isSelected) Color(0xFF2196F3) else Color(0xFF9E9E9E),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isSelected) Color(0x332196F3) else Color.Transparent)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun LinkRow(
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, color = accent, fontSize = 12.sp)
        }
        Text(text = "›", color = Color(0xFF757575), fontSize = 20.sp)
    }
    Spacer(Modifier.height(8.dp))
}
