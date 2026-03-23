# BLE Remote Scanner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Python console tool that connects to a MotoEye E6 BLE remote, captures key press events, and interactively catalogs them for use in the Android app.

**Architecture:** Single entry point (`scanner.py`) with a separate `keymap.py` module for testable key map persistence logic. The scanner uses `asyncio` with `bleak` for BLE operations and a threaded input loop for interactive commands. Two-phase BLE approach: passive advertisement monitoring first, then optional GATT connection.

**Tech Stack:** Python 3.10+, bleak (BLE), asyncio, argparse, json

**Spec:** `docs/superpowers/specs/2026-03-23-ble-remote-scanner-design.md`

---

## File Structure

```
tools/ble_scanner/
  scanner.py          # Entry point: CLI args, BLE discovery, phases, monitoring loop, I/O coordination
  keymap.py           # Key map data model: load, save, lookup, add, edit, delete, list
  test_keymap.py      # Unit tests for keymap module
  requirements.txt    # bleak
  keymap.json         # Generated at runtime (not committed)
```

- `keymap.py` — pure data logic, no BLE or I/O dependencies, fully unit-testable
- `scanner.py` — all BLE and async logic, imports `keymap.py`

---

### Task 1: Project Scaffolding

**Files:**
- Create: `tools/ble_scanner/requirements.txt`
- Create: `tools/ble_scanner/keymap.py` (empty module)
- Create: `tools/ble_scanner/scanner.py` (CLI skeleton)
- Create: `tools/ble_scanner/test_keymap.py` (empty test file)

- [ ] **Step 1: Create requirements.txt**

```
bleak>=0.21.0
```

- [ ] **Step 2: Create keymap.py with empty module docstring**

```python
"""Key map persistence for the MotoEye E6 BLE remote scanner."""

from __future__ import annotations
```

- [ ] **Step 3: Create scanner.py with argparse skeleton**

```python
"""MotoEye E6 BLE Remote Scanner — connects to the remote and catalogs key presses."""

from __future__ import annotations

import argparse
import asyncio
import json
import queue
import sys
import threading
import time
from datetime import datetime

from bleak import BleakScanner, BleakClient, BleakError
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

from keymap import KeyMap

KEYMAP_PATH = "keymap.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="MotoEye E6 BLE Remote Scanner"
    )
    parser.add_argument(
        "--address",
        type=str,
        default=None,
        help="BLE MAC address to connect directly (skip discovery)",
    )
    parser.add_argument(
        "--scan-time",
        type=float,
        default=10.0,
        help="Phase 1 advertisement monitoring duration in seconds (default: 10)",
    )
    return parser.parse_args()


async def main() -> None:
    args = parse_args()
    print(f"MotoEye E6 BLE Remote Scanner")
    print(f"  Address: {args.address or 'auto-discover'}")
    print(f"  Scan time: {args.scan_time}s")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nExiting.")
        sys.exit(0)
```

- [ ] **Step 4: Create empty test file**

```python
"""Tests for keymap module."""
```

- [ ] **Step 5: Verify the skeleton runs**

Run: `cd tools/ble_scanner && python scanner.py --help`
Expected: argparse help output showing `--address` and `--scan-time` options.

- [ ] **Step 6: Commit**

```bash
git add tools/ble_scanner/
git commit -m "feat: scaffold BLE scanner project structure"
```

---

### Task 2: Key Map Data Model

**Files:**
- Modify: `tools/ble_scanner/keymap.py`
- Modify: `tools/ble_scanner/test_keymap.py`

- [ ] **Step 1: Write failing tests for KeyMap**

