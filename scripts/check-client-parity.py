#!/usr/bin/env python3
"""
Purpose:
    Gate the client modpack manifest against the server mod list in BOTH
    directions. Either mismatch is a Fabric registry-handshake kick for every
    player: "Received N registry entries that are unknown to this client."

Context:
    Platform: config/modrinth-mods.txt vs modpack/adventure.mrpack.json.
    Consumer overlay: pass --extra/--remove to fold mods-extra.txt and
    mods-remove.txt into the effective server set.

    Direction A  a slug removed server-side but still in _clientMods.required.
    Direction B  a server mod whose Modrinth client_side is "required" and
                 which is not in _clientMods.required. This is the direction
                 that took the pack down; nothing checked it before.

    Modrinth's client_side is the mod author's own declaration and the only
    static signal available. Exempt a false positive in the manifest's
    _clientMods._parityExempt map (slug -> reason), never by deleting a check.

Usage:
    ./scripts/check-client-parity.py
    ./scripts/check-client-parity.py --extra overlay/mods-extra.txt \
                                     --remove overlay/mods-remove.txt
    ./scripts/check-client-parity.py --offline    # skip direction B

Gotchas:
    Direction B needs the Modrinth API. A network failure warns and passes —
    a lint must not fail the build because a third party is down. Only a
    definite answer ever fails.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = {"User-Agent": "minecraft-server-template/check-client-parity"}
API = "https://api.modrinth.com/v2/projects?ids="
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read_mod_list(path):
    """slug -> {'optional': bool, 'datapack': bool} from a slug:versionId list."""
    out = {}
    if not path or not os.path.isfile(path):
        return out
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#")[0].strip()
            if not line:
                continue
            datapack = line.startswith("datapack:")
            if datapack:
                line = line[len("datapack:") :]
            optional = line.endswith("?")
            slug = line.rstrip("?").split(":")[0].strip()
            if slug:
                out[slug] = {"optional": optional, "datapack": datapack}
    return out


def read_slug_list(path):
    """Bare slug-per-line list (mods-remove.txt)."""
    out = set()
    if not path or not os.path.isfile(path):
        return out
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            slug = line.split("#")[0].strip().rstrip("?")
            if slug:
                out.add(slug)
    return out


def client_buckets(manifest):
    cm = manifest.get("_clientMods", {})
    buckets = {}
    for name in ("required", "optional", "stableOnly"):
        buckets[name] = {
            (e if isinstance(e, str) else str(e)).split(":")[0].strip()
            for e in (cm.get(name) or [])
        }
    return buckets


def fetch_client_side(slugs):
    """slug -> client_side. Returns None when Modrinth cannot be reached."""
    result = {}
    slugs = sorted(slugs)
    for i in range(0, len(slugs), 40):
        chunk = slugs[i : i + 40]
        url = API + urllib.parse.quote(json.dumps(chunk))
        try:
            req = urllib.request.Request(url, headers=UA)
            for project in json.load(urllib.request.urlopen(req, timeout=30)):
                result[project["slug"]] = project.get("client_side", "unknown")
        except (urllib.error.URLError, TimeoutError, ValueError, OSError) as exc:
            print(f"  ! Modrinth unreachable ({exc}) — direction B skipped")
            return None
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--manifest", default=os.path.join(REPO, "modpack/adventure.mrpack.json"))
    ap.add_argument("--server", default=os.path.join(REPO, "config/modrinth-mods.txt"))
    ap.add_argument("--extra", help="consumer overlay/mods-extra.txt")
    ap.add_argument("--remove", help="consumer overlay/mods-remove.txt")
    ap.add_argument("--offline", action="store_true", help="skip direction B")
    args = ap.parse_args()

    with open(args.manifest, encoding="utf-8") as fh:
        manifest = json.load(fh)
    buckets = client_buckets(manifest)
    exempt = manifest.get("_clientMods", {}).get("_parityExempt", {}) or {}

    server = read_mod_list(args.server)
    server.update(read_mod_list(args.extra))
    removed = read_slug_list(args.remove)
    for slug in removed:
        server.pop(slug, None)

    errors = []

    # Direction A — removed server-side, still demanded of the client.
    for slug in sorted(removed & buckets["required"]):
        errors.append(
            f"'{slug}' is removed server-side but still in _clientMods.required — "
            f"players carrying it are KICKED at join"
        )
    for slug in sorted(removed & buckets["optional"]):
        print(
            f"  note: '{slug}' removed server-side is still a client OPTIONAL — "
            f"harmless if client-only, degraded if it talks to the server"
        )

    # Direction B — on the server, needed by the client, absent from the pack.
    checked = 0
    if not args.offline:
        candidates = {
            slug for slug, meta in server.items() if not meta["datapack"]
        } - buckets["required"]
        sides = fetch_client_side(candidates) if candidates else {}
        if sides is None:
            print("  ! direction B inconclusive — parity NOT proven")
        else:
            checked = len(sides)
            for slug in sorted(candidates):
                if sides.get(slug) != "required":
                    continue
                if slug in exempt:
                    print(f"  note: '{slug}' exempt — {exempt[slug]}")
                    continue
                where = "optional" if slug in buckets["optional"] else "absent"
                errors.append(
                    f"'{slug}' runs on the server and declares client_side=required, "
                    f"but is {where} in _clientMods — every player is KICKED at join"
                )

    if errors:
        for msg in errors:
            print(f"::error::{msg}")
        print(f"\n{len(errors)} client/server parity error(s).")
        print("Add the slug to _clientMods.required at the SAME versionId the")
        print("server pins, or record an exemption in _clientMods._parityExempt.")
        return 1

    print(
        f"  client/server parity: {len(server)} server mods, "
        f"{len(buckets['required'])} client required, {checked} sides checked"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
