#!/usr/bin/env python3
"""Generate the adventure:end_open noise settings preset.

Purpose:  Vanilla's End with its origin-anchored island field replaced by an
          origin-free one, so an end-typed dimension gets End terrain shape
          without the central island and void moat every end dimension
          otherwise inherits at world origin.

Context:  minecraft:end_islands is a 2D field, measured on this stack at
          -0.84375 across the open plane and +0.5625 at world origin — the
          centre island is special-cased inside the type, so no constant can
          remove it without also removing every outer island. This maps a
          plain noise onto that same measured range instead.

Input:    vanilla data/minecraft/worldgen/noise_settings/end.json, read from
          the Loom-extracted server jar. Every field except the island term
          is carried through untouched.

Output:   mods/custom-dimensions/src/main/resources/data/adventure/worldgen/
            noise_settings/end_open.json

Usage:    scripts/gen-end-open-preset.py [--gain G] [--check]
          --check exits 1 if the committed output is stale.

Gotchas:  - The island field is INLINE, not a separate density_function file.
            gen-terrain-presets.py deletes data/adventure/worldgen/
            density_function/ wholesale, so a file there would not survive.
            noise_settings/ is safe: that script unlinks only wide.json and
            compressed.json by name.
          - minecraft:continentalness is a vanilla noise id, used unchanged.
            Noise ids are seeded by hashing the id string, so reusing a
            vanilla one is exact and needs no cloned copy.
"""

import argparse
import copy
import json
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = (REPO / "mods/custom-dimensions/src/main/resources/data/adventure"
       / "worldgen/noise_settings/end_open.json")
JAR = Path.home() / ".gradle/caches/fabric-loom/1.21.1/minecraft-extracted_server.jar"
VANILLA_ENTRY = "data/minecraft/worldgen/noise_settings/end.json"

# Measured from minecraft:end_islands on this stack: the open plane sits at
# the floor and world origin reaches the peak.
FLOOR = -0.84375
PEAK = 0.5625


def island_field(gain):
    """The island term: a 2D noise mapped onto end_islands' measured range."""
    return {
        "type": "minecraft:cache_2d",
        "argument": {
            "type": "minecraft:add",
            "argument1": FLOOR,
            "argument2": {
                "type": "minecraft:mul",
                "argument1": PEAK - FLOOR,
                "argument2": {
                    "type": "minecraft:clamp",
                    "min": 0.0,
                    "max": 1.0,
                    "input": {
                        "type": "minecraft:mul",
                        "argument1": gain,
                        "argument2": {
                            "type": "minecraft:noise",
                            "noise": "minecraft:continentalness",
                            "xz_scale": 0.5,
                            "y_scale": 0.0,
                        },
                    },
                },
            },
        },
    }


def build(gain):
    with zipfile.ZipFile(JAR) as zf:
        vanilla = json.loads(zf.read(VANILLA_ENTRY))

    island = island_field(gain)
    # minecraft:end/sloped_cheese is add(end_islands, base_3d_noise); only the
    # island half is replaced, so the 3D shape of the terrain is vanilla's.
    sloped = {"type": "minecraft:add",
              "argument1": copy.deepcopy(island),
              "argument2": "minecraft:end/base_3d_noise"}

    def sub(node):
        if isinstance(node, str):
            return copy.deepcopy(sloped) if node == "minecraft:end/sloped_cheese" else node
        if isinstance(node, dict):
            if node.get("type") == "minecraft:end_islands":
                return copy.deepcopy(island)
            return {k: sub(v) for k, v in node.items()}
        if isinstance(node, list):
            return [sub(v) for v in node]
        return node

    out = sub(copy.deepcopy(vanilla))
    text = json.dumps(out, indent=1, sort_keys=True) + "\n"
    if "end_islands" in text or "sloped_cheese" in text:
        raise SystemExit("an origin-anchored node survived the substitution")
    if "minecraft:end/base_3d_noise" not in text:
        raise SystemExit("the vanilla 3D noise reference was lost")
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gain", type=float, default=3.2,
                    help="noise multiplier before the 0..1 clamp; higher makes islands larger and more frequent")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    text = build(args.gain)
    if args.check:
        if not OUT.exists() or OUT.read_text() != text:
            print(f"STALE: {OUT}", file=sys.stderr)
            return 1
        print(f"up to date: {OUT}")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    print(f"wrote {OUT} (gain {args.gain})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
