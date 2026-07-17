#!/usr/bin/env python3
"""gen-resolve-cache.py — populate config/modrinth-resolve-cache.json.

Resolves every pin in config/modrinth-mods.txt to its primary file
{filename, url} and merges into the committed cache (version IDs are
immutable, so entries never go stale; unknown pins are the only fetches).
The seed image bakes this file in — with a warm cache the seed makes ZERO
Modrinth API calls, so builds and boots survive full API outages.

Run whenever pins change (pin-mod-versions.sh --apply, weekly updates):
    python3 scripts/gen-resolve-cache.py && git add config/modrinth-resolve-cache.json
"""
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "docker" / "defaults-seed"))
from importlib.machinery import SourceFileLoader  # noqa: E402

rm = SourceFileLoader("rm", str(ROOT / "docker/defaults-seed/resolve-mods.py")).load_module()

CACHE = ROOT / "config/modrinth-resolve-cache.json"
MODS = ROOT / "config/modrinth-mods.txt"


def main():
    cache = {}
    if CACHE.exists():
        cache = json.loads(CACHE.read_text() or "{}")
    missing, resolved, failed = 0, 0, []
    for raw in MODS.read_text().splitlines():
        line = raw.split("#", 1)[0].strip().removesuffix("?")
        if line.startswith("datapack:"):
            line = line[len("datapack:"):]
        if not line or ":" not in line:
            continue
        slug, vid = line.rsplit(":", 1)
        key = f"{slug}:{vid}"
        if key in cache:
            continue
        missing += 1
        time.sleep(rm.PACE_SECONDS)
        data = rm.fetch(vid)
        if not data or not data.get("files"):
            failed.append(key)
            continue
        files = data["files"]
        primary = next((f for f in files if f.get("primary")), files[0])
        cache[key] = {"filename": primary["filename"], "url": primary["url"]}
        resolved += 1
    CACHE.write_text(json.dumps(cache, indent=1, sort_keys=True) + "\n")
    print(f"cache: {len(cache)} entries ({resolved} newly resolved, "
          f"{missing - resolved} failed of {missing} missing)")
    if failed:
        print("failed:", ", ".join(failed[:10]), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
