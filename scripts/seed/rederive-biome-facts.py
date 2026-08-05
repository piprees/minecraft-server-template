#!/usr/bin/env python3
"""rederive-biome-facts.py — re-measure the bank's biome-layer facts under
the CURRENT sampler, with a delta report before anything is written.

Banked spawn/biome measurements are pure functions of (seed, generation
config, sampler code). Sampler corrections (noise aliases, overlay-aware
extraction, shift scale, vanilla quantisation, SearchTree tie-breaks,
legacy_random_source climate) change what the exact answer IS for
measurements taken before them — the measurements must be re-derived,
not rescored. This tool re-runs tier2_measure (spawn site selection,
spawn biome, per-biome locate distances, the 3x3 terrain grid) and the
spawn-anchored structure battery for every banked candidate, compares
old vs new rows, and only writes with --write.

Scores are NOT recomputed here: after --write, run
`score-dimensions.py rescore` (fold-based, resumable) so scoring sees
the re-derived measurements.

Usage:
    python3 rederive-biome-facts.py --config <custom-dimensions dir> \
        --seedtest <dir> [--dims slug[,slug...]] [--workers N] [--write]

Template-only: NOT in the bundle MANIFEST.
"""

import argparse
import multiprocessing
import shutil
import sys
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import candidates as cnd  # noqa: E402
import fast_roller  # noqa: E402
from dimension_profiles import build_profile, load_config, rollable  # noqa: E402
from structure_placement import load_structure_sets  # noqa: E402

_G = {}


def _init_worker(profile, biome_params_path, noise_configs, struct_sets_path,
                 seedtest_path):
    struct_sets = load_structure_sets(struct_sets_path)
    struct_to_sets = {}
    for set_id, cfg in struct_sets.items():
        for s in cfg["structures"]:
            struct_to_sets.setdefault(s["id"], []).append(set_id)
    _G.update(profile=profile, biome_params_path=biome_params_path,
              noise_configs=noise_configs, struct_sets=struct_sets,
              struct_to_sets=struct_to_sets, seedtest_path=seedtest_path)


def _measure_one(seed_str):
    profile = _G["profile"]
    seed = int(seed_str)
    sampler = fast_roller._build_sampler(
        seed, profile, _G["biome_params_path"], _G["noise_configs"])
    rows, ok = fast_roller.tier2_measure(seed, profile, sampler)
    spawn_x = next((v for k, v in rows if k == "spawn_x"), 0)
    spawn_z = next((v for k, v in rows if k == "spawn_z"), 0)
    _score, struct_dists = fast_roller.tier1_score(
        seed, profile, _G["struct_sets"], _G["struct_to_sets"],
        origin_x=int(spawn_x), origin_z=int(spawn_z),
        seedtest_path=_G["seedtest_path"])
    for sname, _sid, _band, _kind in profile["battery"]:
        rows.append((f"structure_{sname}_dist", struct_dists.get(sname, -1)))
    return seed_str, rows, ok


