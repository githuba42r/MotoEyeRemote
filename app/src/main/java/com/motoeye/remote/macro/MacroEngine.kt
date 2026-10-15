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

    fun execute(macro: MacroEntity) {
        if (_runningId.value != null) {
            Log.w(TAG, "Macro already running")
            return
        }
        _runningId.value = macro.id
        _progress.value = 0f

        scope.launch {
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
                _runningId.value = null
                _progress.value = 0f
            }
        }
    }

    private suspend fun runStep(step: MacroStep) {
        val keys = AppGraph.keySender
        when (step.type) {
            "key" -> {
                val code = step.value.toIntOrNull() ?: return
                AppGraph.connection.sendKeyClick(code)
            }
            "keydown" -> step.value.toIntOrNull()?.let {
                AppGraph.connection.sendKeyEvent(it, true)
            }
            "keyup" -> step.value.toIntOrNull()?.let {
                AppGraph.connection.sendKeyEvent(it, false)
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
        suspend fun seedBuiltins(dao: MacroDao) {
            if (dao.getAll().any { it.builtin }) return

            fun key(code: Int, delay: Long = 150) =
                MacroStep("key", code.toString(), delay)

            dao.insert(MacroEntity(
                name = "EUC World",
                icon = "euc",
                builtin = true,
                sortOrder = 0,
                steps = listOf(
                    MacroStep("wait", "500"),
                    key(Protocol.Keys.BACK),       // back to launcher
                    MacroStep("wait", "300"),
                    key(Protocol.Keys.DPAD_DOWN),
                    key(Protocol.Keys.DPAD_RIGHT),
                    key(Protocol.Keys.ENTER, 500)  // open highlighted app
                )
            ))

            dao.insert(MacroEntity(
                name = "TPMS on HUD",
                icon = "tpms",
                builtin = true,
                sortOrder = 1,
                steps = listOf(
                    key(Protocol.Keys.BACK),
                    MacroStep("wait", "300"),
                    key(Protocol.Keys.DPAD_LEFT),
                    key(Protocol.Keys.ENTER, 400),
                    MacroStep("key", Protocol.Keys.RECORD.toString()) // TPMS key
                )
            ))

            dao.insert(MacroEntity(
                name = "Rear Camera",
                icon = "camera",
                builtin = true,
                sortOrder = 2,
                steps = listOf(key(Protocol.Keys.CAMERA))
            ))

            dao.insert(MacroEntity(
                name = "Start Recording",
                icon = "record",
                builtin = true,
                sortOrder = 3,
                steps = listOf(key(Protocol.Keys.RECORD))
            ))

            dao.insert(MacroEntity(
                name = "Exit Android Auto",
                icon = "aa",
                builtin = true,
                sortOrder = 4,
                steps = listOf(MacroStep("aakey", "home"))
            ))

            dao.insert(MacroEntity(
                name = "Resume Music",
                icon = "music",
                builtin = true,
                sortOrder = 5,
                steps = listOf(MacroStep("media", "resume"))
            ))

            dao.insert(MacroEntity(
                name = "Pause Music",
                icon = "music",
                builtin = true,
                sortOrder = 6,
                steps = listOf(MacroStep("media", "pause"))
            ))

            dao.insert(MacroEntity(
                name = "Next Track",
                icon = "music",
                builtin = true,
                sortOrder = 7,
                steps = listOf(MacroStep("media", "next"))
            ))
        }
    }
}
