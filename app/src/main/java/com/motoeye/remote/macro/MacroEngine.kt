package com.motoeye.remote.macro

import android.content.Context
import android.content.Intent
import android.util.Log
import com.motoeye.remote.AppGraph
import com.motoeye.remote.accessibility.AutoControlService
import com.motoeye.remote.ble.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Executes macro step sequences: BLE key events, waits, media controls,
 * phone app intents, accessibility key injection.
 *
 * On-HUD app launching (EUC World, TPMS on HUD) is done through BLE
 * key-event navigation macros since the HUD cannot launch apps directly.
 */
class MacroEngine(private val context: Context) {

    private val TAG = "MacroEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _runningId = MutableStateFlow<Long?>(null)
    val runningId: StateFlow<Long?> = _runningId.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _runningName = MutableStateFlow<String?>(null)
    val runningName: StateFlow<String?> = _runningName.asStateFlow()

    /** Minimum gap between macro runs. */
    private val debounceMs = 30_000L
    private var lastRunAt = 0L

    fun execute(macro: MacroEntity) {
        if (_runningId.value != null) {
            Log.w(TAG, "Macro already running")
            return
        }
        val now = System.currentTimeMillis()
        if (lastRunAt != 0L) {
            val since = now - lastRunAt
            if (since < debounceMs) {
                val wait = (debounceMs - since + 999) / 1000
                android.widget.Toast.makeText(
                    context, "Macro cooldown — ${wait}s", android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }
        }
        lastRunAt = now
        _runningId.value = macro.id
        _runningName.value = macro.name
        _progress.value = 0f

        scope.launch {
            var done = false
            val ticker = launch(kotlinx.coroutines.Dispatchers.Main) {
                // re-issue the toast so it stays visible until the macro completes
                while (coroutineContext.isActive && !done) {
                    android.widget.Toast.makeText(
                        context, "Running: ${_runningName.value}", android.widget.Toast.LENGTH_LONG
                    ).show()
                    delay(3000)
                }
            }
            try {
                val total = macro.steps.size.coerceAtLeast(1)
                macro.steps.forEachIndexed { index, step ->
                    runStep(step)
                    _progress.value = (index + 1).toFloat() / total
                }
                Log.i(TAG, "Macro '${macro.name}' complete")
            } catch (e: Exception) {
                Log.e(TAG, "Macro failed: ${e.message}")
            } finally {
                done = true
                _runningId.value = null
                _runningName.value = null
                _progress.value = 0f
                ticker.join()
            }
        }
    }

    private suspend fun runStep(step: MacroStep) {
        val keys = AppGraph.keySender
        // Android keycode -> HID usage for the keyboard path (Vidoar fallback
        // when the HID link isn't attached)
        when (step.type) {
            "key" -> {
                val code = step.value.toIntOrNull() ?: return
                if (AppGraph.phoneHid.isConnected()) {
                    AppGraph.phoneHid.sendUsage(hidUsageFor(code), 60L)
                } else {
                    AppGraph.connection.sendKeyClick(code)
                }
            }
            "keydown" -> step.value.toIntOrNull()?.let {
                if (AppGraph.phoneHid.isConnected()) {
                    AppGraph.phoneHid.sendKeyDown(hidUsageFor(it))
                } else {
                    AppGraph.connection.sendKeyEvent(it, true)
                }
            }
            "keyup" -> step.value.toIntOrNull()?.let {
                if (AppGraph.phoneHid.isConnected()) {
                    AppGraph.phoneHid.sendKeyUp(hidUsageFor(it))
                } else {
                    AppGraph.connection.sendKeyEvent(it, false)
                }
            }
            "wait" -> delay(step.value.toLongOrNull() ?: 100)
            "media" -> when (step.value) {
                "play" -> AppGraph.media.play()
                "pause" -> AppGraph.media.pause()
                "playpause" -> AppGraph.media.playPause()
                "next" -> AppGraph.media.next()
                "previous" -> AppGraph.media.previous()
                "resume" -> AppGraph.media.resumeWhereLeftOff()
            }
            "intent" -> launchApp(step.value)
            "aakey" -> when (step.value) {
                "home" -> AutoControlService.instance?.exitAndroidAuto()
                "back" -> AutoControlService.instance?.pressBack()
            }
        }
        if (step.delayMs > 0) delay(step.delayMs)
    }

    private fun hidUsageFor(keyCode: Int): Int = when (keyCode) {
        Protocol.Keys.BACK -> 0x29
        Protocol.Keys.ENTER -> 0x28
        Protocol.Keys.DPAD_LEFT -> 0x50
        Protocol.Keys.DPAD_RIGHT -> 0x4F
        Protocol.Keys.DPAD_DOWN -> 0x51
        Protocol.Keys.DPAD_UP -> 0x52
        else -> 0x52
    }

    private fun launchApp(target: String) {
        val parts = target.split(";")
        val pkg = parts.first()
        val activity = parts.firstOrNull { it.startsWith("activity:") }
            ?.removePrefix("activity:")

        try {
            val intent = if (activity != null) {
                Intent().setClassName(pkg, activity)
            } else {
                context.packageManager.getLaunchIntentForPackage(pkg)
            } ?: run {
                Log.w(TAG, "No launch intent for $pkg")
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $pkg: ${e.message}")
        }
    }

    companion object {
        /**
         * Seed builtin macros. HUD app navigation uses the key sequence:
         * HOME (launcher) -> navigate to app icon -> OK. Exact sequences
         * depend on the HUD launcher layout and are user-adjustable.
         */
         suspend fun seedBuiltins(dao: MacroDao, settings: com.motoeye.remote.data.SettingsRepository) {
            fun key(code: Int, delay: Long = 200) =
                MacroStep("key", code.toString(), delay)

            // helper: move right N times, down, etc. as proper step lists
            fun rights(n: Int, delay: Long = 200): List<MacroStep> =
                List(n) { key(Protocol.Keys.DPAD_RIGHT, delay) }
            fun downs(n: Int, delay: Long = 200): List<MacroStep> =
                List(n) { key(Protocol.Keys.DPAD_DOWN, delay) }

            // hold BACK as keydown/wait/keyup; every key carries its own delay after
            fun holdBack(holdMs: Long, afterMs: Long): List<MacroStep> = listOf(
                MacroStep("keydown", Protocol.Keys.BACK.toString()),
                MacroStep("wait", holdMs.toString()),
                MacroStep("keyup", Protocol.Keys.BACK.toString()),
                MacroStep("wait", afterMs.toString())
            )

            // 1) EUC World: hold BACK to main screen, 8 right, 1 down, OK
            val deleted = settings.deletedBuiltinNames()
            val eucSteps = holdBack(600, 400) + rights(8) + listOf(
                key(Protocol.Keys.DPAD_DOWN, 220),
                key(Protocol.Keys.ENTER, 500)
            )
            seedOrUpdate(dao, name = "EUC World", icon = "euc", sortOrder = 0, steps = eucSteps, deleted = deleted)

            // 2) Headset settings: hold BACK, 4 right, enter, 3 down, enter
            val hsSteps = holdBack(600, 400) + rights(4) + listOf(
                key(Protocol.Keys.ENTER, 400)
            ) + downs(3) + listOf(
                key(Protocol.Keys.ENTER, 500)
            )
            seedOrUpdate(dao, name = "Headset settings", icon = "hs", sortOrder = 1, steps = hsSteps, deleted = deleted)

            seedOrUpdate(dao, name = "Rear Camera", icon = "camera", sortOrder = 2,
                steps = listOf(key(Protocol.Keys.CAMERA, 60)), deleted = deleted)
            dao.getAll().firstOrNull { it.name == "Rear Camera" }?.let {
                if (!it.builtin) { /* keep user rows */ }
            }
        }

        /** Insert the seeded macro if missing; refresh steps if it changed.
         *  Macros the user explicitly deleted are never re-seeded. */
        private suspend fun seedOrUpdate(
            dao: MacroDao, name: String, icon: String, sortOrder: Int,
            steps: List<MacroStep>, deleted: Set<String>
        ) {
            if (name in deleted) return
            val existing = dao.getAll().firstOrNull { it.name == name }
            if (existing == null) {
                dao.insert(MacroEntity(name = name, icon = icon, builtin = true, sortOrder = sortOrder, steps = steps))
            } else if (existing.builtin && existing.steps != steps) {
                dao.update(existing.copy(steps = steps, icon = icon, sortOrder = sortOrder))
            }
        }
    }
}
