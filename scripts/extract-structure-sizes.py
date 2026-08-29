#!/usr/bin/env python3
"""Extract every structure's declared jigsaw fields from the pinned inputs.

Purpose:  The placement model needs a footprint per structure so a castle
          claims more ground than a well. NEITHER declared field is one:
          `size` is the jigsaw pool's maximum expansion depth and
          `max_distance_from_center` is the assembler's search bound — 280 of
          783 structures declare no bound and 246 of the rest leave it at the
          default 80. This extracts them anyway,
          because they are the only answer available without a running
          server, and because the id list is what tells a stale measured
          table that a pin bump added structures it has never seen.

          The real footprint comes from `/customdim structure-sizes`, which
          assembles each structure and reads the bounding box. Compare the
          two with scripts/gen-structure-sizes.py --report before trusting
          either.

Sources:  the same four extract-structure-sets.py uses, so the two censuses
          describe one pack:
  1. Pinned Modrinth mods/datapacks: config/modrinth-mods.txt (modrinth_pins)
  2. In-house mods:  mods/local-mods.manifest -> mods/<project>/build/libs/,
     the remapped jar only (never the -dev jar from build/devlibs/)
  3. Vanilla:        misode/mcmeta pinned to 1.21.1-data
  4. World datapacks: config/datapacks/*/data/*/worldgen/structure/*.json

Output:   scripts/data/structure-sizes-extracted.json

Usage:    scripts/extract-structure-sizes.py [--cache DIR] [--check]
          --check exits 1 if the committed output is stale instead of
          rewriting it (CI / pre-release gate, matching its siblings).

Gotchas:  - Re-run after any structure-mod pin bump, beside
            extract-structure-sets.py. Both read the same jars, so run them
            together and the shared download cache is paid once.
          - A structure id carries the file's whole relative path, slashes
            included (`terralith:underground/witch_hut`). Do not flatten it —
            the registry does not.
          - An in-house mod with no build/libs/*.jar is skipped with a
            warning, not a failure: the platform's own mods are not always
            freshly built when this runs.
"""

import argparse
import io
import json
import sys as _sys
from pathlib import Path as _Path

_sys.path.insert(0, str(_Path(__file__).resolve().parent))
import mcjson
import os
import sys
import urllib.request
import zipfile
from pathlib import Path

import modrinth_pins

PLATFORM_DIR = Path(__file__).resolve().parent.parent
DATAPACKS_DIR = PLATFORM_DIR / "config" / "datapacks"
LOCAL_MODS_MANIFEST = PLATFORM_DIR / "mods" / "local-mods.manifest"
OUTPUT = PLATFORM_DIR / "scripts" / "data" / "structure-sizes-extracted.json"

MCMETA_REF = "1.21.1-data"
MCMETA_CONTENTS = (
    "https://api.github.com/repos/misode/mcmeta/contents/"
    f"data/minecraft/worldgen/structure?ref={MCMETA_REF}"
)
MCMETA_RAW = (
    f"https://raw.githubusercontent.com/misode/mcmeta/{MCMETA_REF}"
    "/data/minecraft/worldgen/structure"
)

MARKER = "/worldgen/structure/"


def structure_id(entry_path):
    """`data/<ns>/worldgen/structure/<path>.json` -> `<ns>:<path>`, or None."""
    idx = entry_path.find(MARKER)
    if idx < 0 or not entry_path.endswith(".json"):
        return None
    before = entry_path[:idx].split("/")
    if len(before) < 2 or before[-2] != "data":
        return None
    return before[-1] + ":" + entry_path[idx + len(MARKER):-len(".json")]


def parse_structure(data, source_name):
    """The declared fields a footprint proxy could be built from."""
    row = {"type": data.get("type"), "source": source_name}
    for field in ("size", "max_distance_from_center", "terrain_adaptation", "step"):
        if field in data:
            row[field] = data[field]
    pool = data.get("start_pool")
    # An inline pool is an object; only a reference names something.
    if isinstance(pool, str):
        row["start_pool"] = pool
    return row