```python
"""Tests for keymap module."""

import json
import os
import tempfile
from datetime import datetime, timezone

from keymap import KeyMap


def test_new_keymap_is_empty():
    km = KeyMap()
    assert km.keys == {}
    assert km.device is None


def test_add_key():
    km = KeyMap()
    km.add("0000ffe1:01a3", source="0000ffe1", raw_hex="01a3", description="D-pad Up")
    assert "0000ffe1:01a3" in km.keys
    assert km.keys["0000ffe1:01a3"]["description"] == "D-pad Up"
    assert km.keys["0000ffe1:01a3"]["source"] == "0000ffe1"
    assert km.keys["0000ffe1:01a3"]["raw_hex"] == "01a3"
    assert "first_seen" in km.keys["0000ffe1:01a3"]


def test_lookup_known_key():
    km = KeyMap()
    km.add("0000ffe1:01a3", source="0000ffe1", raw_hex="01a3", description="D-pad Up")
    result = km.lookup("0000ffe1:01a3")
    assert result is not None
    assert result["description"] == "D-pad Up"


def test_lookup_unknown_key():
    km = KeyMap()
    result = km.lookup("0000ffe1:ffff")
    assert result is None


def test_edit_key():
    km = KeyMap()
    km.add("0000ffe1:01a3", source="0000ffe1", raw_hex="01a3", description="D-pad Up")
    km.edit("0000ffe1:01a3", "D-pad Up (short)")
    assert km.keys["0000ffe1:01a3"]["description"] == "D-pad Up (short)"


def test_delete_key():
    km = KeyMap()
    km.add("0000ffe1:01a3", source="0000ffe1", raw_hex="01a3", description="D-pad Up")
    km.delete("0000ffe1:01a3")
    assert "0000ffe1:01a3" not in km.keys


def test_list_keys_with_numeric_ids():
    km = KeyMap()
    km.add("0000ffe1:01a3", source="0000ffe1", raw_hex="01a3", description="D-pad Up")
    km.add("0000ffe1:01a4", source="0000ffe1", raw_hex="01a4", description="D-pad Down")
    items = km.list_keys()
    assert len(items) == 2
    assert items[0][0] == 1  # numeric ID
    assert items[1][0] == 2
    assert items[0][1] == "0000ffe1:01a3"
    assert items[0][2]["description"] == "D-pad Up"


def test_get_key_by_numeric_id():
    km = KeyMap()
    km.add("0000ffe1:01a3", source="0000ffe1", raw_hex="01a3", description="D-pad Up")
    km.add("0000ffe1:01a4", source="0000ffe1", raw_hex="01a4", description="D-pad Down")
    key_id = km.get_key_by_id(2)
    assert key_id == "0000ffe1:01a4"


def test_get_key_by_invalid_id():
    km = KeyMap()
    key_id = km.get_key_by_id(99)
    assert key_id is None


def test_save_and_load(tmp_path):
    path = tmp_path / "keymap.json"
    km = KeyMap()
    km.set_device("TestRemote", "AA:BB:CC:DD:EE:FF", {"mfr": "0102"}, ["0000ffe0"])
    km.add("0000ffe1:01a3", source="0000ffe1", raw_hex="01a3", description="D-pad Up")
    km.save(str(path))

    km2 = KeyMap.load(str(path))
    assert km2.device["name"] == "TestRemote"
    assert km2.device["address"] == "AA:BB:CC:DD:EE:FF"
    assert "0000ffe1:01a3" in km2.keys
    assert km2.keys["0000ffe1:01a3"]["description"] == "D-pad Up"


def test_load_nonexistent_returns_empty():
    km = KeyMap.load("/nonexistent/path/keymap.json")
    assert km.keys == {}
    assert km.device is None


def test_adv_mode_key():
    km = KeyMap()
    km.add("adv:0102ff03", source="adv", raw_hex="0102ff03", description="Camera button")
    result = km.lookup("adv:0102ff03")
    assert result is not None
    assert result["description"] == "Camera button"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd tools/ble_scanner && python -m pytest test_keymap.py -v`
Expected: All tests FAIL (KeyMap not defined).

- [ ] **Step 3: Implement KeyMap class**

