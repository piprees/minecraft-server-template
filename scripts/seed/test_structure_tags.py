"""Tests for structure_tags.py — exact tag resolution from extracted jar data.

Covers: flat tags (#minecraft:village), nested tags (#a:outer containing
#a:inner + required:false entry), cycle safety (a tag referencing itself
terminates), the unavailable->None path, and multi-datapack merging.
"""
import json
import os
import sys
import tempfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import structure_tags  # noqa: E402


@pytest.fixture(autouse=True)
def _clear_tag_cache():
    """Each test gets a fresh cache."""
    structure_tags.clear_cache()
    yield
    structure_tags.clear_cache()


def _write_tag(tags_dir, ns, name, data):
    """Write a tag JSON file into the fixture layout."""
    dest = tags_dir / ns / (name + ".json")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(data))


def test_flat_tag():
    """A flat tag with plain string members resolves to a sorted list."""
    with tempfile.TemporaryDirectory() as tmp:
        seedtest = Path(tmp)
        tags_dir = seedtest / ".structure_tags"
        _write_tag(tags_dir, "minecraft", "village", {
            "values": [
                "minecraft:village_plains",
                "minecraft:village_desert",
                "minecraft:village_savanna",
                "minecraft:village_snowy",
                "minecraft:village_taiga",
            ]
        })
        result = structure_tags.resolve_tag(str(seedtest), "#minecraft:village")
        assert result == [
            "minecraft:village_desert",
            "minecraft:village_plains",
            "minecraft:village_savanna",
            "minecraft:village_snowy",
            "minecraft:village_taiga",
        ]


def test_nested_tag_with_required_false():
    """A nested tag (#a:outer containing #a:inner + a required:false entry)
    expands recursively and includes the required:false member."""
    with tempfile.TemporaryDirectory() as tmp:
        seedtest = Path(tmp)
        tags_dir = seedtest / ".structure_tags"
        _write_tag(tags_dir, "a", "inner", {
            "values": ["a:struct_one", "a:struct_two"]
        })
        _write_tag(tags_dir, "a", "outer", {
            "values": [
                "#a:inner",
                {"id": "a:optional_struct", "required": False},
                "a:direct_struct",
            ]
        })
        result = structure_tags.resolve_tag(str(seedtest), "#a:outer")
        assert result == [
            "a:direct_struct",
            "a:optional_struct",
            "a:struct_one",
            "a:struct_two",
        ]


def test_cycle_safety():
    """A tag referencing itself terminates without error."""
    with tempfile.TemporaryDirectory() as tmp:
        seedtest = Path(tmp)
        tags_dir = seedtest / ".structure_tags"
        _write_tag(tags_dir, "loop", "self_ref", {
            "values": ["#loop:self_ref", "loop:real_struct"]
        })
        result = structure_tags.resolve_tag(str(seedtest), "#loop:self_ref")
        assert result == ["loop:real_struct"]


def test_mutual_cycle():
    """Two tags referencing each other terminate without error."""
    with tempfile.TemporaryDirectory() as tmp:
        seedtest = Path(tmp)
        tags_dir = seedtest / ".structure_tags"
        _write_tag(tags_dir, "cyc", "alpha", {
            "values": ["#cyc:beta", "cyc:a_struct"]
        })
        _write_tag(tags_dir, "cyc", "beta", {
            "values": ["#cyc:alpha", "cyc:b_struct"]
        })
        result = structure_tags.resolve_tag(str(seedtest), "#cyc:alpha")
        assert result == ["cyc:a_struct", "cyc:b_struct"]


def test_unavailable_returns_none():
    """When tag data is unavailable (no .structure_tags dir), return None."""
    with tempfile.TemporaryDirectory() as tmp:
        seedtest = Path(tmp)
        # No .structure_tags directory at all
        result = structure_tags.resolve_tag(str(seedtest), "#minecraft:village")
        assert result is None


