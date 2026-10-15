package com.motoeye.remote.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaSessionInfo(
    val packageName: String,
    val appName: String,
    val isPlaying: Boolean
)

/**
 * Controls phone-side media playback using AudioManager media key events.
 * Media sessions are discovered via NotificationListenerService
 * (MediaSessionListener), avoiding the privileged MEDIA_CONTENT_CONTROL
 * permission.
 */
class MediaController(private val context: Context) {

    private val TAG = "MediaController"
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _activeSessions = MutableStateFlow<List<MediaSessionInfo>>(emptyList())
    val activeSessions: StateFlow<List<MediaSessionInfo>> = _activeSessions.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Called by MediaSessionListener when notifications change */
    fun updateSessions(sessions: List<MediaSessionInfo>) {
        _activeSessions.value = sessions
        _isPlaying.value = sessions.any { it.isPlaying }
    }

    private fun sendMediaKey(keyCode: Int) {
        try {
            val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            audio.dispatchMediaKeyEvent(event)
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            Log.d(TAG, "Sent media key $keyCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media key: ${e.message}")
        }
    }

    fun playPause() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun play() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    fun pause() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun next() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun stop() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP)

    /**
     * Resume the last playing session — "resume where you left off".
     * Sends PLAY; the media app restores its previous position/state.
     */
    fun resumeWhereLeftOff() {
        if (!_isPlaying.value) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    fun volumeUp() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
    }

    fun volumeDown() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
    }
}