```python
"""Key map persistence for the MotoEye E6 BLE remote scanner."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


class KeyMap:
    """Manages the key map: lookup, add, edit, delete, save, load."""

    def __init__(self) -> None:
        self.device: dict[str, Any] | None = None
        self.keys: dict[str, dict[str, Any]] = {}

    def set_device(
        self,
        name: str,
        address: str,
        manufacturer_data: dict[str, str],
        service_uuids: list[str],
    ) -> None:
        self.device = {
            "name": name,
            "address": address,
            "advertisement": {
                "manufacturer_data": manufacturer_data,
                "service_uuids": service_uuids,
            },
        }

    def add(
        self, key: str, *, source: str, raw_hex: str, description: str
    ) -> None:
        self.keys[key] = {
            "description": description,
            "source": source,
            "raw_hex": raw_hex,
            "first_seen": datetime.now(timezone.utc).isoformat(),
        }

    def lookup(self, key: str) -> dict[str, Any] | None:
        return self.keys.get(key)

    def edit(self, key: str, description: str) -> None:
        if key in self.keys:
            self.keys[key]["description"] = description

    def delete(self, key: str) -> None:
        self.keys.pop(key, None)

    def list_keys(self) -> list[tuple[int, str, dict[str, Any]]]:
        return [
            (i + 1, k, v) for i, (k, v) in enumerate(self.keys.items())
        ]

    def get_key_by_id(self, numeric_id: int) -> str | None:
        items = list(self.keys.keys())
        if 1 <= numeric_id <= len(items):
            return items[numeric_id - 1]
        return None

    def save(self, path: str) -> None:
        data = {"device": self.device, "keys": self.keys}
        Path(path).write_text(json.dumps(data, indent=2))

    @classmethod
    def load(cls, path: str) -> KeyMap:
        km = cls()
        p = Path(path)
        if p.exists():
            data = json.loads(p.read_text())
            km.device = data.get("device")
            km.keys = data.get("keys", {})
        return km
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd tools/ble_scanner && python -m pytest test_keymap.py -v`
Expected: All 12 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/ble_scanner/keymap.py tools/ble_scanner/test_keymap.py
git commit -m "feat: implement KeyMap data model with full test coverage"
```

---

### Task 3: BLE Device Discovery

**Files:**
- Modify: `tools/ble_scanner/scanner.py`

- [ ] **Step 1: Implement device scanning and selection**

Add to `scanner.py` (imports already present from Task 1):

```python
async def discover_devices(scan_time: float = 5.0) -> list[tuple[BLEDevice, AdvertisementData]]:
    """Scan for BLE devices and return list of (device, advertisement_data)."""
    print(f"\nScanning for BLE devices ({scan_time}s)...")
    devices: dict[str, tuple[BLEDevice, AdvertisementData]] = {}

    def callback(device: BLEDevice, adv: AdvertisementData) -> None:
        devices[device.address] = (device, adv)

    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    await asyncio.sleep(scan_time)
    await scanner.stop()

    return list(devices.values())


def display_devices(devices: list[tuple[BLEDevice, AdvertisementData]]) -> None:
    """Print discovered devices in a numbered list."""
    print(f"\nFound {len(devices)} device(s):\n")
    print(f"{'#':>3}  {'Name':<30} {'Address':<20} {'RSSI':>5}  Manufacturer Data")
    print("-" * 90)
    for i, (dev, adv) in enumerate(devices, 1):
        name = dev.name or "(unknown)"
        mfr = ""
        if adv.manufacturer_data:
            mfr = " ".join(
                f"0x{k:04x}:{v.hex()}" for k, v in adv.manufacturer_data.items()
            )
        print(f"{i:>3}  {name:<30} {dev.address:<20} {adv.rssi:>5}  {mfr}")


async def select_device(
    args: argparse.Namespace,
) -> tuple[BLEDevice, AdvertisementData]:
    """Discover or directly target a BLE device."""
    if args.address:
        print(f"\nScanning for device {args.address}...")
        device = await BleakScanner.find_device_by_address(
            args.address, timeout=10.0
        )
        if device is None:
            print(f"Device {args.address} not found.")
            sys.exit(1)
        # Re-scan briefly to capture advertisement data
        adv_data: AdvertisementData | None = None

        def cb(dev: BLEDevice, adv: AdvertisementData) -> None:
            nonlocal adv_data
            if dev.address == args.address:
                adv_data = adv

        scanner = BleakScanner(detection_callback=cb)
        await scanner.start()
        await asyncio.sleep(2.0)
        await scanner.stop()
        if adv_data is None:
            print("Warning: Could not capture advertisement data.")
            # Create minimal adv data — we still have the device
        return device, adv_data

    devices = await discover_devices()
    if not devices:
        print("No BLE devices found.")
        sys.exit(1)

    display_devices(devices)
    while True:
        try:
            choice = int(input("\nSelect device number: "))
            if 1 <= choice <= len(devices):
                return devices[choice - 1]
            print(f"Enter a number between 1 and {len(devices)}.")
        except ValueError:
            print("Enter a valid number.")
        except EOFError:
            sys.exit(0)