def _num(v):
    """Banked measurement values arrive as strings from the worker
    protocol; fresh rows are ints/floats. Compare numerically."""
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _delta(old_m, rows):
    """Classify the difference between banked measurements and fresh rows."""
    new_m = {k: v for k, v in rows}
    d = {"spawn_biome": 0, "spawn_moved": 0, "biome_flip": 0,
         "biome_shift": 0, "battery_flip": 0, "terrain": 0, "now_rejected": 0}
    if new_m.get("rejected") in (1, "1"):
        d["now_rejected"] = 1
        return d, new_m
    if old_m.get("spawn_biome") != new_m.get("spawn_biome"):
        d["spawn_biome"] = 1
    if (_num(old_m.get("spawn_x")), _num(old_m.get("spawn_z"))) != \
            (_num(new_m.get("spawn_x")), _num(new_m.get("spawn_z"))):
        d["spawn_moved"] = 1
    for k, raw_nv in new_m.items():
        ov = _num(old_m.get(k))
        nv = _num(raw_nv)
        if ov is None or nv is None:
            continue
        if k.startswith("biome_") and k.endswith("_dist"):
            if (ov < 0) != (nv < 0):
                d["biome_flip"] += 1
            elif ov != nv:
                d["biome_shift"] += 1
        elif k.startswith("structure_") and k.endswith("_dist"):
            if (ov < 0) != (nv < 0):
                d["battery_flip"] += 1
        elif k.startswith(("height_", "water_")):
            if ov != nv:
                d["terrain"] += 1
    return d, new_m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--seedtest", required=True)
    ap.add_argument("--dims", default="",
                    help="comma-separated slugs; default: every rollable dim")
    ap.add_argument("--workers", type=int, default=0)
    ap.add_argument("--write", action="store_true",
                    help="fold re-derived rows into the bank (default: report only)")
    ap.add_argument("--limit", type=int, default=0,
                    help="candidates per dim (0 = all) — for pilot timing")
    args = ap.parse_args()

    cnd.set_bank_root(args.seedtest)
    if args.write:
        # One backup per invocation, before the first store is touched —
        # the write pass moves candidates and rewrites measurements.
        src = cnd.candidates_dir(Path(args.config))
        if src.is_dir():
            stamp = time.strftime("%Y%m%d-%H%M%S")
            dst = src.parent / f"candidates.bak.{stamp}"
            shutil.copytree(src, dst)
            print(f"bank backed up to {dst}", flush=True)
    config = load_config(args.config)
    sources = {d["name"]: d for d in config.get("dimensions", [])}
    sources.update({w["name"]: w for w in config.get("worlds", [])})
    only = {s for s in args.dims.split(",") if s}

    from biome_sampler import load_noise_configs
    noise_configs = load_noise_configs()
    seedtest = Path(args.seedtest)
    biome_params_path = str(seedtest / "biome_params.json")
    struct_sets_path = str(seedtest / ".structure_sets")
    workers = args.workers or max(2, multiprocessing.cpu_count() * 2 // 3)

    grand = {"cands": 0, "spawn_biome": 0, "spawn_moved": 0, "biome_flip": 0,
             "biome_shift": 0, "battery_flip": 0, "terrain": 0,
             "now_rejected": 0}
    for name, src in sorted(sources.items()):
        if only and name not in only:
            continue
        if not rollable(src):
            continue
        store_path = cnd.candidates_dir(Path(args.config)) / f"{name}.json"
        if not store_path.exists():
            continue
        store = cnd.load_store(store_path)
        seeds = list(store["candidates"].keys())
        if args.limit:
            seeds = seeds[:args.limit]
        if not seeds:
            continue
        profile = build_profile(src, config)
        t0 = time.time()
        totals = {"spawn_biome": 0, "spawn_moved": 0, "biome_flip": 0,
                  "biome_shift": 0, "battery_flip": 0, "terrain": 0,
                  "now_rejected": 0}
        results = {}
        with multiprocessing.Pool(
                workers, initializer=_init_worker,
                initargs=(profile, biome_params_path, noise_configs,
                          struct_sets_path, args.seedtest)) as pool:
            for seed_str, rows, _ok in pool.imap_unordered(
                    _measure_one, seeds, chunksize=16):
                old_m = store["candidates"][seed_str].get("measurements") or {}
                d, new_m = _delta(old_m, rows)
                for k, v in d.items():
                    totals[k] += v
                results[seed_str] = rows
        dt = time.time() - t0
        grand["cands"] += len(seeds)
        for k, v in totals.items():
            grand[k] += v
        print(f"{name}: {len(seeds)} candidates in {dt:.0f}s "
              f"({dt / max(1, len(seeds)):.2f}s/cand) — "
              f"spawn_biome changed {totals['spawn_biome']}, "
              f"spawn moved {totals['spawn_moved']}, "
              f"biome found/not flips {totals['biome_flip']}, "
              f"dist shifts {totals['biome_shift']}, "
              f"battery flips {totals['battery_flip']}, "
              f"terrain cells {totals['terrain']}, "
              f"now-rejected {totals['now_rejected']}", flush=True)
        if args.write:
            written = moved = 0
            for s, rows in results.items():
                rowd = {k: v for k, v in rows}
                if rowd.get("rejected") in (1, "1"):
                    # The corrected sampler rejects this seed outright — the
                    # spawn filter finds no matching biome anywhere in the
                    # window. A fresh roll would have banked it rejected.
                    store["rejected"].setdefault(
                        s, "spawn filter (re-derivation): no matching biome")
                    store["candidates"].pop(s, None)
                    moved += 1
                    continue
                cand = store["candidates"].get(s)
                if cand is None:
                    continue
                cand.setdefault("measurements", {}).update(rowd)
                written += 1
            cnd.save_store(store_path, store)
            print(f"{name}: bank updated ({written} candidates, "
                  f"{moved} moved to rejected)", flush=True)

    print("\nTOTAL: {cands} candidates — spawn_biome changed {spawn_biome}, "
          "spawn moved {spawn_moved}, biome flips {biome_flip}, "
          "dist shifts {biome_shift}, battery flips {battery_flip}, "
          "terrain cells {terrain}".format(**grand))
    return 0


if __name__ == "__main__":
    sys.exit(main())
