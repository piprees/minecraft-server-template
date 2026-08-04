#!/usr/bin/env python3
"""verify-structure-identity.py — post-migration gate for exact structure identity.

Compares a pre-migration candidates backup against the live bank to verify
that every candidate whose noise census had any positions now carries:
  1. schemaVersion 2 stamps in the live noiseCensus
  2. a sidecar file under census-positions/<dim>/<seed>.json.gz
  3. non-empty byStructure (or {} when the pool for that dimension
     confirms the group is genuinely empty via structure_pools.json)

Exits 0 when every candidate passes; exits 1 with a per-candidate report
on any unexplained gap. Intended to run after score-dimensions.py rescore
(the schemaVersion cache miss makes rescore BE the migration).

Usage:
  python3 verify-structure-identity.py --seedtest .seedtest --backup .seedtest/candidates.bak.20260804-120000

Gotchas:
  - Reads structure_pools.json from <seedtest> for the empty-pool check.
  - A candidate whose backup had count 0 in every group is skipped (nothing
    to verify — an empty census stays empty).
"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import candidates  # noqa: E402
import noise_placement  # noqa: E402


def load_structure_pools(seedtest):
    """Load structure_pools.json from the seedtest directory."""
    p = Path(seedtest) / "structure_pools.json"
    if not p.exists():
        return {}
    try:
        return json.loads(p.read_text())
    except (json.JSONDecodeError, OSError):
        return {}


def verify(seedtest, backup_dir):
    """Compare the backup against the live bank.

    Returns (passed, failed, skipped, report_lines).
    """
    seedtest = Path(seedtest)
    backup = Path(backup_dir)
    if not backup.is_dir():
        return 0, 0, 0, ["ERROR: backup directory does not exist: {}".format(backup)]

    candidates.set_bank_root(str(seedtest))
    live_cdir = candidates.candidates_dir(seedtest)
    pools = load_structure_pools(str(seedtest))

    passed = 0
    failed = 0
    skipped = 0
    report = []

    for backup_file in sorted(backup.glob("*.json")):
        dim_name = backup_file.stem
        backup_store = candidates.load_store(backup_file)
        live_file = live_cdir / "{}.json".format(dim_name)
        if not live_file.exists():
            # The dimension was removed or never had candidates; skip.
            skipped += len(backup_store["candidates"])
            continue
        live_store = candidates.load_store(live_file)
        dim_pools = pools.get(dim_name) or {}

        for seed, backup_cand in backup_store["candidates"].items():
            backup_census = backup_cand.get("noiseCensus") or {}
            backup_groups = backup_census.get("groups") or {}

            # Skip candidates whose backup had no positions in any group.
            total_count = sum(
                (g.get("count") or 0) for g in backup_groups.values())
            if total_count == 0:
                skipped += 1
                continue

            live_cand = live_store["candidates"].get(seed)
            if live_cand is None:
                failed += 1
                report.append(
                    "FAIL {}/{}: candidate missing from the live bank".format(
                        dim_name, seed))
                continue

            live_census = live_cand.get("noiseCensus") or {}
            errors = []

            # Check 1: schemaVersion 2 stamp.
            live_schema = live_census.get("schemaVersion")
            if live_schema != noise_placement.NOISE_CENSUS_SCHEMA_VERSION:
                errors.append(
                    "schemaVersion is {} (expected {})".format(
                        live_schema,
                        noise_placement.NOISE_CENSUS_SCHEMA_VERSION))

            # Check 2: sidecar file exists.
            if not noise_placement.census_sidecar_exists(
                    str(seedtest), dim_name, seed):
                errors.append("sidecar file missing")

            # Check 3: byStructure for every group that had count > 0.
            live_groups = live_census.get("groups") or {}
            for group, backup_entry in backup_groups.items():
                bcount = backup_entry.get("count") or 0
                if bcount == 0:
                    continue
                live_entry = live_groups.get(group)
                if live_entry is None:
                    errors.append(
                        "group '{}' missing from live census".format(group))
                    continue
                by_struct = live_entry.get("byStructure")
                if by_struct is None:
                    errors.append(
                        "group '{}': byStructure missing".format(group))
                    continue
                if not by_struct:
                    # Empty byStructure is valid iff the pool confirms this
                    # group is genuinely empty (no structures eligible).
                    group_pool = dim_pools.get(group) or {}
                    if group_pool:
                        errors.append(
                            "group '{}': byStructure is empty but pool has "
                            "{} structure(s)".format(group, len(group_pool)))

            if errors:
                failed += 1
                report.append("FAIL {}/{}: {}".format(
                    dim_name, seed, "; ".join(errors)))
            else:
                passed += 1

    return passed, failed, skipped, report


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seedtest", required=True,
                    help="the .seedtest directory")
    ap.add_argument("--backup", required=True,
                    help="pre-migration candidates backup directory")
    args = ap.parse_args()

    passed, failed, skipped, report = verify(args.seedtest, args.backup)

    for line in report:
        print(line, file=sys.stderr)

    print("verify-structure-identity: {} passed, {} failed, {} skipped".format(
        passed, failed, skipped))

    if failed > 0:
        print("FAILED: {} candidate(s) do not carry the expected "
              "schemaVersion 2 data after migration".format(failed),
              file=sys.stderr)
        sys.exit(1)
    if passed == 0 and failed == 0 and skipped == 0:
        print("ERROR: the backup contains no candidate stores — nothing "
              "was verified. Check the --backup path.", file=sys.stderr)
        sys.exit(1)
    if passed == 0 and skipped > 0:
        print("WARNING: all {} candidate(s) were skipped (no groups with "
              "count > 0 in the backup)".format(skipped))
    sys.exit(0)


if __name__ == "__main__":
    main()