def test_unknown_tag_returns_none():
    """A tag that does not exist in the extracted data returns None."""
    with tempfile.TemporaryDirectory() as tmp:
        seedtest = Path(tmp)
        tags_dir = seedtest / ".structure_tags"
        _write_tag(tags_dir, "minecraft", "village", {
            "values": ["minecraft:village_plains"]
        })
        result = structure_tags.resolve_tag(str(seedtest), "#minecraft:nonexistent")
        assert result is None


def test_empty_tag_returns_empty_list():
    """A tag that exists but has no members returns [] (not None)."""
    with tempfile.TemporaryDirectory() as tmp:
        seedtest = Path(tmp)
        tags_dir = seedtest / ".structure_tags"
        _write_tag(tags_dir, "empty", "nothing", {"values": []})
        result = structure_tags.resolve_tag(str(seedtest), "#empty:nothing")
        assert result == []


def test_not_a_tag_returns_none():
    """A non-tag string (no #) returns None."""
    result = structure_tags.resolve_tag("/some/path", "minecraft:village")
    assert result is None


def test_multi_datapack_merge():
    """Multiple files for the same tag are merged (append semantics)."""
    with tempfile.TemporaryDirectory() as tmp:
        seedtest = Path(tmp)
        tags_dir = seedtest / ".structure_tags"
        # First datapack's contribution
        _write_tag(tags_dir, "minecraft", "village", {
            "values": ["minecraft:village_plains", "minecraft:village_desert"]
        })
        # Second datapack's contribution (different filename, same tag id)
        _write_tag(tags_dir, "minecraft", "village__modded", {
            "values": ["modded:village_swamp"]
        })
        result = structure_tags.resolve_tag(str(seedtest), "#minecraft:village")
        assert "minecraft:village_plains" in result
        assert "minecraft:village_desert" in result
        assert "modded:village_swamp" in result


def test_replace_true_discards_prior():
    """A tag file with replace=true discards all prior entries."""
    with tempfile.TemporaryDirectory() as tmp:
        seedtest = Path(tmp)
        tags_dir = seedtest / ".structure_tags"
        # First file (loaded first due to sort order)
        dest = tags_dir / "minecraft" / "aaa_village.json"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps({
            "values": ["minecraft:village_plains"]
        }))
        # Second file replaces
        dest2 = tags_dir / "minecraft" / "bbb_village.json"
        dest2.write_text(json.dumps({
            "replace": True,
            "values": ["modded:only_this"]
        }))
        # Both share the tag id "minecraft:aaa_village" / "minecraft:bbb_village"
        # — but they are DIFFERENT tag ids. Test actual replace on same id:
        # We need the files to resolve to the same tag id via the path layout.
        # Since tag id is derived from the path, two files at the same path
        # would overwrite. The merge logic handles files sorted.
        # Let me create a proper test with the __suffix convention:
        structure_tags.clear_cache()

        tags_dir2 = Path(tmp) / "seedtest2" / ".structure_tags"
        p1 = tags_dir2 / "test" / "replaced.json"
        p1.parent.mkdir(parents=True, exist_ok=True)
        p1.write_text(json.dumps({"values": ["test:old_member"]}))
        p2 = tags_dir2 / "test" / "replaced__mod.json"
        p2.write_text(json.dumps({"replace": True, "values": ["test:new_member"]}))

        result = structure_tags.resolve_tag(
            str(Path(tmp) / "seedtest2"), "#test:replaced")
        # The __mod file sorts after replaced.json, so its replace=true
        # discards old_member
        assert result == ["test:new_member"]


def test_nested_required_false_tag_ref():
    """A nested tag reference inside a required:false object is expanded."""
    with tempfile.TemporaryDirectory() as tmp:
        seedtest = Path(tmp)
        tags_dir = seedtest / ".structure_tags"
        _write_tag(tags_dir, "ns", "base", {
            "values": ["ns:base_struct"]
        })
        _write_tag(tags_dir, "ns", "wrapper", {
            "values": [
                {"id": "#ns:base", "required": False},
            ]
        })
        result = structure_tags.resolve_tag(str(seedtest), "#ns:wrapper")
        assert result == ["ns:base_struct"]