```

- [ ] **Step 2: Wire into main()**

Update `main()` in `scanner.py`:

```python
async def main() -> None:
    args = parse_args()
    print("MotoEye E6 BLE Remote Scanner")

    keymap = KeyMap.load(KEYMAP_PATH)
    device, adv_data = await select_device(args)
    print(f"\nSelected: {device.name or '(unknown)'} [{device.address}]")
```

- [ ] **Step 3: Test manually (requires BLE adapter)**

Run: `cd tools/ble_scanner && sudo python scanner.py`
Expected: Lists nearby BLE devices, allows selection by number.

- [ ] **Step 4: Commit**

```bash
git add tools/ble_scanner/scanner.py
git commit -m "feat: add BLE device discovery and selection"
```

---

### Task 4: Phase 1 — Advertisement Monitoring

**Files:**
- Modify: `tools/ble_scanner/scanner.py`

- [ ] **Step 1: Implement advertisement monitoring phase**

Add to `scanner.py`:

```python
async def phase1_monitor_advertisements(
    address: str, duration: float
) -> tuple[list[dict], bool]:
    """Monitor advertisements from a specific device.

    Returns (distinct_payloads, is_connectable).
    """
    print(f"\n--- Phase 1: Monitoring advertisements for {duration}s ---")
    print("Press buttons on the remote during this period.\n")

    payloads: list[dict] = []
    seen_payloads: set[str] = set()
    is_connectable: bool | None = None

    def callback(device: BLEDevice, adv: AdvertisementData) -> None:
        nonlocal is_connectable
        if device.address != address:
            return

        mfr_hex = {
            f"0x{k:04x}": v.hex() for k, v in adv.manufacturer_data.items()
        } if adv.manufacturer_data else {}

        payload_sig = json.dumps(mfr_hex, sort_keys=True)

        if is_connectable is None:
            is_connectable = adv.connectable

        if payload_sig not in seen_payloads:
            seen_payloads.add(payload_sig)
            entry = {
                "manufacturer_data": mfr_hex,
                "service_uuids": adv.service_uuids,
                "local_name": adv.local_name,
                "rssi": adv.rssi,
                "connectable": adv.connectable,
            }
            payloads.append(entry)
            print(
                f"  [{len(payloads)}] mfr={mfr_hex} uuids={adv.service_uuids} "
                f"rssi={adv.rssi} connectable={adv.connectable}"
            )

    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    await asyncio.sleep(duration)
    await scanner.stop()

    conn = is_connectable if is_connectable is not None else False
    print(f"\nPhase 1 complete. Observed {len(payloads)} distinct advertisement payload(s).")
    print(f"Advertisements are {'connectable' if conn else 'non-connectable'}.")

    return payloads, conn


def phase1_decision(payloads: list[dict], is_connectable: bool) -> str:
    """Determine monitoring mode based on Phase 1 results. Returns 'adv' or 'gatt'."""
    if not is_connectable:
        print("Device is non-connectable. Using advertisement monitoring mode.")
        return "adv"

    if len(payloads) > 1:
        print(f"\nObserved {len(payloads)} distinct payloads during button presses.")
        print("Recommendation: advertisement mode (payloads change with actions).")
        while True:
            choice = input("Use advertisement mode? [Y/n]: ").strip().lower()
            if choice in ("", "y", "yes"):
                return "adv"
            if choice in ("n", "no"):
                return "gatt"
            print("Enter y or n.")
    else:
        print("Recommendation: GATT connection (static advertisements).")
        return "gatt"
```

- [ ] **Step 2: Wire Phase 1 into main()**

Update `main()`:

```python
async def main() -> None:
    args = parse_args()
    print("MotoEye E6 BLE Remote Scanner")

    keymap = KeyMap.load(KEYMAP_PATH)
    device, adv_data = await select_device(args)
    print(f"\nSelected: {device.name or '(unknown)'} [{device.address}]")

    # Phase 1
    payloads, is_connectable = await phase1_monitor_advertisements(
        device.address, args.scan_time
    )
    mode = phase1_decision(payloads, is_connectable)
    print(f"\nMonitoring mode: {mode.upper()}")

    # Store device info in keymap
    mfr_data = {}
    if payloads:
        mfr_data = payloads[0].get("manufacturer_data", {})
    service_uuids = payloads[0].get("service_uuids", []) if payloads else []
    keymap.set_device(
        device.name or "(unknown)",
        device.address,
        mfr_data,
        service_uuids,
    )
    keymap.save(KEYMAP_PATH)
