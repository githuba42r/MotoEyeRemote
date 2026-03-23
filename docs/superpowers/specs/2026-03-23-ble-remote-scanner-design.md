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

Once selected, the tool:

1. Logs the full advertisement data (raw manufacturer bytes, service UUIDs, local name) — required for the Android app to replicate the remote's identity
2. Connects to the remote as a Central
3. Handles pairing/bonding via BlueZ if required
4. Discovers all GATT services and characteristics
5. Prints a summary of the GATT profile (services, characteristics, descriptors, properties)

### 2. Key Press Monitoring & Cataloging

After connection and GATT discovery, the tool subscribes to all characteristics that support notify or indicate.

**Real-time monitoring loop:**

- When a notification arrives: display timestamp, characteristic UUID, raw hex bytes
- Look up the raw value in the key map:
  - **Known:** print the description (e.g., `D-pad Up (short)`)
  - **Unknown:** prompt the user to enter a description, then save to the key map

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
- Type `list` -> show all cataloged keys
- Type `edit <key>` -> change a description
- Type `delete <key>` -> remove an entry
- Type `quit` -> save and exit

The key map JSON is saved on every change and loaded on startup for cross-session persistence.

### 3. Project Structure

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
