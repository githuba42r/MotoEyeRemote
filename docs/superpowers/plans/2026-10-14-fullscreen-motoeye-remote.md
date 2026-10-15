# MotoEye Full-Screen Remote App — Implementation Plan

**Goal:** Android app that replaces the MotoEye physical BLE remote with a full-screen touch interface, running persistently alongside the hardware remote, with notification quick actions, Google Assistant voice control, Android Auto integration, TPMS display, media controls, and macros.

**Protocol source:** Reverse-engineered from MotoEye APK (`com.vidoar.motohud` v1.0.2604141705), decompiled at `/tmp/opencode/motoeye/decompiled/`.

**Architecture context:** The MotoEye E6 device runs Android with side-loaded apps (EUC World, TPMS, etc.). The phone app connects to it via BLE and sends HID key events that navigate the device's UI — effectively emulating the physical remote. The HUD also sends sensor data (TPMS, navigation, device status) back to the phone via BLE notifications.

---

## 1. Reverse-Engineered Protocol Reference

### 1.1 BLE GATT UUIDs

| Role | UUID | Write Type |
|------|------|-----------|
| Service | `0000fe86-0000-1000-8000-00805f9b34fb` | — |
| Write (JSON commands) | `00000012-b100-0000-0000-100000000003` | WRITE_NO_RESPONSE (2) |
| Notify | `00000011-b100-0000-0000-100000000003` | — |
| Read (device status) | `00000013-b100-0000-0000-100000000003` | — |
| HID Input (key events) | `00000014-b100-0000-0000-100000000003` | WRITE (1) |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | — |

### 1.2 Connection Handshake

After GATT connect + service discovery:
1. Subscribe to notify characteristic (`00000011-...`) by writing `ENABLE_NOTIFICATION_VALUE` to CCCD
2. Read device status from `00000013-...`
3. Send ping: `{"type":1}` (framed, via write characteristic)
4. Send device info: `{"type":1000,"content":{"code":"...","ptype":"android","uid":"...","proVer":5,"pBand":"...","pModel":"...","pVer":"...","appVer":"...","lan":"..."}}`
5. Device responds with its protocol version in host info — determines HID vs JSON key mode

### 1.3 Key Event Protocol (protocol v5, HID mode)

Write **6 bytes** to HID characteristic `00000014-...`:

```
[0x00, pressed, keycode_lo, keycode_hi, 0x00, 0x00]
```

- `pressed`: `1` on ACTION_DOWN, `0` on ACTION_UP
- `keycode_lo/hi`: Android `KeyEvent` constant, little-endian 16-bit

| Button | Code | KeyEvent Constant |
|--------|------|-------------------|
| Up | 19 | KEYCODE_DPAD_UP |
| Down | 20 | KEYCODE_DPAD_DOWN |
| Left | 21 | KEYCODE_DPAD_LEFT |
| Right | 22 | KEYCODE_DPAD_RIGHT |
| OK/Center | 66 | KEYCODE_ENTER |
| Back | 4 | KEYCODE_BACK |
| Volume Up | 24 | KEYCODE_VOLUME_UP |
| Volume Down | 25 | KEYCODE_VOLUME_DOWN |
| Camera (rear view) | 132 | KEYCODE_CAMERA |
| Record (non-M3/M3S) | 134 | KEYCODE_FUNCTION |
| Record (M3/M3S) | 133 | KEYCODE_SYSRQ |
| PTT | 131 | KEYCODE_HEADSETHOOK |

### 1.4 JSON Command Protocol (protocol < 4)

For older firmware, key events go as JSON via the write characteristic with binary framing:

- Click: `{"type":8001,"content":{"key":<code>}}`
- Up/Down: `{"type":8002,"content":{"key":<code>,"isUp":0|1}}`

### 1.5 JSON Command Framing

Commands are serialized with Fastjson as `{"type":<int>,"content":{...}}` and framed:

- **Header** (5 bytes): `[pkgCount & 0x7F, len_lo, len_hi, 0, 0]`
- **Body** (20 bytes each): `[pkgIndex | 0x80, up to 19 payload bytes]`
- Max payload per packet: 19 bytes
- Max total payload: 2413 bytes

### 1.6 Coexistence with Hardware Remote

