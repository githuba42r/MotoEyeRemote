package com.motoeye.remote.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.motoeye.remote.AppGraph
import com.motoeye.remote.ble.Protocol
import com.motoeye.remote.accessibility.AutoControlService
import com.motoeye.remote.media.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives Google Assistant shortcut voice commands.
 *
 * Shortcuts are registered by the app (see VoiceShortcuts.kt) so the user
 * can say "OK Google, MotoEye camera" etc. Each shortcut delivers a
 * command string here which is mapped to remote actions.
 *
 * Supported commands:
 *   camera      -> toggle rear camera
 *   record      -> toggle recording
 *   up/down/left/right/ok/back -> D-pad
 *   volume up / volume down
 *   voice       -> voice assistant trigger (headset hook)
 *   play/pause/next/previous -> media
 *   exit aa     -> exit Android Auto (accessibility)
 *   connect aa  -> launch Android Auto
 *   euc world / tpms -> launch apps on HUD via BLE nav macro
 *   resume music -> resume where left off
 */
class RemoteActionReceiver : BroadcastReceiver() {

    private val TAG = "RemoteActionReceiver"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val command = intent.getStringExtra(EXTRA_COMMAND)?.lowercase()?.trim() ?: return
        Log.i(TAG, "Voice command: $command")

        AppGraph.init(context)
        appContext = context.applicationContext

        scope.launch {
            try {
                execute(command)
            } catch (e: Exception) {
                Log.e(TAG, "Command execution failed: ${e.message}")
            }
        }
    }

    private fun execute(command: String) {
        val keys = AppGraph.keySender
        val media = AppGraph.media

        when {
            command.startsWith("raw:") || command.startsWith("key:") ||
                command.startsWith("keyhold:") || command == "hidprobe" ||
                command.startsWith("hidk:") ->
                AppGraph.executeDebugCommand(command)
            command == "camera" -> AppGraph.sendCameraTrigger()
            command == "record" -> keys.click(Protocol.Keys.RECORD)
            command == "up" -> keys.click(Protocol.Keys.DPAD_UP)
            command == "down" -> keys.click(Protocol.Keys.DPAD_DOWN)
            command == "left" -> keys.click(Protocol.Keys.DPAD_LEFT)
            command == "right" -> keys.click(Protocol.Keys.DPAD_RIGHT)
            command == "ok" || command == "select" -> keys.click(Protocol.Keys.ENTER)
            command == "back" -> keys.click(Protocol.Keys.BACK)
            command == "volume up" || command == "louder" -> media.volumeUp()
            command == "volume down" || command == "quieter" -> media.volumeDown()
            command == "voice" || command == "assistant" -> keys.triggerVoiceAssistant()
            command == "play" || command == "play music" -> media.play()
            command == "pause" || command == "pause music" -> media.pause()
            command == "play pause" || command == "music" -> media.playPause()
            command == "resume music" || command == "resume" -> media.resumeWhereLeftOff()
            command == "next" || command == "next track" -> media.next()
            command == "previous" || command == "previous track" -> media.previous()
            command == "exit aa" || command == "exit android auto" -> {
                if (!AutoControlService.exitAndroidAutoBestEffort()) {
                    Log.w(TAG, "Accessibility service not enabled; cannot exit AA")
                }
            }
            command == "connect aa" || command == "android auto" -> {
                // Launch Android Auto app on phone
                val ctx = appContext ?: return
                val launch = ctx.packageManager.getLaunchIntentForPackage("com.google.android.projection.gearhead")
                if (launch != null) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch ?: return)
            }
            command == "tpms" -> AppGraph.connection.requestTpmVehicle()
            else -> Log.w(TAG, "Unknown voice command: $command")
        }
    }

    private var appContext: Context? = null

    companion object {
        const val ACTION = "com.motoeye.remote.ACTION_VOICE_COMMAND"
        const val EXTRA_COMMAND = "command"

        fun send(context: Context, command: String) {
            val intent = Intent(ACTION).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_COMMAND, command)
            }
            context.sendBroadcast(intent)
        }
    }
}
