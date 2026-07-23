#!/usr/bin/env python3
"""gen-exit-shrine.py — generate the adventure:exit_shrine structure templates.

Writes vanilla structure-template NBT (gzipped, big-endian) into the
custom-dimensions jar datapack at data/adventure/structure/exit_shrine/.
Run from the repo root after changing the shrine design; the templates are
committed (small, deterministic — same input, byte-identical output).

    python3 scripts/gen-exit-shrine.py

The shrine: a ruined stone-brick platform with corner pillars and a
standing crying-obsidian portal frame (X axis, 2-wide x 3-tall interior)
over a BEACON centrepiece buried under the frame centre. The beacon is
the mod's detection marker: chunks carry their block entities in a cheap
map, so ExitShrineManager scans newly loaded chunks for beacons and
registers any with a valid frame above as exit zones (see the Java side).
The portal interior ships as AIR — the mod lights it at registration
(same NOTIFY_LISTENERS | FORCE_STATE dance as ExitPortalManager; portal
blocks in templates get popped by neighbour updates during placement).

Deliberately squat (7x7 footprint, height 8) so it sits on terrain
without jigsaw terrain-matching gymnastics: one piece, one pool.
"""

import gzip
import struct
import sys
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent.parent / (
    "mods/custom-dimensions/src/main/resources/data/adventure/structure/exit_shrine")

# ---------------------------------------------------------------------------
# Minimal big-endian NBT writer (only the tags structure templates need).
# ---------------------------------------------------------------------------
TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def w_str(buf, s):
    data = s.encode("utf-8")
    buf += struct.pack(">H", len(data)) + data
    return buf


def w_tag(buf, tag_type, name):
    buf += struct.pack(">B", tag_type)
    return w_str(buf, name)


def w_int(buf, name, value):
    buf = w_tag(buf, TAG_INT, name)
    buf += struct.pack(">i", value)
    return buf


def w_string(buf, name, value):
    buf = w_tag(buf, TAG_STRING, name)
    return w_str(buf, value)


def w_int_list(buf, name, values):
    buf = w_tag(buf, TAG_LIST, name)
    buf += struct.pack(">Bi", TAG_INT, len(values))
    for v in values:
        buf += struct.pack(">i", v)
    return buf


def compound_list_header(buf, name, count):
    buf = w_tag(buf, TAG_LIST, name)
    buf += struct.pack(">Bi", TAG_COMPOUND, count)
    return buf


def end(buf):
    buf += struct.pack(">B", TAG_END)
    return buf


# ---------------------------------------------------------------------------
# Shrine geometry. Palette entries are (block_id, properties dict or None).
# ---------------------------------------------------------------------------
SIZE = (7, 8, 7)  # x, y, z

PALETTE = [
    ("minecraft:air", None),
    ("minecraft:cracked_stone_bricks", None),
    ("minecraft:mossy_stone_bricks", None),
    ("minecraft:stone_bricks", None),
    ("minecraft:mossy_stone_brick_wall", None),
    ("minecraft:crying_obsidian", None),
    ("minecraft:beacon", None),
    ("minecraft:chiseled_stone_bricks", None),
]
AIR, CRACKED, MOSSY, BRICK, WALL, FRAME, BEACON, CHISEL = range(8)


def build_blocks():
    """(x, y, z) -> palette index for every non-air block."""
    blocks = {}
    # y0: 7x7 floor plate, deterministic moss/crack mix
    for x in range(7):
        for z in range(7):
            blocks[(x, 0, z)] = (CRACKED, MOSSY, BRICK)[(x * 3 + z * 5) % 3]
    # Corner pillars (ruined: two tall, two stumps)
    for (px, pz, h) in ((0, 0, 3), (6, 0, 2), (0, 6, 2), (6, 6, 3)):
        for y in range(1, h + 1):
            blocks[(px, y, pz)] = BRICK if y < h else CHISEL
        blocks[(px, h + 1, pz)] = WALL
    # Beacon centrepiece buried in the plate under the frame centre (3, 0, 3)
    blocks[(3, 0, 3)] = BEACON
    # Portal frame on the plate, X axis, interior 2 wide x 3 tall:
    # columns x=2..5 at z=3; interior x=3..4, y=2..4 stays AIR.
    for x in range(2, 6):
        blocks[(x, 1, 3)] = FRAME          # bottom rail
        blocks[(x, 5, 3)] = FRAME          # top rail
    for y in range(2, 5):
        blocks[(2, y, 3)] = FRAME          # left column
        blocks[(5, y, 3)] = FRAME          # right column
    return blocks


def encode_template():
    blocks = build_blocks()
    buf = bytearray()
    # root compound (empty name)
    buf = w_tag(buf, TAG_COMPOUND, "")
    buf = w_int_list(buf, "size", list(SIZE))
    buf = w_int(buf, "DataVersion", 3955)  # 1.21.1

    # entities: empty list (element type TAG_END per vanilla convention)
    buf = w_tag(buf, TAG_LIST, "entities")
    buf += struct.pack(">Bi", TAG_END, 0)

    # palette
    buf = compound_list_header(buf, "palette", len(PALETTE))
    for block_id, props in PALETTE:
        buf = w_string(buf, "Name", block_id)
        if props:
            buf = w_tag(buf, TAG_COMPOUND, "Properties")
            for k, v in sorted(props.items()):
                buf = w_string(buf, k, v)
            buf = end(buf)
        buf = end(buf)

    # blocks
    entries = sorted(blocks.items())
    buf = compound_list_header(buf, "blocks", len(entries))
    for (x, y, z), state in entries:
        buf = w_int_list(buf, "pos", [x, y, z])
        buf = w_int(buf, "state", state)
        buf = end(buf)

    buf = end(buf)  # root
    return bytes(buf)


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    raw = encode_template()
    # mtime=0 => deterministic gzip output (byte-identical re-runs)
    out = OUT_DIR / "shrine_ruin.nbt"
    with open(out, "wb") as fh:
        with gzip.GzipFile(fileobj=fh, mode="wb", mtime=0) as gz:
            gz.write(raw)
    print(f"wrote {out} ({out.stat().st_size} bytes, {len(raw)} raw)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
