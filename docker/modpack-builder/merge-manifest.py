#!/usr/bin/env python3
"""Deep-merge a consumer manifest patch onto the default adventure.mrpack.json.

Usage: merge-manifest.py <default.json> <patch.json> <output.json>

Patch keys (all optional):
  name, versionId          — replace at top level
  remove[]                 — slugs to drop from _clientMods required+optional
  add.required/optional[]  — slug:versionId entries to add; one already listed
                             under the same slug is replaced, in either list

Entries match by slug, the part before ":", so "remove": ["sodium"] drops
"sodium:QV48eyCs".
  _resourcePacks           — replace entire section
  _shaderPacks             — replace entire section
"""
import json
import sys


def slug_of(entry):
    return entry.split(":", 1)[0]


def merge(default, patch):
    removes = {slug_of(e) for e in patch.get("remove", [])}

    if removes:
        cm = default.get("_clientMods", {})
        for key in ("required", "optional"):
            cm[key] = [s for s in cm.get(key, []) if slug_of(s) not in removes]
        default["_clientMods"] = cm

    add = patch.get("add", {})
    if add:
        cm = default.setdefault("_clientMods", {})
        for key in ("required", "optional"):
            for entry in add.get(key, []):
                for other in ("required", "optional"):
                    cm[other] = [s for s in cm.get(other, []) if slug_of(s) != slug_of(entry)]
                cm[key].append(entry)

    for scalar in ("name", "versionId"):
        if scalar in patch:
            default[scalar] = patch[scalar]

    for section in ("_resourcePacks", "_shaderPacks"):
        if section in patch:
            default[section] = patch[section]

    return default


def main():
    if len(sys.argv) != 4:
        print(f"Usage: {sys.argv[0]} <default> <patch> <output>", file=sys.stderr)
        sys.exit(1)

    default_path, patch_path, output_path = sys.argv[1:4]

    with open(default_path) as f:
        default = json.load(f)
    with open(patch_path) as f:
        patch = json.load(f)

    result = merge(default, patch)

    with open(output_path, "w") as f:
        json.dump(result, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
