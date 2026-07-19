#!/usr/bin/env python3
"""extract-entities.py — every entity type from installed mod JARs into JSON.

Entity types are CODE-registered, so health/damage aren't statically
readable. Two static sources ARE reliable and cover what the configurator
needs:
  1. Lang keys (entity.<ns>.<name> / entity_type.<ns>.<name>) enumerate
     every entity a mod registers, with display names.
  2. Biome spawner tables (data/*/worldgen/biome/*.json) say which spawn
     GROUP an entity belongs to (monster/creature/ambient/water_*), its
     weight range, and how many installed biomes spawn it — the ground
     truth for peaceful-dimension and difficulty work.

Sources: vanilla server JAR + <consumer>/data/mods/*.jar + config/datapacks/.

Output: config/custom-dimensions/extractors/entities.json

Usage:
  ./scripts/extract-entities.py [consumer_dir]   # default: ~/Projects/elfydd
"""
import json
import sys
import zipfile
from pathlib import Path

PLATFORM_DIR = Path(__file__).resolve().parent.parent
CONSUMER_DIR = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Projects" / "elfydd"
MODS_DIR = CONSUMER_DIR / "data" / "mods"
VANILLA_JAR = CONSUMER_DIR / "data" / "versions" / "1.21.1" / "server-1.21.1.jar"
DATAPACKS_DIR = PLATFORM_DIR / "config" / "datapacks"
OUTPUT = PLATFORM_DIR / "config" / "custom-dimensions" / "extractors" / "entities.json"

BIOME_MARKER = "worldgen/biome/"


def record(entities, entity_id):
    return entities.setdefault(entity_id, {
        "source": None, "name": None, "spawn_groups": {},
    })


def scan_lang(zf, jar_name, entities):
    found = 0
    for entry in zf.namelist():
        parts = entry.split("/")
        if not (len(parts) == 4 and parts[0] == "assets" and parts[2] == "lang"
                and parts[3] == "en_us.json"):
            continue
        try:
            lang = json.loads(zf.read(entry))
        except json.JSONDecodeError:
            continue
        for key, value in lang.items():
            bits = key.split(".")
            if len(bits) == 3 and bits[0] in ("entity", "entity_type"):
                entity_id = f"{bits[1]}:{bits[2]}"
                rec = record(entities, entity_id)
                if rec["name"] is None:
                    rec["name"] = value
                    rec["source"] = rec["source"] or jar_name
                    found += 1
    return found


def scan_spawners_json(data, entities):
    for group, entries in (data.get("spawners") or {}).items():
        for e in entries or []:
            entity_id = e.get("type")
            if not entity_id:
                continue
            rec = record(entities, entity_id)
            g = rec["spawn_groups"].setdefault(group, {
                "biomes": 0, "min_weight": None, "max_weight": None})
            g["biomes"] += 1
            w = e.get("weight")
            if w is not None:
                g["min_weight"] = w if g["min_weight"] is None else min(g["min_weight"], w)
                g["max_weight"] = w if g["max_weight"] is None else max(g["max_weight"], w)


def scan_jar(path, entities):
    try:
        with zipfile.ZipFile(path) as zf:
            n = scan_lang(zf, path.name, entities)
            for entry in zf.namelist():
                if BIOME_MARKER in entry and entry.endswith(".json"):
                    try:
                        scan_spawners_json(json.loads(zf.read(entry)), entities)
                    except json.JSONDecodeError:
                        pass
            return n
    except (zipfile.BadZipFile, FileNotFoundError) as e:
        print(f"  SKIP {path.name}: {e}", file=sys.stderr)
        return 0


def main():
    entities = {}
    print(f"Scanning vanilla JAR: {VANILLA_JAR}")
    scan_jar(VANILLA_JAR, entities)
    jars = sorted(MODS_DIR.glob("*.jar"))
    print(f"Scanning {len(jars)} mod JARs in {MODS_DIR}")
    for jar in jars:
        n = scan_jar(jar, entities)
        if n:
            print(f"  {jar.name}: {n} named entities")
    if DATAPACKS_DIR.exists():
        for f in sorted(DATAPACKS_DIR.rglob("*.json")):
            if BIOME_MARKER in f.as_posix():
                try:
                    scan_spawners_json(json.loads(f.read_text()), entities)
                except json.JSONDecodeError:
                    pass

    # Derive a primary category from the spawn groups (monster wins).
    for rec in entities.values():
        groups = rec["spawn_groups"]
        if "monster" in groups:
            rec["category"] = "hostile"
        elif groups:
            rec["category"] = "passive"
        else:
            rec["category"] = "unknown"

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    by_ns = {}
    for eid in entities:
        ns = eid.split(":")[0]
        by_ns[ns] = by_ns.get(ns, 0) + 1
    hostile = sum(1 for r in entities.values() if r["category"] == "hostile")
    OUTPUT.write_text(json.dumps({
        "count": len(entities),
        "hostile_count": hostile,
        "note": "Static JAR scan: names from lang keys, spawn groups from "
                "biome spawner tables. Health/damage are code-side.",
        "by_namespace": dict(sorted(by_ns.items(), key=lambda x: -x[1])),
        "entities": dict(sorted(entities.items())),
    }, indent=2) + "\n")
    print(f"\nWrote {len(entities)} entities ({hostile} hostile) to {OUTPUT}")
    for ns, c in sorted(by_ns.items(), key=lambda x: -x[1])[:10]:
        print(f"  {ns}: {c}")


if __name__ == "__main__":
    main()