def _scan_zip(zf, source_name):
    """One zip's worldgen/structure/*.json entries -> {id: row}."""
    rows = {}
    for entry in zf.namelist():
        sid = structure_id(entry)
        if sid is None:
            continue
        try:
            data = mcjson.loads(zf.read(entry))
        except (json.JSONDecodeError, UnicodeDecodeError):
            continue
        if isinstance(data, dict) and "type" in data:
            rows[sid] = parse_structure(data, source_name)
    return rows


def extract_from_jar_path(jar_path, source_label=None):
    label = source_label or jar_path.name
    try:
        with zipfile.ZipFile(jar_path) as zf:
            return _scan_zip(zf, label)
    except (zipfile.BadZipFile, FileNotFoundError) as e:
        print(f"  SKIP {label}: {e}", file=sys.stderr)
        return {}


def extract_from_jar_bytes(data, source_label):
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            return _scan_zip(zf, source_label)
    except zipfile.BadZipFile as e:
        print(f"  SKIP {source_label}: {e}", file=sys.stderr)
        return {}


def extract_from_datapacks(datapacks_dir):
    """Structures declared by the world datapacks this platform ships."""
    rows = {}
    if not datapacks_dir.exists():
        return rows
    for root, _dirs, files in os.walk(datapacks_dir):
        for f in files:
            if not f.endswith(".json"):
                continue
            full = os.path.join(root, f)
            rel = os.path.relpath(full, datapacks_dir).replace(os.sep, "/")
            if MARKER not in "/" + rel:
                continue
            sid = structure_id(rel)
            if sid is None:
                continue
            try:
                with open(full) as fh:
                    data = mcjson.loads(fh.read())
            except (json.JSONDecodeError, UnicodeDecodeError):
                continue
            if isinstance(data, dict) and "type" in data:
                rows[sid] = parse_structure(data, "datapack:" + rel.split("/")[0])
    return rows


def extract_pinned_mods(cache_dir):
    """Every structure in the platform's pinned Modrinth mods/datapacks."""
    pin_map = modrinth_pins.pins()
    print(f"Scanning {len(pin_map)} pinned Modrinth mods/datapacks")
    rows = {}
    scanned = 0
    for slug, version_id in sorted(pin_map.items()):
        try:
            f = modrinth_pins.primary_file(
                modrinth_pins.version_meta(slug, version_id, cache_dir))
            if f is None:
                print(f"  SKIP {slug}:{version_id}: no primary file", file=sys.stderr)
                continue
            data = modrinth_pins.fetch(f["url"], cache_dir, f"{slug}-{version_id}.zip")
        except Exception as e:
            print(f"  SKIP {slug}:{version_id}: {e}", file=sys.stderr)
            continue
        found = extract_from_jar_bytes(data, f["filename"])
        if found:
            print(f"  {f['filename']}: {len(found)} structures")
        rows.update(found)
        scanned += 1
    if scanned == 0:
        raise SystemExit("no pinned mod resolved — network or Modrinth API problem, "
                         "refusing to write a census with zero mod-sourced structures")
    return rows


def extract_local_mods():
    """Every structure in this platform's own in-house mod jars."""
    if not LOCAL_MODS_MANIFEST.exists():
        print(f"  SKIP: {LOCAL_MODS_MANIFEST} not found — no in-house mods to scan",
              file=sys.stderr)
        return {}
    rows = {}
    for line in LOCAL_MODS_MANIFEST.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        jar_name, project, _ref_map = line.split("|")
        libs_dir = PLATFORM_DIR / "mods" / project / "build" / "libs"
        candidates = [j for j in sorted(libs_dir.glob("*.jar"))
                      if not j.name.endswith("-sources.jar")
                      and not j.name.endswith("-dev.jar")]
        if not candidates:
            print(f"  SKIP {jar_name}: no built jar in {libs_dir} "
                  f"(run mods/{project}'s Gradle build first)", file=sys.stderr)
            continue
        found = extract_from_jar_path(candidates[0], source_label=jar_name)
        print(f"  {jar_name}: {len(found)} structures")
        rows.update(found)
    return rows


