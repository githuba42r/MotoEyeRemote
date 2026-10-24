# Google Play listing — MotoEye Remote

Package `com.a42r.motoeye.remote` · default language English (United States) – en-US · prepared 2026-10-24.
The graphics are in this folder, rebuilt by `make-assets.sh <raw-screencap-dir>`.

## Graphics

| Console field | File | Spec |
|---|---|---|
| App icon | `icon-512.png` | 512×512 32-bit PNG, full bleed (Play rounds the corners) |
| Feature graphic | `feature-graphic.png` | 1024×500 24-bit PNG |
| Phone screenshots (upload in this order) | `screenshots/phone-1.png` … `phone-6.png` | 1080×1920 (9:16) 24-bit PNG |

Screenshot order and captions:
1. Big keys. Gloves on. — main remote
2. Tyre pressure, live — TPMS readout close-up
3. One-tap macros — macro list
4. Works from the lock screen — connection and controls settings
5. Tuned for the ride — media, battery, voice, camera-delay settings
6. Connects on its own — connect screen

Tablet screenshots are optional and can be skipped; the app is phone-first.

## Store listing

**App name** (30 max)

```
Remote for MotoEye E6 HUD
```

Why not "MotoEye Remote": "MotoEye" is someone else's brand. Play's impersonation and intellectual-property policies flag app titles that read as the official product. "Remote for … HUD" states compatibility, and it is also the phrase people search for. The in-app label can stay "MotoEye Remote"; only the listing title is checked this strictly.

**Short description** (80 max)

```
Glove-friendly phone remote for the MotoEye E6 HUD: D-pad, camera, DVR and TPMS
```

**Full description** (4000 max)

```
Turn your Android phone into a big, glove-friendly remote for the MotoEye E6 motorcycle head-up display.

The remote that comes with the HUD is small and fiddly with riding gloves on. Remote for MotoEye E6 HUD puts every key on a full-screen layout you can hit without looking twice, on a phone mounted where you can reach it.

WHAT YOU GET
• Full-screen D-pad with OK, BACK and volume, sized for gloved thumbs, with haptic feedback
• CAM button: flip the HUD to the rear-view camera with one tap
• REC button (optional): start and stop background DVR / dash-cam recording
• Live tyre pressure (TPMS): pressure, temperature and sensor battery read straight from MotoEye TPMS sensors over Bluetooth, even on HUD firmware that hides its own TPMS screen
• Macros: record a key sequence once — open an app on the HUD, jump to a screen — and replay it with one tap
• Lock-screen pad (optional): D-pad, camera and BACK above the lock screen, so you can drive the HUD without unlocking your phone
• Media keys: play, previous and next for whatever music or podcast app is playing
• Exit Android Auto with one tap, and Google Assistant shortcuts for voice commands
• Auto-reconnect: switch the HUD off at a stop and back on, and the remote reconnects by itself
• A status notification with Camera and BACK actions, one swipe away

HOW IT WORKS
The phone joins the HUD over Bluetooth as a second remote, alongside the one in the box — you can keep using both. Nothing on the HUD is changed, and you don't need the official MotoEye app running.

PRIVATE BY DESIGN
• No account, no ads, no analytics, no tracking
• Nothing is sent off your phone — the app only talks to your HUD and tyre sensors over Bluetooth
• Your settings and macros stay on your device

ABOUT PERMISSIONS
Only Nearby devices (Bluetooth) is required. The rest are optional and switch on specific features:
• Notifications — the connected-status notification
• Battery optimisation exemption — keeps reconnects working with the screen off
• Notification access — finds the playing media app for the media keys
• Accessibility services — used only to show the remote pad on the lock screen and to perform the Home action for "Exit Android Auto". They cannot read your screen content and collect no data.
The setup guide explains each one: https://motoeye-remote.a42r.com/setup

REQUIREMENTS
• A MotoEye E6 HUD (other Vidoar-based MotoEye HUDs may work but are untested)
• Android 8.0 or later with Bluetooth LE

Use the remote only when it is safe and legal to do so where you ride.

This is an independent app. It is not made by, endorsed by or affiliated with MotoEye or Vidoar. MotoEye is a trademark of its owner.
```

**App category:** Auto & Vehicles
**Tags** (choose up to 5 in the console): Auto & vehicles · Remote control · Motorcycle · Navigation & maps accessory · Utilities (pick the closest ones Play offers)

**Contact details**
- Email: `github@a42r.com`
- Website: `https://motoeye-remote.a42r.com/`
- Phone: leave blank (optional)

**Privacy policy URL:** `https://motoeye-remote.a42r.com/privacy`

## Release notes — first internal release (500 max)

```
First Google Play release.
• Full-screen, glove-friendly remote for the MotoEye E6 HUD
• Rear camera and DVR recording, live TPMS, macros
• Lock-screen pad, media keys, Exit Android Auto
• Auto-reconnect with a status notification
```

Later releases get their notes from the commit subjects (the `play` job in `release.yml`).

