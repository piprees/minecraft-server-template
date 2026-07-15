#!/usr/bin/env python3
"""Generate the structure-frequency override datapack presets.

Purpose:  Build three variants of the `structures` override datapack
          (default / dense / sparse) from the curated dial list in
          mods/.ideas/customising-structures.csv. Each override is a
          WHOLE-FILE copy of the structure_set JSON from the exact pinned
          mod jar (world datapacks shadow mod data at the same path), with
          only placement fields changed.

Context:  mods/.ideas/customising-structures.md Option A. The default
          preset encodes the "sparse and natural" main-overworld intent:
          big/hostile sets rarer, villages and small settlements more
          common, fortified villages and a couple of castle sets kept,
          other landmarks slightly rarer.

Output:   config/datapacks/structures/            (active platform default)
          config/datapack-presets/{default,dense,sparse}/structures/
          (variants; a consumer copies one over
           overlay/config/datapacks/structures/ to swap preset)

Usage:    scripts/gen-structure-presets.py [--cache DIR]
          Downloads each needed pinned jar once (cached); vanilla files come
          from misode/mcmeta (1.21.1-data).

Gotchas:  - Re-run when any structure mod pin bumps (weekly mod-updates PR);
            the script warns when a jar's baseline spacing/separation drifts
            from the CSV's `current` column — re-verify the dial then.
          - Deliberately NEVER overridden: explorify + towns_and_towers
            (Cristel Lib rewrites their placements at runtime — a datapack
            override would fight it), YUNG sets with custom placement types,
            dungeons_reborn (no structure sets), ultra-rare by-design sets
            (nova shrine_tower, philipsruins rare_ruin), and vanilla sets
            other than minecraft:villages.
          - `frequency` changes are the safe knob (never move existing
            placements); the villages spacing change re-rolls the placement
            grid — new-world / new-major only.
"""

import argparse
import csv
import io
import json
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CSV_PATH = REPO / "mods/.ideas/customising-structures.csv"
MODS_TXT = REPO / "config/modrinth-mods.txt"
ACTIVE_OUT = REPO / "config/datapacks/structures"
PRESETS_OUT = REPO / "config/datapack-presets"
MCMETA_RAW = "https://raw.githubusercontent.com/misode/mcmeta/1.21.1-data/data/minecraft/worldgen/structure_set"

# CSV `mod` column -> modrinth-mods.txt slug (identity unless listed)
SLUG_ALIASES = {"dungeonsplus": "dungeons+"}

# Sets we never touch, beyond the rule-level exclusions.
NEVER = {
    "nova_structures:shrine_tower",      # ultra-rare by design (600/312)
    "philipsruins:rare_ruin",            # ultra-rare by design (360/130)
}
# Mods excluded wholesale.
EXCLUDED_MODS = {
    "explorify", "towns-and-towers",     # Cristel Lib owns their placement
    "dungeonsreborn",                    # placed features, no structure sets
}
# Landmark-theme sets kept at default in the `default` preset ("a handful of
# castles"): fortified villages are theme=settlement and untouched anyway.
CASTLE_KEEPS = {"mvs:castle_ruins", "mss:castle_tower"}

PACK_DESCRIPTION = {
    "default": "Adventure structure tuning: sparse & natural overworld",
    "dense": "Adventure structure tuning: structure-dense world",
    "sparse": "Adventure structure tuning: structure-sparse world",
}


def parse_current(cur):
    """CSV `current` -> (spacing, separation, frequency|None); None when n/a."""
    m = re.match(r"^(\d+)/(\d+)(?:\s+f=([0-9.]+))?$", cur.strip())
    if not m:
        return None
    return int(m.group(1)), int(m.group(2)), float(m.group(3)) if m.group(3) else None


def freq_mul(body, factor):
    f = float(body["placement"].get("frequency", 1.0)) * factor
    body["placement"]["frequency"] = round(min(1.0, f), 4)


def spacing_scale(body, factor):
    p = body["placement"]
    spacing = max(2, round(int(p["spacing"]) * factor))
    separation = min(spacing - 1, max(1, round(int(p["separation"]) * factor)))
    p["spacing"], p["separation"] = spacing, separation


def set_spacing(body, spacing, separation=None):
    p = body["placement"]
    p["spacing"] = spacing
    if separation is not None:
        p["separation"] = separation
    if p["separation"] >= p["spacing"]:
        p["separation"] = p["spacing"] - 1


