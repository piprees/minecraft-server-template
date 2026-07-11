#!/usr/bin/env python3
"""patch-mod-data.py - Repair known-bad data inside third-party mod JARs.

Currently one patch: Epic Dungeons ships structure template NBTs whose
chest block entities reference CamelCase loot table ids
(epic:chests/DungeonPoop1). Modern identifiers reject uppercase, so
feature placement aborts for the chunk and the chest generates lootless.
The SAME jar ships the tables correctly renamed to snake_case
(data/epic/loot_table/chests/dungeon_poop1.json) - the references inside
the templates were just never updated. This rewrites them to match.

Safe + idempotent:
  - a patched jar contains no CamelCase refs, so re-runs are no-ops;
  - the itzg MODS_FILE flow only downloads MISSING files and the prune
    manifest matches by filename, so a patched jar persists on the server;
  - server-side only - clients never need structure loot data.

NBT strings are u16 big-endian length-prefixed UTF-8 inside gzip; each
rewrite adjusts the prefix, so no NBT library is needed. Only ids whose
snake_case target actually exists as a shipped loot table are rewritten.

Usage: python3 patch-mod-data.py <mods-dir>
Exit 0 always (a failed patch must never block a deploy); prints a summary.
"""

import gzip
import re
import shutil
import struct
import sys
import zipfile
from pathlib import Path

CAMEL_REF = re.compile(rb"epic:chests/[A-Za-z0-9]+")


def snake(name: bytes) -> bytes:
    return re.sub(rb"(?<=[a-z0-9])([A-Z])", rb"_\1", name).lower()


def patch_nbt_bytes(data: bytes, valid_tables: set) -> tuple:
    """Rewrite CamelCase loot refs in decompressed NBT. Returns (data, count)."""
    count = 0
    pos = 0
    while True:
        m = CAMEL_REF.search(data, pos)
        if m is None:
            break
        start = m.start()
        # The TAG_String length prefix (u16 BE) sits immediately before.
        if start < 2:
            pos = m.end()
            continue
        (length,) = struct.unpack(">H", data[start - 2 : start])
        old = data[start : start + length]
        if length != m.end() - m.start() or not old.startswith(b"epic:chests/"):
            pos = m.end()
            continue
        name = old[len(b"epic:chests/") :]
        new_name = snake(name)
        if new_name == name:
            pos = m.end()
            continue
        if new_name.decode() not in valid_tables:
            print(f"    skip: no shipped table for {old.decode()} -> {new_name.decode()}")
            pos = m.end()
            continue
        new = b"epic:chests/" + new_name
        data = data[: start - 2] + struct.pack(">H", len(new)) + new + data[start + length :]
        count += 1
        pos = start + len(new)
    return data, count


def patch_jar(jar_path: Path) -> int:
    with zipfile.ZipFile(jar_path) as zf:
        names = zf.namelist()
        valid_tables = {
            Path(n).stem
            for n in names
            if n.startswith("data/epic/loot_table/chests/") and n.endswith(".json")
        }
        if not valid_tables:
            return 0
        replacements = {}
        total = 0
        for n in names:
            if not n.endswith(".nbt"):
                continue
            raw = zf.read(n)
            try:
                data = gzip.decompress(raw)
            except OSError:
                continue
            if not CAMEL_REF.search(data):
                continue
            patched, count = patch_nbt_bytes(data, valid_tables)
            if count:
                replacements[n] = gzip.compress(patched)
                total += count

    if not replacements:
        return 0

    tmp = jar_path.with_suffix(".patched-tmp")
    with zipfile.ZipFile(jar_path) as zin, zipfile.ZipFile(
        tmp, "w", zipfile.ZIP_DEFLATED
    ) as zout:
        for item in zin.infolist():
            payload = replacements.get(item.filename, None)
            if payload is None:
                payload = zin.read(item.filename)
            zout.writestr(item, payload)
    shutil.move(str(tmp), str(jar_path))
    return total


def main():
    if len(sys.argv) != 2:
        print("usage: patch-mod-data.py <mods-dir>")
        return
    mods_dir = Path(sys.argv[1])
    for jar in sorted(mods_dir.glob("*.jar")):
        if "epic" not in jar.name.lower():
            continue
        try:
            n = patch_jar(jar)
        except Exception as e:  # never block a deploy
            print(f"  patch-mod-data: FAILED on {jar.name}: {e}")
            continue
        if n:
            print(f"  patch-mod-data: {jar.name}: rewrote {n} loot table reference(s)")
        else:
            print(f"  patch-mod-data: {jar.name}: nothing to patch (ok)")


main()
