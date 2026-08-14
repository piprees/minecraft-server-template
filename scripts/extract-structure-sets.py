#!/usr/bin/env python3
"""Extract every structure set from the platform's own pinned inputs.

Sources:
  1. Pinned Modrinth mods/datapacks: config/modrinth-mods.txt, downloaded the
     same way gen-structure-presets.py does (scripts/modrinth_pins.py).
  2. In-house mods:  mods/local-mods.manifest -> mods/<project>/build/libs/,
     the remapped jar only (never the -dev jar from build/devlibs/).
  3. Vanilla:        misode/mcmeta on GitHub, pinned to 1.21.1-data — every
     worldgen/structure_set/*.json listed there, not a hand-picked subset.
  4. World datapacks: config/datapacks/*/data/*/worldgen/structure_set/*.json
     (these shadow a mod's/vanilla's set at the same registry path and win
     the census for it).

Output: scripts/data/structure-sets-extracted.json
        config/custom-dimensions/extractors/structures.json  (v4 Phase 0)

Usage:
    scripts/extract-structure-sets.py [--cache DIR] [--check]
    --check exits 1 if the committed outputs are stale instead of
    rewriting them (CI / pre-release gate, matches gen-structure-groups.py).

Gotchas: - Not currently run by any .github/workflows/*.yml — nothing keeps
           this census in step with mod-updates.yml's weekly re-pins. Re-run
           by hand after a pin bump, same as gen-structure-groups.py.
         - An in-house mod with no build/libs/*.jar is skipped with a
           warning, not a failure — the platform's own mods aren't always
           freshly built when this runs.
"""

import argparse
import io
import json
import os
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

import modrinth_pins

PLATFORM_DIR = Path(__file__).resolve().parent.parent
DATAPACKS_DIR = PLATFORM_DIR / "config" / "datapacks"
LOCAL_MODS_MANIFEST = PLATFORM_DIR / "mods" / "local-mods.manifest"
OUTPUT_EXTRACTED = PLATFORM_DIR / "scripts" / "data" / "structure-sets-extracted.json"
OUTPUT_JSON = PLATFORM_DIR / "config" / "custom-dimensions" / "extractors" / "structures.json"

MCMETA_REF = "1.21.1-data"
MCMETA_CONTENTS = (
    "https://api.github.com/repos/misode/mcmeta/contents/"
    f"data/minecraft/worldgen/structure_set?ref={MCMETA_REF}"
)
MCMETA_RAW = f"https://raw.githubusercontent.com/misode/mcmeta/{MCMETA_REF}/data/minecraft/worldgen/structure_set"

# ── Dimension inference ──────────────────────────────────────────────

NETHER_KEYWORDS = {
    "nether", "bastion", "fortress", "piglin", "blaze", "wither_skeleton",
    "crimson", "warped", "soul_sand", "blackstone", "magma", "lava_ocean",
    "incendium", "mns:", "nether_",
}
END_KEYWORDS = {
    "end_city", "end_ship", "the_end", "ender", "chorus", "shulker",
    "enderskog", "mes:", "end_", "purpur", "obsidian_tower",
}

NETHER_NAMESPACES = {"incendium", "mns"}
END_NAMESPACES = {"mes", "nullscape"}


def infer_dimension(structure_set_id, structures_list, mod_source):
    """Infer dimensions from namespace, structure names, and mod source."""
    ns = structure_set_id.split(":")[0] if ":" in structure_set_id else ""
    all_text = (structure_set_id + " " + " ".join(structures_list) + " " + mod_source).lower()

    if ns in NETHER_NAMESPACES:
        return "nether"
    if ns in END_NAMESPACES:
        return "end"

    for kw in NETHER_KEYWORDS:
        if kw in all_text:
            return "nether"
    for kw in END_KEYWORDS:
        if kw in all_text:
            return "end"

    if ns == "minecraft":
        nether_sets = {
            "minecraft:nether_complexes", "minecraft:nether_fossils",
            "minecraft:ruined_portals_nether",
        }
        end_sets = {"minecraft:end_cities"}
        if structure_set_id in nether_sets:
            return "nether"
        if structure_set_id in end_sets:
            return "end"

    return "overworld"


# ── Theme classification ─────────────────────────────────────────────