```

- [ ] **Step 3: Test manually**

Run: `cd tools/ble_scanner && sudo python scanner.py`
Expected: After device selection, monitors advertisements for 10s, shows distinct payloads, recommends mode.

- [ ] **Step 4: Commit**

```bash
git add tools/ble_scanner/scanner.py
git commit -m "feat: add Phase 1 advertisement monitoring and mode detection"
```

---

### Task 5: Phase 2 — GATT Connection & Service Discovery

**Files:**
- Modify: `tools/ble_scanner/scanner.py`

- [ ] **Step 1: Implement GATT connection and service enumeration**

Add to `scanner.py`:

```python
async def phase2_gatt_connect(device: BLEDevice) -> BleakClient:
    """Connect to device and print GATT service/characteristic summary."""
    print(f"\n--- Phase 2: Connecting via GATT ---")
    client = BleakClient(device, timeout=15.0)
    await client.connect()
    print(f"Connected: {client.is_connected}")

    print("\nGATT Services:")
    for service in client.services:
        print(f"\n  Service: {service.uuid} ({service.description})")
        for char in service.characteristics:
            props = ", ".join(char.properties)
            print(f"    Char: {char.uuid} [{props}] ({char.description})")
            for desc in char.descriptors:
                print(f"      Desc: {desc.uuid} ({desc.description})")

    return client


def get_notify_characteristics(client: BleakClient) -> list[str]:
    """Return UUIDs of all characteristics that support notify or indicate."""
    uuids = []
    for service in client.services:
        for char in service.characteristics:
            if "notify" in char.properties or "indicate" in char.properties:
                uuids.append(char.uuid)
    return uuids
```

- [ ] **Step 2: Wire Phase 2 into main()**

Add after Phase 1 in `main()`:

```python
    client: BleakClient | None = None
    notify_uuids: list[str] = []

    if mode == "gatt":
        client = await phase2_gatt_connect(device)
        notify_uuids = get_notify_characteristics(client)
        print(f"\nNotify/indicate characteristics: {notify_uuids}")
```

- [ ] **Step 3: Test manually with BLE device**

Run: `cd tools/ble_scanner && sudo python scanner.py`
Expected: After Phase 1 recommends GATT, connects and prints full service/characteristic tree.

- [ ] **Step 4: Commit**

```bash
git add tools/ble_scanner/scanner.py
git commit -m "feat: add Phase 2 GATT connection and service discovery"
```

---

### Task 6: Key Press Monitoring Loop (Both Modes)

**Files:**
- Modify: `tools/ble_scanner/scanner.py`

- [ ] **Step 1: Implement the event queue and monitoring loops**

Add to `scanner.py`:

```python
# Shared state (imports already present from Task 1)
event_queue: queue.Queue[tuple[str, str, bytes]] = queue.Queue()
unknown_queue: queue.Queue[tuple[str, str, str]] = queue.Queue()  # (key, source, hex_val)
last_event_time: dict[str, float] = {}
keymap_lock = threading.Lock()
raw_mode = False


def format_event(
    source: str, raw_bytes: bytes, keymap: KeyMap
) -> str:
    """Format a BLE event for display."""
    now = time.time()
    hex_val = raw_bytes.hex()
    key = f"{source}:{hex_val}"

    delta_str = ""
    if source in last_event_time:
        delta = now - last_event_time[source]
        delta_str = f" (+{delta:.3f}s)"
    last_event_time[source] = now

    timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    result = keymap.lookup(key)

    if result:
        return (
            f"[{timestamp}]{delta_str} {source} -> {hex_val}"
            f"  => {result['description']}"
        )
    else:
        return f"[{timestamp}]{delta_str} {source} -> {hex_val}  => *** UNKNOWN ***"


def make_notification_handler(char_uuid: str):
    """Create a notification callback for a specific characteristic."""
    def handler(sender, data: bytearray) -> None:
        event_queue.put(("gatt", char_uuid, bytes(data)))
    return handler


