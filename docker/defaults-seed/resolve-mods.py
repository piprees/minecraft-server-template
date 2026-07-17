#!/usr/bin/env python3
"""resolve-mods.py - Resolve pinned Modrinth versions to direct download URLs.

Runs inside the defaults-seed container after seed.sh merges the mod list.
Replaces itzg's MODRINTH_PROJECTS resolution, which re-queries the Modrinth
API for EVERY pin on every sync-enabled boot (~160 version lookups) and
429-crash-loops the mc container whenever the mod list changes.

Reads the merged list (slug:versionId, optional `datapack:` prefix, optional
trailing `?` for optional entries, inline `#` comments tolerated), resolves
each versionId to its primary file URL + filename via the Modrinth API, and
caches results forever in .resolve-cache.json — a versionId is immutable on
Modrinth, so a warm cache means ZERO API calls at boot. Only new pins are
fetched (paced, with 429 backoff honouring Retry-After).

Outputs (in the shared stack-mods volume, consumed by the mc container):
  mods-urls.txt        one URL per line -> itzg MODS_FILE
  datapacks-urls.txt   one URL per line -> itzg DATAPACKS_FILE
  mods-manifest.txt    expected mod jar filenames -> stale-jar cleanup

Exit codes: 0 ok; 1 unresolvable required entry (fail the seed loudly rather
than boot a server with silently missing mods).
"""
import json
import os
import sys
import time
import urllib.request
import urllib.error

API = "https://api.modrinth.com/v2/version/"
UA = "piprees/minecraft-server-template (defaults-seed resolver)"
PACE_SECONDS = 0.35
MAX_RETRIES = 6


def fetch(version_id):
    delay = 2.0
    for attempt in range(MAX_RETRIES):
        req = urllib.request.Request(API + version_id, headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                retry_after = e.headers.get("Retry-After")
                wait = float(retry_after) if retry_after else delay
                print(f"resolve: 429 for {version_id}, waiting {wait:.0f}s "
                      f"(attempt {attempt + 1}/{MAX_RETRIES})", file=sys.stderr)
                time.sleep(wait)
                delay = min(delay * 2, 60)
                continue
            if e.code == 404:
                return None
            if e.code >= 500:
                # Modrinth 5xx flapping killed five consecutive CI runs
                # (2026-07-17): transient server errors get the same
                # backoff as 429 instead of crashing the seed.
                print(f"resolve: {e.code} for {version_id}, retrying in "
                      f"{delay:.0f}s (attempt {attempt + 1}/{MAX_RETRIES})",
                      file=sys.stderr)
                time.sleep(delay)
                delay = min(delay * 2, 60)
                continue
            raise
        except (urllib.error.URLError, TimeoutError):
            time.sleep(delay)
            delay = min(delay * 2, 60)
    return None


def main(list_path, out_dir):
    cache_path = os.path.join(out_dir, ".resolve-cache.json")
    cache = {}
    if os.path.exists(cache_path):
        try:
            cache = json.load(open(cache_path))
        except (json.JSONDecodeError, OSError):
            cache = {}

    entries = []
    for raw in open(list_path):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        optional = line.endswith("?")
        if optional:
            line = line[:-1]
        datapack = line.startswith("datapack:")
        if datapack:
            line = line[len("datapack:"):]
        parts = line.rsplit(":", 1)
        if len(parts) != 2 or not parts[1]:
            print(f"resolve: skipping unpinned entry '{line}' "
                  f"(no versionId)", file=sys.stderr)
            continue
        entries.append((parts[0], parts[1], datapack, optional))

    mods, datapacks, manifest = [], [], []
    failures = []
    fetched = 0
    for slug, version_id, datapack, optional in entries:
        key = f"{slug}:{version_id}"
        info = cache.get(key)
        if info is None:
            time.sleep(PACE_SECONDS)
            data = fetch(version_id)
            fetched += 1
            if data is None:
                if optional:
                    print(f"resolve: optional '{key}' unresolvable, skipping",
                          file=sys.stderr)
                    continue
                failures.append(key)
                continue
            files = data.get("files") or []
            primary = next((f for f in files if f.get("primary")), files[0] if files else None)
            if primary is None:
                failures.append(key)
                continue
            info = {"filename": primary["filename"], "url": primary["url"]}
            cache[key] = info
        if datapack:
            datapacks.append(info["url"])
        else:
            mods.append(info["url"])
            manifest.append(info["filename"])

    # Persist the cache even on failure — successful lookups stay warm.
    tmp = cache_path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(cache, f, indent=1, sort_keys=True)
    os.replace(tmp, cache_path)

    if failures:
        for key in failures:
            print(f"resolve: FAILED to resolve required entry '{key}'", file=sys.stderr)
        sys.exit(1)

    def write(name, lines):
        tmp = os.path.join(out_dir, "." + name + ".tmp")
        with open(tmp, "w") as f:
            f.write("\n".join(lines) + ("\n" if lines else ""))
        os.replace(tmp, os.path.join(out_dir, name))

    write("mods-urls.txt", mods)
    write("datapacks-urls.txt", datapacks)
    write("mods-manifest.txt", sorted(manifest))
    print(f"resolve: {len(mods)} mods, {len(datapacks)} datapacks "
          f"({fetched} API lookups, {len(entries) - fetched} cached)")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