THEME_RULES = [
    ("maritime", re.compile(
        r"ship|pirate|corsair|galley|nautilus|lighthouse|maritime|ocean|"
        r"sunken|underwater|aquatic|voyager|blimp|harbor|harbour|dock|"
        r"fishing_hut|fishing_ship|sea_fort", re.I)),
    ("settlement", re.compile(
        r"village|town|city|camp|campsite|settlement|outpost|hamlet|"
        r"pub|tavern|inn|farm|house|hut|windmill|market|bazaar|"
        r"merchant|waystation|waypoint|oasis|small_prairie|"
        r"illager_campsite|mushroom_village|bandit_village", re.I)),
    ("dungeon", re.compile(
        r"dungeon|labyrinth|catacomb|crypt|vault|trial_chambers|"
        r"stronghold|mineshaft|mines|mining|complex|cave|"
        r"ancient_city|deep_dark|underground|cellar|basement|"
        r"infested|plague|sanctum|asylum|buried", re.I)),
    ("ruins", re.compile(
        r"ruin|remnant|ancient|fossil|wreck|abandoned|desolat|"
        r"desert_pyramid|jungle_pyramid|igloo|ocean_ruin|trail_ruin|"
        r"ruined_portal|crumbl|decay|eroded|weathered", re.I)),
    ("landmark", re.compile(
        r"monument|temple|shrine|tower|castle|fortress|fort|palace|"
        r"cathedral|chapel|monastery|sanctuary|citadel|coliseum|"
        r"pyramid|pillar|spire|spiral|obelisk|statue|colossus|"
        r"heavenly|typhon|nest|foundry|keep|aviary|thornborn|"
        r"mega_ship|starlight|battleground|arena|prison|"
        r"mansion|witch|pillager|bastion", re.I)),
    ("loot", re.compile(
        r"treasure|chest|loot|stash|cache|buried_treasure|shipwreck|"
        r"wishing_well|reward|supply|stockpile", re.I)),
    ("deco", re.compile(
        r"deco|flower|garden|statue_small|small_|tiny_|"
        r"well|fountain|lamp|lantern|bench|sign|scarecrow|"
        r"log_cabin|arch|bridge|gazebo|banner", re.I)),
]

ENDGAME_PATTERNS = re.compile(
    r"ancient_city|mansion|woodland|coliseum|sanctum|mega.*fortress|"
    r"trial_chambers|mega_ship|mega_dungeon|stronghold|"
    r"boss|climax|arena|typhon|heavenly_conqueror|"
    r"heavenly_rider|ceryneian|flying_dutchman|"
    r"forbidden_castle|shiraz_palace|plague_asylum|"
    r"large_dungeon|citadel", re.I)


def classify_theme(structure_set_id, structures_list):
    all_text = structure_set_id + " " + " ".join(structures_list)
    for theme, pattern in THEME_RULES:
        if pattern.search(all_text):
            return theme
    return "landmark"


def classify_rarity(spacing, separation, frequency, structure_set_id, structures_list):
    """Classify rarity based on effective attempts per 1000 chunks."""
    all_text = (structure_set_id + " " + " ".join(structures_list)).lower()

    if ENDGAME_PATTERNS.search(all_text):
        return "endgame"

    if spacing <= 0:
        return "common"

    attempts = (1000.0 / (spacing * spacing)) * frequency
    if attempts > 1.0:
        return "common"
    if attempts > 0.3:
        return "uncommon"
    if attempts > 0.1:
        return "rare"
    if attempts > 0.03:
        return "very_rare"
    return "legendary"


# ── Structure set parsing ────────────────────────────────────────────

def parse_structure_set(data, file_path, source_name):
    """Parse a structure set JSON dict into a row dict."""
    structures_raw = data.get("structures", [])
    structure_ids = []
    for entry in structures_raw:
        sid = entry.get("structure", "")
        weight = entry.get("weight", 1)
        structure_ids.append(f"{sid}(w={weight})")

    placement = data.get("placement", {})
    spacing = placement.get("spacing", 0)
    separation = placement.get("separation", 0)
    frequency = placement.get("frequency", 1.0)

    # Derive the structure set ID from the file path
    # Pattern: data/<namespace>/worldgen/structure_set/<name>.json
    parts = file_path.replace("\\", "/").split("/")
    try:
        data_idx = parts.index("data")
        namespace = parts[data_idx + 1]
        name = parts[-1].replace(".json", "")
        structure_set_id = f"{namespace}:{name}"
    except (ValueError, IndexError):
        structure_set_id = file_path

    struct_names = [e.get("structure", "") for e in structures_raw]
    dimension = infer_dimension(structure_set_id, struct_names, source_name)
    theme = classify_theme(structure_set_id, struct_names)
    rarity = classify_rarity(spacing, separation, frequency, structure_set_id, struct_names)

    return {
        "mod_source": source_name,
        "structure_set_id": structure_set_id,
        "theme": theme,
        "structures": "; ".join(structure_ids),
        "spacing": spacing,
        "separation": separation,
        "frequency": frequency,
        "dimensions": dimension,
        "rarity_class": rarity,
    }


