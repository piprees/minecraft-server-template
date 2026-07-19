#!/usr/bin/env bash
# migrate-to-v4-config.sh — Split the monolithic multiverse_config.json into
# the v4 per-dimension directory (config/custom-dimensions/).
#
# Context: Custom Dimensions v4 Phase 1. Reads config/multiverse_config.json
# and config/configurable-difficulty/configurable-difficulty.json5, writes:
#   config/custom-dimensions/settings.json            global defaults
#   config/custom-dimensions/dimensions/{slug}.json   one file per dimension,
#                                                     portal + difficulty merged
#                                                     in, seedRoll preserved
#                                                     verbatim
# Base worlds (overworld, the_nether, the_end, paradise_lost) become files
# named after their slug; the top-level worldSeed lands on overworld.json.
#
# Usage:
#   ./scripts/migrate-to-v4-config.sh [project_root]   # default: repo root
#
# Idempotent: output is deterministic; stale *.json files under dimensions/
# that no longer correspond to a config entry are removed. The monolithic
# config is left untouched (the mod keeps reading it as a deprecated
# fallback until it is deleted).
#
# Gotchas:
#   - Python (not jq) does the JSON work: the difficulty config is JSON5
#     with // comments that need stripping.
#   - macOS bash 3.2 compatible.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${1:-$(cd "$SCRIPT_DIR/.." && pwd)}"

MONOLITH="$PROJECT_ROOT/config/multiverse_config.json"
DIFFICULTY="$PROJECT_ROOT/config/configurable-difficulty/configurable-difficulty.json5"
OUT_DIR="$PROJECT_ROOT/config/custom-dimensions"

[[ -f "$MONOLITH" ]] || {
  echo "Error: $MONOLITH not found" >&2
  exit 1
}

python3 - "$MONOLITH" "$DIFFICULTY" "$OUT_DIR" << 'PYEOF'
import json
import re
import sys
from pathlib import Path

monolith_path, difficulty_path, out_dir = sys.argv[1:4]
cfg = json.loads(Path(monolith_path).read_text())
out = Path(out_dir)
dims_dir = out / "dimensions"
dims_dir.mkdir(parents=True, exist_ok=True)

# --- configurable-difficulty.json5 (full-line // comments only) --------------
difficulty = {}
dp = Path(difficulty_path)
if dp.exists():
    text = re.sub(r"^\s*//.*$", "", dp.read_text(), flags=re.M)
    try:
        difficulty = json.loads(text)
    except json.JSONDecodeError as e:
        sys.exit(f"Error: could not parse {difficulty_path}: {e}")
mob_mult = difficulty.get("dimensionMultipliers", {})
luck_mult = difficulty.get("dimensionLuckMultipliers", {})

# --- settings.json -----------------------------------------------------------
# NOTES:
# - defaults.difficulty deliberately carries NO hostileSpawning — the
#   loader merges defaults UNDER every dimension, and a default there would
#   shadow a legacy top-level "hostileSpawning" flag in hand-written files.
# - No depthScaling default either: the old configurable-difficulty mod
#   applied depth scaling in the OVERWORLD only, so it lands explicitly on
#   overworld.json below. A default here would scale every dimension.
settings = {
    "namespace": cfg.get("namespace", "adventure"),
    "idleUnloadMinutes": cfg.get("idleUnloadMinutes", 5),
    "frames": {
        "overworld": cfg.get("frameOverworld", "minecraft:crying_obsidian"),
        "nether": cfg.get("frameNether", "minecraft:obsidian"),
        "end": cfg.get("frameEnd", "minecraft:iron_block"),
    },
    "defaults": {
        "frameBlock": cfg.get("frameOverworld", "minecraft:crying_obsidian"),
        "borders": {"player": 8192, "generation": 8192},
        "difficulty": {
            "mobMultiplier": 1.0,
            "attributes": {
                "health": difficulty.get("enableHealth", True),
                "damage": difficulty.get("enableDamage", True),
                "armor": difficulty.get("enableArmor", True),
                "speed": difficulty.get("enableSpeed", False),
                "knockback": difficulty.get("enableKnockback", False),
            },
            "playerLuck": 1.0,
        },
    },
}

def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")

write_json(out / "settings.json", settings)

portals = {p["id"]: p for p in cfg.get("portals", [])}
expected = set()

def existing_difficulty(slug):
    """Difficulty block already present in a previously generated file —
    the fallback source once configurable-difficulty.json5 is gone (the
    per-dimension files ARE the record after the first migration). Without
    this, re-running the migration after the json5's removal silently
    stripped every mobMultiplier (caught 2026-07-19)."""
    f = dims_dir / f"{slug}.json"
    if not f.exists():
        return {}
    try:
        block = json.loads(f.read_text()).get("difficulty")
    except (json.JSONDecodeError, OSError):
        return {}
    return dict(block) if isinstance(block, dict) else {}

