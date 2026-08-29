#!/usr/bin/env python3
"""Generate the open-water placement guard datapack.

Purpose:  Land structures gate on a biome tag that contains ocean biomes, so
          houses, windmills and sheds generate standing in open sea. This
          writes Lithostitched `set_structure_spawn_condition` modifiers that
          add a placement condition to each land structure: not in an ocean
          biome, and not tens of blocks under the water surface. Ocean
          villages get a separate condition that keeps them within sight of a
          coast instead of mid-ocean.

Context:  The condition wraps the structure's registry entry
          (DelegatingStructure); the structure's own `biomes` field is
          untouched, so `/customdim catalogue` reports the same biome set
          before and after — placement counts are the only evidence.
          Structure-set placement is a different mechanism
          (scripts/gen-structure-presets.py) and the two do not overlap.

Input:    config/custom-dimensions/extractors/registries.json — the LIVE
          registry dump from `/customdim catalogue`. Convention tags resolve
          at runtime, so a jar scan cannot answer "does this tag contain
          oceans" (`#c:is_overworld` is 92 biomes with no oceans in a jar and
          305 with 9 oceans in game). Refresh it with
          scripts/extract-registries.py before re-running.

Output:   config/datapacks/open-water/

Usage:    scripts/gen-open-water-guard.py [--check]
          --check exits 1 when the written pack would differ (CI, lint).

Gotchas:  - A structure is "marine by design" when at least half its valid
            biomes are ocean; everything below that threshold and on a
            surface step is guarded. EXEMPT holds the exceptions that rule
            gets wrong — sky structures, underground ones, our own shrines.
          - Every guarded namespace needs a NS_SLUG entry: ownership.json is
            what lets deploy.sh strip a modifier whose mod a consumer
            removed, and an unknown structure id in a modifier fails
            datapack load.
"""

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
REGISTRIES = REPO / "config/custom-dimensions/extractors/registries.json"
OUT = REPO / "config/datapacks/open-water"
NAMESPACE = "adventure"
WATER_TAG = f"{NAMESPACE}:open_water"

# Ocean membership comes from these tags, unioned. Both are runtime-populated,
# so modded oceans that register into either are covered without a list here.
OCEAN_TAGS = ("minecraft:is_ocean", "c:is_ocean", "c:is_deep_ocean", "c:is_shallow_ocean")

# Below this fraction of ocean biomes a structure is land-going.
MARINE_FRACTION = 0.5

# Steps whose structures are visible above ground. An underground structure
# under a seabed is correct, not a misplacement.
GUARDED_STEPS = {"surface_structures"}

# Structures the fraction rule misclassifies.
EXEMPT = {
    "minecraft:stronghold",          # underground; vanilla ring placement
    "adventure:exit_shrine",         # per-dimension opt-in, may need an ocean
    "skyvillages:skyvillage",        # floats above the terrain
    "mvs:floating_islands",          # floats above the terrain
    "mvs:large_floating_island",     # floats above the terrain
}

# Guarded namespace -> modrinth slug, for ownership.json.
NS_SLUG = {
    "mvs": "moogs-voyager-structures",
    "mss": "mss-moogs-soaring-structures",
    "towns_and_towers": "towns-and-towers",
}

# Ocean villages stay, but only where a sample within RADIUS blocks is not open
# water — coastal and island villages survive, mid-ocean ones do not.
COASTAL = {
    "towns_and_towers:village_ocean": {"radius": 192, "step": 48},
    "towns_and_towers:pillager_outpost_ocean": {"radius": 192, "step": 48},
}

# A guarded structure may sit this far below the water/ground surface at its own
# generation point. Relative to WORLD_SURFACE_WG, so it holds in a dimension
# with any sea level.
MAX_SUBMERSION = 5


def not_open_water():
    return {
        "type": "lithostitched:not",
        "condition": {"type": "lithostitched:in_biome", "biomes": f"#{WATER_TAG}"},
    }