The physical remote is a **HUD-side peripheral** — it connects to the MotoEye device independently. The phone is a separate BLE Central. The HUD multiplexes both connections. **No coexistence arbitration needed** — the app just connects and sends HID writes. The HUD reports a connect count; if > 1 phones connected, it may show a dialog.

---

## 2. Feature Scope

### 2.1 Core Remote (Essential)
- Full-screen landscape UI with large glove-friendly buttons
- BLE GATT connection to MotoEye device
- HID key event sending (press/release pairs)
- Auto-reconnect with exponential backoff
- Persist device address across restarts
- Haptic feedback on button press

### 2.2 Always-On Operation (Essential)
- Foreground service (`foregroundServiceType="connectedDevice"`) maintaining BLE connection
- Boot receiver to auto-start on device boot
- Auto-reconnect on Bluetooth adapter restart
- Battery optimization exemption prompt

### 2.3 Notification Quick Actions (High value, low effort)
- Persistent foreground service notification
- Action buttons: Rear Camera, Record Start/Stop, Volume Up/Down, **Voice** (triggers Assistant)
- Tap notification → open full-screen remote
- Long-press "Back" action → exit Android Auto (via Accessibility Service)
- Optional: AA Connect action, TPMS toggle, Media play/pause

### 2.4 Voice Control via Google Assistant / Android Auto (High value, moderate effort)

Voice control leverages **Google Assistant** (on phone or via Android Auto) rather than a custom on-device speech recognizer. This is more reliable in motorcycle noise and integrates with the existing "OK Google" workflow.

**Mechanism: Android Shortcuts + App Actions**

1. Register **voice-triggered shortcuts** via `ShortcutManagerCompat`:
   - Each shortcut maps a spoken phrase to an intent
   - Google Assistant discovers these shortcuts automatically
   - Phrases work on phone ("OK Google, open MotoEye camera") AND via Android Auto ("OK Google, MotoEye camera")

2. Shortcut → intent → `RemoteActionReceiver` → action/macro execution

**Registered voice phrases:**

| Phrase | Action |
|--------|--------|
| "MotoEye camera" / "rear camera" | Toggle rear view camera (key 132) |
| "MotoEye record" | Start/stop recording (key 134) |
| "MotoEye volume up" | Volume up (key 24) |
| "MotoEye volume down" | Volume down (key 25) |
| "MotoEye EUC World" | Execute EUC World macro (launch on MotoEye device) |
| "MotoEye TPMS" | Execute TPMS macro (show TPMS on HUD or phone) |
| "MotoEye exit auto" | Exit Android Auto |
| "MotoEye pause" / "play" | Media pause/play |
| "MotoEye next" / "previous" | Media skip |
| "MotoEye [macro name]" | Execute any user-defined macro |

**Voice trigger options (configurable in Settings):**

| Trigger | How it works |
|---------|-------------|
| **Long-press center button** (default) | Hold OK/Enter ≥ 500ms → triggers Google Assistant via `KEYCODE_HEADSETHOOK` |
| **Dedicated app button** | PTT/voice button in the full-screen remote UI |
| **Notification action** | "Voice" action button in the foreground notification |
| **Hardware remote long-press** | If physical remote sends distinct long-press event for center button |

**Physical remote button layout** (from original design spec):

| Button | Short Press | Long Press |
|--------|-------------|------------|
| D-pad Up | Up (19) | Volume Up (24) |
| D-pad Down | Down (20) | Volume Down (25) |
| D-pad Left | Left (21) | — |
| D-pad Right | Right (22) | — |
| D-pad Center | OK/Enter (66) | **Voice trigger** (configurable) |
| Top button | Rear Camera (132) | Start/Stop Recording (134) |
| Bottom button | Back (4) | — |

**Note:** The physical remote has no dedicated PTT button. The virtual remote in the MotoEye app has `btn_ptt` (key 131), but this may not map to a physical button. The app defaults to long-press center for voice, but this is configurable.

**Why not custom SpeechRecognizer?**
- Motorcycle wind noise makes on-device recognition unreliable
- Google Assistant's cloud recognition is significantly more accurate
- Zero battery cost when not actively triggered
- Works seamlessly across phone and Android Auto

