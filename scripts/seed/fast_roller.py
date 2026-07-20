#!/usr/bin/env python3
"""fast_roller.py — Pure-Python seed candidate generator.

Two-tier screening:
  Tier 1 (instant): structure placement only — 100k+ seeds/sec.
         Scores structures, rejects seeds with zero battery hits.
  Tier 2 (fast):    biome sampling on tier-1 survivors — ~15 seeds/sec.
         Spawn filter, biome variety, terrain proxy.

No server, no RCON, no Docker. Writes the same CSV format that
score-dimensions.py consumes.

Usage:
    python3 fast_roller.py --config <dir> --seedtest <dir> [--dims a,b,c]
                           [--count 500] [--workers 4] [--tier1-pool 5000]
"""

import argparse
import importlib.util
import multiprocessing
import os
import struct
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from biome_sampler import BiomeSampler, load_noise_configs  # noqa: E402
from dimension_profiles import (  # noqa: E402
    build_profile, load_config, load_difficulty, rollable,
)
from structure_placement import load_structure_sets, nearest_structure  # noqa: E402


LOCATE_HORIZON = 1600


def _window_score(value, lo, hi):
    if value is None:
        return 0.0
    width = max(hi - lo, 1e-9)
    if lo <= value <= hi:
        return 1.0
    if value < lo:
        return max(0.0, 1.0 - (lo - value) / width)
    return max(0.0, 1.0 - (value - hi) / width)


def want_score(dist, lo, hi, radius):
    hi = min(hi, radius)
    if lo >= LOCATE_HORIZON:
        if dist is None or dist < 0:
            return 0.8
        return 0.2 if dist < radius * 0.3 else 1.0
    if dist is None or dist < 0:
        return 0.0 if hi <= LOCATE_HORIZON else 0.6
    return _window_score(dist, lo, hi)


def shun_score(dist, radius, min_distance=None):
    threshold = min_distance if min_distance else radius
    return 0.0 if (dist is not None and 0 <= dist < threshold) else 1.0


SCRIPT_DIR = Path(__file__).resolve().parent
BIOME_PARAMS = SCRIPT_DIR / "biome_params.json"

WATER_BIOMES = {
    "minecraft:ocean", "minecraft:deep_ocean", "minecraft:cold_ocean",
    "minecraft:deep_cold_ocean", "minecraft:frozen_ocean",
    "minecraft:deep_frozen_ocean", "minecraft:lukewarm_ocean",
    "minecraft:deep_lukewarm_ocean", "minecraft:warm_ocean",
    "minecraft:river", "minecraft:frozen_river",
    "terralith:deep_warm_ocean", "terralith:warm_river",
}

_CONT_TO_HEIGHT = [
    (-1.2, 40), (-0.455, 55), (-0.19, 62), (-0.11, 63),
    (0.03, 70), (0.3, 100), (0.55, 140), (0.8, 190), (1.0, 256),
]

FAMILY_NOISE = {
    "overworld": "overworld", "nether": "nether",
    "end": "end", None: "overworld",
}

# Clone-type families that need a specific noise config despite family_of()
# returning "overworld" (paradise_lost is a mod dimension with its own noise).
_TYPE_NOISE_OVERRIDE = {
    "paradise_lost:paradise_lost": "paradise_lost",
}


def _cont_to_height(cont):
    for i in range(len(_CONT_TO_HEIGHT) - 1):
        c0, h0 = _CONT_TO_HEIGHT[i]
        c1, h1 = _CONT_TO_HEIGHT[i + 1]
        if cont <= c1:
            t = (cont - c0) / (c1 - c0) if c1 != c0 else 0
            return h0 + t * (h1 - h0)
    return _CONT_TO_HEIGHT[-1][1]


def random_seed():
    return struct.unpack("<q", os.urandom(8))[0]


def _resolve_struct_set(sid, struct_sets, struct_to_sets):
    clean = sid.lstrip("#")
    if clean in struct_to_sets:
        return struct_sets[struct_to_sets[clean][0]]
    if sid.startswith("#"):
        tag_path = clean.split(":")[-1] if ":" in clean else clean
        for set_id, cfg in struct_sets.items():
            for s in cfg["structures"]:
                if tag_path in s["id"]:
                    return cfg
    if clean in struct_sets:
        return struct_sets[clean]
    return None