# --- The dial tables ----------------------------------------------------------
# Explicit dials (CSV "CONFIGURE" rows + task intent), per preset:
# value = (fn, args) applied to the copied structure_set body.
EXPLICIT = {
    "default": {
        # CSV dense-set suggestions
        "dungeons_plus:dungeons": (freq_mul, 0.5 / 0.9),   # 8/3 f=0.9 -> f=0.5
        "betterdungeons:small_dungeons": (freq_mul, 0.6),
        "philipsruins:ancient_dungeon": (freq_mul, 0.5),
        "terralith:underground": (freq_mul, 0.7),
        "terralith:underground_dungeon": (freq_mul, 0.6),
        "ati_structures:underground_small": (freq_mul, 0.6),
        "nova_structures:deepslate_camp": (freq_mul, 0.7),
        # Flagship mega-dungeons rarer (task: WDA major f~0.5)
        "dungeons_arise:major_structures": (freq_mul, 0.5),
        # Villages and small settlements MORE common: spacing down ~25%
        "minecraft:villages": (set_spacing, 26),
        "nova_structures:villages_birch": (set_spacing, 26),
        "nova_structures:villages_jungle": (set_spacing, 26),
        "nova_structures:villages_swamp": (set_spacing, 26),
    },
    "dense": {
        "dungeons_arise:major_structures": (set_spacing, (30, 20)),  # report's boost
        "bettermineshafts:mineshafts": (freq_mul, 2.0),  # chance-per-chunk: frequency only
        "minecraft:villages": (set_spacing, 26),
        "nova_structures:villages_birch": (set_spacing, 26),
        "nova_structures:villages_jungle": (set_spacing, 26),
        "nova_structures:villages_swamp": (set_spacing, 26),
    },
    "sparse": {
        "bettermineshafts:mineshafts": (freq_mul, 0.5),
    },
}

# Theme-level rules per preset: theme -> (fn, args), applied when no explicit
# dial exists. `dims_filter` restricts the default preset's landmark thinning
# to the overworld ("main overworld intent"); dense/sparse apply everywhere.
RULES = {
    "default": {
        "landmark": ((freq_mul, 0.85), {"overworld", "sky+overworld"}),
    },
    "dense": {
        "dungeon": ((spacing_scale, 0.7), None),
        "loot": ((spacing_scale, 0.7), None),
        "landmark": ((spacing_scale, 0.8), None),
    },
    "sparse": {
        "dungeon": ((freq_mul, 0.5), None),
        "loot": ((freq_mul, 0.5), None),
        "landmark": ((freq_mul, 0.5), None),
        "maritime": ((freq_mul, 0.7), None),
        "settlement": ((freq_mul, 0.7), None),
    },
}
# sparse: villages stay at mod default (settlement rule excludes them below).
SPARSE_SETTLEMENT_KEEPS = {
    "minecraft:villages", "nova_structures:villages_birch",
    "nova_structures:villages_jungle", "nova_structures:villages_swamp",
}
# sparse: MVS ambient clutter thinned per CSV suggestion.
MVS_DECO_SPARSE_FACTOR = 0.6


def load_rows():
    rows = []
    with open(CSV_PATH, newline="") as fh:
        for row in csv.DictReader(fh):
            rows.append(row)
    return rows


def pins():
    out = {}
    for line in MODS_TXT.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        entry = line.split("#")[0].strip()
        if entry.startswith("datapack:"):
            entry = entry[len("datapack:"):]
        if ":" not in entry:
            continue
        slug, version_id = entry.rsplit(":", 1)
        out[slug] = version_id
    return out


def fetch(url, cache_dir, name):
    dest = cache_dir / name
    if dest.exists():
        return dest.read_bytes()
    print(f"  fetching {name} ...")
    with urllib.request.urlopen(url) as r:
        data = r.read()
    dest.write_bytes(data)
    return data


def jar_for(slug, version_id, cache_dir):
    api = f"https://api.modrinth.com/v2/version/{version_id}"
    meta = json.loads(fetch(api, cache_dir, f"{slug}-{version_id}.meta.json"))
    for f in meta["files"]:
        if f.get("primary"):
            return fetch(f["url"], cache_dir, f"{slug}-{version_id}.zip")
    raise SystemExit(f"no primary file for {slug}:{version_id}")


def structure_set_from_zip(data, set_id):
    ns, path = set_id.split(":", 1)
    inner = f"data/{ns}/worldgen/structure_set/{path}.json"
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        for name in zf.namelist():
            if name == inner or name.endswith("/" + inner):
                return json.loads(zf.read(name))
    return None


def custom_placement(body):
    return body["placement"].get("type", "minecraft:random_spread") != "minecraft:random_spread"