async def monitor_advertisements(address: str, cooldown: float = 0.5) -> None:
    """Continuously scan for advertisements and queue events.

    Uses timestamp-based deduplication: the same payload is suppressed
    for `cooldown` seconds after first seen, then accepted again.
    """
    last_seen: dict[str, float] = {}  # payload_sig -> timestamp

    def callback(device: BLEDevice, adv: AdvertisementData) -> None:
        if device.address != address:
            return
        if adv.manufacturer_data:
            now = time.time()
            for _k, v in adv.manufacturer_data.items():
                sig = v.hex()
                if sig in last_seen and (now - last_seen[sig]) < cooldown:
                    continue
                last_seen[sig] = now
                event_queue.put(("adv", "adv", bytes(v)))

    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    try:
        while True:
            await asyncio.sleep(0.1)
    finally:
        await scanner.stop()


async def subscribe_and_monitor(
    client: BleakClient, notify_uuids: list[str]
) -> None:
    """Subscribe to all notify characteristics and keep connection alive."""
    for uuid in notify_uuids:
        await client.start_notify(uuid, make_notification_handler(uuid))
        print(f"  Subscribed to {uuid}")

    # Keep alive — disconnection is handled by callback
    while client.is_connected:
        await asyncio.sleep(0.1)
```

- [ ] **Step 2: Run tests**

Run: `cd tools/ble_scanner && python -m pytest test_keymap.py -v`
Expected: All existing tests still PASS (no keymap changes).

- [ ] **Step 3: Commit**

```bash
git add tools/ble_scanner/scanner.py
git commit -m "feat: add event queue, notification handlers, and monitoring loops"
```

---

### Task 7: Interactive Command Handler & I/O Coordination

**Files:**
- Modify: `tools/ble_scanner/scanner.py`

- [ ] **Step 1: Implement the input thread and event processing**

Add to `scanner.py`:

```python
def process_events(keymap: KeyMap) -> None:
    """Process all queued BLE events. Runs in the async event loop.

    Displays known keys immediately. Unknown keys are queued for the
    input thread to prompt the user (avoids blocking the async loop).
    """
    while not event_queue.empty():
        try:
            mode_tag, source, raw_bytes = event_queue.get_nowait()
        except queue.Empty:
            break

        hex_val = raw_bytes.hex()
        key = f"{source}:{hex_val}"

        with keymap_lock:
            line = format_event(source, raw_bytes, keymap)
            if raw_mode:
                line += f"  [raw: {' '.join(f'{b:02x}' for b in raw_bytes)}]"
            print(line)
            result = keymap.lookup(key)

        if result is None:
            unknown_queue.put((key, source, hex_val))


def drain_unknown_keys(keymap: KeyMap) -> None:
    """Prompt user for any unknown keys queued by the event processor."""
    while not unknown_queue.empty():
        try:
            key, source, hex_val = unknown_queue.get_nowait()
        except queue.Empty:
            break
        # Re-check — might have been cataloged by a previous iteration
        with keymap_lock:
            if keymap.lookup(key) is not None:
                continue
        desc = input(f"  Enter description for {key} (or Enter to skip): ").strip()
        if desc:
            with keymap_lock:
                keymap.add(key, source=source, raw_hex=hex_val, description=desc)
                keymap.save(KEYMAP_PATH)
            print(f"  Saved: {key} => {desc}")


