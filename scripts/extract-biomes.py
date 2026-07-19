#!/usr/bin/env python3
"""extract-biomes.py — every biome from installed mod JARs into machine-readable JSON.

Scans data/<ns>/worldgen/biome/**/*.json in:
  1. Vanilla JAR:   <consumer>/data/versions/1.21.1/server-1.21.1.jar
  2. Mod JARs:      <consumer>/data/mods/*.jar
  3. Datapacks:     config/datapacks/*/data/*/worldgen/biome/**/*.json

Per biome: id, source, temperature, downfall, has_precipitation, effects
colours (sky/fog/water/grass — configurator hints), and the mob spawner
table by spawn group. Later sources override earlier ones for the same id
(datapack > mod > vanilla), matching the game's datapack layering.

Output: config/custom-dimensions/extractors/biomes.json

Usage:
  ./scripts/extract-biomes.py [consumer_dir]     # default: ~/Projects/elfydd

Context: Custom Dimensions v4 Phase 0 — feeds the configurator and
validates dimension biome lists against what is actually installed.
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
OUTPUT = PLATFORM_DIR / "config" / "custom-dimensions" / "extractors" / "biomes.json"

MARKER = "worldgen/biome/"


def biome_id_from_path(path):
    """data/<ns>/worldgen/biome/<sub/dirs/name>.json -> <ns>:<sub/dirs/name>"""
    parts = path.replace("\\", "/").split("/")
    try:
        data_idx = parts.index("data")
        ns = parts[data_idx + 1]
        rel = "/".join(parts[data_idx + 2:])
        assert rel.startswith(MARKER)
        return f"{ns}:{rel[len(MARKER):-len('.json')]}"
    except (ValueError, IndexError, AssertionError):
        return None


def parse_biome(data, source):
    effects = data.get("effects", {})
    spawners = {}
    for group, entries in (data.get("spawners") or {}).items():
        if not entries:
            continue
        spawners[group] = [
            {"type": e.get("type"), "weight": e.get("weight"),
             "minCount": e.get("minCount"), "maxCount": e.get("maxCount")}
            for e in entries
        ]
    return {
        "source": source,
        "temperature": data.get("temperature"),
        "downfall": data.get("downfall"),
        "has_precipitation": data.get("has_precipitation"),
        "effects": {k: effects.get(k) for k in
                    ("sky_color", "fog_color", "water_color", "water_fog_color",
                     "grass_color", "foliage_color")
                    if effects.get(k) is not None},
        "spawners": spawners,
    }


def scan_zip(path, label, out):
    found = 0
    try:
        with zipfile.ZipFile(path) as zf:
            for entry in zf.namelist():
                if MARKER not in entry or not entry.endswith(".json"):
                    continue
                biome_id = biome_id_from_path(entry)
                if biome_id is None:
                    continue
                try:
                    data = json.loads(zf.read(entry))
                except json.JSONDecodeError:
                    continue
                if "effects" not in data and "spawners" not in data:
                    continue
                out[biome_id] = parse_biome(data, label)
                found += 1
    except (zipfile.BadZipFile, FileNotFoundError) as e:
        print(f"  SKIP {path.name}: {e}", file=sys.stderr)
    return found


def scan_datapacks(root, out):
    found = 0
    for f in sorted(root.rglob("*.json")):
        rel = f.relative_to(root).as_posix()
        if MARKER not in rel:
            continue
        biome_id = biome_id_from_path(rel)
        if biome_id is None:
            continue
        try:
            data = json.loads(f.read_text())
        except json.JSONDecodeError:
            continue
        out[biome_id] = parse_biome(data, "datapack:" + rel.split("/")[0])
        found += 1
    return found


def main():
    biomes = {}
    print(f"Scanning vanilla JAR: {VANILLA_JAR}")
    n = scan_zip(VANILLA_JAR, "vanilla", biomes)
    print(f"  {n} biomes")
    jars = sorted(MODS_DIR.glob("*.jar"))
    print(f"Scanning {len(jars)} mod JARs in {MODS_DIR}")
    for jar in jars:
        n = scan_zip(jar, jar.name, biomes)
        if n:
            print(f"  {jar.name}: {n} biomes")
    if DATAPACKS_DIR.exists():
        n = scan_datapacks(DATAPACKS_DIR, biomes)
        print(f"Datapacks: {n} biomes")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    by_ns = {}
    for bid in biomes:
        ns = bid.split(":")[0]
        by_ns[ns] = by_ns.get(ns, 0) + 1
    OUTPUT.write_text(json.dumps({
        "count": len(biomes),
        "by_namespace": dict(sorted(by_ns.items(), key=lambda x: -x[1])),
        "biomes": dict(sorted(biomes.items())),
    }, indent=2) + "\n")
    print(f"\nWrote {len(biomes)} biomes to {OUTPUT}")
    for ns, c in sorted(by_ns.items(), key=lambda x: -x[1]):
        print(f"  {ns}: {c}")


if __name__ == "__main__":
    main()