### 2.5 Android Auto Control (High value, moderate effort)

**Auto-connect + full-screen navigation:**

- **AA Connect button** appears in-app when MotoEye device connects (or as notification action)
- Tapping it:
  1. Sends `MediaSession` intent to start AA projection
  2. If AA supports `CarProjectionManager` (system API), uses that (requires privileged permission)
  3. Fallback: launches AA via `com.google.android.projection.gearhead.START` intent
  4. AA automatically shows the default navigation app (configured in AA settings) in full screen

**Exit Android Auto:**
- Accessibility Service injects `KEYCODE_HOME` via `performGlobalAction`
- Works from notification action, voice command, or macro

**Media controls:**
- `MediaSessionManager` dispatches play/pause/next/prev to the active AA media session

**Note:** MotoEye APK has zero Android Auto integration — this is pure system-level control built new.

### 2.6 Media Controls (High value, moderate effort)

Control playback on the phone's active media session (Amazon Music, YouTube, Brave, Spotify, etc.) via `MediaSession` API.

**Implementation: NotificationListenerService**

- `MediaSessionManager.getActiveSessions()` requires system permission (`MEDIA_CONTENT_CONTROL`)
- Alternative: `NotificationListenerService` discovers media sessions from media notifications
- From the `MediaSession.Token`, create a `MediaController` to send transport controls

**Controls:**

| Button | Action | KeyEvent |
|--------|--------|----------|
| Play/Pause | Toggle playback | `KEYCODE_MEDIA_PLAY_PAUSE` |
| Next/Skip | Skip to next track | `KEYCODE_MEDIA_NEXT` |
| Previous/Rewind | Go to previous track | `KEYCODE_MEDIA_PREVIOUS` |
| Resume | Resume last paused session | `MediaController.getTransportControls().play()` |

**Resume where left off:**
- Track last active media session (package name + token) via NotificationListenerService
- When AA disconnects or media pauses, store the session reference
- "Resume" button sends `play()` to the last active session — the media app resumes from its paused position
- Works with Amazon Music, YouTube, Spotify, Brave (any app implementing `MediaSession`)

**Integration points:**
- Media control buttons in full-screen remote UI (toggleable layout mode)
- Media control actions in notification
- Voice commands: "pause", "play", "next", "previous"
- Long-press center button can trigger play/pause (configurable)

### 2.7 TPMS Display (High value, low effort)

The MotoEye device has built-in TPMS (Tire Pressure Monitoring System) sensors. Data flows to the phone via BLE — the phone app can receive and display it natively.

**Data flow (from decompiled `BleCommService.java`):**

1. Phone sends `CMD_TPM_VEHICLE` (1031) → HUD responds with vehicle type, sensor addresses (lf/lr/rf/rr)
2. Phone sends `CMD_TPM_DATA` (1032) with `{"isOn":"1"}` → HUD enables TPMS data reporting
3. HUD pushes TPMS data via notify characteristic as JSON:
   ```json
   {
     "type": 1032,
     "content": {
       "isSuccess": 1,
       "tpms": [
         {"id":"...","position":0,"pressure":245.5,"temperature":28.0,"voltage":2.75,"rssi":-45,"vehicleID":1},
         ...
       ],
       "pU": 0, "tU": 0,
       "pMax": 280.0, "pMin": 200.0,
       "tMax": 60.0, "vMin": 2.2
     }
   }
   ```

**TpmData fields** (from `TpmData.java`):

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Sensor ID |
| `position` | int | 0=LF, 1=LR, 2=RF, 3=RR |
| `pressure` | float | Raw pressure (unit depends on `pU`) |
| `temperature` | float | Temperature (unit depends on `tU`) |
| `voltage` | float | Sensor battery voltage |
| `rssi` | int | Signal strength |
| `vehicleID` | long | Vehicle identifier |

**Unit conversion:**
- Pressure: `pU=0` → Bar (`×0.01`), `pU=1` → Psi (`×0.1450377`), `pU=2` → KPa (raw)
- Temperature: `tU=0` → Celsius (raw), `tU=1` → Fahrenheit (`×9/5+32`)
- Battery: `voltage ≥ 2.9V` → 100%, `< 2.55V` → 0%, linear interpolation between