def input_loop(keymap: KeyMap, stop_event: threading.Event) -> None:
    """Handle interactive commands in a separate thread."""
    print("\nCommands: list, edit <id>, delete <id>, raw, quit")
    print("Press buttons on the remote to capture key events.\n")

    while not stop_event.is_set():
        # First, prompt for any unknown keys from BLE events
        drain_unknown_keys(keymap)

        try:
            cmd = input("> ").strip().lower()
        except EOFError:
            stop_event.set()
            break

        if not cmd:
            continue

        if cmd == "quit":
            stop_event.set()
            break

        if cmd == "list":
            with keymap_lock:
                items = keymap.list_keys()
            if not items:
                print("  No keys cataloged yet.")
            else:
                for num_id, key, entry in items:
                    print(f"  [{num_id}] {key} => {entry['description']}")
            continue

        if cmd == "raw":
            global raw_mode
            raw_mode = not raw_mode
            print(f"  Raw mode: {'ON' if raw_mode else 'OFF'}")
            continue

        if cmd.startswith("edit "):
            try:
                num_id = int(cmd.split()[1])
                with keymap_lock:
                    key = keymap.get_key_by_id(num_id)
                    if key is None:
                        print(f"  No key with ID {num_id}.")
                        continue
                    current = keymap.keys[key]["description"]
                new_desc = input(f"  Current: '{current}'. New description: ").strip()
                if new_desc:
                    with keymap_lock:
                        keymap.edit(key, new_desc)
                        keymap.save(KEYMAP_PATH)
                    print(f"  Updated: {key} => {new_desc}")
            except (ValueError, IndexError):
                print("  Usage: edit <id>")
            continue

        if cmd.startswith("delete "):
            try:
                num_id = int(cmd.split()[1])
                with keymap_lock:
                    key = keymap.get_key_by_id(num_id)
                    if key is None:
                        print(f"  No key with ID {num_id}.")
                        continue
                    desc = keymap.keys[key]["description"]
                confirm = input(f"  Delete '{desc}' ({key})? [y/N]: ").strip().lower()
                if confirm in ("y", "yes"):
                    with keymap_lock:
                        keymap.delete(key)
                        keymap.save(KEYMAP_PATH)
                    print(f"  Deleted: {key}")
            except (ValueError, IndexError):
                print("  Usage: delete <id>")
            continue

        print(f"  Unknown command: {cmd}")
```

- [ ] **Step 2: Wire everything into main()**

Replace the end of `main()` with the full orchestration:

```python
async def main() -> None:
    args = parse_args()
    print("MotoEye E6 BLE Remote Scanner")

    keymap = KeyMap.load(KEYMAP_PATH)
    device, adv_data = await select_device(args)
    print(f"\nSelected: {device.name or '(unknown)'} [{device.address}]")

    # Phase 1
    payloads, is_connectable = await phase1_monitor_advertisements(
        device.address, args.scan_time
    )
    mode = phase1_decision(payloads, is_connectable)
    print(f"\nMonitoring mode: {mode.upper()}")

    # Store device info in keymap
    mfr_data = {}
    if payloads:
        mfr_data = payloads[0].get("manufacturer_data", {})
    service_uuids = payloads[0].get("service_uuids", []) if payloads else []
    keymap.set_device(
        device.name or "(unknown)",
        device.address,
        mfr_data,
        service_uuids,
    )
    keymap.save(KEYMAP_PATH)

    # Phase 2 (if GATT mode)
    client: BleakClient | None = None
    notify_uuids: list[str] = []
    if mode == "gatt":
        client = await phase2_gatt_connect(device)
        notify_uuids = get_notify_characteristics(client)
        print(f"\nNotify/indicate characteristics: {notify_uuids}")

    # Start monitoring
    stop_event = threading.Event()

    # Event processing timer
    async def event_processor():
        while not stop_event.is_set():
            process_events(keymap)
            await asyncio.sleep(0.1)

    # Start input thread
    input_thread = threading.Thread(
        target=input_loop, args=(keymap, stop_event), daemon=True
    )
    input_thread.start()

    # Start BLE monitoring + event processing
    try:
        if mode == "adv":
            await asyncio.gather(
                monitor_advertisements(device.address),
                event_processor(),
            )
        else:
            await asyncio.gather(
                subscribe_and_monitor(client, notify_uuids),
                event_processor(),
            )
    except asyncio.CancelledError:
        pass
    finally:
        if client and client.is_connected:
            await client.disconnect()
        keymap.save(KEYMAP_PATH)
        print("\nKey map saved. Goodbye.")
