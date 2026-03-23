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


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nExiting.")
        sys.exit(0)
