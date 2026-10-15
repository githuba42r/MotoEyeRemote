package com.motoeye.remote.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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
 * Controls phone-side media playback.
 *
 * Primary path: direct transport controls on the active MediaSession via
 * MediaSessionManager (works because the app is a granted notification
 * listener). Fallback: AudioManager media key events (may be dropped by
 * OEMs for background apps or when no session exists).
 */
class MediaController(private val context: Context) {

    private val TAG = "MediaController"
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
    private val ourListenerComponent =
        ComponentName(context, MediaSessionListener::class.java)

    private val _activeSessions = MutableStateFlow<List<MediaSessionInfo>>(emptyList())
    val activeSessions: StateFlow<List<MediaSessionInfo>> = _activeSessions.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Called by MediaSessionListener when notifications change */
    fun updateSessions(sessions: List<MediaSessionInfo>) {
        _activeSessions.value = sessions
        _isPlaying.value = sessions.any { it.isPlaying }
    }

    private class Pick {
        var controller: MediaController? = null
        var playing = false
    }

    /** Choose the best media session: the one whose playback state was
     *  updated most RECENTLY (zombie sessions from closed apps keep a
     *  stale "playing" state from last boot — recency outranks them). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun pickSession(): Pick {
        val out = Pick()
        try {
            val sessions = sessionManager.getActiveSessions(ourListenerComponent)
            if (sessions.isEmpty()) return out
            var bestTime = Long.MIN_VALUE
            for (s in sessions) {
                val state = s.playbackState ?: continue
                val t = state.lastPositionUpdateTime
                if (t > bestTime) {
                    bestTime = t
                    out.controller = s
                    out.playing = state.state == PlaybackState.STATE_PLAYING
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getActiveSessions failed: ${e.message}")
        }
        return out
    }

    /** Direct transport control with key-event fallback */
    private fun withSession(transport: (MediaController.TransportControls) -> Unit, keyFallback: Int) {
        val p = pickSession()
        val c = p.controller
        if (c != null) {
            try {
                transport(c.transportControls)
                Log.i(TAG, "transport control sent to ${c.packageName}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "transport control failed: ${e.message}")
            }
        }
        // Fallback: dispatch media key (OS routes to active session if any)
        try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyFallback))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyFallback))
            Log.i(TAG, "media key $keyFallback dispatched (fallback)")
        } catch (e: Exception) {
            Log.e(TAG, "media dispatch failed: ${e.message}")
        }
    }

    fun playPause() = withSession(
        { t -> if (pickSession().playing) t.pause() else t.play() },
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    )
    fun play() = withSession({ t -> t.play() }, KeyEvent.KEYCODE_MEDIA_PLAY)
    fun pause() = withSession({ t -> t.pause() }, KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun next() = withSession({ t -> t.skipToNext() }, KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = withSession({ t -> t.skipToPrevious() }, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun stop() = withSession({ t -> t.stop() }, KeyEvent.KEYCODE_MEDIA_STOP)


    /**
     * Resume the last playing session — "resume where you left off".
     * Sends PLAY; the media app restores its previous position/state.
     */
    fun resumeWhereLeftOff() {
        if (!_isPlaying.value) {
            withSession({ t -> t.play() }, KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    fun volumeUp() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
    }

    fun volumeDown() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
    }
}
