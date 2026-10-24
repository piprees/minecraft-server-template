#!/usr/bin/env python3
"""carry_holds.py - carry version holds from the open mod-update PR into a re-pin.

Context: mod-updates.yml re-pins every mod from main each week and
force-pushes mod-updates/auto. dep-review.yml holds mods back (or releases
them) by editing `_holds` on that branch, so before re-pinning, the
branch's hold edits are merged into main's manifest. The merge is three-way
per slug against the merge base: a slug the branch added, changed or removed
takes the branch's state; every other slug keeps main's, so holds added on
main meanwhile survive too.

Usage:
  python3 scripts/dep_review/carry_holds.py --manifest modpack/adventure.mrpack.json \
      --branch BRANCH_MANIFEST --base BASE_MANIFEST

Gotchas:
  - Writes --manifest in place, formatted exactly as the repo keeps it
    (indent 2, UTF-8, trailing newline) so only `_holds` shows in the diff.
  - Prints one line per carried slug; prints nothing when there is nothing
    to carry.
"""
import argparse
import json

_MISSING = object()


def merge_holds(main, branch, base):
    """Three-way merge of slug -> reason maps. Returns (merged, carried slugs)."""
    merged = dict(main)
    carried = []
    for slug in sorted(set(main) | set(branch) | set(base)):
        in_branch = branch.get(slug, _MISSING)
        if in_branch == base.get(slug, _MISSING):
            continue
        carried.append(slug)
        if in_branch is _MISSING:
            merged.pop(slug, None)
        else:
            merged[slug] = in_branch
    return merged, carried


def _holds(path):
    with open(path) as f:
        return json.load(f).get("_holds", {})


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--base", required=True)
    args = parser.parse_args()

    with open(args.manifest) as f:
        manifest = json.load(f)
    merged, carried = merge_holds(manifest.get("_holds", {}), _holds(args.branch), _holds(args.base))
    if not carried:
        return
    manifest["_holds"] = merged
    with open(args.manifest, "w") as f:
        f.write(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    for slug in carried:
        state = "held" if slug in merged else "released"
        print(f"  {slug}: {state} (carried from the open PR)")


if __name__ == "__main__":
    main()
