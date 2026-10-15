package com.motoeye.remote.media

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.motoeye.remote.AppGraph

/**
 * Discovers active media sessions by watching media-style notifications
 * (playback state + actions) from apps like Amazon Music, YouTube,
 * Spotify, etc. Feeds results into MediaController.
 *
 * Requires user to grant notification access in system settings.
 */
class MediaSessionListener : NotificationListenerService() {

    private val TAG = "MediaSessionListener"

    // Packages with known media-style notifications
    private val mediaPackages = setOf(
        "com.amazon.mp3",
        "com.google.android.apps.youtube.music",
        "com.spotify.music",
        "com.google.android.music",
        "com.apple.android.music",
        "com.pandora.android",
        "deezer.android.app",
        "com.maxmpz.audioplayer",          // Poweramp
        "com.sec.android.app.music",
        "com.htc.music",
        "com.miui.player",
        "ru.yandex.music",
        "com.vkontakte.android",
        "com.zhiliaoapp.musically",         // TikTok
        "com.whatsapp",
        "com.instagram.android"
    )

    override fun onListenerConnected() {
        Log.i(TAG, "Notification listener connected")
        refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    private fun refresh() {
        try {
            val sessions = activeNotifications
                .orEmpty()
                .filter { sbn ->
                    val pkg = sbn.packageName
                    val isMediaStyle = sbn.notification.extras
                        ?.getCharSequence("android.template")?.toString()
                        ?.contains("MediaStyle", ignoreCase = true) == true
                    pkg in mediaPackages || isMediaStyle ||
                        sbn.notification.actions?.isNotEmpty() == true &&
                        sbn.notification.extras?.getBoolean("android.showWhen", false) == true
                }
                .map { sbn ->
                    val actions = sbn.notification.actions.orEmpty()
                    val hasPause = actions.any {
                        it.title?.contains("Pause", true) == true ||
                            it.title?.contains("❚❚", true) == true
                    }
                    MediaSessionInfo(
                        packageName = sbn.packageName,
                        appName = sbn.packageName,
                        isPlaying = hasPause  // if a Pause action exists, media is playing
                    )
                }
                .distinctBy { it.packageName }

            if (AppGraph.isInitialized) {
                AppGraph.media.updateSessions(sessions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing sessions: ${e.message}")
        }
    }
}
