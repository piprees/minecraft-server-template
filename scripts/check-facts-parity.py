#!/usr/bin/env python3
# =============================================================================
# check-facts-parity.py — assert the facts engine's structure census equals the
#                         live server's, and that absences are absences
# =============================================================================
#
# Purpose:
#   Phase 4's gate. The facts engine builds a dimension's structure census
#   headlessly for an arbitrary seed, from the mod's own NoisePoolBuilder,
#   NoiseFieldIndex and StructurePick. `/customdim structure-census` dumps the
#   same thing from the LIVE StructurePlacementCalculator — the object chunk
#   generation and /locate consult. For the world's own seed the two must be
#   identical, structure for structure and count for count. A difference means
#   the facts engine is describing a world the server will not generate.
#
#   This is the one comparison that can catch a facts engine that is
#   self-consistent and wrong, which is the failure mode a unit test cannot see.
#
# Context:
#   Dump both for the same dimension at its OWN seed (the census only exists
#   for the seed the world was created with):
#     docker exec -i mc rcon-cli "customdim load <slug>"
#     docker exec -i mc rcon-cli "customdim structure-census <ns>:<slug>"
#     docker exec -i mc rcon-cli "customdim facts <ns>:<slug> <that world's seed>"
#
# Usage:
#   ./scripts/check-facts-parity.py --facts <consumer>/data/config/custom-dimensions/facts \
#                                   --census <consumer>/data/config/custom-dimensions/census
#
# Gotchas:
#   - Only (dimension, seed) pairs where the seed matches the census's own
#     `seed` field are compared; anything else is a different world and is
#     reported as skipped rather than silently passed.
#   - The census records forced and pass-through placements separately from the
#     noise groups. The facts engine measures the NOISE census only, so the
#     comparison is against `groups` alone — stated here rather than left for a
#     reader to infer from a mismatch.
# =============================================================================

import argparse
import json
import sys
from collections import Counter
from pathlib import Path


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--facts", required=True, type=Path)
    ap.add_argument("--census", required=True, type=Path)
    ap.add_argument("--expect-pairs", type=int, default=0)
    args = ap.parse_args()

    failures = []
    compared = 0
    skipped = []

    def check(name, ok, detail=""):
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}"
              f"{(' — ' + detail) if detail else ''}")
        if not ok:
            failures.append((name, detail))

    for fp in sorted(args.facts.glob("*.json")):
        facts = json.loads(fp.read_text())
        if facts.get("schemaVersion") != 1:
            check(f"{fp.name}: schemaVersion", False,
                  f"expected 1, got {facts.get('schemaVersion')!r}")
            continue
        dim = facts["dimension"]
        seed = facts["seed"]
        cp = args.census / (dim.replace(":", "__") + ".json")
        if not cp.exists():
            skipped.append((dim, seed, "no census dumped"))
            continue
        census = json.loads(cp.read_text())
        if census.get("seed") != seed:
            skipped.append((dim, seed,
                            f"census is for seed {census.get('seed')}, a different world"))
            continue

        compared += 1
        tag = f"{dim}@{seed}"

        live_by_group = {g: len(e.get("positions") or [])
                         for g, e in (census.get("groups") or {}).items()}
        facts_by_group = facts["structures"]["byGroup"]

        # An absent structure fact and an empty live census are the SAME
        # answer stated two ways: the dimension places nothing. Comparing the
        # absence object against {} would fail a correct engine, so the
        # equivalence is checked explicitly rather than left to ==.
        if _absent(facts_by_group):
            check(f"{tag}: absent structures agree with an empty live census",
                  not live_by_group,
                  f"facts say {facts_by_group['absent']!r} but live has {live_by_group}")
            continue

        check(f"{tag}: per-group position counts match",
              live_by_group == facts_by_group,
              f"live {live_by_group} vs facts {facts_by_group}")

        live_by_structure = Counter()
        for e in (census.get("groups") or {}).values():
            for p in e.get("positions") or []:
                if p[2]:
                    live_by_structure[p[2]] += 1
        facts_by_structure = facts["structures"]["byStructure"]
        check(f"{tag}: per-structure assignment counts match",
              dict(live_by_structure) == facts_by_structure,
              _first_diff(dict(live_by_structure), facts_by_structure))

        live_total = sum(live_by_group.values())
        check(f"{tag}: total positions match",
              live_total == facts["structures"]["totalPositions"],
              f"live {live_total} vs facts {facts['structures']['totalPositions']}")

    print()
    # An absence must be an object with a reason, never a zero. Checked over
    # every facts file, including the ones with no census to compare against.
    for fp in sorted(args.facts.glob("*.json")):
        facts = json.loads(fp.read_text())
        bad = []
        for section, fields in facts.items():
            if not isinstance(fields, dict):
                continue
            for name, value in fields.items():
                if isinstance(value, dict) and "absent" in value:
                    if not str(value["absent"]).strip():
                        bad.append(f"{section}.{name}")
        check(f"{fp.name}: every absence carries a reason", not bad, str(bad))

    if args.expect_pairs:
        check(f"compared {args.expect_pairs} (dimension, seed) pairs",
              compared >= args.expect_pairs, f"compared {compared}")

    if skipped:
        print(f"\n  [note] {len(skipped)} pair(s) skipped:")
        for dim, seed, why in skipped[:10]:
            print(f"          {dim}@{seed}: {why}")

    print(f"\n{compared} pair(s) compared, {len(failures)} failed")
    for name, detail in failures:
        print(f"  FAILED: {name} — {detail}")
    return 1 if failures else 0


def _absent(v):
    return isinstance(v, dict) and "absent" in v


def _first_diff(a, b):
    keys = set(a) | set(b)
    diffs = [(k, a.get(k), b.get(k)) for k in sorted(keys) if a.get(k) != b.get(k)]
    return f"{len(diffs)} differing: {diffs[:5]}" if diffs else ""


if __name__ == "__main__":
    sys.exit(main())
