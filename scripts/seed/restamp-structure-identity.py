#!/usr/bin/env python3
"""restamp-structure-identity.py — restamp generation fingerprints after a
structure-identity migration (pick-v1).

pick-v1 changes ONLY which structure occupies a noise site; terrain and
biome generation are unchanged. The migration rescore recomputes the
entire structure layer exactly (schemaVersion 2 sidecars + byStructure,
poolHash-stamped, verify gate 0 failures). A candidate whose noiseCensus
carries the CURRENT noise_fingerprint and poolHash, with its sidecar
present, has measurements that describe the currently-configured world in
full — restamping its generation fingerprint to the current value states
a verified fact, not an assumption.

A dimension whose seedRoll/worldgen config changes AFTER restamping
drifts again correctly: the new generation_fingerprint differs from the
stamped one, and seed-status reports DRIFTED as expected.

Usage:
    python3 restamp-structure-identity.py \\
        --seedtest <dir> --config <config/custom-dimensions>

    --dry-run   prints per-dim counts without writing

Template-only script — NOT shipped in the bundle MANIFEST.
"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import candidates  # noqa: E402
import census_scoring  # noqa: E402
import noise_placement  # noqa: E402
from dimension_profiles import (  # noqa: E402
    generation_fingerprint, load_config, noise_fingerprint, rollable,
)


def restamp(seedtest, config_dir, dry_run=False):
    """Restamp eligible candidates and return (restamped, skipped) counts."""
    config = load_config(config_dir)
    sources = {d["name"]: d for d in config.get("dimensions", [])}
    sources.update({w["name"]: w for w in config.get("worlds", [])})

    pools = census_scoring.load_structure_pools(seedtest)
    cdir = candidates.candidates_dir(Path(config_dir))

    total_restamped = 0
    total_skipped = 0

    for name, src in sorted(sources.items()):
        if not rollable(src):
            continue
        cur_gen_fp = generation_fingerprint(src)
        cur_noise_fp = noise_fingerprint(src)
        if cur_gen_fp is None:
            continue

        dim_pools = (pools or {}).get(name) or {}
        cur_ph = noise_placement.pool_hash(dim_pools)

        store_path = cdir / "{}.json".format(name)
        store = candidates.load_store(store_path)
        if not store["candidates"]:
            continue

        restamped = 0
        skipped = 0
        for seed, cand in store["candidates"].items():
            existing_fp = candidates.effective_fingerprint(cand)
            if existing_fp == cur_gen_fp:
                continue  # already current — nothing to do

            census = cand.get("noiseCensus")
            if not census:
                skipped += 1
                continue

            if census.get("schemaVersion") != noise_placement.NOISE_CENSUS_SCHEMA_VERSION:
                skipped += 1
                continue

            if census.get("fp") != cur_noise_fp:
                skipped += 1
                continue

            if census.get("poolHash") != cur_ph:
                skipped += 1
                continue

            if not noise_placement.census_sidecar_exists(seedtest, name, seed):
                skipped += 1
                continue

            # All conditions met: the structure layer was recomputed exactly
            # under the current noise config, so the candidate's measurements
            # describe the world the current generation config produces.
            cand["fingerprint"] = cur_gen_fp
            restamped += 1

        if restamped or skipped:
            label = "[dry-run] " if dry_run else ""
            print("{}{}:  {} restamped, {} skipped".format(
                label, name, restamped, skipped))

        if restamped and not dry_run:
            candidates.save_store(store_path, store)

        total_restamped += restamped
        total_skipped += skipped

    return total_restamped, total_skipped


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seedtest", required=True,
                    help=".seedtest directory (candidate bank root)")
    ap.add_argument("--config", required=True,
                    help="config/custom-dimensions/ directory")
    ap.add_argument("--dry-run", action="store_true",
                    help="print counts without writing")
    args = ap.parse_args()

    # Bank-root ordering: set_bank_root BEFORE touching the bank.
    candidates.set_bank_root(args.seedtest)

    restamped, skipped = restamp(args.seedtest, args.config,
                                 dry_run=args.dry_run)
    action = "would restamp" if args.dry_run else "restamped"
    print("\ntotal: {} {}, {} skipped".format(restamped, action, skipped))
    return 0


if __name__ == "__main__":
    sys.exit(main())
