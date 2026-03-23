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