**Vehicle types** (from `TpmDataFragment.java`):
- 0 = Motorcycle (2 wheels: LF, RR)
- 1 = Unicycle (1 wheel: RF)
- 2 = Trike (3 wheels: LF, RF, RR)
- 3 = Reverse Trike (3 wheels: LF, RF, LR)
- 4 = ATV (4 wheels: LF, LR, RF, RR)

**UI:**
- TPMS card/panel in the full-screen remote UI (collapsible)
- Shows per-wheel: pressure, temperature, battery %, signal
- Alert coloring when pressure ≥ max or ≤ min, temperature ≥ max, voltage ≤ min
- TPMS notification with current readings (updates on push)
- Toggle TPMS reporting on/off to save battery

### 2.8 App Launching on MotoEye Device via BLE (High value, moderate effort)

The MotoEye device runs Android with side-loaded apps (EUC World, TPMS, etc.). The phone app can launch these apps by sending BLE key events that navigate the device's UI — same as the physical remote.

**How it works:**
- BLE HID key events (up/down/left/right/enter/back) navigate the MotoEye device's Android UI
- A macro is a sequence of key events that: exits current app → navigates app grid → selects target app → presses enter
- The number of navigation presses depends on the app's position in the MotoEye device's launcher (configurable per user)

**Example: EUC World macro**
```
1. SEND_KEY back (4)          — exit current app
2. WAIT 500ms                 — wait for home/app grid
3. SEND_KEY right (22) × 8   — navigate to EUC World position
4. WAIT 200ms                 — brief pause between presses
5. SEND_KEY enter (66)        — launch EUC World
```

**Note:** App position in the MotoEye device's launcher varies per device configuration. The macro editor allows users to adjust the navigation sequence (number of right/down presses) to match their layout.

**Alternative: Direct app launch on phone**
- For apps installed on the *phone* (not the MotoEye device), use `LAUNCH_APP` step with package name
- For apps on the *MotoEye device*, use `SEND_KEY` sequence via BLE

### 2.9 Macros (High value, moderate effort)

Named sequences of actions stored in Room database. Triggerable from UI buttons, notification actions, **Google Assistant voice commands**, or other macros.

**Step types:**

| Type | Parameters | Description |
|------|-----------|-------------|
| `SEND_KEY` | keyCode, pressed | Send BLE HID key event to MotoEye device |
| `WAIT` | durationMs | Delay before next step |
| `LAUNCH_APP` | packageName | Launch app on phone via intent |
| `SEND_INTENT` | intentAction, extras | Send arbitrary intent |
| `AA_EXIT` | — | Exit Android Auto (Accessibility Service) |
| `AA_CONNECT` | — | Start Android Auto projection |
| `MEDIA_PLAY` | — | Resume/play media |
| `MEDIA_PAUSE` | — | Pause media |
| `MEDIA_NEXT` | — | Skip to next track |
| `MEDIA_PREV` | — | Go to previous track |
| `TPMS_ON` | — | Enable TPMS reporting |
| `TPMS_OFF` | — | Disable TPMS reporting |

**Preset macros:**

| Macro | Steps | Target |
|-------|-------|--------|
| **EUC World** | `SEND_KEY` back, `WAIT` 500ms, `SEND_KEY` right×N, `SEND_KEY` enter | MotoEye device |
| **TPMS on HUD** | `SEND_KEY` back, `WAIT` 500ms, `SEND_KEY` right×N, `SEND_KEY` down×M, `SEND_KEY` enter | MotoEye device |
| **TPMS on phone** | `TPMS_ON`, `WAIT` 1000ms | Phone (display) |
| **Rear Camera** | `SEND_KEY` 132 | MotoEye device |
| **Start Recording** | `SEND_KEY` 134 | MotoEye device |
| **Exit Android Auto** | `AA_EXIT` | Phone |
| **Connect AA + Nav** | `AA_CONNECT`, `WAIT` 2000ms | Phone |
| **Resume Music** | `MEDIA_PLAY` | Phone |
| **Pause Music** | `MEDIA_PAUSE` | Phone |
| **Next Track** | `MEDIA_NEXT` | Phone |
| **Custom** | User-defined sequence | Either |

