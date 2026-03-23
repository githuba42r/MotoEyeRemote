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
