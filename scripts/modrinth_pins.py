"""Shared pinned-mod-jar download helpers for the structure/terrain generators.

Both gen-structure-presets.py and extract-structure-sets.py need the same
thing: resolve a config/modrinth-mods.txt pin to the jar Modrinth serves for
it, cached on disk so repeat runs don't re-download.
"""

import json
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODS_TXT = REPO / "config/modrinth-mods.txt"


def pins():
    """config/modrinth-mods.txt -> {slug: version_id}.

    Strips the `datapack:` prefix and a trailing `?` (optional marker) —
    both describe how a pin is used elsewhere, not how it resolves to a jar.
    """
    out = {}
    for line in MODS_TXT.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        entry = line.split("#")[0].strip()
        if entry.startswith("datapack:"):
            entry = entry[len("datapack:"):]
        entry = entry.rstrip("?")
        if ":" not in entry:
            continue
        slug, version_id = entry.rsplit(":", 1)
        out[slug] = version_id
    return out


def fetch(url, cache_dir, name):
    """Download url into cache_dir/name once; cached bytes on repeat calls."""
    dest = cache_dir / name
    if dest.exists():
        return dest.read_bytes()
    print(f"  fetching {name} ...")
    with urllib.request.urlopen(url) as r:
        data = r.read()
    dest.write_bytes(data)
    return data


def version_meta(slug, version_id, cache_dir):
    """Cached Modrinth version metadata for slug:version_id."""
    api = f"https://api.modrinth.com/v2/version/{version_id}"
    return json.loads(fetch(api, cache_dir, f"{slug}-{version_id}.meta.json"))


def primary_file(meta):
    """The version metadata's primary file entry, or None."""
    for f in meta["files"]:
        if f.get("primary"):
            return f
    return None


def jar_for(slug, version_id, cache_dir):
    """The primary file's bytes for slug:version_id, cached under cache_dir."""
    f = primary_file(version_meta(slug, version_id, cache_dir))
    if f is None:
        raise SystemExit(f"no primary file for {slug}:{version_id}")
    return fetch(f["url"], cache_dir, f"{slug}-{version_id}.zip")