**Macro editor UI:** Create, edit, reorder, delete macros. Each step is a card with type-specific parameters. Presets can be duplicated and modified. Live test button to execute macro immediately.

---

## 3. Architecture

### 3.1 Module Structure

```
app/
  src/main/java/com/motoeye/remote/
    ble/
      BleConnectionManager.kt     # GATT connection, service discovery, handshake
      KeyEventSender.kt           # HID key event packets + JSON command framing
      DeviceScanner.kt            # BLE scan by service UUID fe86
      Protocol.kt                 # UUIDs, key codes, packet builders
      TpmsDataParser.kt           # Parse TPMS JSON from HUD notify characteristic
    service/
      RemoteForegroundService.kt  # Always-on BLE connection + notification
      BootReceiver.kt             # Auto-start on boot
    ui/
      MainActivity.kt             # Full-screen remote UI (Compose)
      SettingsActivity.kt         # Device selection, macro editor, voice config
      RemoteButton.kt             # Reusable button component with haptics
      DPad.kt                     # Directional pad component
      TpmsPanel.kt                # TPMS data display panel
      MediaControlPanel.kt        # Media play/pause/next/prev controls
      MacroListScreen.kt          # Macro list + editor
    notification/
      NotificationHelper.kt       # Foreground notification with actions
    accessibility/
      AutoControlService.kt       # Accessibility Service for Android Auto control
    media/
      MediaSessionListener.kt     # NotificationListenerService for media sessions
      MediaControllerHelper.kt    # MediaController wrapper for transport controls
    macro/
      MacroEngine.kt              # Macro execution engine
      Macro.kt                    # Data model (Room entity)
      MacroRepository.kt          # Persistence
    voice/
      ShortcutRegistrar.kt        # Register Google Assistant voice shortcuts
      RemoteActionReceiver.kt     # Receive shortcut intents → dispatch actions
    tpms/
      TpmsManager.kt              # TPMS request/reporting state machine
      TpmsData.kt                 # Data model (matches decompiled TpmData)
      TpmsFormatter.kt            # Unit conversion + display formatting
```

### 3.2 Key Design Decisions

**Decision 1: Foreground Service vs Bound Service**
→ **Foreground Service**. BLE connections require a persistent process. Android 14+ requires `foregroundServiceType="connectedDevice"` for BLE. The notification provides ongoing status and quick actions.

**Decision 2: Compose vs Views**
→ **Jetpack Compose**. Modern, declarative, better for dynamic layouts. The remote UI is simple enough that Compose overhead is negligible.

**Decision 3: Voice — Google Assistant vs Custom SpeechRecognizer**
→ **Google Assistant Shortcuts**. More reliable in motorcycle noise, zero battery cost when idle, works across phone and Android Auto. PTT button on remote can trigger Assistant via `KEYCODE_HEADSETHOOK`.

**Decision 4: Android Auto — Accessibility Service vs System API**
→ **Accessibility Service**. `CarProjectionManager` is a system API (requires privileged permission). Accessibility Service can inject `KEYCODE_HOME`/`KEYCODE_BACK` to exit projection. Requires user to enable the service in Settings.

**Decision 5: Macro Storage**
→ **Room database**. Structured data with relationships (macro → steps). Enables future macro sharing/import.

**Decision 6: Media Control — MediaSessionManager vs NotificationListenerService**
→ **NotificationListenerService**. `MediaSessionManager.getActiveSessions()` requires `MEDIA_CONTENT_CONTROL` (system permission). NotificationListenerService is available to normal apps and discovers media sessions from media notifications.

**Decision 7: TPMS — Pull vs Push**
→ **Push with pull trigger**. Phone sends `CMD_TPM_DATA` (1032) with `isOn=1` to enable reporting. HUD pushes TPMS updates via notify characteristic. Phone can also send `CMD_TPM_VEHICLE` (1031) to pull sensor addresses on demand.

### 3.3 Service Lifecycle