# -----------------------------------------------------------------------
# Tier 1: structure-only screening (instant)
# -----------------------------------------------------------------------
def tier1_score(seed, profile, struct_sets, struct_to_sets):
    """Structure battery score for a seed. Returns (score, distances dict).
    No biomes, no noise — pure math. <0.1ms per seed."""
    cap = profile.get("locate_cap")
    dists = {}
    if not profile["battery"]:
        return 0.5, dists

    ss, n = 0.0, 0
    for sname, sid, spec, kind in profile["battery"]:
        set_cfg = _resolve_struct_set(sid, struct_sets, struct_to_sets)
        if set_cfg:
            result = nearest_structure(
                seed, set_cfg["spacing"], set_cfg["separation"],
                set_cfg["salt"], spread_type=set_cfg.get("spread_type", "linear"),
                frequency=set_cfg.get("frequency", 1.0),
                search_radius=50)
            dist = result[0] if result else -1
            if cap is not None and dist > cap:
                dist = -1
        else:
            dist = -1
        dists[sname] = dist
        if kind == "shun":
            ss += shun_score(dist, profile["radius"], spec)
        else:
            ss += want_score(dist, spec[0], spec[1], profile["radius"])
        n += 1
    return (ss / n if n else 0.0), dists


# -----------------------------------------------------------------------
# Tier 2: biome + terrain on survivors (coarse grid for speed)
# -----------------------------------------------------------------------
def tier2_measure(seed, profile, sampler):
    """Full measurement with biome sampler. Returns (rows, accepted)."""
    rows = []
    fam = profile["family"] or "overworld"

    # Spawn filter — coarse grid (step=256 for speed: 49 points, ~54ms)
    spawn = "unknown"
    spawn_x, spawn_z = 0, 0

    if profile["namesake"]:
        namesake_set = set(profile["namesake"])
        result = sampler.spawn_filter(namesake_set, radius=768, step=256)
        best_b, best_d, best_x, best_z = result

        if best_b is not None and best_d >= 0:
            if best_d <= 48:
                spawn = best_b
            spawn_x, spawn_z = best_x, best_z
            rows.append(("spawn_filter_dist", best_d))
        else:
            rows.append(("spawn_biome", "unknown"))
            rows.append(("rejected", 1))
            return rows, False

    if spawn == "unknown":
        spawn = sampler.biome_at(0, 0)

    rows.append(("spawn_biome", spawn))
    rows.append(("spawn_x", spawn_x))
    rows.append(("spawn_z", spawn_z))

    # Biome variety — coarse locate (step=256, capped radius for speed)
    variety_radius = min(profile.get("locate_cap", 6400), 3200)
    for biome in profile["variety_biomes"]:
        result = sampler.locate_biome(biome, radius=variety_radius, step=256)
        dist = result[0] if result else -1
        rows.append((f"biome_{biome}_dist", dist))

    # Terrain proxy from climate
    pitch = profile["grid_pitch"]
    has_cont = fam == "overworld" and not profile.get("is_void")
    for r in range(3):
        for c in range(3):
            x, z = (c - 1) * pitch, (r - 1) * pitch
            if profile.get("is_void"):
                pass
            elif has_cont:
                climate = sampler.sample_climate(x, z)
                cont = climate.get("continentalness", 0.0)
                h = _cont_to_height(cont)
                rows.append((f"height_r{r}c{c}", int(h)))
                biome = sampler.biome_at(x, z)
                rows.append((f"water_r{r}c{c}", 1 if biome in WATER_BIOMES else 0))
            else:
                climate = sampler.sample_climate(x, z)
                ero = climate.get("erosion", 0.0)
                h = 64 + int(ero * 30)
                rows.append((f"height_r{r}c{c}", h))
                biome = sampler.biome_at(x, z)
                rows.append((f"water_r{r}c{c}", 1 if biome in WATER_BIOMES else 0))

    rows.append(("errors", 0))
    return rows, True