```

- [ ] **Step 3: Test manually**

Run: `cd tools/ble_scanner && sudo python scanner.py`
Expected: Full interactive session — discover device, Phase 1, Phase 2, monitor key presses, use list/edit/delete/raw/quit commands.

- [ ] **Step 4: Commit**

```bash
git add tools/ble_scanner/scanner.py
git commit -m "feat: add interactive command handler and I/O coordination"
```

---

### Task 8: Disconnect & Reconnection

**Files:**
- Modify: `tools/ble_scanner/scanner.py`

- [ ] **Step 1: Add disconnect callback and reconnection logic**

Add to `scanner.py`:

```python
async def reconnect_loop(
    device: BLEDevice,
    notify_uuids: list[str],
    stop_event: threading.Event,
) -> BleakClient | None:
    """Attempt to reconnect with exponential backoff."""
    delays = [1, 2, 4, 8, 16]
    for attempt, delay in enumerate(delays, 1):
        if stop_event.is_set():
            return None
        print(f"\n  Reconnection attempt {attempt}/{len(delays)} in {delay}s...")
        await asyncio.sleep(delay)
        try:
            client = BleakClient(device, timeout=15.0)
            await client.connect()
            if client.is_connected:
                print("  Reconnected!")
                for uuid in notify_uuids:
                    await client.start_notify(uuid, make_notification_handler(uuid))
                    print(f"  Re-subscribed to {uuid}")
                return client
        except (BleakError, asyncio.TimeoutError) as e:
            print(f"  Attempt {attempt} failed: {e}")

    print("\n  5 reconnection attempts failed.")
    print("  Press a button on the remote to trigger advertisement, then press Enter.")
    return None
```

- [ ] **Step 2: Integrate reconnection into GATT monitoring**

Replace `subscribe_and_monitor` with:

```python
async def subscribe_and_monitor(
    client: BleakClient,
    device: BLEDevice,
    notify_uuids: list[str],
    stop_event: threading.Event,
) -> None:
    """Subscribe to notifications with reconnection support.

    Note: The disconnect callback is passed via the BleakClient constructor
    (bleak >= 0.21.0 removed set_disconnected_callback). This means
    phase2_gatt_connect must accept the callback parameter.
    """
    for uuid in notify_uuids:
        await client.start_notify(uuid, make_notification_handler(uuid))
        print(f"  Subscribed to {uuid}")

    while not stop_event.is_set():
        if not client.is_connected:
            print("\n  *** Device disconnected ***")
            new_client = await reconnect_loop(device, notify_uuids, stop_event)
            if new_client:
                client = new_client
            else:
                # Fall back to waiting for user
                await asyncio.sleep(1.0)
        await asyncio.sleep(0.1)
```

- [ ] **Step 3: Update main() to pass device and stop_event to subscribe_and_monitor**

In `main()`, update the GATT monitoring call:

```python
        else:
            await asyncio.gather(
                subscribe_and_monitor(client, device, notify_uuids, stop_event),
                event_processor(),
            )
```

- [ ] **Step 4: Test manually (disconnect by moving remote out of range, then back)**

Run: `cd tools/ble_scanner && sudo python scanner.py`
Expected: On disconnect, prints message and attempts reconnection with backoff.

- [ ] **Step 5: Commit**

```bash
git add tools/ble_scanner/scanner.py
git commit -m "feat: add disconnect detection and reconnection with backoff"
```

---

### Task 9: Final Integration & Cleanup

**Files:**
- Modify: `tools/ble_scanner/scanner.py`
- Create: `tools/ble_scanner/.gitignore`

- [ ] **Step 1: Add .gitignore for runtime files**

```
keymap.json
__pycache__/
*.pyc
```

- [ ] **Step 2: Verify all imports are present at top of scanner.py**

All imports were added in Task 1. Verify they are still intact — no changes needed unless something was accidentally removed.

- [ ] **Step 3: Run full test suite**

Run: `cd tools/ble_scanner && python -m pytest test_keymap.py -v`
Expected: All 12 tests PASS.

- [ ] **Step 4: Run linter check**

Run: `cd tools/ble_scanner && python -m py_compile scanner.py && python -m py_compile keymap.py && echo "OK"`
Expected: `OK` (no syntax errors).

- [ ] **Step 5: Commit**

```bash
git add tools/ble_scanner/.gitignore tools/ble_scanner/scanner.py
git commit -m "chore: add gitignore and finalize scanner imports"
```

- [ ] **Step 6: End-to-end manual test**

Run: `cd tools/ble_scanner && sudo python scanner.py`
Full walkthrough:
1. Discover and select the MotoEye E6 remote
2. Phase 1 monitors advertisements
3. Phase 2 connects via GATT (or stays in adv mode)
4. Press each button on remote, catalog all 10 actions
5. Use `list` to verify all entries
6. Use `edit 1` to rename an entry
7. Use `delete` to remove and re-add one
8. Use `raw` to toggle hex dump
9. Use `quit` to save and exit
10. Restart tool — verify keymap.json loads correctly
