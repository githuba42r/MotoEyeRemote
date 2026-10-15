package com.motoeye.remote.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * High-level key event sender that wraps BleConnectionManager.
 * Provides convenient methods for sending remote control commands.
 */
class KeyEventSender(private val connectionManager: BleConnectionManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun sendUp(pressed: Boolean = true) = connectionManager.sendKeyEvent(Protocol.Keys.DPAD_UP, pressed)
    fun sendDown(pressed: Boolean = true) = connectionManager.sendKeyEvent(Protocol.Keys.DPAD_DOWN, pressed)
    fun sendLeft(pressed: Boolean = true) = connectionManager.sendKeyEvent(Protocol.Keys.DPAD_LEFT, pressed)
    fun sendRight(pressed: Boolean = true) = connectionManager.sendKeyEvent(Protocol.Keys.DPAD_RIGHT, pressed)
    fun sendOk(pressed: Boolean = true) = connectionManager.sendKeyEvent(Protocol.Keys.ENTER, pressed)
    fun sendBack(pressed: Boolean = true) = connectionManager.sendKeyEvent(Protocol.Keys.BACK, pressed)
    fun sendCamera(pressed: Boolean = true) = connectionManager.sendKeyEvent(Protocol.Keys.CAMERA, pressed)
    fun sendVolumeUp(pressed: Boolean = true) = connectionManager.sendKeyEvent(Protocol.Keys.VOLUME_UP, pressed)
    fun sendVolumeDown(pressed: Boolean = true) = connectionManager.sendKeyEvent(Protocol.Keys.VOLUME_DOWN, pressed)
    fun sendPtt(pressed: Boolean = true) = connectionManager.sendKeyEvent(Protocol.Keys.HEADSETHOOK, pressed)

    /** Send a quick click (press + release) */
    fun click(keyCode: Int) = connectionManager.sendKeyClick(keyCode)

    /** Send a held key: press, wait holdMs, then release */
    fun hold(keyCode: Int, holdMs: Long) {
        connectionManager.sendKeyEvent(keyCode, true)
        scope.launch {
            delay(holdMs)
            connectionManager.sendKeyEvent(keyCode, false)
        }
    }

    /** Send navigation sequence (for macro execution) */
    fun sendNavigationSequence(
        direction: Int,
        count: Int,
        intervalMs: Long = 150,
        onProgress: ((Int) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ) {
        scope.launch {
            for (i in 0 until count) {
                connectionManager.sendKeyClick(direction)
                onProgress?.invoke(i + 1)
                delay(intervalMs)
            }
            onComplete?.invoke()
        }
    }

    /** Long-press voice trigger (for Assistant) */
    fun triggerVoiceAssistant() {
        // Send HEADSETHOOK (131) to trigger Google Assistant
        connectionManager.sendKeyClick(Protocol.Keys.HEADSETHOOK)
    }
}
