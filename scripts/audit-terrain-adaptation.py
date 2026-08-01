#!/usr/bin/env python3
"""Audit terrain_adaptation across every installed structure JSON.

The Beardifier only integrates a structure into the terrain when its
structure JSON names a terrain_adaptation (beard_thin / beard_box / bury /
encapsulate); absent means "none" and the structure sits on whatever the
noise field produced — the main cause of cliff-embedded and floating
builds. This audit ranks the population so the adaptation-override theme
defaults (structure-type-defaults.json) are tuned from data, not guesses.

Sources (same set as extract-structure-sets.py):
  1. Mod JARs:    elfydd/data/mods/*.jar
  2. Vanilla JAR: elfydd/data/versions/1.21.1/server-1.21.1.jar

Theme/group per structure comes from config/custom-dimensions/
structure-groups.json via the structure_set files in the same jars
(structure -> owning set -> group/theme). Structures in no known set are
reported unclassified.

Output: scripts/data/terrain-adaptation-audit.csv + ranked console summary.
Offline — no Docker, no network.

Usage:
    python3 scripts/audit-terrain-adaptation.py [--mods-dir DIR]
"""

import argparse
import csv
import json
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

PLATFORM_DIR = Path(__file__).resolve().parent.parent
ELFYDD_DIR = Path.home() / "Projects" / "elfydd"
DEFAULT_MODS_DIR = ELFYDD_DIR / "data" / "mods"
VANILLA_JAR = ELFYDD_DIR / "data" / "versions" / "1.21.1" / "server-1.21.1.jar"
GROUPS_JSON = PLATFORM_DIR / "config" / "custom-dimensions" / "structure-groups.json"
OUTPUT_CSV = PLATFORM_DIR / "scripts" / "data" / "terrain-adaptation-audit.csv"

STRUCTURE_PREFIX = "worldgen/structure/"
SET_PREFIX = "worldgen/structure_set/"


def registry_id(entry_path):
    """data/<ns>/worldgen/structure/<name...>.json -> <ns>:<name...>"""
    parts = entry_path.replace("\\", "/").split("/")
    try:
        data_idx = parts.index("data")
    except ValueError:
        return None
    ns = parts[data_idx + 1]
    try:
        kind_idx = parts.index("structure", data_idx)
    except ValueError:
        try:
            kind_idx = parts.index("structure_set", data_idx)
        except ValueError:
            return None
    name = "/".join(parts[kind_idx + 1:])
    if not name.endswith(".json"):
        return None
    return f"{ns}:{name[:-5]}"


def scan_jar(jar_path, source, structures, struct_to_set):
    try:
        zf = zipfile.ZipFile(jar_path)
    except (zipfile.BadZipFile, FileNotFoundError) as e:
        print(f"  SKIP {jar_path.name}: {e}", file=sys.stderr)
        return
    with zf:
        for entry in zf.namelist():
            if not entry.endswith(".json"):
                continue
            if STRUCTURE_PREFIX in entry:
                sid = registry_id(entry)
                if sid is None:
                    continue
                try:
                    data = json.loads(zf.read(entry))
                except (json.JSONDecodeError, KeyError):
                    continue
                if not isinstance(data, dict) or "type" not in data:
                    continue
                # First-found wins per id — later jars shadow nothing here;
                # the registry conflict story is out of this audit's scope.
                structures.setdefault(sid, {
                    "structure_id": sid,
                    "source": source,
                    "structure_type": data.get("type", ""),
                    "terrain_adaptation": data.get("terrain_adaptation") or "none",
                })
            elif SET_PREFIX in entry:
                set_id = registry_id(entry)
                if set_id is None:
                    continue
                try:
                    data = json.loads(zf.read(entry))
                except (json.JSONDecodeError, KeyError):
                    continue
                for member in data.get("structures") or []:
                    member_id = member.get("structure")
                    if member_id:
                        struct_to_set.setdefault(member_id, set_id)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--mods-dir", default=str(DEFAULT_MODS_DIR))
    args = ap.parse_args()
    mods_dir = Path(args.mods_dir)
    if not mods_dir.is_dir():
        sys.exit(f"mods dir not found: {mods_dir}")

    structures = {}
    struct_to_set = {}

    if VANILLA_JAR.exists():
        print(f"Scanning vanilla JAR: {VANILLA_JAR}")
        scan_jar(VANILLA_JAR, "vanilla", structures, struct_to_set)
    jars = sorted(mods_dir.glob("*.jar"))
    print(f"Scanning {len(jars)} mod JARs in {mods_dir}")
    for jar in jars:
        scan_jar(jar, jar.name, structures, struct_to_set)

    groups = {}
    if GROUPS_JSON.exists():
        groups = json.loads(GROUPS_JSON.read_text()).get("sets", {})

    rows = []
    for sid, row in structures.items():
        set_id = struct_to_set.get(sid)
        meta = groups.get(set_id or "", {})
        row["set_id"] = set_id or ""
        row["group"] = meta.get("group", "")
        row["theme"] = meta.get("theme", "")
        rows.append(row)
    rows.sort(key=lambda r: (r["group"] or "~", r["source"], r["structure_id"]))

    OUTPUT_CSV.parent.mkdir(parents=True, exist_ok=True)
    fields = ["structure_id", "set_id", "group", "theme",
              "terrain_adaptation", "structure_type", "source"]
    with open(OUTPUT_CSV, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    print(f"\nWrote {len(rows)} structures to {OUTPUT_CSV}")

    # Ranked summaries: which mods and which groups ship bare structures.
    by_adaptation = Counter(r["terrain_adaptation"] for r in rows)
    print("\nBy terrain_adaptation:")
    for k, c in by_adaptation.most_common():
        print(f"  {k:12} {c}")

    per_source = defaultdict(Counter)
    for r in rows:
        per_source[r["source"]][r["terrain_adaptation"]] += 1
    print("\nBy mod, ranked by bare ('none') structures:")
    ranked = sorted(per_source.items(),
                    key=lambda kv: -kv[1].get("none", 0))
    for source, counts in ranked:
        total = sum(counts.values())
        bare = counts.get("none", 0)
        if total == 0:
            continue
        detail = " ".join(f"{k}={c}" for k, c in counts.most_common()
                          if k != "none")
        print(f"  {source:45} none={bare:3}/{total:<3} {detail}")

    per_group = defaultdict(Counter)
    for r in rows:
        per_group[r["group"] or "(unclassified)"][r["terrain_adaptation"]] += 1
    print("\nBy noise group:")
    for group, counts in sorted(per_group.items()):
        total = sum(counts.values())
        bare = counts.get("none", 0)
        detail = " ".join(f"{k}={c}" for k, c in counts.most_common()
                          if k != "none")
        print(f"  {group:15} none={bare:3}/{total:<3} {detail}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
