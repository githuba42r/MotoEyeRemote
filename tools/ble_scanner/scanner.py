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


async def main() -> None:
    args = parse_args()
    print("MotoEye E6 BLE Remote Scanner")

    keymap = KeyMap.load(KEYMAP_PATH)
    device, adv_data = await select_device(args)
    print(f"\nSelected: {device.name or '(unknown)'} [{device.address}]")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nExiting.")
        sys.exit(0)