## App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | `https://motoeye-remote.a42r.com/privacy` |
| Ads | No, my app does not contain ads |
| App access | **All or some functionality is restricted.** Add instructions: "The app controls a MotoEye E6 motorcycle HUD over Bluetooth, and its main screens appear only once it is connected to that hardware. No login is required. A demo video of the app connected to the HUD is at <YouTube URL>." (Reviewers can't pair a HUD, so the video is what they assess.) |
| Content rating (IARC) | Category **Utility, productivity, communication or other**. Answer No to violence, sexuality, language, controlled substances, gambling, user interaction/sharing, location sharing and digital purchases. The expected result is Everyone / PEGI 3. |
| Target audience | **18 and over** only (riders of road vehicles). This keeps the app out of the Families policy. Answer "No" to "appeals to children". |
| News app | No |
| COVID-19 contact tracing | No |
| Data safety | Collects data: **No**. Shares data: **No**. Settings, macros and the HUD's Bluetooth address stay on the device and are never transmitted, so they don't count as "collected" under Play's definition. Encryption in transit: not applicable (no data is transmitted). Deletion: clearing app storage or uninstalling removes everything. |
| Government app | No |
| Financial features | None |
| Health | No health features |

## Permission declarations

### Accessibility API (required — two services)

Play's form asks for a description and a video, and requires an **in-app prominent disclosure** that the user accepts before the app opens Android's accessibility settings.

✅ **Done in v1.3.0.** `SettingsScreen.kt` shows an `AccessibilityDisclosureDialog` before opening `ACTION_ACCESSIBILITY_SETTINGS`. Only "Agree" opens the settings; "Not now", back or tapping outside declines. The demo video is `motoeye-accessibility-disclosure.mp4`, recorded 2026-10-24. The dialog text in the app follows the suggestions below.

Suggested disclosure, lock-screen pad:
```
Lock-screen pad uses Android's Accessibility Service API

To show the D-pad, rear camera and BACK buttons above your lock screen, MotoEye Remote needs its "Lock screen pad" accessibility service. The service only draws those buttons. It cannot read what is on your screen, it ignores all accessibility events, and it collects and shares no data. You can turn it off at any time in Android's accessibility settings.
```

Suggested disclosure, Exit Android Auto:
```
Exit Android Auto uses Android's Accessibility Service API

To leave Android Auto with one tap, MotoEye Remote needs its "Android Auto / Exit AA" accessibility service, which performs Android's Home action when you press the button. It cannot read what is on your screen, it ignores all accessibility events, and it collects and shares no data. You can turn it off at any time in Android's accessibility settings.
```

Declaration form answers:
- **Is the app an accessibility tool (isAccessibilityTool)?** No.
- **Core functionality:**
  ```
  Two optional services. "Lock screen pad" draws a TYPE_ACCESSIBILITY_OVERLAY with D-pad, rear-camera and BACK buttons above the keyguard so a motorcycle rider can control the paired HUD without unlocking the phone; this is the only API that can show controls over the lock screen. "Android Auto / Exit AA" calls performGlobalAction(GLOBAL_ACTION_HOME) when the rider presses the Exit AA button. Both services set canRetrieveWindowContent=false, handle no accessibility events, and collect no data.
  ```
- **Video:** screen recording showing the disclosure → accepting → enabling the service → the lock-screen pad in use.

### Foreground service (connectedDevice)

```
RemoteForegroundService (foregroundServiceType="connectedDevice") holds the Bluetooth LE GATT connection and the HID keyboard link to the user-paired MotoEye HUD while the rider uses the remote, including with the screen off, and reconnects automatically when the HUD drops out. A persistent notification shows the connection state with Camera and BACK actions. It starts when the user connects to their HUD and stops when the connection is given up or the user disconnects.
```
Video: connect to the HUD → notification appears → lock the phone → press Camera in the notification.

### Battery optimisation exemption (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)

Acceptable-use justification (companion app for a user-paired Bluetooth device):
```
The app is the controller for a user-paired MotoEye HUD. Doze would suspend the Bluetooth reconnect logic during a ride with the screen off, leaving the rider without camera and BACK controls until they unlock the phone. The exemption is requested from an explicit Settings row with an explanation, never automatically.
```
If Play rejects this declaration, the fallback is to drop the permission and send users to the system battery settings page (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), which needs no declaration.

### Other

- `INTERNET` is declared but unused. Removing it before the first upload simplifies the Data safety review. (The privacy policy already says the app makes no network connections.)
- `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE`, `POST_NOTIFICATIONS`: no declaration needed.

## Before you submit

- [x] Add the accessibility prominent-disclosure dialogs (app change, v1.3.0)
- [x] Record the demo videos: `motoeye-accessibility-disclosure.mp4` (disclosure → Agree → enable) and `motoeye-foreground-service.mp4` (connect → ongoing notification → Camera action). Upload both to YouTube as unlisted.
- [ ] Decide on the listing title (recommended: "Remote for MotoEye E6 HUD")
- [ ] Optional: remove the unused `INTERNET` permission
- [ ] Fix the landscape layout overlap, or lock the remote screen to portrait. Reviewers on tablets and foldables will see landscape.
