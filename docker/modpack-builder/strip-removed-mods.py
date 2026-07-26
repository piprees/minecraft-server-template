#!/usr/bin/env python3
"""Strip server-removed mods from the client manifest's _clientMods lists.

Usage: strip-removed-mods.py <manifest.json> <mods-remove.txt> <output.json>

A consumer's overlay/mods-remove.txt drives SERVER-side mod removal
(config/modrinth-mods.txt). The client manifest is a separate,
consumer-forked file with no native awareness of that list -- a mod
removed server-side but left in _clientMods is a join-time registry
mismatch for every player (see the parity lint in build-modpack.sh).
This closes the gap automatically for the overlay-driven path.

A mods-remove.txt slug matches a manifest entry that starts with
"slug:" or equals "slug" exactly (bare, unpinned). Comments (#) and a
trailing "?" (optional-on-the-server marker, borrowed from
modrinth-mods.txt syntax) are stripped before matching.

Never fails the build -- this is a warning, logged so the consumer sees
what changed.
"""
import json
import sys


def load_removed_slugs(path):
    slugs = set()
    with open(path) as f:
        for line in f:
            slug = line.split("#", 1)[0].strip()
            if slug:
                slugs.add(slug.rstrip("?"))
    return slugs


def entry_slug(entry):
    return entry.split(":", 1)[0]


def main():
    if len(sys.argv) != 4:
        print(f"Usage: {sys.argv[0]} <manifest> <mods-remove.txt> <output>", file=sys.stderr)
        sys.exit(1)

    manifest_path, removes_path, output_path = sys.argv[1:4]

    with open(manifest_path) as f:
        manifest = json.load(f)

    removed_slugs = load_removed_slugs(removes_path)
    client_mods = manifest.get("_clientMods", {})

    any_stripped = False
    for key in ("required", "optional"):
        entries = client_mods.get(key, [])
        kept, stripped = [], []
        for entry in entries:
            (stripped if entry_slug(entry) in removed_slugs else kept).append(entry)
        client_mods[key] = kept
        for entry in stripped:
            any_stripped = True
            print(f"  WARNING: '{entry}' removed server-side (overlay/mods-remove.txt) -- "
                  f"stripping from _clientMods.{key} to keep the client pack in sync.")

    if not any_stripped:
        print("  client/server parity: no server-removed mods were present in the client manifest")

    manifest["_clientMods"] = client_mods

    with open(output_path, "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
