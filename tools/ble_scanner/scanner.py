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


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nExiting.")
        sys.exit(0)