# ── Source extractors ────────────────────────────────────────────────

def _scan_zip(zf, source_name):
    """One zip's worldgen/structure_set/*.json entries -> parsed rows."""
    rows = []
    for entry in zf.namelist():
        if "worldgen/structure_set/" in entry and entry.endswith(".json"):
            try:
                data = json.loads(zf.read(entry))
                if "structures" in data and "placement" in data:
                    rows.append(parse_structure_set(data, entry, source_name))
            except (json.JSONDecodeError, KeyError):
                pass
    return rows


def extract_from_jar_path(jar_path, source_label=None):
    """Structure sets from an on-disk jar (the in-house mod build output)."""
    label = source_label or jar_path.name
    try:
        with zipfile.ZipFile(jar_path) as zf:
            return _scan_zip(zf, label)
    except (zipfile.BadZipFile, FileNotFoundError) as e:
        print(f"  SKIP {label}: {e}", file=sys.stderr)
        return []


def extract_from_jar_bytes(data, source_label):
    """Structure sets from a downloaded jar/datapack zip's raw bytes."""
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            return _scan_zip(zf, source_label)
    except zipfile.BadZipFile as e:
        print(f"  SKIP {source_label}: {e}", file=sys.stderr)
        return []


def extract_from_datapacks(datapacks_dir):
    """Extract structure sets from filesystem datapacks."""
    rows = []
    for root, dirs, files in os.walk(datapacks_dir):
        for f in files:
            if not f.endswith(".json"):
                continue
            full = os.path.join(root, f)
            rel = os.path.relpath(full, datapacks_dir)
            if "worldgen/structure_set/" not in rel:
                continue
            # source is the datapack name (first directory component)
            dp_name = "datapack:" + rel.split(os.sep)[0]
            try:
                with open(full) as fh:
                    data = json.load(fh)
                if "structures" in data and "placement" in data:
                    rows.append(parse_structure_set(data, full, dp_name))
            except (json.JSONDecodeError, KeyError):
                pass
    return rows


def extract_pinned_mods(cache_dir):
    """Every structure set in the platform's pinned Modrinth mods/datapacks."""
    pin_map = modrinth_pins.pins()
    print(f"Scanning {len(pin_map)} pinned Modrinth mods/datapacks")
    rows = []
    scanned = 0
    for slug, version_id in sorted(pin_map.items()):
        try:
            f = modrinth_pins.primary_file(modrinth_pins.version_meta(slug, version_id, cache_dir))
            if f is None:
                print(f"  SKIP {slug}:{version_id}: no primary file", file=sys.stderr)
                continue
            data = modrinth_pins.fetch(f["url"], cache_dir, f"{slug}-{version_id}.zip")
        except Exception as e:
            print(f"  SKIP {slug}:{version_id}: {e}", file=sys.stderr)
            continue
        found = extract_from_jar_bytes(data, f["filename"])
        if found:
            print(f"  {f['filename']}: {len(found)} structure sets")
        rows.extend(found)
        scanned += 1
    if scanned == 0:
        raise SystemExit("no pinned mod resolved — network or Modrinth API problem, refusing to "
                          "write a census with zero mod-sourced structure sets")
    return rows


def extract_local_mods():
    """Every structure set in this platform's own in-house mod jars."""
    if not LOCAL_MODS_MANIFEST.exists():
        print(f"  SKIP: {LOCAL_MODS_MANIFEST} not found — no in-house mods to scan", file=sys.stderr)
        return []
    rows = []
    for line in LOCAL_MODS_MANIFEST.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        jar_name, project, _ref_map = line.split("|")
        libs_dir = PLATFORM_DIR / "mods" / project / "build" / "libs"
        candidates = [j for j in sorted(libs_dir.glob("*.jar"))
                      if not j.name.endswith("-sources.jar") and not j.name.endswith("-dev.jar")]
        if not candidates:
            print(f"  SKIP {jar_name}: no built jar in {libs_dir} "
                  f"(run mods/{project}'s Gradle build first)", file=sys.stderr)
            continue
        found = extract_from_jar_path(candidates[0], source_label=jar_name)
        print(f"  {jar_name}: {len(found)} structure sets")
        rows.extend(found)
    return rows


