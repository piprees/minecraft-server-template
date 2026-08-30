#!/usr/bin/env python3
"""Generate the open-water placement guard datapack.

Purpose:  Land structures generate standing in open sea because
          NoiseStructureSelectionMixin replaces the assigned structure's
          biome predicate with ANY_BIOME at every noise site. This writes
          Lithostitched `set_structure_spawn_condition` modifiers restoring
          an ocean check that the bypass cannot reach: a spawn condition runs
          after the predicate, so `not(in_biome #adventure:open_water)` holds
          where the structure's own `biomes` field no longer does. Ocean
          villages get a condition keeping them within sight of a coast.

Context:  The condition wraps the structure's registry entry
          (DelegatingStructure), preserving its biomes, spawn overrides, step
          and terrain adaptation. The structure's own `biomes` field is
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
          - A depth check belongs here and cannot be written. `height_filter`
            is the only condition that reads terrain, and heightmap-relative
            against WORLD_SURFACE_WG measures from the bedrock roof in a
            nether dimension, which rejects every nether structure. Absolute
            ranges are worse: 25 dimensions set sea levels from -64 to 90.
            No condition subtracts two heightmaps, so "is it underwater" is
            unreachable and only the biome check ships.
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
    "adventuredungeons": "adventure-dungeons",
    "ati_structures": "ati-structures-fabricforge",
    "betterdeserttemples": "yungs-better-desert-temples",
    "betterend": "betterend",
    "betterfortresses": "yungs-better-nether-fortresses",
    "betterjungletemples": "yungs-better-jungle-temples",
    "betternether": "betternether",
    "betterwitchhuts": "yungs-better-witch-huts",
    "ddd": "deadly-deadly-dungeon",
    "dungeons_arise": "when-dungeons-arise",
    "epic": "epic-structures-dungeons",
    "explorify": "explorify",
    "friendsandfoes": "friends-and-foes",
    "medievalend": "medieval-buildings-end-edition",
    "mes": "mes-moogs-end-structures",
    "minecraft": "minecraft",
    "mns": "mns-moogs-nether-structures",
    "mss": "mss-moogs-soaring-structures",
    "mtr": "mtr-moogs-temples-reimagined",
    "mvs": "moogs-voyager-structures",
    "natures_spirit": "natures-spirit",
    "nova_structures": "dungeons-and-taverns",
    "nullscape": "nullscape",
    "paradise_lost": "paradise-lost",
    "philipsruins": "philips-ruins",
    "simply_houses": "simply-houses",
    "skyvillages": "sky-villages",
    "stoneholm": "stoneholm",
    "structory": "structory",
    "structory_towers": "structory-towers",
    "terralith": "terralith",
    "towns_and_towers": "towns-and-towers",
    "undergroundworlds": "underground-worlds",
}

# Ocean villages stay, but only where a sample within RADIUS blocks is not open
# water — coastal and island villages survive, mid-ocean ones do not.
COASTAL = {
    "towns_and_towers:village_ocean": {"radius": 192, "step": 48},
    "towns_and_towers:pillager_outpost_ocean": {"radius": 192, "step": 48},
}


def not_open_water():
    return {
        "type": "lithostitched:not",
        "condition": {"type": "lithostitched:in_biome", "biomes": f"#{WATER_TAG}"},
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
    """Land structures on a surface step, by namespace.

    Every one of them, not only those whose biome tag contains an ocean: the
    noise placement bypasses the biome predicate, so a structure's declared
    biomes no longer constrain where it starts.
    """
    tags, structures = registries["biomeTags"], registries["structures"]
    oceans = ocean_biomes(tags)
    by_ns = {}
    for sid, info in sorted(structures.items()):
        if sid in EXEMPT or info.get("step") not in GUARDED_STEPS:
            continue
        values = tags.get(info.get("biomeTag") or "")
        if values and len(oceans & set(values)) / len(values) >= MARINE_FRACTION:
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
            "spawn_condition": not_open_water(),
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
