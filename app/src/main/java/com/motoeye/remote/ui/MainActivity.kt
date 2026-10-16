package com.motoeye.remote.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.motoeye.remote.AppGraph
import com.motoeye.remote.voice.VoiceShortcuts

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) onPermissionsGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)

        setContent {
            MotoEyeTheme {
                RemoteAppUi(
                    initialScreen = if (intent?.getStringExtra("preview") == "remote")
                        Screen.Remote else Screen.Connect
                )
            }
        }

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Register voice shortcuts once permissions are in place
        runCatching { VoiceShortcuts.registerAll(this) }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) needed += Manifest.permission.BLUETOOTH_SCAN
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun onPermissionsGranted() {
        VoiceShortcuts.registerAll(this)
    }
}

@Composable
private fun RemoteAppUi(initialScreen: Screen = Screen.Connect) {
    var screen by remember { mutableStateOf(initialScreen) }
    var settingsReturnScreen by remember { mutableStateOf<Screen>(Screen.Remote) }

    // Gesture/tap back from Settings returns to the screen that opened it
    // instead of finishing the Activity (previous default behaviour).
    if (screen == Screen.Settings) {
        androidx.activity.compose.BackHandler { screen = settingsReturnScreen }
    }
    if (screen == Screen.Connect) {
        val unlockCtx = LocalContext.current
        androidx.activity.compose.BackHandler {
            (unlockCtx as? android.app.Activity)?.finish()
        }
    }

    when (val s = screen) {
        Screen.Connect -> DeviceSelectScreen(
            onConnected = { screen = Screen.Remote },
            onOpenSettings = {
                settingsReturnScreen = Screen.Connect
                screen = Screen.Settings
            }
        )
        Screen.Remote -> RemoteScreen(
            onOpenSettings = {
                settingsReturnScreen = Screen.Remote
                screen = Screen.Settings
            },
            onOpenDeviceSelect = { screen = Screen.Connect }
        )
        Screen.Settings -> SettingsScreen(onBack = { screen = settingsReturnScreen })
    }
}

sealed class Screen {
    data object Connect : Screen()
    data object Remote : Screen()
    data object Settings : Screen()
}
