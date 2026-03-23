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