def extract_vanilla(cache_dir):
    """Every vanilla structure, from misode/mcmeta pinned to 1.21.1-data."""
    req = urllib.request.Request(
        MCMETA_CONTENTS, headers={"User-Agent": "extract-structure-sizes.py"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            listing = mcjson.loads(r.read())
    except Exception as e:
        raise SystemExit(f"could not list vanilla structures from {MCMETA_CONTENTS}: {e}")
    rows = {}
    for entry in listing:
        name = entry.get("name", "")
        if not name.endswith(".json"):
            continue
        body = mcjson.loads(
            modrinth_pins.fetch(f"{MCMETA_RAW}/{name}", cache_dir, f"vanilla-structure-{name}"))
        if isinstance(body, dict) and "type" in body:
            rows["minecraft:" + name[:-len(".json")]] = parse_structure(body, "vanilla")
    if not rows:
        raise SystemExit(f"vanilla listing at {MCMETA_CONTENTS} returned zero structures")
    return rows


def build(cache_dir):
    """All four sources, later ones winning — datapacks shadow their mod."""
    rows = {}
    print("\nVanilla (misode/mcmeta " + MCMETA_REF + ")")
    rows.update(extract_vanilla(cache_dir))
    print("\nPinned Modrinth mods")
    rows.update(extract_pinned_mods(cache_dir))
    print("\nIn-house mods")
    rows.update(extract_local_mods())
    print("\nWorld datapacks")
    dp = extract_from_datapacks(DATAPACKS_DIR)
    print(f"  {len(dp)} structures")
    rows.update(dp)
    return dict(sorted(rows.items()))


def render(rows):
    counts = {}
    for row in rows.values():
        counts[row.get("type") or "(none)"] = counts.get(row.get("type") or "(none)", 0) + 1
    body = {
        "_comment": "Generated by scripts/extract-structure-sizes.py — do not hand-edit. "
                    "DECLARED jigsaw fields only. `size` is pool depth and "
                    "`max_distance_from_center` is a search bound; neither is a footprint. "
                    "Measured footprints come from /customdim structure-sizes.",
        "_counts": {"structures": len(rows), "byType": dict(sorted(counts.items()))},
        "structures": rows,
    }
    return json.dumps(body, indent=1, sort_keys=False) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--cache", default=str(PLATFORM_DIR / ".cache/structure-jars"))
    ap.add_argument("--check", action="store_true",
                    help="exit 1 if the committed output is stale instead of rewriting")
    args = ap.parse_args()
    cache_dir = Path(args.cache)
    cache_dir.mkdir(parents=True, exist_ok=True)

    rows = build(cache_dir)
    body = render(rows)
    current = OUTPUT.read_text() if OUTPUT.exists() else None

    if args.check:
        if current != body:
            print(f"STALE: {OUTPUT.relative_to(PLATFORM_DIR)}", file=sys.stderr)
            print("Run scripts/extract-structure-sizes.py to regenerate.", file=sys.stderr)
            return 1
        print(f"structure size census up to date ({len(rows)} structures)")
        return 0

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(body)
    print(f"\nWrote {len(rows)} structures to {OUTPUT}")

    missing = [sid for sid, row in rows.items()
               if "size" not in row and "max_distance_from_center" not in row]
    default_mdfc = sum(1 for row in rows.values()
                       if row.get("max_distance_from_center") == 80)
    print(f"  {len(missing)} declare neither field")
    print(f"  {default_mdfc} sit on the max_distance_from_center default of 80")
    return 0


if __name__ == "__main__":
    sys.exit(main())