def extract_vanilla(cache_dir):
    """Every vanilla structure set, from misode/mcmeta pinned to 1.21.1-data."""
    req = urllib.request.Request(MCMETA_CONTENTS, headers={"User-Agent": "extract-structure-sets.py"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            listing = json.loads(r.read())
    except Exception as e:
        raise SystemExit(f"could not list vanilla structure sets from {MCMETA_CONTENTS}: {e}")
    rows = []
    for entry in listing:
        name = entry.get("name", "")
        if not name.endswith(".json"):
            continue
        body = json.loads(modrinth_pins.fetch(f"{MCMETA_RAW}/{name}", cache_dir, f"vanilla-{name}"))
        if "structures" in body and "placement" in body:
            rows.append(parse_structure_set(
                body, f"data/minecraft/worldgen/structure_set/{name}", "vanilla"))
    if not rows:
        raise SystemExit(f"vanilla listing at {MCMETA_CONTENTS} returned zero structure sets")
    return rows


# ── Main ─────────────────────────────────────────────────────────────

def build(cache_dir):
    all_rows = []

    print(f"Scanning vanilla structure sets ({MCMETA_REF})")
    vanilla = extract_vanilla(cache_dir)
    print(f"  Found {len(vanilla)} structure sets")
    all_rows.extend(vanilla)

    all_rows.extend(extract_pinned_mods(cache_dir))

    print("Scanning in-house mods")
    all_rows.extend(extract_local_mods())

    if DATAPACKS_DIR.exists():
        print(f"Scanning datapacks in {DATAPACKS_DIR}")
        dp = extract_from_datapacks(DATAPACKS_DIR)
        print(f"  Found {len(dp)} structure sets from datapacks")
        all_rows.extend(dp)

    # Deduplicate: datapacks override mod/vanilla if same structure_set_id
    seen = {}
    for row in all_rows:
        key = row["structure_set_id"]
        if key in seen:
            existing = seen[key]
            # Datapacks take precedence, then mods, then vanilla
            if row["mod_source"].startswith("datapack:"):
                seen[key] = row
            elif existing["mod_source"] == "vanilla" and row["mod_source"] != "vanilla":
                seen[key] = row
        else:
            seen[key] = row

    return sorted(seen.values(), key=lambda r: (r["mod_source"], r["structure_set_id"]))


def render_extracted(final):
    fields = [
        "mod_source", "structure_set_id", "theme", "structures",
        "spacing", "separation", "frequency", "dimensions", "rarity_class",
    ]
    rows_out = [{field: str(r[field]) for field in fields} for r in final]
    return json.dumps(rows_out, indent=1) + "\n"


def render_extractors_json(final):
    sets_json = {}
    for r in final:
        sets_json[r["structure_set_id"]] = {
            "source": r["mod_source"],
            "theme": r["theme"],
            "structures": [s.split("(w=")[0] for s in r["structures"].split("; ") if s],
            "spacing": r["spacing"],
            "separation": r["separation"],
            "frequency": r["frequency"],
            "dimension": r["dimensions"],
            "rarity": r["rarity_class"],
        }
    return json.dumps({"count": len(sets_json),
                       "structure_sets": dict(sorted(sets_json.items()))}, indent=2) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--cache", default=str(PLATFORM_DIR / ".cache/structure-jars"))
    ap.add_argument("--check", action="store_true",
                    help="exit 1 if the committed outputs are stale instead of rewriting")
    args = ap.parse_args()
    cache_dir = Path(args.cache)
    cache_dir.mkdir(parents=True, exist_ok=True)

    final = build(cache_dir)

    outputs = {
        OUTPUT_EXTRACTED: render_extracted(final),
        OUTPUT_JSON: render_extractors_json(final),
    }

    stale = []
    for path, body in outputs.items():
        current = path.read_text() if path.exists() else None
        if current != body:
            stale.append(path)

    if args.check:
        if stale:
            for p in stale:
                print(f"STALE: {p.relative_to(PLATFORM_DIR)}", file=sys.stderr)
            print("Run scripts/extract-structure-sets.py to regenerate.", file=sys.stderr)
            return 1
        print(f"structure set census up to date ({len(final)} sets)")
        return 0

    for path, body in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
    print(f"\nWrote {len(final)} structure sets to {OUTPUT_EXTRACTED}")
    print(f"Wrote {len(final)} structure sets to {OUTPUT_JSON}")

    # Summary
    by_source = {}
    by_dim = {}
    by_rarity = {}
    for r in final:
        src = r["mod_source"]
        by_source[src] = by_source.get(src, 0) + 1
        by_dim[r["dimensions"]] = by_dim.get(r["dimensions"], 0) + 1
        by_rarity[r["rarity_class"]] = by_rarity.get(r["rarity_class"], 0) + 1

    print("\nBy source:")
    for s, c in sorted(by_source.items(), key=lambda x: -x[1]):
        print(f"  {s}: {c}")
    print("\nBy dimension:")
    for d, c in sorted(by_dim.items()):
        print(f"  {d}: {c}")
    print("\nBy rarity:")
    for r, c in sorted(by_rarity.items()):
        print(f"  {r}: {c}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
