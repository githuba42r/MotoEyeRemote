# MotoEyeFullScreenRemote

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

| Action | Android | HID |
|---|---|---|
| Up/Down/Left/Right | 19/20/21/22 | 0x52/0x51/0x50/0x4F |
| OK / Enter | 66 | 0x28 |
| Back | 4 | 0x29 |
| Vol+ / Vol− | 24 / 25 | 0x4E / 0x4A |
| Camera (F1/CAM) | 132 | 0x81-ish vendor key |
| Record / PTT | 134 / 131 | F2 / vendor |

Observed HUD behaviours (hardware timing calibration):

- **Short F2 press** = rear-view camera toggle.
- **Held F2 ≈ 1100 ms** = toggle background recording (900 ms does nothing;
  1200 ms toggles). The app enforces a minimum hold on REC.
- **Vol+ / Vol− minimum hold 2000 ms**: the HUD needs ~1 s of it; anything
  below reads as a click. Long-press repeat is emitted while held.
- The record/HID keys (F1/F2/PTT) only flow through the HID keyboard channel
  in newer firmware; the remote widget uses `keycode`-style falls-back via
  `0012` writes for navigation keys only.

### Phone as BLE HID keyboard

`BluetoothHidDevice` registers the phone as a keyboard (report ID 1, 6KRO
descriptor) and attaches it to the HUD's HID host. This channel is used for
camera/record/vendor keys and macro automation (`hidUsageFor()` maps Android
keycodes to HID usages). Attach is **staggered after connection** (user-tunable
0–30 s delay, default 5 s): attaching too early while the GATT link settles
caused intermittent dual-link drops.

## Motoeye TPMS sensor BLE format (validated live)

The Motoeye TPMS sensors are broadcast-only BLE devices (no pairing) that also
sleep between transmissions — they wake and advertise on pressure change,
motion, and periodic timers. The app scans with a 12 s duty-cycle window and
caches readings; stale readings are pruned after 30 minutes.

Advertisement shape:

- Local name: **`VD`** (all Motoeye TPMS sensors)
- Manufacturer data, company id **`0x3000`**, 4 payload bytes:

```
byte 0: battery raw          volts = raw * 0.01 + 1.22  (3.0 V = 100 %)
byte 1: temperature raw      °C = raw - 50
byte 2: absolute pressure kPa, big-endian high byte
byte 3: absolute pressure kPa, big-endian low byte
         gauge kPa = abs kPa - 100
         psi = gauge * 0.145037
```

Live example captured 2026-10-15, sensor `B9:42:FB:00:02:A6`,
manufacturer payload `b0 49 01 36` → **2.98 V / 100 %**, **23.0 °C**,
**abs 310 kPa → gauge 210 kPa → 30.45 psi**.

The decoder matches the one used by EUC World 2.66 for the same sensors
("VD" / company `0x3000`), which was used as cross-validation.

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