def on_dry_land():
    return {
        "type": "lithostitched:all_of",
        "conditions": [
            not_open_water(),
            {
                "type": "lithostitched:height_filter",
                "range_type": "heightmap_relative",
                "heightmap": "WORLD_SURFACE_WG",
                "permitted_range": {"min_inclusive": -MAX_SUBMERSION, "max_inclusive": 512},
            },
        ],
    }


def near_land(radius, step):
    return {
        "type": "lithostitched:grid",
        "radius": radius,
        "distance_between_points": step,
        "allowed_count": {"min_inclusive": 1, "max_inclusive": 4096},
        "condition": not_open_water(),
    }


def ocean_biomes(tags):
    out = set()
    for t in OCEAN_TAGS:
        out |= set(tags.get(t, []))
    return out


def guarded(registries):
    """Land structures that can currently generate in an ocean biome, by namespace."""
    tags, structures = registries["biomeTags"], registries["structures"]
    oceans = ocean_biomes(tags)
    by_ns = {}
    for sid, info in sorted(structures.items()):
        if sid in EXEMPT or info.get("step") not in GUARDED_STEPS:
            continue
        values = tags.get(info.get("biomeTag") or "")
        if not values:
            continue
        wet = len(oceans & set(values))
        if not wet or wet / len(values) >= MARINE_FRACTION:
            continue
        by_ns.setdefault(sid.split(":", 1)[0], []).append(sid)
    return by_ns


def build(registries):
    """Return {relative path: json body} for the whole pack."""
    files = {
        "pack.mcmeta": {
            "pack": {
                "pack_format": 48,
                "supported_formats": [48, 999],
                "description": "Adventure structure tuning: land structures stay out of open water",
            }
        },
        f"data/{NAMESPACE}/tags/worldgen/biome/open_water.json": {
            "values": [{"id": f"#{t}", "required": False} for t in OCEAN_TAGS]
        },
    }
    ownership = {}
    modifiers = f"data/{NAMESPACE}/lithostitched/worldgen_modifier"

    for ns, ids in sorted(guarded(registries).items()):
        if ns not in NS_SLUG:
            sys.exit(f"{ns}: no NS_SLUG entry — add the modrinth slug before shipping")
        rel = f"{modifiers}/land_structures_{ns}.json"
        files[rel] = {
            "type": "lithostitched:set_structure_spawn_condition",
            "structures": ids,
            "spawn_condition": on_dry_land(),
            "append": True,
        }
        ownership[rel] = NS_SLUG[ns]

    known = set(registries["structures"])
    for sid, dials in sorted(COASTAL.items()):
        if sid not in known:
            continue
        ns = sid.split(":", 1)[0]
        rel = f"{modifiers}/coastal_{sid.split(':', 1)[1]}.json"
        files[rel] = {
            "type": "lithostitched:set_structure_spawn_condition",
            "structures": sid,
            "spawn_condition": near_land(dials["radius"], dials["step"]),
            "append": True,
        }
        ownership[rel] = NS_SLUG[ns]

    files["ownership.json"] = dict(sorted(ownership.items()))
    return files


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="exit 1 if the pack on disk is stale")
    args = ap.parse_args()

    if not REGISTRIES.is_file():
        sys.exit(f"missing {REGISTRIES} — run scripts/extract-registries.py")
    files = build(json.loads(REGISTRIES.read_text()))

    stale = []
    for rel, body in sorted(files.items()):
        text = json.dumps(body, indent=1) + "\n"
        dest = OUT / rel
        if args.check:
            if not dest.is_file() or dest.read_text() != text:
                stale.append(rel)
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text)

    if args.check:
        extra = sorted(str(p.relative_to(OUT)) for p in OUT.rglob("*")
                       if p.is_file() and str(p.relative_to(OUT)) not in files)
        if stale or extra:
            for rel in stale + extra:
                print(f"  stale: {rel}")
            sys.exit(f"{OUT} is out of date — run {Path(__file__).name}")
        print(f"{OUT}: up to date ({len(files)} files)")
        return

    guards = sum(len(v) for v in guarded(json.loads(REGISTRIES.read_text())).values())
    print(f"{OUT}: {guards} land structures guarded, {len(COASTAL)} kept coastal")


if __name__ == "__main__":
    main()