def _process_dimension(task):
    """Process one dimension: tier-1 screen, then tier-2 on top-N."""
    (dim_name, profile, pool_size, keep_count,
     struct_sets_path, biome_params_path, noise_configs, seen_set) = task

    t0 = time.time()

    # Load structure sets
    struct_sets = load_structure_sets(struct_sets_path)
    struct_to_sets = {}
    for set_id, cfg in struct_sets.items():
        for s in cfg["structures"]:
            struct_to_sets.setdefault(s["id"], []).append(set_id)

    # Tier 1: screen pool_size seeds on structures alone
    tier1 = []
    for _ in range(pool_size):
        seed = random_seed()
        while str(seed) in seen_set:
            seed = random_seed()
        seen_set.add(str(seed))
        score, dists = tier1_score(seed, profile, struct_sets, struct_to_sets)
        tier1.append((score, seed, dists))

    tier1.sort(reverse=True)
    survivors = tier1[:keep_count]
    tier1_ms = (time.time() - t0) * 1000

    # Tier 2: full biome+terrain on survivors — ALL families use biome
    # sampling when biome_params.json has entries for their biomes.
    fam = profile["family"] or "overworld"
    dim_type = profile.get("type", "")
    noise_family = _TYPE_NOISE_OVERRIDE.get(dim_type, FAMILY_NOISE.get(fam, "overworld"))
    noise_config = noise_configs.get(noise_family, noise_configs.get("overworld"))
    config_biomes = profile.get("create_args", {}).get("biome")
    biome_filter = set(config_biomes.split(",")) if config_biomes else None

    results = []
    accepted = 0
    rejected = 0
    for _t1_score, seed, struct_dists in survivors:
        sampler = BiomeSampler(seed, biome_params_path,
                               noise_config=noise_config,
                               biome_filter=biome_filter,
                               family=noise_family)
        rows, ok = tier2_measure(seed, profile, sampler)

        # Merge structure distances into rows
        for sname, sid, _band, _kind in profile["battery"]:
            dist = struct_dists.get(sname, -1)
            rows.append((f"structure_{sname}_dist", dist))

        results.append((seed, rows, ok))
        if ok:
            accepted += 1
        else:
            rejected += 1

    tier2_ms = (time.time() - t0) * 1000 - tier1_ms
    total_ms = (time.time() - t0) * 1000

    return (dim_name, results, accepted, rejected,
            pool_size, len(survivors), tier1_ms, tier2_ms, total_ms)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", required=True,
                    help="config/custom-dimensions/ directory")
    ap.add_argument("--seedtest", required=True,
                    help=".seedtest/ directory for output")
    ap.add_argument("--dims",
                    help="comma-separated subset of dimension names")
    ap.add_argument("--count", type=int, default=100,
                    help="accepted candidates to keep per dimension")
    ap.add_argument("--tier1-pool", type=int, default=5000,
                    help="seeds to screen in tier 1 per dimension")
    ap.add_argument("--workers", type=int, default=0,
                    help="parallel workers (0 = CPU count)")
    ap.add_argument("--output-csv",
                    help="output CSV path (default: <seedtest>/fast-roller.csv)")
    args = ap.parse_args()

    config = load_config(args.config)
    difficulty = load_difficulty(args.config)
    noise_configs = load_noise_configs()

    dims = [d for d in config["dimensions"] if rollable(d)]
    worlds = config.get("worlds", [])

    if args.dims:
        wanted = {d.strip() for d in args.dims.split(",")}
        dims = [d for d in dims if d["name"] in wanted]
        worlds = [w for w in worlds if w["name"] in wanted]

    all_targets = []
    for w in worlds:
        all_targets.append((w["name"], build_profile(w, config, difficulty)))
    for d in dims:
        all_targets.append((d["name"], build_profile(d, config, difficulty)))

    if not all_targets:
        print("No rollable targets found")
        return 1

    # Load seen seeds (seed_worker.py uses a hyphen in other contexts but
    # the module itself is importable via sys.path)
    try:
        from seed_worker import load_seen_seeds
    except ImportError:
        spec = importlib.util.spec_from_file_location(
            "seed_worker", str(SCRIPT_DIR / "seed_worker.py"))
        sw = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(sw)
        load_seen_seeds = sw.load_seen_seeds
    seen = load_seen_seeds(args.seedtest, args.config)

    struct_sets_path = str(Path(args.seedtest) / ".structure_sets")
    if not Path(struct_sets_path).exists():
        print(f"ERROR: structure sets not extracted at {struct_sets_path}")
        print("Run ./dev seed-roll once to extract them.")
        return 1

    biome_params_path = str(BIOME_PARAMS)
    if not Path(biome_params_path).exists():
        print(f"ERROR: biome params not found at {biome_params_path}")
        return 1

    tasks = []
    for dim_name, profile in all_targets:
        tasks.append((dim_name, profile, args.tier1_pool, args.count,
                      struct_sets_path, biome_params_path, noise_configs, set(seen)))

    num_workers = args.workers or min(multiprocessing.cpu_count(), len(tasks))
    csv_path = args.output_csv or str(Path(args.seedtest) / "fast-roller.csv")

    total_seeds = len(all_targets) * args.tier1_pool
    print(f"Fast roller: {len(all_targets)} targets")
    print(f"  Tier 1: {args.tier1_pool} seeds/target (structure screening)")
    print(f"  Tier 2: top {args.count}/target (biome + terrain)")
    print(f"  Workers: {num_workers}, output: {csv_path}")
    t0 = time.time()

    if num_workers > 1 and len(tasks) > 1:
        with multiprocessing.Pool(num_workers) as pool:
            all_results = pool.map(_process_dimension, tasks)
    else:
        all_results = [_process_dimension(t) for t in tasks]

    # Write CSV
    total_accepted = 0
    total_rejected = 0
    csv_new = not Path(csv_path).exists()
    with open(csv_path, "a") as fh:
        if csv_new:
            fh.write("target,seed,metric,value\n")
        for dim_name, results, acc, rej, *_ in all_results:
            total_accepted += acc
            total_rejected += rej
            for seed, rows, _ok in results:
                for metric, value in rows:
                    fh.write(f"{dim_name},{seed},{metric},{value}\n")

    elapsed = time.time() - t0

    # Fold into candidate store
    print("\nFolding into candidate store...")
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "score_dimensions",
        str(SCRIPT_DIR / "score-dimensions.py"))
    sd = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sd)

    fargs = argparse.Namespace(
        config=args.config, seedtest=args.seedtest, csv=csv_path)

    profiles = {name: prof for name, prof in all_targets}
    data = sd.gather_measurements(fargs)
    results_scored, rejected_counts = sd.score_all(profiles, data)
    sd.persist_candidates(fargs, config, profiles, results_scored, data)

    # Summary
    print(f"\n{'dimension':30} {'pool':>5} {'t1→':>4} {'acc':>4} "
          f"{'t1ms':>6} {'t2ms':>6} {'total':>7} {'best':>6}  spawn")
    print("-" * 105)
    for dim_name, results, acc, rej, pool, surv, t1ms, t2ms, tms in all_results:
        cands = results_scored.get(dim_name, [])
        best = cands[0] if cands else None
        bscore = f"{best['score']:.1f}" if best else "—"
        bspawn = best['spawn_biome'] if best else ""
        print(f"{dim_name:30} {pool:>5} {surv:>4} {acc:>4} "
              f"{t1ms:>5.0f}ms {t2ms:>5.0f}ms {tms:>6.0f}ms {bscore:>6}  {bspawn}")

    t1_total = sum(r[6] for r in all_results)
    t2_total = sum(r[7] for r in all_results)
    print(f"\nTotal: {elapsed:.1f}s wall, {t1_total/1000:.1f}s tier-1, {t2_total/1000:.1f}s tier-2")
    print(f"Accepted: {total_accepted}, Rejected: {total_rejected}")
    print(f"Tier-1 rate: {total_seeds / (t1_total/1000):.0f} seeds/sec (structure-only)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
