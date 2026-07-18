#!/usr/bin/env python3
"""Extract every structure set from mod JARs, datapacks, and vanilla server JAR.

Sources:
  1. Mod JARs:     elfydd/data/mods/*.jar
  2. Datapacks:     config/datapacks/*/data/*/worldgen/structure_set/*.json
  3. Vanilla JAR:   elfydd/data/versions/1.21.1/server-1.21.1.jar

Output: mods/.ideas/structure-sets-extracted.csv
"""

import csv
import json
import os
import re
import sys
import zipfile
from pathlib import Path

PLATFORM_DIR = Path(__file__).resolve().parent.parent
ELFYDD_DIR = Path.home() / "Projects" / "elfydd"

MODS_DIR = ELFYDD_DIR / "data" / "mods"
VANILLA_JAR = ELFYDD_DIR / "data" / "versions" / "1.21.1" / "server-1.21.1.jar"
DATAPACKS_DIR = PLATFORM_DIR / "config" / "datapacks"
OUTPUT_CSV = PLATFORM_DIR / "mods" / ".ideas" / "structure-sets-extracted.csv"

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
    ptype = placement.get("type", "unknown")
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

def extract_from_jar(jar_path, source_label=None):
    """Extract all structure sets from a JAR/ZIP file."""
    rows = []
    label = source_label or jar_path.name
    try:
        with zipfile.ZipFile(jar_path) as zf:
            for entry in zf.namelist():
                if "worldgen/structure_set/" in entry and entry.endswith(".json"):
                    try:
                        data = json.loads(zf.read(entry))
                        if "structures" in data and "placement" in data:
                            rows.append(parse_structure_set(data, entry, label))
                    except (json.JSONDecodeError, KeyError):
                        pass
    except (zipfile.BadZipFile, FileNotFoundError) as e:
        print(f"  SKIP {jar_path.name}: {e}", file=sys.stderr)
    return rows


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


# ── Main ─────────────────────────────────────────────────────────────

def main():
    all_rows = []

    # 1. Vanilla server JAR
    print(f"Scanning vanilla JAR: {VANILLA_JAR}")
    vanilla = extract_from_jar(VANILLA_JAR, "vanilla")
    print(f"  Found {len(vanilla)} structure sets")
    all_rows.extend(vanilla)

    # 2. Mod JARs
    jar_files = sorted(MODS_DIR.glob("*.jar"))
    print(f"Scanning {len(jar_files)} mod JARs in {MODS_DIR}")
    mod_total = 0
    for jar in jar_files:
        rows = extract_from_jar(jar)
        if rows:
            mod_total += len(rows)
            print(f"  {jar.name}: {len(rows)} structure sets")
        all_rows.extend(rows)
    print(f"  Total from mods: {mod_total}")

    # 3. Datapacks
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

    final = sorted(seen.values(), key=lambda r: (r["mod_source"], r["structure_set_id"]))

    # Write CSV
    OUTPUT_CSV.parent.mkdir(parents=True, exist_ok=True)
    fields = [
        "mod_source", "structure_set_id", "theme", "structures",
        "spacing", "separation", "frequency", "dimensions", "rarity_class",
    ]
    with open(OUTPUT_CSV, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(final)

    print(f"\nWrote {len(final)} structure sets to {OUTPUT_CSV}")

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


if __name__ == "__main__":
    main()
