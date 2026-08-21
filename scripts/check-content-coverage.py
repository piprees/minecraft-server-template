#!/usr/bin/env python3
"""Report which installed biomes any dimension can actually produce.

Purpose:  Adding a biome mod puts biomes in the registry; it does not put them
          in a world. A managed dimension generates the biomes its `biomes`
          list names and nothing else, so a mod nobody names is installed,
          loaded, paid for on every boot, and invisible in game.

Context:  The catalogue comes from scripts/extract-biomes.py, which must be
          re-run after any mod-list change or it reports the previous pack.

Usage:    scripts/check-content-coverage.py [--namespace NS] [--unused]
          --unused lists the unreferenced biome ids rather than counting them.

Gotchas:  - Reads BOTH the platform configs and, when present, the consumer
            overlay, because an overlay `biomes` array replaces the platform
            one outright — counting only the platform would over-report.
          - Says nothing about structures. Whether a structure can generate
            depends on the dimension's biome-filtered noise pool, which is
            built in the mod at world load; `/customdim structure-census` is
            the instrument for that, not this script.
"""

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CATALOGUE = REPO / "config/custom-dimensions/extractors/biomes.json"
PLATFORM_DIMS = REPO / "config/custom-dimensions/dimensions"
OVERLAY_DIMS = Path.home() / "Projects/elfydd/overlay/config/custom-dimensions/dimensions"


def named_biomes(path):
    """Every biome id a dimension file names, in `biomes` or `biomePatches`."""
    out = set()
    if not path.exists():
        return out
    for f in sorted(path.glob("*.json")):
        try:
            doc = json.loads(f.read_text())
        except json.JSONDecodeError as e:
            print(f"  UNPARSEABLE {f.name}: {e}", file=sys.stderr)
            continue
        cfg = doc.get("overrides", doc)
        for entry in cfg.get("biomes") or []:
            if isinstance(entry, str):
                out.add(entry)
            elif isinstance(entry, dict) and entry.get("id"):
                out.add(entry["id"])
        biome = cfg.get("biome")
        if isinstance(biome, str):
            out.update(b.strip() for b in biome.split(","))
        for patch in cfg.get("biomePatches") or []:
            if isinstance(patch, dict) and patch.get("biome"):
                out.add(patch["biome"])
        flat = cfg.get("flatBiome")
        if isinstance(flat, str):
            out.add(flat)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--namespace")
    ap.add_argument("--unused", action="store_true")
    args = ap.parse_args()

    if not CATALOGUE.exists():
        print(f"No catalogue at {CATALOGUE} — run scripts/extract-biomes.py", file=sys.stderr)
        return 2
    cat = json.loads(CATALOGUE.read_text())
    installed = set(cat["biomes"])

    used = named_biomes(PLATFORM_DIMS) | named_biomes(OVERLAY_DIMS)

    by_ns = {}
    for bid in installed:
        ns = bid.split(":", 1)[0]
        total, hit = by_ns.get(ns, (0, 0))
        by_ns[ns] = (total + 1, hit + (1 if bid in used else 0))

    rows = sorted(by_ns.items(), key=lambda kv: (kv[1][1] / kv[1][0], -kv[1][0]))
    print(f"{'namespace':<22} {'named':>6} {'installed':>10} {'coverage':>9}")
    for ns, (total, hit) in rows:
        if args.namespace and ns != args.namespace:
            continue
        print(f"{ns:<22} {hit:>6} {total:>10} {100 * hit / total:>8.0f}%")

    unreachable = sorted(b for b in installed if b not in used
                         and (not args.namespace or b.startswith(args.namespace + ":")))
    print(f"\n{len(unreachable)} of {len(installed)} installed biomes are named by no dimension")
    if args.unused:
        for b in unreachable:
            print(f"  {b}")

    named_but_absent = sorted(b for b in used if b not in installed and ":" in b)
    if named_but_absent:
        print(f"\n{len(named_but_absent)} named biome(s) are not installed:")
        for b in named_but_absent[:20]:
            print(f"  {b}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
