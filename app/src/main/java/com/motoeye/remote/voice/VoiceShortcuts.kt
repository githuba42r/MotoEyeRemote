package com.motoeye.remote.voice

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Registers Google Assistant voice shortcuts ("OK Google, MotoEye <cmd>")
 * so the user can drive the remote hands-free while riding.
 *
 * Shortcut phrases are user-editable in the Assistant shortcuts settings;
 * the deliverable intent is a broadcast to RemoteActionReceiver.
 */
object VoiceShortcuts {

    data class VoiceCommand(val id: String, val command: String, val label: String)

    /**
     * Android caps dynamic shortcuts at 4 per launcher activity
     * (ShortcutService.enforceMaxActivityShortcuts). Register only the
     * 4 riding-critical commands; users can add more via Assistant's
     * shortcut settings if desired.
     */
    val COMMANDS = listOf(
        VoiceCommand("camera", "camera", "Camera"),
        VoiceCommand("record", "record", "Record"),
        VoiceCommand("play_pause", "play pause", "Play/Pause Music"),
        VoiceCommand("exit_aa", "exit aa", "Exit Android Auto")
    )

    fun registerAll(context: Context) {
        try {
            val shortcuts = COMMANDS.map { cmd ->
                val intent = Intent(RemoteActionReceiver.ACTION).apply {
                    setPackage(context.packageName)
                    putExtra(RemoteActionReceiver.EXTRA_COMMAND, cmd.command)
                }

                ShortcutInfoCompat.Builder(context, cmd.id)
                    .setShortLabel(cmd.label)
                    .setLongLabel("MotoEye ${cmd.label}")
                    .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_btn_speak_now))
                    .setIntent(intent)
                    .build()
            }

            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            // Never crash the app over shortcut registration — some OEMs
            // enforce stricter limits than AOSP.
            android.util.Log.w("VoiceShortcuts", "Shortcut registration failed: ${e.message}")
        }
    }
}
