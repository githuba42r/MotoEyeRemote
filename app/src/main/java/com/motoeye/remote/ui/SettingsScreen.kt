package com.motoeye.remote.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.motoeye.remote.AppGraph
import com.motoeye.remote.macro.MacroEntity
import com.motoeye.remote.macro.MacroStep
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


            Spacer(Modifier.height(8.dp))
            SectionTitle("Display")

            ToggleRow(
                title = "Enable TPMS",
                subtitle = "Tire pressure display + direct sensor reading",
                checked = s?.showTpms ?: true,
                onCheckedChange = { scope.launch { settings.setShowTpms(it) } }
            )

            ToggleRow(
                title = "Show record button",
                subtitle = "Recording toggle on remote screen",
                checked = s?.showRecordButton ?: true,
                onCheckedChange = { scope.launch { settings.setShowRecordButton(it) } }
            )



            Spacer(Modifier.height(8.dp))
            // ---- Macros: run, add, edit, delete ----
            val macroList = AppGraph.macroDb.macroDao().observeAll().collectAsState(initial = emptyList())
            var editing by remember { mutableStateOf<MacroEntity?>(null) }
            var showEditor by remember { mutableStateOf(false) }
            var confirmDelete by remember { mutableStateOf<MacroEntity?>(null) }
            val runningId by AppGraph.macroEngine.runningId.collectAsState()
            val runScope = rememberCoroutineScope()

            SectionTitle("Macros")
            macroList.value.sortedBy { it.sortOrder }.forEach { macro ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E1E1E))
                        .clickable { editing = macro; showEditor = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(macro.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = "${macro.steps.size} steps" + (if (macro.id == runningId) " · running…" else ""),
                            color = if (macro.id == runningId) Color(0xFF66BB6A) else Color(0xFF9E9E9E),
                            fontSize = 11.sp
                        )
                    }
                    Text("RUN", color = Color(0xFF66BB6A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { if (runningId == null) AppGraph.macroEngine.execute(macro) }
                            .padding(horizontal = 10.dp, vertical = 8.dp))
                    Text("EDIT", color = Color(0xFF42A5F5), fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { editing = macro; showEditor = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp))
                    Text("DEL", color = Color(0xFFF44336), fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { confirmDelete = macro }
                            .padding(horizontal = 10.dp, vertical = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
            androidx.compose.material3.OutlinedButton(onClick = { editing = null; showEditor = true }) {
                Text("+ New macro", color = Color(0xFF2196F3))
            }
            Spacer(Modifier.height(8.dp))

            confirmDelete?.let { victim ->
                androidx.compose.ui.window.Dialog(onDismissRequest = { confirmDelete = null }) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1D1D1D))
                            .padding(16.dp)
                    ) {
                        Text("Delete macro", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Delete \"${victim.name}\"?",
                            color = Color(0xFF9E9E9E), fontSize = 14.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text("Cancel", color = Color(0xFF9E9E9E), fontSize = 15.sp,
                                modifier = Modifier
                                    .clickable { confirmDelete = null }
                                    .padding(8.dp))
                            Spacer(Modifier.width(16.dp))
                            Text("Delete", color = Color(0xFFF44336), fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable {
                                        confirmDelete = null
                                        runScope.launch { AppGraph.macroDb.macroDao().delete(victim.id) }
                                    }
                                    .padding(8.dp))
                        }
                    }
                }
            }

            if (showEditor) {
                MacroEditorDialog(
                    initial = editing,
                    onDismiss = { showEditor = false },
                    onSaved = { m ->
                        showEditor = false
                        runScope.launch {
                            if (m.id == 0L) AppGraph.macroDb.macroDao().insert(m)
                            else AppGraph.macroDb.macroDao().update(m)
                        }
                    }
                )
            }

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

/** Human labels for the key codes commonly used in HUD macros. */
private fun keyName(code: Int): String = when (code) {
    19 -> "Left↑ Up"                         // dpad up
    20 -> "Down"                             // dpad down
    21 -> "Left"                             // dpad left
    22 -> "Right"                            // dpad right
    66 -> "OK / Enter"
    4 -> "Back"
    132 -> "DVR key"
    134 -> "Record"
    131 -> "Voice"
    85 -> "Play/Pause"
    87 -> "Next"
    88 -> "Prev"
    126 -> "Play"
    127 -> "Pause"
    24 -> "Volume Up"
    25 -> "Volume Down"
    else -> "Key $code"
}

