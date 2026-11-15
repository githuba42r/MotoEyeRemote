package com.motoeye.remote.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An installed app that exposes a MediaBrowserService (a candidate default player). */
data class PlayerApp(val packageName: String, val label: String)

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

    private val main = Handler(Looper.getMainLooper())

    /** Package started by PLAY when no media session exists (Settings → Default player). */
    @Volatile
    var defaultPlayer: String? = null

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

    fun playPause() {
        if (startDefaultPlayerIfIdle()) return
        withSession(
            { t -> if (pickSession().playing) t.pause() else t.play() },
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        )
    }
    fun play() {
        if (startDefaultPlayerIfIdle()) return
        withSession({ t -> t.play() }, KeyEvent.KEYCODE_MEDIA_PLAY)
    }
    fun pause() = withSession({ t -> t.pause() }, KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun next() = withSession({ t -> t.skipToNext() }, KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = withSession({ t -> t.skipToPrevious() }, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun stop() = withSession({ t -> t.stop() }, KeyEvent.KEYCODE_MEDIA_STOP)


    /**
     * Resume the last playing session — "resume where you left off".
     * Sends PLAY; the media app restores its previous position/state.
     */
    fun resumeWhereLeftOff() {
        if (!_isPlaying.value) play()
    }

    // ------------------------------------------------------- default player

    /** Installed apps exposing a MediaBrowserService, sorted by label. */
    fun installedPlayers(): List<PlayerApp> {
        val pm = context.packageManager
        return runCatching {
            pm.queryIntentServices(Intent(BROWSER_SERVICE_ACTION), 0)
        }.getOrDefault(emptyList())
            .map { it.serviceInfo.packageName }
            .distinct()
            // launchable apps only (drops system plumbing like the Bluetooth stack)
            .filter { it != context.packageName && pm.getLaunchIntentForPackage(it) != null }
            .map { pkg ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                PlayerApp(pkg, label)
            }
            .sortedBy { it.label.lowercase() }
    }

    fun playerLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * No media session at all (nothing playing or paused) and a default
     * player is set: start it instead of sending PLAY into the void.
     * Returns true when the default player was started.
     */
    private fun startDefaultPlayerIfIdle(): Boolean {
        val pkg = defaultPlayer ?: return false
        if (pickSession().controller != null) return false
        Log.i(TAG, "no media session — starting default player $pkg")
        startPlayer(pkg)
        return true
    }

    /**
     * Ask the player's MediaBrowserService for its session and send PLAY
     * (how Android Auto starts playback, the app stays in the background).
     * If the service refuses or nothing plays, launch the app and send PLAY
     * once its session appears.
     */
    private fun startPlayer(pkg: String) {
        val service = runCatching {
            context.packageManager.queryIntentServices(
                Intent(BROWSER_SERVICE_ACTION).setPackage(pkg), 0
            ).firstOrNull()?.serviceInfo
        }.getOrNull()
        if (service == null) {
            launchAndPlay(pkg)
            return
        }
        main.post {
            var browser: MediaBrowser? = null
            var settled = false
            fun fallback(reason: String) {
                if (settled) return
                settled = true
                Log.w(TAG, "$pkg browser: $reason — launching the app")
                runCatching { browser?.disconnect() }
                launchAndPlay(pkg)
            }
            browser = MediaBrowser(
                context,
                ComponentName(service.packageName, service.name),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        if (settled) return
                        settled = true
                        val b = browser ?: return
                        runCatching {
                            MediaController(context, b.sessionToken).transportControls.play()
                            Log.i(TAG, "PLAY sent to $pkg via its browser service")
                        }.onFailure { Log.w(TAG, "browser PLAY failed: ${it.message}") }
                        // Keep the binding briefly so the player can start its own
                        // foreground service, then check it really plays.
                        main.postDelayed({
                            runCatching { b.disconnect() }
                            if (!sessionPlaying(pkg)) {
                                Log.w(TAG, "$pkg not playing after browser PLAY — launching the app")
                                launchAndPlay(pkg)
                            }
                        }, BROWSER_SETTLE_MS)
                    }

                    override fun onConnectionFailed() = fallback("connection refused")
                    override fun onConnectionSuspended() = fallback("connection suspended")
                },
                null
            )
            runCatching { browser.connect() }.onFailure { fallback("connect threw ${it.message}") }
            main.postDelayed({ fallback("connect timed out") }, BROWSER_CONNECT_TIMEOUT_MS)
        }
    }

    /** Open the player app, wait for its media session, then send PLAY. */
    private fun launchAndPlay(pkg: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Log.e(TAG, "default player $pkg has no launch intent")
            return
        }
        runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Log.e(TAG, "launching $pkg failed: ${it.message}")
            return
        }
        val deadline = System.currentTimeMillis() + LAUNCH_SESSION_TIMEOUT_MS
        fun poll() {
            val session = sessionFor(pkg)
            when {
                session != null -> {
                    runCatching { session.transportControls.play() }
                    Log.i(TAG, "PLAY sent to $pkg after launch")
                }
                System.currentTimeMillis() < deadline -> main.postDelayed({ poll() }, 500)
                else -> Log.w(TAG, "$pkg opened no media session")
            }
        }
        main.postDelayed({ poll() }, 500)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun sessionFor(pkg: String): MediaController? = runCatching {
        sessionManager.getActiveSessions(ourListenerComponent).firstOrNull { it.packageName == pkg }
    }.getOrNull()

    private fun sessionPlaying(pkg: String): Boolean =
        sessionFor(pkg)?.playbackState?.state.let {
            it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING ||
                it == PlaybackState.STATE_CONNECTING
        }

    fun volumeUp() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
    }

    fun volumeDown() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
    }

    private companion object {
        const val BROWSER_SERVICE_ACTION = "android.media.browse.MediaBrowserService"
        const val BROWSER_CONNECT_TIMEOUT_MS = 4_000L
        const val BROWSER_SETTLE_MS = 4_000L
        const val LAUNCH_SESSION_TIMEOUT_MS = 10_000L
    }
}
