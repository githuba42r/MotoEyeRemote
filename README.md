# MotoEyeFullScreenRemote

[![Latest release](https://img.shields.io/github/v/release/githuba42r/MotoEyeRemote.svg)](https://github.com/githuba42r/MotoEyeRemote/releases/latest)

## Download

Grab the latest signed APK from
https://github.com/githuba42r/MotoEyeRemote/releases/latest.

Full-screen Android remote (`com.motoeye.remote`) that replaces the small physical
BLE remote shipped with the MotoEye E6 motorcycle HUD. The phone connects to the
HUD as a second Vidoar-protocol remote over BLE and presents a glove-friendly
full-screen UI: D-pad + OK, BACK, volume hold-keys, camera trigger, record
toggle, macro automation, and a live TPMS readout.

This README documents the reverse-engineered Bluetooth protocols used for the
**phone → HUD remote channel** and the **Motoeye TPMS sensors**, since neither
is publicly documented.

## Reconstructing the remote protocol

Source of truth was the decompiled MotoEye APK (`com.vidoar.motohud`
v1.0.2604141705); every structural detail below was confirmed live against real
hardware.

### GATT services and characteristics

| Item | UUID | Notes |
|---|---|---|
| Service | `0000fe86-0000-1000-8000-00805f9b34fb` | Vidoar remote service |
| Write | `00000012-b100-...` | Commands, `WRITE_TYPE_NO_RESPONSE` |
| Notify | `00000011-b100-...` | HUD → phone JSON messages |
| Read | `00000013-b100-...` | Status byte handshake |
| HID input | `00000014-b100-...` | HID raw key reports (notify) |

### Connection handshake

1. Connect (autoConnect) and read the `0013` status characteristic.
2. Send ping `{"type":1}`.
3. Request device info: `{"type":1000,"data":"host"}`.

The device info reply (`type:1000`) is the only place the HUD battery is
exposed — an ASCII string field `"power":"86"` meaning percent. The HUD never
pushes it spontaneously, so the app polls device info every 45 s while connected
and renders `NN%` next to the connection status.

### JSON command types decoded from the APK

| Type | Constant | Purpose | Works on tested firmware? |
|---|---|---|---|
| `1` | `PING` | Keep-alive | yes |
| `1000` | `DEVICE_INFO` | Device info + HUD battery (`"power"` field) | yes |
| `8001` | `KEY_CLICK` | `{"key":<keycode>}` — single key event | yes (nav keys) |
| `8002` | `KEY_EVENT` | `{"key":<keycode>,"isUp":<bool>}` — press/release | yes (nav keys) |
| `1029` | `REARVIEW_MIRROR` | Display the rear camera feed | yes (unverified in normal use) |
| `1030` | `CMD_CMAERA_KEY` | Camera key trigger | yes, but HID channel preferred |
| `1031` | `TPM_VEHICLE` | Request TPMS vehicle data | **no** — firmware-gated, HUD silently ignores |
| `1032` | `TPM_DATA` | Enable/disable TPMS data report | **no** — firmware-gated |
| `2001`–`2003` | `CONTROLLER_*` | Bluetooth controller accessory (scan/bond/info) | accessory-only (type `2002` reports a controller's battery, NOT the HUD's) |

### Dual control channels (the key architectural finding)

The HUD exposes **two independent control channels over the same GATT link**,
and the physical remote uses both:

**Channel A — Vidoar JSON/reflective channel** (`0012` writes with the JSON
framing above). Key events travel as `KEY_CLICK`/`KEY_EVENT` JSON commands and
are interpreted *inside the MotoEye HUD app*. Reliable for the navigation keys
(arrows, OK/Enter, Back) and volume-style keys whose Android keycodes the app
maps to UI actions (DPAD 19–22, Enter 66, Back 4, Vol 24/25, media 85/87/88).

**Channel B — HOGP HID keyboard channel (the physical-remote path)**. The HUD
runs an Android **HID host**. A companion device can register itself as a
standard BLE keyboard via `BluetoothHidDevice` (the remote shim does exactly
this) and its reports arrive as *system-level* Android key events — no MotoEye
app involvement. The physical remote's CAM/REC/PTT buttons only work through
this channel, which is why the app registers the phone as a HID keyboard
(report ID 1, 6-KRO descriptor) and attaches it to the HUD. Practical details
learned the hard way:

- Vendor/F-key codes (PTT 131, Record 133/134, Camera 132) do **not** trigger
  their hardware functions when sent through channel A; only the HID channel
  reaches the DVR/camera stack. (The app's "legacy record keycode" fallback
  was removed after this was proven.)
- Attach timing matters: attaching the HID keyboard while the GATT link is
  still settling caused intermittent dual-link drops. Attach is therefore
  staggered after connection — user-tunable 0–30 s, default 5 s — and the
  attach/retry loop persists through transient HOGP failures.

### Camera and DVR recording — two behaviours on one key (F2)

A live HID probe on the physical remote showed its **CAM button emits F2**
(keyboard usage `0x3B`). The HUD treats that one key differently by press
duration:

| Gesture | Duration | HUD effect |
|---|---|---|
| Short tap (F2 down → up ≈ 60 ms) | < ~900 ms | Toggles the **rear-view camera** display on/off (video flip to the backup camera) |
| **Hold** F2 ≈ 1100 ms then release | 1100–1200 ms | Toggles **background DVR recording** on/off |

Calibration measured while riding: 900 ms hold = no reaction (identical to
nothing); 1200 ms = toggled recording; the working threshold sits at ~1100 ms.
DVR recording runs in the background (files to HUD storage) and the HUD
exposes no record-state field — the app keeps its own `isRecording` toggle to
flip the REC/STOP button label.

The app therefore sends record toggles as `sendUsage(0x3B, 1100L)` (press,
wait 1100 ms, release) on the HID channel; camera = the same usage with a
60 ms hold. Fallback if the HID channel is not attached: raw keycodes
`RECORD = 134` (134/F4 on this firmware, `133`/F3 on M3/M3S) via
`KEY_CLICK` — degraded path only, since it is not the same event stream the
HUD really consumes.

### JSON message framing (both directions)

Messages are chunked with a small header; the receive side mirrors the send
side (ported from the APK's `MessageReceiver02`):

- Header (4 bytes): `pkgCount & 0x7F`, payload length (little-endian 16-bit), `0`, `0`
- Body, per chunk: first byte `idx | 0x80` then up to 19 payload bytes

### HID key packet (device-info `proVer: 5`)

```
[0x00, pressed (1/0), usageLo, usageHi, 0x00, 0x00]
```

Our app reproduces these reports when the phone is attached to the HUD as a
BLE HID keyboard — see next section — and falls back to raw `0012` writes for
key events otherwise.

Key code map (Android keycode ↔ HID usage):

| Action | Android | HID usage |
|---|---|---|
| Up / Down / Left / Right | 19 / 20 / 21 / 22 | 0x52 / 0x51 / 0x50 / 0x4F |
| OK / Enter | 66 | 0x28 |
| Back | 4 | 0x29 (ESC) |
| Vol+ / Vol− (remote) | 24 / 25 | 0x52 / 0x51 (Up / Down) |
| CAM (rear camera / DVR toggle) | 132 | 0x3B (F2) |
| Record (M3+ firmware) | 133 | F3 equivalent |
| Record / DVR (other firmware) | 134 | F4 equivalent |
| PTT / headset hook | 131 | 0x3A (F1) |
| Play / Pause / Next / Prev | 85 / 87 / 88 | consumer usage page |

Observed HUD behaviours (hardware timing calibration):

- **Short F2 press** = rear-view camera toggle.
- **Held F2 ≈ 1100 ms** = toggle background recording (900 ms does nothing;
  1200 ms toggles). The app enforces a minimum hold on REC.
- **Vol+ / Vol− minimum hold 2000 ms**: the HUD needs ~1 s of it; anything
  below reads as a click. Long-press repeat is emitted while held.
- On the tested HUD firmware `V1.0.2509191441`, the HUD battery and TPMS
  relay commands are firmware-gated — see the TPMS section.

### Phone as BLE HID keyboard

`BluetoothHidDevice` registers the phone as a keyboard (report ID 1, 6KRO
descriptor) and attaches it to the HUD's HID host. This channel is used for
camera/record/vendor keys and macro automation (`hidUsageFor()` maps Android
keycodes to HID usages). Attach is **staggered after connection** (user-tunable
0–30 s delay, default 5 s): attaching too early while the GATT link settles
caused intermittent dual-link drops.

## Motoeye TPMS sensor BLE protocol (fully decoded, live-validated)

### Why the app reads the sensors directly

The MotoEye APK contains a full TPMS relay path: the HUD pairs to the sensors
and the app asks for the relayed data with JSON commands `TPM_VEHICLE` (1031)
and `TPM_DATA` (1032, wired to `XBluetoothManager.switchTpmDataReport`). That
whole path is **firmware-gated**: the MotoEye app itself hides the TPMS UI
below `TPM_SUPPORTS_VERSION = "2512121814"`, and the tested HUD runs
`V1.0.2509191441` — commands 1031/1032 are silently ignored (verified multiple
times live). The HUD in-UI TPMS screen is also part of thatgate. The sensors
themselves are plain BLE advertisers, so the app skips the HUD entirely and
decodes their advertisements with the format below (same reader the EUC World
2.66 app uses for the same hardware — cross-validated).

### Transport & advertisement shape

| Property | Value |
|---|---|
| Link | Plain BLE advertising (`ADV_IND`), **no connection, no pairing, no GATT** |
| Local name | `VD` (all Motoeye TPMS sensors) |
| Manufacturer data company id | `0x3000` |
| Manufacturer payload | exactly 4 bytes (decode below) |
| Example sensor address | `B9:42:FB:00:02:A6` |

The sensors are **sleep-on-idle** devices: they transmit on pressure change,
motion, and periodic timed wakes. Between transmissions they are silent (not
connectable-not-useful), so passive scanning is the only viable read model.
The app therefore runs 12-second scan windows, caches the latest reading per
MAC address, and prunes readings older than 30 minutes so stale batteries or
removed sensors age out of the UI.

### Payload decode (company `0x3000`, 4 bytes)

```
b0  battery raw        volts  = b0 * 0.01 + 1.22
                       percent: 3.00 V ⇒ 100 %
b1  temperature raw    °C = b1 - 50
b2  absolute pressure kPa, big-endian high byte
b3  absolute pressure kPa, big-endian low byte
       gauge kPa = abs kPa - 100
       psi       = gauge kPa * 0.145037
```

Worked example (captured live 2026-10-15, sensor `B9:42:FB:00:02:A6`):

```
manufacturer payload = b0 49 01 36

b0 = 0xB0 = 176 → volts  = 176 * 0.01 + 1.22 = 2.98 V ≙ 100 %
b1 = 0x49 = 73  → temp   = 73 - 50           = 23.0 °C
b2,b3 = 0x01,0x36 → abs  = 0x0136 = 310 kPa
                   gauge = 310 - 100        = 210 kPa
                   psi   = 210 * 0.145037   = 30.45 psi
```

Which decoded to a healthy tyre at 30.45 psi / 23 °C / full battery — the
values the physical gauge showed. The pressure is **gauge-referenced within
the byte range**: the sensor reports absolute kPa (ambient ≈ 100 kPa offset
built in), hence the `- 100` to get the number a tyre gauge shows.

### Implementation notes

- Scanner filter: require local name `VD` **and** manufacturer company id
  `0x3000` (matches nothing else on the bike; no false positives seen).
- The 12 s duty cycle plus cached overlays keeps the mini-readout on the
  remote screen and the full TPMS overlay fed without a permanent Bluetooth
  LE scan (background-scan-limit friendly).
- Battery percent is derived from the voltage curve (3.0 V = 100 %), not a
  separate percentage field — the payload has no explicit percentage byte.

## App behaviour notes

- **Macro engine**: seeded recipes ("EUC World", "Headset settings",
  "Rear Camera") express each step as key + delay-after, and a key + hold
  duration for hold semantics; run is debounced to one macro per 30 s with a
  repeating `Running: <name>` toast until completion.
- **Disconnected UX**: every key-sending button dim-disables when the HUD BLE
  link drops (CAM/REC need the HID channel too and report `OFF`/`HID…`).
- **Reconnect**: on link loss the app retries directly, then Watchdog-style
  presence checks of the paired HUD (log-driven) every few seconds — no
  continuous background scanning.

## Layout

- `app/src/main/java/com/motoeye/remote/macro/` — macro engine and Room DB
- `app/src/main/java/com/motoeye/remote/tpms/` — direct OBD TPMS reader
- `app/src/main/java/com/motoeye/remote/ble/` — Vidoar GATT client + key sender
- `app/src/main/java/com/motoeye/remote/hid/` — phone-side HID keyboard
- `app/src/main/java/com/motoeye/remote/ui/` — full-screen remote UI + settings
- `docs/superpowers/plans/` — dev plans
- Decompiled reference sources (external, not in repo):
  `/tmp/opencode/motoeye/decompiled/sources/` — MotoEye APK
  (`MessageReceiver02.java` is the framing port source,
  `HostFragment.java` shows the `"power"` render),
  `/tmp/opencode/eucworld/dec/sources/` — EUC World TPMS decoder reference.