/** One flippable row in the key picker. */
private data class KeyChoice(val code: Int)

/** Verb action kinds the macro editor supports. */
private enum class MacroKind { KEY, WAIT }

/**
 * Human-friendly macro editor: a vertical list of steps described in words
 * (Left / Right / OK / hold Back ...), a "+ Add button" button that opens a
 * key picker with a delay field (default 200 ms), long-press a step row to
 * delete it. Throws the step grammar only at persistence time.
 */
@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
@Composable
private fun MacroEditorDialog(
    initial: MacroEntity?,
    onDismiss: () -> Unit,
    onSaved: (MacroEntity) -> Unit
) {
    data class Row(
        val kind: MacroKind,
        val keyCode: Int,     // for KEY
        val keyDown: Boolean, // KEY true=click/press, false=release
        val waitMs: Long,     // WAIT value
        val delayAfter: Long  // applies to KEY rows too
    )

    // seed rows from the stored grammar
    fun rowsOf(m: MacroEntity?): MutableList<Row> {
        val out = mutableListOf<Row>()
        fun consume(steps: List<MacroStep>) {
            var i = 0
            while (i < steps.size) {
                val st = steps[i]
                when (st.type) {
                    "keydown" -> {
                        // look ahead for a matching keyup
                        var j = i + 1
                        while (j < steps.size) {
                            val f = steps[j]
                            if (f.type == "keyup" && f.value == st.value) break
                            if (f.type == "keydown" || f.type == "keyup") break
                            j++
                        }
                        if (j > i + 1 && steps[j].type == "keyup" && steps[j].value == st.value) {
                            // held key: keydown -> wait -> keyup, shown as one HOLD row
                            var w: Long = 0
                            for (k in i + 1 until j) {
                                if (steps[k].type == "wait") w += (steps[k].value.toLongOrNull() ?: 0)
                            }
                            // trailing waits after release become this row's delay
                            var d: Long = 0
                            var k2 = j + 1
                            while (k2 < steps.size && steps[k2].type == "wait") {
                                d += (steps[k2].value.toLongOrNull() ?: 0)
                                k2++
                            }
                            out.add(Row(MacroKind.KEY, st.value.toIntOrNull() ?: 0, true, w, d))
                            i = k2
                            continue
                        }
                        out.add(Row(MacroKind.KEY, st.value.toIntOrNull() ?: 0, true, 0, 0))
                        i++
                    }
                    "keyup" -> {
                        out.add(Row(MacroKind.KEY, st.value.toIntOrNull() ?: 0, false, 0, 0))
                        i++
                    }
                    "wait" -> {
                        out.add(Row(MacroKind.WAIT, 0, false, st.value.toLongOrNull() ?: 100, 0))
                        i++
                    }
                    "key" -> {
                        // bare key + trailing wait as delay
                        var d = st.delayMs
                        var j = i + 1
                        while (j < steps.size && steps[j].type == "wait") {
                            d += steps[j].value.toLongOrNull() ?: 0
                            j++
                        }
                        out.add(Row(MacroKind.KEY, st.value.toIntOrNull() ?: 0, true, 0, d))
                        i = if (j > i + 1) j else i + 1
                    }
                    else -> i++     // media / aakey / intent not offered in picker yet
                }
            }
        }
        if (m != null) consume(m.steps)
        return out
    }

    val rows = remember { mutableStateListOf<Row>().apply { addAll(rowsOf(initial)) } }
    var name by remember { mutableStateOf(initial?.name ?: "") }

    var showPicker by remember { mutableStateOf(false) }
    var insertIndex by remember { mutableStateOf(0) }
    var editIndex by remember { mutableStateOf(-1) }
    var pickerKey by remember { mutableStateOf(19) }
    var pickerWaitText by remember { mutableStateOf("200") }
    var pickerHoldChecked by remember { mutableStateOf(false) }
    var pickerHoldText by remember { mutableStateOf("600") }
    var pickerDropdownOpen by remember { mutableStateOf(false) }

    fun serialize(entityId: Long): MacroEntity {
        val steps = mutableListOf<MacroStep>()
        rows.forEach { r ->
            when (r.kind) {
                MacroKind.KEY -> {
                    if (r.keyDown) {
                        if (r.waitMs > 0) {
                            // press, hold, release = keydown/wait/keyup
                            steps.add(MacroStep("keydown", r.keyCode.toString()))
                            steps.add(MacroStep("wait", r.waitMs.toString()))
                            steps.add(MacroStep("keyup", r.keyCode.toString()))
                            if (r.delayAfter > 0) steps.add(MacroStep("wait", r.delayAfter.toString()))
                        } else {
                            steps.add(MacroStep("key", r.keyCode.toString(), r.delayAfter))
                        }
                    } else {
                        steps.add(MacroStep("keyup", r.keyCode.toString()))
                    }
                }
                MacroKind.WAIT -> steps.add(MacroStep("wait", r.waitMs.toString()))
            }
        }
        return MacroEntity(
            id = initial?.id ?: 0L,
            name = name.trim(),
            icon = initial?.icon ?: "custom",
            steps = steps,
            builtin = false,
            sortOrder = initial?.sortOrder ?: 100
        )
    }

    val pickerChoices = remember {
        listOf(
            KeyChoice(19), KeyChoice(20), KeyChoice(21), KeyChoice(22),
            KeyChoice(66), KeyChoice(4),
            KeyChoice(132), KeyChoice(134), KeyChoice(131),
            KeyChoice(85), KeyChoice(87), KeyChoice(88),
            KeyChoice(24), KeyChoice(25)
        )
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF161616))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Macro", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text("Save",
                    color = Color(0xFF66BB6A),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        if (name.isNotBlank() && rows.isNotEmpty()) onSaved(serialize(initial?.id ?: 0L))
                    })
                Spacer(Modifier.width(12.dp))
                Text("Cancel", color = Color(0xFF9E9E9E), fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onDismiss))
            }
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            // step list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(rows) { idx, r ->
                    val label = when (r.kind) {
                        MacroKind.KEY -> when {
                            r.keyDown && r.waitMs > 0 ->
                                if (r.delayAfter > 0)
                                    "HOLD ${keyName(r.keyCode).uppercase()} ${(r.waitMs)}ms · wait ${r.delayAfter}ms"
                                else "HOLD ${keyName(r.keyCode).uppercase()} ${(r.waitMs)}ms"
                            r.keyDown -> if (r.delayAfter > 0)
                                "${keyName(r.keyCode).uppercase()} · wait ${r.delayAfter}ms"
                            else keyName(r.keyCode).uppercase()
                            else -> "RELEASE ${keyName(r.keyCode).uppercase()}"
                        }
                        MacroKind.WAIT -> "WAIT ${(r.waitMs)}ms"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF232323))
                            .clickable {
                                editIndex = idx
                                pickerKey = r.keyCode
                                val isHold = r.kind == MacroKind.KEY && r.keyDown && r.waitMs > 0
                                pickerHoldChecked = isHold
                                pickerHoldText = if (isHold) r.waitMs.toString() else "600"
                                pickerWaitText = when {
                                    r.kind == MacroKind.WAIT -> r.waitMs.toString()
                                    r.delayAfter > 0 -> r.delayAfter.toString()
                                    else -> "200"
                                }
                                showPicker = true
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("+ BELOW", color = Color(0xFF42A5F5), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                insertIndex = idx + 1
                                editIndex = -1
                                pickerHoldChecked = false
                                pickerWaitText = "200"
                                showPicker = true
                            })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            androidx.compose.material3.OutlinedButton(
                onClick = {
                    insertIndex = rows.size
                    editIndex = -1
                    pickerHoldChecked = false
                    pickerWaitText = "200"
                    showPicker = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("+ Add button", color = Color(0xFF42A5F5)) }

            // key picker dialog: dropdown for the button + wait slider/text field;
            // doubles as edit (Save/Delete) when opened from a step row
            if (showPicker) {
                val waitVal = pickerWaitText.toLongOrNull() ?: 200L
                val holdVal = pickerHoldText.toLongOrNull() ?: 600L
                val isEdit = editIndex >= 0
                androidx.compose.ui.window.Dialog(onDismissRequest = { showPicker = false }) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1D1D1D))
                            .padding(14.dp)
                    ) {
                        Text(if (isEdit) "Edit step" else "Add button",
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.ExposedDropdownMenuBox(
                            expanded = pickerDropdownOpen,
                            onExpandedChange = { pickerDropdownOpen = it }
                        ) {
                            androidx.compose.material3.OutlinedTextField(
                                value = keyName(pickerKey),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Button") },
                                trailingIcon = {
                                    androidx.compose.material3.Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = null
                                    )
                                },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = pickerDropdownOpen,
                                onDismissRequest = { pickerDropdownOpen = false }
                            ) {
                                pickerChoices.forEach { c ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(keyName(c.code), color = Color.White) },
                                        onClick = {
                                            pickerKey = c.code
                                            pickerDropdownOpen = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = pickerHoldChecked,
                                onCheckedChange = { pickerHoldChecked = it }
                            )
                            Text("Hold", color = Color.White, fontSize = 14.sp)
                        }
                        if (pickerHoldChecked) {
                            val holdVal = pickerHoldText.toLongOrNull() ?: 600L
                            Text("Hold duration", color = Color(0xFF9E9E9E), fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Slider(
                                    value = holdVal.coerceIn(200L, 1500L).toFloat(),
                                    onValueChange = { pickerHoldText = (it.toInt() / 10 * 10).toString() },
                                    valueRange = 200f..1500f,
                                    steps = 129,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(10.dp))
                                androidx.compose.material3.OutlinedTextField(
                                    value = pickerHoldText,
                                    onValueChange = { v -> pickerHoldText = v.filter { it.isDigit() }.take(4) },
                                    suffix = { Text("ms", color = Color(0xFF9E9E9E), fontSize = 12.sp) },
                                    singleLine = true,
                                    modifier = Modifier.width(110.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(if (pickerHoldChecked) 10.dp else 14.dp))
                        Text("Wait after press", color = Color(0xFF9E9E9E), fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Slider(
                                value = waitVal.coerceIn(200L, 1500L).toFloat(),
                                onValueChange = { pickerWaitText = (it.toInt() / 10 * 10).toString() },
                                valueRange = 200f..1500f,
                                steps = 129,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = pickerWaitText,
                                onValueChange = { v -> pickerWaitText = v.filter { it.isDigit() }.take(4) },
                                suffix = { Text("ms", color = Color(0xFF9E9E9E), fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.width(110.dp)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (isEdit) {
                                Text("DELETE", color = Color(0xFFF44336), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable {
                                            rows.removeAt(editIndex)
                                            showPicker = false
                                            editIndex = -1
                                        }
                                        .padding(8.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            Text("Cancel", color = Color(0xFF9E9E9E), fontSize = 15.sp,
                                modifier = Modifier
                                    .clickable { showPicker = false; editIndex = -1 }
                                    .padding(8.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(if (isEdit) "Save" else "Add",
                                color = Color(0xFF66BB6A), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable {
                                        if (isEdit) {
                                            val old = rows[editIndex]
                                            rows[editIndex] = when {
                                                old.kind == MacroKind.WAIT ->
                                                    Row(MacroKind.WAIT, 0, false, if (pickerHoldChecked) holdVal else waitVal, 0)
                                                else -> Row(
                                                    MacroKind.KEY, pickerKey, true,
                                                    if (pickerHoldChecked) holdVal else 0,
                                                    waitVal
                                                )
                                            }
                                        } else {
                                            rows.add(
                                                insertIndex.coerceIn(0, rows.size),
                                                Row(MacroKind.KEY, pickerKey, true,
                                                    if (pickerHoldChecked) holdVal else 0, waitVal)
                                            )
                                        }
                                        showPicker = false
                                        editIndex = -1
                                    }
                                    .padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}