def difficulty_block(slug, dimension_id, hostile_spawning):
    block = existing_difficulty(slug)
    block.pop("hostileSpawning", None)
    if hostile_spawning is not None:
        block["hostileSpawning"] = hostile_spawning
    if dimension_id in mob_mult:
        block["mobMultiplier"] = mob_mult[dimension_id]
    if dimension_id in luck_mult:
        block["playerLuck"] = luck_mult[dimension_id]
    return block or None

# Border radii mirror the old deploy.sh dance: PLAYER_BORDER_RADIUS (8192)
# scaled by portal travel ratio for custom dims; the fixed per-world values
# for the base worlds. The mod applies borders.player as the vanilla world
# border at boot (Phase 3); borders.generation is tooling metadata.
BASE_RADIUS = 8192
BASE_WORLD_RADII = {"overworld": 8192, "the_nether": 1024,
                    "the_end": 4096, "paradise_lost": 4096}

def borders_block(radius):
    radius = int(round(radius))
    return {"player": radius, "generation": radius}

def portal_block(slug):
    p = portals.get(slug)
    if p is None:
        return None
    block = {}
    for key in ("frameBlock", "igniterItem", "color", "lightLevel", "scale",
                "cooldown", "particleType"):
        if key in p:
            block[key] = p[key]
    block["sounds"] = {
        "ignite": p.get("igniteSound", "block.portal.trigger"),
        "enter": p.get("enterSound", "block.portal.travel"),
        "exit": p.get("exitSound", "block.portal.travel"),
    }
    return block

def put(target, key, value):
    if value is not None:
        target[key] = value

# --- one file per custom dimension ------------------------------------------
for d in cfg.get("dimensions", []):
    slug = d["name"]
    sr = d.get("seedRoll")
    entry = {}
    put(entry, "type", d.get("type"))
    if isinstance(sr, dict) and sr.get("description"):
        entry["description"] = sr["description"]
    put(entry, "seed", d.get("seed"))
    put(entry, "spawn", d.get("spawn"))
    put(entry, "noiseSettings", d.get("noiseSettings"))
    if d.get("biome"):
        entry["biomes"] = [b.strip() for b in d["biome"].split(",") if b.strip()]
    scale = float(portals.get(slug, {}).get("scale", 1.0)) or 1.0
    entry["borders"] = borders_block(BASE_RADIUS / scale)
    put(entry, "difficulty",
        difficulty_block(slug, d.get("dimensionId", ""), d.get("hostileSpawning")))
    put(entry, "structureDensity", d.get("structureDensity"))
    put(entry, "portal", portal_block(slug))
    put(entry, "seedRoll", sr)  # preserved verbatim (Phase 6 owns ranges)
    write_json(dims_dir / f"{slug}.json", entry)
    expected.add(f"{slug}.json")

# --- base-world files --------------------------------------------------------
for w in cfg.get("worlds", []):
    slug = w["name"]
    entry = {}
    seed = w.get("seed")
    if slug == "overworld" and seed is None:
        # The overworld seed IS the save seed: the top-level worldSeed drives
        # it; "env" keeps the legacy .env SEED behaviour when neither is set.
        seed = cfg.get("worldSeed", "env")
    put(entry, "seed", seed)
    put(entry, "spawn", w.get("spawn"))
    put(entry, "scale", w.get("scale"))
    if slug in BASE_WORLD_RADII:
        entry["borders"] = borders_block(BASE_WORLD_RADII[slug])
    dblock = difficulty_block(slug, w.get("dimensionId", ""), None) or {}
    if slug == "overworld" and "depthScaling" not in dblock:
        # Depth scaling was overworld-only in configurable-difficulty —
        # carried here explicitly, never as a global default.
        dblock["depthScaling"] = difficulty.get("depthScaling", {
            "enabled": True, "startY": 64, "endY": -64,
            "minMultiplier": 1.0, "maxMultiplier": 1.5,
        })
    put(entry, "difficulty", dblock or None)
    put(entry, "seedRoll", w.get("seedRoll"))
    write_json(dims_dir / f"{slug}.json", entry)
    expected.add(f"{slug}.json")

# --- prune stale platform files ---------------------------------------------
removed = 0
for f in sorted(dims_dir.glob("*.json")):
    if f.name not in expected:
        f.unlink()
        removed += 1
        print(f"  removed stale: dimensions/{f.name}")

n_dims = len(cfg.get("dimensions", []))
n_worlds = len(cfg.get("worlds", []))
print(f"Wrote {n_dims} dimension + {n_worlds} world file(s) to {dims_dir}")
print(f"Wrote {out / 'settings.json'}" + (f"; {removed} stale file(s) removed" if removed else ""))
PYEOF

echo ""
echo "Migration complete. The mod prefers config/custom-dimensions/ when the"
echo "directory exists; multiverse_config.json remains a deprecated fallback."