def decide(preset, row):
    """Return (fn, args) or None for a CSV row under a preset."""
    set_id = row["structure_set"]
    theme = row["theme"]
    if set_id in NEVER or row["mod"] in EXCLUDED_MODS:
        return None
    if "custom placement type" in (row.get("notes") or ""):
        return None
    explicit = EXPLICIT[preset].get(set_id)
    if explicit:
        fn, args = explicit
        return fn, args
    if row["mod"] == "minecraft":
        return None  # vanilla sets only via explicit dials
    if set_id == "bettermineshafts:mineshafts":
        return None  # explicit-only (chance-per-chunk model)
    if preset == "sparse" and theme == "settlement" and set_id in SPARSE_SETTLEMENT_KEEPS:
        return None
    if preset == "sparse" and theme == "deco" and set_id.startswith("mvs:") \
            and "MVS ambient clutter" in row["rec_global"]:
        return freq_mul, MVS_DECO_SPARSE_FACTOR
    rule = RULES[preset].get(theme)
    if not rule:
        return None
    (fn, args), dims_filter = rule
    if preset == "default":
        if set_id in CASTLE_KEEPS:
            return None
        if dims_filter and row["dims"] not in dims_filter:
            return None
    return fn, args


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=str(REPO / ".cache/structure-jars"))
    args = ap.parse_args()
    cache_dir = Path(args.cache)
    cache_dir.mkdir(parents=True, exist_ok=True)

    rows = load_rows()
    pin_map = pins()
    jars = {}

    counts = {}
    for preset in ("default", "dense", "sparse"):
        out_root = PRESETS_OUT / preset / "structures"
        written = 0
        for row in rows:
            action = decide(preset, row)
            if action is None:
                continue
            set_id = row["structure_set"]
            mod = row["mod"]
            if mod == "minecraft":
                path = set_id.split(":", 1)[1]
                body = json.loads(fetch(f"{MCMETA_RAW}/{path}.json", cache_dir,
                                        f"vanilla-{path}.json"))
            else:
                slug = SLUG_ALIASES.get(mod, mod)
                if slug not in pin_map:
                    raise SystemExit(f"{mod}: no pin found in modrinth-mods.txt")
                if slug not in jars:
                    jars[slug] = jar_for(slug, pin_map[slug], cache_dir)
                body = structure_set_from_zip(jars[slug], set_id)
                if body is None:
                    raise SystemExit(f"{set_id} not found in {slug} jar")
            if custom_placement(body):
                print(f"  skip {set_id}: custom placement type "
                      f"{body['placement'].get('type')} (caveat)")
                continue

            # Baseline drift alarm: jar vs CSV `current`.
            cur = parse_current(row["current"] or "")
            if cur:
                sp, se, fr = cur
                p = body["placement"]
                if int(p.get("spacing", -1)) != sp or int(p.get("separation", -1)) != se:
                    print(f"  WARNING: {set_id} baseline drifted: jar "
                          f"{p.get('spacing')}/{p.get('separation')} vs CSV {sp}/{se}")

            fn, fargs = action
            if fn is set_spacing and isinstance(fargs, tuple):
                fn(body, *fargs)
            else:
                fn(body, fargs)

            ns, path = set_id.split(":", 1)
            dest = out_root / "data" / ns / "worldgen/structure_set" / (path + ".json")
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(json.dumps(body, indent=1) + "\n")
            written += 1

        (out_root / "pack.mcmeta").write_text(json.dumps({
            "pack": {
                "pack_format": 48,
                "supported_formats": [48, 999],
                "description": PACK_DESCRIPTION[preset],
            }
        }, indent=1) + "\n")
        counts[preset] = written
        print(f"preset {preset}: {written} structure_set overrides")

    # The active platform pack is the default preset, copied verbatim.
    import shutil
    if ACTIVE_OUT.exists():
        shutil.rmtree(ACTIVE_OUT)
    shutil.copytree(PRESETS_OUT / "default/structures", ACTIVE_OUT)
    print(f"active pack refreshed: {ACTIVE_OUT} ({counts['default']} overrides)")

    # Theme map for the custom-dimensions mod (per-dimension structure
    # control needs runtime theme knowledge; jar resource, not a datapack).
    themes = {}
    for row in rows:
        set_id = row["structure_set"]
        if ":" not in set_id or "(" in set_id:
            continue  # marker rows (e.g. dungeons_reborn placed-features note)
        themes[set_id] = row["theme"]
    themes_path = REPO / "mods/custom-dimensions/src/main/resources/structure_themes.json"
    themes_path.write_text(json.dumps(dict(sorted(themes.items())), indent=1) + "\n")
    print(f"theme map: {len(themes)} entries -> {themes_path}")


if __name__ == "__main__":
    main()
