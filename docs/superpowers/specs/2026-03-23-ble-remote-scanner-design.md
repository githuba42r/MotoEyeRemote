# MotoEye E6 BLE Remote Scanner — Design Spec

## Overview

A Python console tool that connects to a MotoEye E6 BLE remote, captures all key press events, and maintains an interactive catalog of known/unknown key mappings. This tool serves as a reverse-engineering aid: the captured BLE advertisement data, GATT profile, and key map will feed directly into the Android app that replicates the remote's functionality.

## Context

The MotoEye E6 remote is a BLE Peripheral that advertises when a button is pressed. The MotoEye unit acts as the Central, scanning for and connecting to the remote. The remote likely includes custom data in its advertisement packets (manufacturer-specific data, service UUIDs, or device name patterns) that the MotoEye uses to identify it.

The remote has approximately 7 physical buttons producing 10 distinct actions via short and long press differentiation:

| Physical Action         | Function                  |
|-------------------------|---------------------------|
| D-pad Up (short)        | Up                        |
| D-pad Down (short)      | Down                      |
| D-pad Left (short)      | Left                      |
| D-pad Right (short)     | Right                     |
| D-pad Center (short)    | Connect/Select            |
| Top button (short)      | Rear View Camera          |
| Top button (long)       | Start/Stop Recording      |
| Bottom button (short)   | Back                      |
| D-pad Up (long)         | Volume Up                 |
| D-pad Down (long)       | Volume Down               |

It is unknown whether long press is differentiated by the remote (distinct BLE values) or by the receiver (timing-based). The tool will determine this.

## Design

### 1. BLE Discovery & Connection

The tool scans for BLE devices using `bleak` and displays all discovered devices with name, MAC address, RSSI, and any manufacturer data or service UUIDs in the advertisement. The user selects the remote from the list.

Once selected, the tool proceeds through two phases:

**Phase 1 — Advertisement Monitoring:**

Before attempting a connection, the tool passively monitors advertisements from the selected device for a configurable period (default 10 seconds) while the user presses buttons. This determines whether the remote communicates key data via changing advertisement payloads (some BLE remotes work this way without ever establishing a GATT connection). The tool logs:

- Raw manufacturer data bytes per advertisement
- Service UUIDs
- Local name
- RSSI
- Whether the advertisement is connectable or non-connectable

If advertisement payloads change with button presses, the tool enters advertisement-only monitoring mode (the remote communicates via advertisements, not GATT).

If advertisements are non-connectable, the tool stays in advertisement monitoring mode and informs the user that GATT connection is not possible.

**Phase 2 — GATT Connection (if advertisements are connectable and static):**

1. Connects to the remote as a Central
2. Handles pairing/bonding via BlueZ if required
3. Discovers all GATT services and characteristics
4. Prints a summary of the GATT profile (services, characteristics, descriptors, properties)

Both the advertisement data and the GATT profile are critical for the Android app to replicate the remote's identity.

### 2. Key Press Monitoring & Cataloging

The monitoring mode depends on Phase 1 results:

- **Advertisement mode:** The tool continuously scans for advertisements from the device and treats each unique advertisement payload as a key event.
- **GATT mode:** The tool subscribes to all characteristics that support notify or indicate and receives key events as notifications.

**Real-time monitoring loop:**

- When an event arrives: display timestamp, source (advertisement or characteristic UUID), raw hex bytes, and time delta since the last event from the same source (to help identify long-press timing patterns)
- Look up the raw value in the key map:
  - **Known:** print the description (e.g., `D-pad Up (short)`)
  - **Unknown:** prompt the user to enter a description, then save to the key map

**Long press detection aid:** If the same raw value fires repeatedly or a distinct "key up" event follows a "key down" event, the tool displays the duration/timing to help the user determine whether long press is timing-based or value-based.

**Key map structure (JSON):**

```json
{
  "device": {
    "name": "...",
    "address": "AA:BB:CC:DD:EE:FF",
    "advertisement": {
      "manufacturer_data": "...",
      "service_uuids": ["..."]
    }
  },
  "keys": {
    "<char_uuid>:<hex_value>": {
      "description": "D-pad Up (short)",
      "characteristic": "0000ffe1-...",
      "raw_hex": "01a3",
      "first_seen": "2026-03-23T12:00:00"
    }
  }
}
```

The key is the combination of characteristic UUID + raw hex value, since different characteristics could send identical byte values with different meanings.

**Interactive commands while monitoring:**

- Press a button on the remote -> see/catalog it
- Type `list` -> show all cataloged keys with numeric IDs
- Type `edit <id>` -> change a description by numeric ID
- Type `delete <id>` -> remove an entry by numeric ID
- Type `raw` -> toggle verbose hex dump mode for all incoming data
- Type `quit` -> save and exit

The key map JSON is saved on every change and loaded on startup for cross-session persistence.

### 3. Disconnect & Reconnection

BLE connections are fragile. The tool registers a disconnect callback via `bleak`. On disconnect:

1. Notify the user immediately
2. Automatically attempt reconnection with exponential backoff (1s, 2s, 4s, up to 30s)
3. On reconnection, re-subscribe to all notify/indicate characteristics
4. After 5 failed attempts, prompt the user to press a button on the remote (to trigger advertisement) and retry

### 4. I/O Coordination

The interactive prompt (user input thread) and BLE event callbacks (async loop) share stdout. To avoid garbled output, incoming BLE events are queued and only displayed when the prompt is idle. When the user is being prompted for a key description, events are buffered and flushed after the prompt completes.

### 5. Project Structure

```
tools/
  ble_scanner/
    scanner.py          # Single-file tool, entry point
    keymap.json         # Generated/maintained at runtime
    requirements.txt    # bleak
```

**Dependencies:**

- `bleak` — async BLE library for Python
- Python 3.10+
- No other external dependencies

**Running:**

```bash
cd tools/ble_scanner
pip install -r requirements.txt
python scanner.py
```

The tool uses `asyncio` since `bleak` is async-native. The interactive prompt runs in a separate thread to avoid blocking the BLE event loop.

## Future Use

The captured data from this tool feeds the Android app implementation:

- **Advertisement data** — the Android app must broadcast matching advertisement packets so the MotoEye recognizes it as a valid remote
- **GATT profile** — the Android app must expose the same services and characteristics
- **Key map** — the Android app must send the same byte values for each action
