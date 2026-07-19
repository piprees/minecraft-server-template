#!/usr/bin/env python3
"""extract-blocks.py — every block id from installed mod JARs into JSON.

Blocks are CODE-registered (not data-driven), so a static JAR scan cannot
see hardness or tool requirements — those need a running game. What IS
statically reliable: every block has exactly one blockstate definition at
assets/<ns>/blockstates/<name>.json, and usually a display name at
lang key block.<ns>.<name>. That id + name inventory is what the
configurator needs (portal frameBlock pickers, validation).

Sources:
  1. Vanilla JAR:   <consumer>/data/versions/1.21.1/server-1.21.1.jar
     (the server jar carries no assets/ — vanilla blocks come from the
      bundled generated data when present, else the known lang file)
  2. Mod JARs:      <consumer>/data/mods/*.jar

Output: config/custom-dimensions/extractors/blocks.json

Usage:
  ./scripts/extract-blocks.py [consumer_dir]     # default: ~/Projects/elfydd
"""
import json
import sys
import zipfile
from pathlib import Path

PLATFORM_DIR = Path(__file__).resolve().parent.parent
CONSUMER_DIR = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Projects" / "elfydd"
MODS_DIR = CONSUMER_DIR / "data" / "mods"
VANILLA_JAR = CONSUMER_DIR / "data" / "versions" / "1.21.1" / "server-1.21.1.jar"
OUTPUT = PLATFORM_DIR / "config" / "custom-dimensions" / "extractors" / "blocks.json"


def scan_jar(path, out):
    """assets/<ns>/blockstates/<name>.json -> block ids; lang -> names."""
    found = 0
    langs = {}
    try:
        with zipfile.ZipFile(path) as zf:
            names = zf.namelist()
            for entry in names:
                parts = entry.split("/")
                if (len(parts) == 4 and parts[0] == "assets"
                        and parts[2] == "blockstates" and entry.endswith(".json")):
                    block_id = f"{parts[1]}:{parts[3][:-len('.json')]}"
                    if block_id not in out:
                        out[block_id] = {"source": path.name, "name": None}
                        found += 1
            for entry in names:
                parts = entry.split("/")
                if (len(parts) == 4 and parts[0] == "assets" and parts[2] == "lang"
                        and parts[3] == "en_us.json"):
                    try:
                        langs.update(json.loads(zf.read(entry)))
                    except json.JSONDecodeError:
                        pass
    except (zipfile.BadZipFile, FileNotFoundError) as e:
        print(f"  SKIP {path.name}: {e}", file=sys.stderr)
        return 0
    for key, value in langs.items():
        if key.startswith("block."):
            bits = key.split(".")
            if len(bits) == 3:
                block_id = f"{bits[1]}:{bits[2]}"
                if block_id in out and out[block_id]["name"] is None:
                    out[block_id]["name"] = value
                elif block_id not in out:
                    # lang-only block (some mods skip blockstates for techical blocks)
                    out[block_id] = {"source": path.name, "name": value}
    return found


def main():
    blocks = {}
    print(f"Scanning vanilla JAR: {VANILLA_JAR}")
    n = scan_jar(VANILLA_JAR, blocks)
    print(f"  {n} blocks (server jars carry no assets — vanilla blocks come from mods' overrides or stay absent)")
    jars = sorted(MODS_DIR.glob("*.jar"))
    print(f"Scanning {len(jars)} mod JARs in {MODS_DIR}")
    for jar in jars:
        n = scan_jar(jar, blocks)
        if n:
            print(f"  {jar.name}: {n} blocks")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    by_ns = {}
    for bid in blocks:
        ns = bid.split(":")[0]
        by_ns[ns] = by_ns.get(ns, 0) + 1
    OUTPUT.write_text(json.dumps({
        "count": len(blocks),
        "note": "Static JAR scan: ids + display names only. Hardness/tool "
                "requirements are code-side and need a running game.",
        "by_namespace": dict(sorted(by_ns.items(), key=lambda x: -x[1])),
        "blocks": dict(sorted(blocks.items())),
    }, indent=2) + "\n")
    print(f"\nWrote {len(blocks)} blocks to {OUTPUT}")
    for ns, c in sorted(by_ns.items(), key=lambda x: -x[1])[:10]:
        print(f"  {ns}: {c}")


if __name__ == "__main__":
    main()