```
Boot / App Launch
    ↓
RemoteForegroundService.start()
    ↓
BLE Scan (if no saved address) → User selects device → Save address
    ↓
BLE Connect → Service Discovery → Handshake (ping + device info)
    ↓
[Connected State]
    ├── Notification actions → KeyEventSender (camera, record, vol)
    ├── Full-screen UI → KeyEventSender (D-pad, buttons)
    ├── Google Assistant voice → RemoteActionReceiver → KeyEvent/Macro/Media
    ├── Macros → KeyEventSender + Intent launcher + MediaController
    ├── TPMS → TpmsManager → TpmsPanel display
    ├── Media → MediaSessionListener → MediaControllerHelper
    ├── Android Auto → Accessibility Service (exit, media)
    └── AA Connect button → Launch AA projection
    ↓
[Disconnected] → Exponential backoff reconnect (1s, 2s, 4s, 8s, 16s, 30s max)
```

---

## 4. Implementation Tasks

### Task 1: Project Scaffolding
- Create Android project (Kotlin, minSdk 26, targetSdk 34)
- Configure Gradle with Compose, Room, CameraX (if needed)
- Set up module structure
- Add permissions to manifest:
  - `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (Android 12+)
  - `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (Android < 12)
  - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`
  - `RECORD_AUDIO` (voice commands)
  - `WAKE_LOCK` (keep screen on)
  - `RECEIVE_BOOT_COMPLETED`
  - `QUERY_ALL_PACKAGES` (macro app launching)

### Task 2: BLE Protocol Layer
- Implement `Protocol.kt` with all UUIDs and key codes
- Implement packet builders:
  - `buildHidKeyEvent(keyCode: Int, pressed: Boolean): ByteArray` → 6-byte HID packet
  - `buildJsonCommand(type: Int, content: JSONObject?): String` → JSON string
  - `frameCommand(json: String): List<ByteArray>` → header + body packets
- Implement `DeviceScanner.kt` — scan for service UUID `fe86`, return device list
- Unit tests for packet builders

### Task 3: BLE Connection Manager
- Implement `BleConnectionManager.kt`:
  - `connect(address: String)` — GATT connect with 30s timeout
  - `discoverServices()` — find service `fe86`, get all characteristics
  - `performHandshake()` — subscribe notify, read status, send ping + device info
  - `sendKeyEvent(keyCode: Int, pressed: Boolean)` — HID write (protocol v5) or JSON (protocol < 4)
  - `disconnect()` — clean teardown
  - Auto-reconnect with exponential backoff
  - Connection state callback interface
- Handle protocol version detection (HID vs JSON mode)

### Task 4: Foreground Service
- Implement `RemoteForegroundService.kt`:
  - `onStartCommand` → start foreground with notification
  - Manage `BleConnectionManager` lifecycle
  - Handle start/stop/reconnect intents
  - Expose connection state via `StateFlow`
- Implement `BootReceiver.kt` — start service on boot if device address saved
- Implement battery optimization exemption prompt

### Task 5: Full-Screen Remote UI
- Implement `MainActivity.kt` (Compose):
  - Landscape orientation, keep screen on
  - D-pad (up/down/left/right) with center OK button
  - Back button
  - Camera button (rear view toggle)
  - Record button (start/stop)
  - Volume up/down
  - Optional PTT/voice button (toggleable in Settings)
  - Connection status indicator
  - TPMS panel (collapsible)
  - Media control panel (toggleable)
- Implement `RemoteButton.kt` — large touch target (≥64dp), haptic feedback on press/release
- Implement `DPad.kt` — directional pad with visual feedback
- **Long-press detection on center button**: hold ≥ 500ms → trigger voice command (configurable)
- Gesture support: swipe for volume, long-press for secondary action
- Layout modes: minimal (D-pad only), full (all buttons), media (media controls prominent)

### Task 6: Notification Quick Actions
- Implement `NotificationHelper.kt`:
  - Foreground notification with channel `remote_status`
  - Content text: connection status + device name
  - Actions:
    - Rear Camera (sends key 132)
    - Record (sends key 134, toggles icon)
    - Vol+ (sends key 24)
    - Vol− (sends key 25)
    - Exit Android Auto (sends KEYCODE_HOME via Accessibility Service)
  - Tap → open `MainActivity`
- Update notification on connection state changes

### Task 7: Google Assistant Voice Integration
- Implement `ShortcutRegistrar.kt`:
  - Register voice-triggered shortcuts via `ShortcutManagerCompat`
  - Phrases: "MotoEye camera", "MotoEye record", "MotoEye EUC World", "MotoEye TPMS", "MotoEye pause", "MotoEye next", etc.
  - Each shortcut maps to an intent action
- Implement `RemoteActionReceiver.kt`:
  - Receive shortcut intents
  - Dispatch to appropriate handler (key event, macro, media control, AA control)
- Define shortcut XML (`shortcuts.xml`) for App Actions capability binding
- Register shortcuts on app install and device connection

### Task 8: Android Auto Control
- Implement `AutoControlService.kt` (Accessibility Service):
  - Service config: `canRetrieveWindowContent=false`, `canPerformGestures=true`
  - `exitAndroidAuto()` — inject `KEYCODE_HOME` via `performGlobalAction`
  - `pressBack()` — inject `KEYCODE_BACK`
  - `launchAndroidAuto()` — send intent `com.google.android.projection.gearhead.START`
- Implement AA Connect button in UI and notification
- Add accessibility service settings screen with enable/disable toggle

### Task 9: Media Controls
- Implement `MediaSessionListener.kt` (NotificationListenerService):
  - Discover active media sessions from media notifications
  - Track last active session (package name + token)
  - Expose session state via StateFlow
- Implement `MediaControllerHelper.kt`:
  - Wrap `MediaController` for transport controls
  - `play()`, `pause()`, `togglePlayPause()`, `next()`, `previous()`, `resumeLast()`
- Add media control buttons to full-screen remote UI (toggleable layout)
- Add media control actions to notification
- Wire voice commands: "pause" → `MEDIA_PAUSE`, "next" → `MEDIA_NEXT`, etc.

### Task 10: TPMS Display
- Implement `TpmsData.kt` (matches decompiled `TpmData`):
  - Fields: id, position, pressure, temperature, voltage, rssi, vehicleID
  - Position constants: LF=0, LR=1, RF=2, RR=3
- Implement `TpmsManager.kt`:
  - `requestVehicleData()` — send `CMD_TPM_VEHICLE` (1031)
  - `enableReporting()` — send `CMD_TPM_DATA` (1032) with `isOn=1`
  - `disableReporting()` — send `CMD_TPM_DATA` (1032) with `isOn=0`
  - Parse TPMS JSON from notify characteristic
  - Expose TPMS data via StateFlow
- Implement `TpmsFormatter.kt`:
  - Unit conversion: Bar/Psi/KPa, Celsius/Fahrenheit
  - Battery percent from voltage (2.55V→0%, 2.9V→100%)
  - Alert thresholds (pMax, pMin, tMax, vMin)
- Implement `TpmsPanel.kt` (Compose):
  - Collapsible panel showing per-wheel data
  - Vehicle type layout (motorcycle, unicycle, trike, ATV)
  - Alert coloring for out-of-range values
- Add TPMS toggle to notification actions

### Task 11: Macro Engine
- Implement `Macro.kt` (Room entity):
  - `id`, `name`, `description`, `steps` (JSON array)
  - Step types: `SEND_KEY`, `WAIT`, `LAUNCH_APP`, `SEND_INTENT`, `AA_EXIT`, `AA_CONNECT`, `MEDIA_PLAY`, `MEDIA_PAUSE`, `MEDIA_NEXT`, `MEDIA_PREV`, `TPMS_ON`, `TPMS_OFF`
- Implement `MacroEngine.kt`:
  - `execute(macro: Macro)` — run steps sequentially
  - Step executor: dispatches to KeyEventSender, MediaController, Accessibility Service, or Intent launcher
  - Cancellation support
- Implement `MacroRepository.kt` — CRUD operations
- Implement `MacroListScreen.kt` (Compose):
  - List of macros with edit/delete
  - Create new macro with step editor
  - Live test button
  - Import/export macros as JSON
- Create preset macros:
  - "EUC World" → SEND_KEY back, WAIT, SEND_KEY right×N, SEND_KEY enter
  - "TPMS on HUD" → SEND_KEY navigation sequence
  - "TPMS on phone" → TPMS_ON
  - "Rear Camera" → SEND_KEY 132
  - "Start Recording" → SEND_KEY 134
  - "Exit Android Auto" → AA_EXIT
  - "Connect AA" → AA_CONNECT
  - "Resume Music" → MEDIA_PLAY
  - "Pause Music" → MEDIA_PAUSE
  - "Next Track" → MEDIA_NEXT

### Task 12: Settings & Device Management
- Implement `SettingsActivity.kt`:
  - Device selection (BLE scan + connect)
  - Saved device display + forget device
  - Voice command configuration (shortcut list)
  - Macro editor (create/edit/delete macros)
  - Accessibility service status + link to system settings
  - Notification listener service status + link to system settings
  - Notification action customization
  - TPMS display toggle
  - Media controls toggle
  - Protocol version display
  - Connection diagnostics (log view)

### Task 13: Integration & Testing
- End-to-end test: connect to MotoEye device, send each key, verify HUD responds
- Test coexistence: connect hardware remote + app simultaneously
- Test notification actions while app is backgrounded
- Test Google Assistant voice commands (phone and Android Auto)
- Test Android Auto connect/exit via Accessibility Service
- Test media controls with Amazon Music, YouTube, Spotify
- Test TPMS data reception and display
- Test macro execution (key sequences, app launches, media controls)
- Test auto-reconnect after Bluetooth restart
- Test boot receiver
- Test resume-where-left-off media playback

---

## 5. Permissions Summary

| Permission | Purpose | Android Version |
|-----------|---------|-----------------|
| `BLUETOOTH_CONNECT` | GATT connection | 12+ |
| `BLUETOOTH_SCAN` | BLE device scanning | 12+ |
| `BLUETOOTH` + `BLUETOOTH_ADMIN` | Legacy BLE | < 12 |
| `ACCESS_FINE_LOCATION` | BLE scan (Android < 12) | < 12 |
| `FOREGROUND_SERVICE` | Persistent service | 28+ |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | BLE foreground service type | 34+ |
| `WAKE_LOCK` | Keep screen on | All |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on boot | All |
| `VIBRATE` | Haptic feedback | All |
| `QUERY_ALL_PACKAGES` | Macro app launching | All |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Media session discovery | All |
| `MEDIA_CONTENT_CONTROL` | (System only — NOT requested; using NotificationListenerService instead) | — |

**Special permissions (user must enable in Settings):**
- **Notification Listener** — required for media session discovery. User grants via Settings → Apps → Special access → Notification access.
- **Accessibility Service** — required for Android Auto control. User grants via Settings → Accessibility.

---

## 6. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Protocol v5 HID writes may require prior handshake | Connection fails | Implement full handshake before HID writes; fallback to JSON mode |
| HUD may reject second BLE Central (multi-phone limit) | Connection refused | Detect connect count > 1; prompt user to disconnect other phone |
| Accessibility Service may not work with Android Auto projection | Exit AA fails | Fallback: launch Settings intent for manual disconnect |
| Google Assistant shortcuts not discoverable on Android Auto | Voice commands fail on AA | Register shortcuts early; test with AA simulator; fallback to phone-only voice |
| NotificationListenerService killed by OS | Media controls fail | Restart service on media notification; use foreground service priority |
| TPMS data format varies by firmware version | Parsing fails | Version-gate parsing; graceful degradation |
| Macro navigation (right×N) depends on MotoEye launcher layout | Wrong app launched | Configurable N per user; live test button; document layout assumptions |
| Android 14+ foreground service restrictions | Service killed | Use `connectedDevice` type; request battery optimization exemption |
| Physical remote and app send conflicting keys | Unexpected HUD behavior | HUD multiplexes; both can coexist (confirmed from APK analysis) |

---

## 7. Success Criteria

1. App connects to MotoEye device via BLE and sends HID key events
2. Full-screen UI responds to touch with haptic feedback
3. Notification actions work while app is backgrounded
4. Google Assistant voice commands trigger remote functions and macros
5. Android Auto connects and exits on command
6. Media controls (play/pause/next/prev) work with Amazon Music, YouTube, Spotify
7. "Resume" restores playback where last paused
8. TPMS data received via BLE and displayed with alerts
9. Macros execute correctly (key sequences on MotoEye device, app launches, media controls)
10. App auto-reconnects after Bluetooth restart or out-of-range
11. App coexists with hardware remote (both connected to HUD simultaneously)
12. App survives device reboot (boot receiver)
