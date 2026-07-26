#!/usr/bin/env bash
# sync-mod-cache.sh - Reconcile mods-cache/ against the pinned mod lists.
#
# mods-cache/server/ and mods-cache/client/ hold the exact jar for every pin in
# config/modrinth-mods.txt and modpack/adventure.mrpack.json (_clientMods).
# scripts/sync-mods.sh copies from mods-cache/server/ instead of hitting the
# Modrinth CDN, so a complete cache means a fully offline first boot.
#
# The cache drifts every time a pin moves - the weekly mod-updates.yml PR is
# enough to invalidate it. Run this after any re-pin.
#
# Usage:
#   ./scripts/sync-mod-cache.sh            # report drift, change nothing
#   ./scripts/sync-mod-cache.sh --apply    # delete stale, download missing
#
# Needs network in --apply mode (Modrinth API + CDN). Filenames come from the
# Modrinth version API and match what sync-mods.sh computes from the CDN URL,
# which is what makes a cache hit an exact match.
#
# Gotchas:
#   - Removing a file here does not shrink git history; it only stops the
#     working tree growing. See TROUBLESHOOTING.md if clone size becomes a
#     problem.
#   - Datapack pins (`datapack:` prefix) resolve to .zip files and live in
#     mods-cache/server/ alongside the jars, exactly as sync-mods.sh expects.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

APPLY=0
[[ "${1:-}" == "--apply" ]] && APPLY=1

python3 - "$PROJECT_DIR" "$APPLY" <<'PY'
import json, pathlib, sys, time, urllib.parse, urllib.request

root = pathlib.Path(sys.argv[1])
apply_changes = sys.argv[2] == "1"
UA = {"User-Agent": "piprees/minecraft-server-template sync-mod-cache"}


def server_pins():
    pins = {}
    for raw in (root / "config/modrinth-mods.txt").read_text().splitlines():
        line = raw.split("#")[0].strip()
        if not line:
            continue
        if line.startswith("datapack:"):
            line = line[len("datapack:"):]
        if ":" not in line:
            continue
        slug, vid = line.rsplit(":", 1)
        slug, vid = slug.rstrip("?").strip(), vid.strip().rstrip("?")
        if slug and vid:
            pins[vid] = slug
    return pins


def client_pins():
    manifest = json.loads((root / "modpack/adventure.mrpack.json").read_text())
    cm = manifest["_clientMods"]
    pins = {}
    for key in ("required", "optional"):
        for entry in cm.get(key, []):
            if ":" in entry:
                slug, vid = entry.rsplit(":", 1)
                pins[vid.strip()] = slug.strip()
    return pins


pins = {"server": server_pins(), "client": client_pins()}
ids = sorted(set(pins["server"]) | set(pins["client"]))

resolved = {}
for i in range(0, len(ids), 100):
    url = "https://api.modrinth.com/v2/versions?ids=" + urllib.parse.quote(
        json.dumps(ids[i:i + 100]))
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        for v in json.load(r):
            files = [f for f in v["files"] if f.get("primary")] or v["files"]
            resolved[v["id"]] = (files[0]["filename"], files[0]["url"])
    time.sleep(1)

unresolved = [i for i in ids if i not in resolved]
if unresolved:
    print(f"WARNING: {len(unresolved)} version id(s) did not resolve: {unresolved}")

exit_code = 0
for side in ("server", "client"):
    cache = root / "mods-cache" / side
    cache.mkdir(parents=True, exist_ok=True)
    want = {resolved[v][0]: v for v in pins[side] if v in resolved}
    have = {p.name for p in cache.iterdir() if p.is_file()}
    stale = sorted(have - set(want))
    missing = sorted(set(want) - have)

    print(f"\n{side}: {len(have)} cached, {len(want)} pinned, "
          f"{len(stale)} stale, {len(missing)} missing")

    for name in stale:
        if apply_changes:
            (cache / name).unlink()
            print(f"  removed  {name}")
        else:
            print(f"  STALE    {name}")
    for name in missing:
        slug = pins[side][want[name]]
        if not apply_changes:
            print(f"  MISSING  {name}  ({slug})")
            continue
        _, url = resolved[want[name]]
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=180) as r:
                (cache / name).write_bytes(r.read())
            print(f"  fetched  {name}")
        except Exception as exc:                       # noqa: BLE001 - report and continue
            print(f"  FAILED   {slug}: {exc}")
            exit_code = 1
    if not apply_changes and (stale or missing):
        exit_code = 1

if not apply_changes and exit_code:
    print("\nCache is out of step with the pins. Re-run with --apply to fix.")
elif exit_code == 0:
    print("\nCache matches the pinned mod lists exactly.")
sys.exit(exit_code)
PY
