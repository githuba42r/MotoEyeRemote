package com.motoeye.remote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "remote_settings")

data class AppSettings(
    val pairedDeviceAddress: String?,
    val pairedDeviceName: String?,
    val autoReconnect: Boolean,
    val wakeLockEnabled: Boolean,
    val vibrationEnabled: Boolean,
    val longPressVoice: Boolean,
    val voiceLongPressMs: Int,
    val showTpms: Boolean,
    val showVoiceButton: Boolean,
    val showRecordButton: Boolean,
    val hidAttachDelaySeconds: Int,  // wait after BLE link-up before stating the HID keyboard channel
    val protocolClickMode: Boolean,  // force JSON click protocol instead of HID
)

class SettingsRepository(private val context: Context) {

    private val ds = context.dataStore

    private object Keys {
        val PAIRED_ADDRESS = stringPreferencesKey("paired_address")
        val PAIRED_NAME = stringPreferencesKey("paired_name")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val WAKE_LOCK = booleanPreferencesKey("wake_lock")
        val VIBRATION = booleanPreferencesKey("vibration")
        val LONG_PRESS_VOICE = booleanPreferencesKey("long_press_voice")
        val VOICE_LONG_PRESS_MS = intPreferencesKey("voice_long_press_ms")
        val SHOW_TPMS = booleanPreferencesKey("show_tpms")
        val SHOW_VOICE = booleanPreferencesKey("show_voice_button")
        val SHOW_RECORD = booleanPreferencesKey("show_record_button")
        val HID_DELAY = intPreferencesKey("hid_attach_delay_seconds")
        val CLICK_MODE = booleanPreferencesKey("click_mode")
    }

    val settings: Flow<AppSettings> = ds.data.map { p ->
        AppSettings(
            pairedDeviceAddress = p[Keys.PAIRED_ADDRESS],
            pairedDeviceName = p[Keys.PAIRED_NAME],
            autoReconnect = p[Keys.AUTO_RECONNECT] ?: true,
            wakeLockEnabled = p[Keys.WAKE_LOCK] ?: true,
            vibrationEnabled = p[Keys.VIBRATION] ?: true,
            longPressVoice = p[Keys.LONG_PRESS_VOICE] ?: true,
            voiceLongPressMs = p[Keys.VOICE_LONG_PRESS_MS] ?: 500,
            showTpms = p[Keys.SHOW_TPMS] ?: true,
            showVoiceButton = p[Keys.SHOW_VOICE] ?: true,
            showRecordButton = p[Keys.SHOW_RECORD] ?: true,
            hidAttachDelaySeconds = p[Keys.HID_DELAY] ?: 5,
            protocolClickMode = p[Keys.CLICK_MODE] ?: false,
        )
    }

    suspend fun pairedDeviceAddress(): String? = ds.data.first()[Keys.PAIRED_ADDRESS]
    val pairedDeviceAddressFlow: Flow<String?> = ds.data.map { it[Keys.PAIRED_ADDRESS] }

    suspend fun autoReconnect(): Boolean = ds.data.first()[Keys.AUTO_RECONNECT] ?: true

    suspend fun savePairedDevice(address: String, name: String) {
        ds.edit {
            it[Keys.PAIRED_ADDRESS] = address
            it[Keys.PAIRED_NAME] = name
        }
    }

    suspend fun clearPairedDevice() {
        ds.edit {
            it.remove(Keys.PAIRED_ADDRESS)
            it.remove(Keys.PAIRED_NAME)
        }
    }

    suspend fun setAutoReconnect(enabled: Boolean) = ds.edit { it[Keys.AUTO_RECONNECT] = enabled }
    suspend fun setWakeLock(enabled: Boolean) = ds.edit { it[Keys.WAKE_LOCK] = enabled }
    suspend fun setVibration(enabled: Boolean) = ds.edit { it[Keys.VIBRATION] = enabled }
    suspend fun setLongPressVoice(enabled: Boolean) = ds.edit { it[Keys.LONG_PRESS_VOICE] = enabled }
    suspend fun setVoiceLongPressMs(ms: Int) = ds.edit { it[Keys.VOICE_LONG_PRESS_MS] = ms }
    suspend fun setShowTpms(enabled: Boolean) = ds.edit { it[Keys.SHOW_TPMS] = enabled }
    suspend fun setShowVoiceButton(enabled: Boolean) = ds.edit { it[Keys.SHOW_VOICE] = enabled }
    suspend fun setShowRecordButton(enabled: Boolean) = ds.edit { it[Keys.SHOW_RECORD] = enabled }


    suspend fun hidAttachDelaySeconds(): Int = ds.data.first()[Keys.HID_DELAY] ?: 5
    suspend fun setHidAttachDelaySeconds(v: Int) = ds.edit { it[Keys.HID_DELAY] = v }
    suspend fun setClickMode(enabled: Boolean) = ds.edit { it[Keys.CLICK_MODE] = enabled }
}
