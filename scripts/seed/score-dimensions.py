#!/usr/bin/env python3
"""score-dimensions.py — plan, score, and finalise parallel dimension seed rolls.

Subcommands (all take --config and --seedtest <dir>). --config is the
config directory (config/custom-dimensions/); winners are written into the
individual dimensions/{slug}.json files.

  manifest  Split dimensions across workers and generate, per worker:
              .seedtest/work-<w>.txt      dim|candidateName|seed lines
              .seedtest/mvconfig-<w>.json roll multiverse config carrying
                                          every candidate as a full entry
                                          (the seed/density/peaceful mixins
                                          resolve by config lookup)
            Already-measured (dim,seed) pairs in the merged CSV count toward
            --candidates, so re-runs only roll the remainder.
            Options: --workers N --candidates N --dims a,b,c
    render-manifest
                        Write finite work-r<w>.txt files for the winners-render pass:
                        the top --top measured candidates per target.
  score     Score every measured candidate; prints a ranked table and writes
            .seedtest/scores.json.
  finalise  score + pick winners + write them into the config (with .bak),
            generate .seedtest/index.html, print the summary table.
            Options: --write-config --viewer --open-viewer
  rescore   Recompute all scores from banked measurements against the
            CURRENT configs — no Docker, no re-rolling (directory mode).
  status    Candidate-bank status: counts, winners, score freshness.

Measurement storage (v4 Phase 5, directory mode): canonical store is
{config}/candidates/{slug}.json (measurements + scores keyed by config
hash + winner + rejected/abandoned seeds). Workers spool to
.seedtest/worker-*.csv; every finalise/rescore folds the spools into the
store. A legacy .seedtest/measurements.csv is read as an import source.
Worker CSV metrics per candidate:
  spawn_biome            first matching probe biome id (or "unknown")
  biome_<id>_dist        locate biome distance (-1 = not found)
  structure_<name>_dist  locate structure distance (-1 = not found)
  height_rNcM / water_rNcM / errors — terrain grid + filtered error count

Scoring model lives in dimension_profiles.py; this file is the maths + IO.
"""
import argparse
import csv
import html
import json
import multiprocessing
import os
import shutil
import statistics
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import biome_sampler  # noqa: E402
import candidates  # noqa: E402
import census_scoring  # noqa: E402
from surface_rules import placeholder_colour  # noqa: E402
import noise_placement  # noqa: E402
import terrain_survey  # noqa: E402
from dimension_profiles import (  # noqa: E402
    build_profile, generation_fingerprint, load_config, load_difficulty,
    noise_fingerprint, rollable,
)

LOCATE_HORIZON = 1600  # locate's practical search radius (~100 chunks)

#: Candidates handed to a census worker in one go. Paired with sorting the
#: task list by dimension, this keeps a worker on one dimension long enough
#: for noise_placement.geometry's memo to pay for itself.
CENSUS_CHUNKSIZE = 64

#: Results banked between store writes. Also bounded by a 60s timer, so a
#: dimension whose candidates are slow still checkpoints.
CENSUS_FLUSH_EVERY = 2000


def default_workers():
    """Processes for the census and terrain backfills when none is asked for.

    Two thirds of the cores. Measured on the overworld census (18 logical
    cores, 6 performance + 12 efficiency), per candidate:

        1 worker   0.5213s   100% efficiency
        8 workers  0.1212s    54%
        12         0.1080s    40%

    Throughput flattens well before every-core-but-two: past twelve the curve
    buys a few percent for a third more CPU, so the extra workers cost the
    machine's usability and return nothing. Below about eight it is giving up
    real wall-clock on a job that runs for hours.

    Two is the floor, so a small machine still overlaps work.
    """
    return max(2, multiprocessing.cpu_count() * 2 // 3)


# ---------------------------------------------------------------------------
# Manifests + per-worker roll configs
# ---------------------------------------------------------------------------
def random_signed_seed():
    import struct
    return struct.unpack("<q", os.urandom(8))[0]


def candidate_entry(dim, cand_name, seed, namespace):
    """A full config entry for one candidate — the mixins (seed, structure
    density, peaceful spawning) resolve dimensions by config-name lookup, so
    every candidate must exist in the roll config the container boots with."""
    entry = {k: v for k, v in dim.items() if k not in ("name", "dimensionId", "seed")}
    entry["name"] = cand_name
    entry["dimensionId"] = f"{namespace}:{cand_name}"
    entry["seed"] = seed
    return entry


def write_worker_files(seedtest, config, jobs_by_worker, prefix=""):
    """jobs: (dim, cand_base, [attempt seeds]). Every attempt seed gets its
    own config entry (<base>aK) — the seed/density/peaceful mixins resolve
    dimensions by config NAME, so a seed with no entry would silently clone
    the main world."""
    ns = config.get("namespace", "adventure")
    for w, jobs in jobs_by_worker.items():
        (seedtest / f"work-{prefix}{w}.txt").write_text(
            "".join(f"{dim['name']}|{base}|{','.join(map(str, seeds))}\n"
                    for dim, base, seeds in jobs))
        roll = roll_boot_config(config)
        roll["idleUnloadMinutes"] = 9999
        roll["dimensions"] = [candidate_entry(d, f"{base}a{k}", s, ns)
                              for d, base, seeds in jobs for k, s in enumerate(seeds)]
        (seedtest / f"mvconfig-{prefix}{w}.json").write_text(json.dumps(roll, indent=2))


def roll_boot_config(config):
    """A measurement container's boot config: no dimensions/portals, and —
    critically — no worldSeed / worlds[].seed / spawn. The mod now drives
    the REAL worlds from those keys, and a roll boot must stay a plain
    SEED=1 vanilla boot: candidates carry their own runtime seeds, the
    container's own overworld is never measured."""
    roll = {k: v for k, v in config.items()
            if k not in ("dimensions", "portals", "worldSeed")}
    roll["dimensions"] = []
    roll["portals"] = []
    roll["worlds"] = [{k: v for k, v in w.items() if k not in ("seed", "spawn")}
                      for w in config.get("worlds", [])]
    return roll


def cmd_manifest(args, config, profiles, world_profiles=None):
    """Indefinite mode: workers cycle a dimension ROTATION forever (one
    accepted candidate per dim per cycle, unbounded attempts) — the manifest
    is just each worker's rotation. '@worlds' rolls the shared world seed as
    coupled overworld/nether/end clones inside the same container. Seeds are
    generated in-worker (runtime definitions in the mod made pre-written
    candidate config entries unnecessary). @worlds slots only appear when
    world profiles survive filtering (--dims without world names skips
    them) and --no-worlds is not set."""
    seedtest = Path(args.seedtest)
    seedtest.mkdir(parents=True, exist_ok=True)
    names = list(profiles)
    workers = max(1, args.workers)

    roll = roll_boot_config(config)
    roll["idleUnloadMinutes"] = 9999
    (seedtest / "mvconfig-roll.json").write_text(json.dumps(roll, indent=2))

    # The four real worlds roll as INDEPENDENT clone slots (@world:<name>),
    # placed FIRST in the rotations — the old trailing @worlds slot starved
    # behind 12 dimension slots and never measured a single world seed.
    world_items = [] if args.no_worlds else [f"@world:{n}" for n in (world_profiles or {})]
    for w in range(workers):
        rotation = world_items[w::workers] + names[w::workers]
        (seedtest / f"work-{w}.txt").write_text(
            "\n".join(rotation) + ("\n" if rotation else ""))
    print(f"manifest: {len(names)} dims + {len(world_items)} world slots "
          f"split across {workers} workers, indefinite rotation")


def cmd_world_manifest(args, config, world_profiles):
    """World seeds: ONE seed drives all configured worlds, and every
    candidate costs a full boot. Each worker gets an accepted-seed quota
    plus an oversupplied seed pool for overworld spawn-filter rejections."""
    seedtest = Path(args.seedtest)
    seedtest.mkdir(parents=True, exist_ok=True)
    if not world_profiles:
        for w in range(max(1, args.workers)):
            (seedtest / f"work-v{w}.txt").write_text("")
        print("world manifest: no worlds configured")
        return
    measured = gather_measurements(args)
    accepted = sum(1 for rows in measured.get("overworld", {}).values() if "errors" in rows)
    needed = max(0, args.candidates - accepted)
    workers = max(1, args.workers)
    seen = set(measured.get("overworld", {}))
    cfg = Path(args.config)
    if cfg.is_dir():
        seen |= candidates.seen_seeds(cfg)

    roll = roll_boot_config(config)
    for w in range(workers):
        quota = needed // workers + (1 if w < needed % workers else 0)
        seeds = []
        while len(seeds) < quota * args.spawn_attempts:
            s = random_signed_seed()
            if str(s) not in seen:
                seen.add(str(s))
                seeds.append(s)
        (seedtest / f"work-v{w}.txt").write_text(
            f"quota|{quota}\n" + "".join(f"{s}\n" for s in seeds))
        (seedtest / f"mvconfig-v{w}.json").write_text(json.dumps(roll, indent=2))
    print(f"world manifest: {needed} world seeds needed "
          f"(x{args.spawn_attempts} pool) across {workers} workers")


def cmd_render_manifest(args, config, profiles):
    seedtest = Path(args.seedtest)
    results, _rejected = score_all(profiles, gather_measurements(args))
    sources = {d["name"]: d for d in config["dimensions"]}
    sources.update({w["name"]: w for w in config.get("worlds", [])})
    workers = max(1, args.workers)
    renders = seedtest / "renders"

    jobs = []
    for name, cands in results.items():
        if name not in sources:
            continue
        for j, c in enumerate(cands[: args.top]):
            if (renders / name / f"{c['seed']}.png").exists():
                continue
            jobs.append((name, c["seed"]))
    for w in range(workers):
        (seedtest / f"work-r{w}.txt").write_text(
            "".join(f"{name}|{seed}\n" for name, seed in jobs[w::workers]))
    print(f"render manifest: {len(jobs)} candidates (top {args.top}/dim) across {workers} workers")


# ---------------------------------------------------------------------------
# Scoring maths
# ---------------------------------------------------------------------------
#: Comfort gradient inside a window band. A flat top returns 1.0 everywhere
#: inside and so stops ranking once most candidates are acceptable (see T21).
#: Subtracted from the edges rather than added at the centre, so an in-window
#: score never exceeds 1.0.
WINDOW_COMFORT = 0.1


def window_score(value, lo, hi):
    """Peak inside [lo,hi], linear falloff over one window-width outside.

    Inside the band the score runs from 1.0 at the centre to
    1.0 - WINDOW_COMFORT at either edge. Outside it decays to 0 over one
    window-width, exactly as before.
    """
    if value is None:
        return 0.0
    width = max(hi - lo, 1e-9)
    edge = 1.0 - WINDOW_COMFORT
    if lo <= value <= hi:
        centre = (lo + hi) / 2.0
        half = max((hi - lo) / 2.0, 1e-9)
        return 1.0 - WINDOW_COMFORT * min(1.0, abs(value - centre) / half)
    # Falloff starts from the edge value: decaying from 1.0 would let a value
    # just outside the band outscore one on the boundary.
    over = (lo - value) if value < lo else (value - hi)
    return max(0.0, edge * (1.0 - over / width))


def at_most(value, cap):
    """1.0 up to `cap`, then linear falloff to 0 at 1.0.

    A cap, not a target: no gradient below it, because "well under the limit"
    is not better than "under the limit".
    """
    if value is None:
        return 0.0
    if value <= cap:
        return 1.0
    return max(0.0, 1.0 - (value - cap) / max(1.0 - cap, 1e-9))


def want_score(dist, lo, hi, radius):
    """A structure that BELONGS, judged by its placement range in blocks.

    Scoring:
      - Inside [lo, hi]: 1.0 + up to 0.1 comfort bonus at centre
      - Too close (dist < lo): proportional to dist/lo (0 at spawn, 1.0 at lo)
      - Too far (dist > hi): linear falloff over one range-width past hi
      - Not found, range within horizon: 0.0
      - Not found, range beyond horizon: 0.8 (absence is compatible)
    """
    hi = min(hi, radius)
    if dist is None or dist < 0:
        if lo >= LOCATE_HORIZON:
            return 0.8
        return 0.0 if hi <= LOCATE_HORIZON else 0.6
    # Too close: scales from -0.5 at spawn to 1.0 at the range minimum.
    # Being found WAY too close actively penalises the total, not just zeros out.
    if dist < lo:
        t = dist / max(lo, 1)
        return t * 1.5 - 0.5
    # Inside the wanted range: 1.0 + comfort bonus
    if dist <= hi:
        centre = (lo + hi) / 2
        half_width = max((hi - lo) / 2, 1)
        comfort = 1.0 - abs(dist - centre) / half_width
        return 1.0 + 0.1 * comfort
    # Too far: linear falloff
    width = max(hi - lo, 1)
    return max(0.0, 1.0 - (dist - hi) / width)


def shun_score(dist, radius, min_distance=None):
    """A structure that has NO BUSINESS here (or not this close): presence
    closer than the threshold costs the point; absence (or beyond it)
    earns it. The threshold is minDistance when set, else the playable
    radius (legacy "must not exist inside the world" semantics)."""
    threshold = min_distance if min_distance else radius
    return 0.0 if (dist is not None and 0 <= dist < threshold) else 1.0


def terrain_metrics(rows):
    """(relief, grain, water, land_fraction) for one candidate.

    WATER comes from the full-radius terrain survey when one exists
    (ensure_terrain_surveys), because the 3x3 climate grid cannot see the ocean.
    At radius 8192 the grid's columns sit at -512, 0, +512 — a 1024-block box at
    spawn, 0.1% of the render's area, all of it in the middle. The overworld
    winner measured 0.111 from the 3x3 and 0.358 from the survey: that is exactly
    the reported bug, a winner captioned "almost no water" beside a render a
    third ocean.

    Water needs no recalibration to switch, because it is a FRACTION and its
    target windows are scale-free. It also moves the overworld INTO its window
    (six sampled candidates averaged 0.519 on the 3x3, outside the 0.00-0.45
    target, and 0.391 surveyed).

    RELIEF AND GRAIN NOW COME FROM THE SURVEY TOO, over its CAPPED window
    (terrain_survey.RELIEF_SPAN — a 9x9 lattice spanning min(radius, 2048)) and
    not over the full radius.

    Both are absolute block figures, so the window they are measured over is part
    of what they mean, and that is why the switch needed the cap first. Uncapped,
    relief scaled with the dimension rather than with the terrain: median 15
    blocks at radius 256 against 96 at radius 8192, a ~6x spread produced by the
    measurement. No single window in TERRAIN_TARGETS can describe both ends of
    that.

    Capped, every dimension of radius >= 2048 is measured over the same
    4096-block box. Measured residual size effect within a noise preset after
    capping: compressed 1.2x (essentially gone), wide 1.9x, vanilla-default 3.1x
    — and the last is a real property of vanilla continentalness, which needs
    thousands of blocks to cross a band, not an artefact of the window.

    The 3x3 rows stay as the fallback for any candidate not yet surveyed, so a
    partially-surveyed bank still scores. TERRAIN_TARGETS was re-fitted for the
    capped window in the same change — see its comment for why the windows come
    from the feel vocabulary rather than from the bank's percentiles.
    """
    heights, waters = [], []
    hmap = {}
    for metric, value in rows.items():
        if metric.startswith("height_r"):
            r, c = metric[8], metric[10]
            hmap[(int(r), int(c))] = float(value)
            heights.append(float(value))
        elif metric.startswith("water_r"):
            waters.append(float(value))
    relief = (max(heights) - min(heights)) if len(heights) >= 2 else 0.0
    grains = []
    for (r, c), h in hmap.items():
        for dr, dc in ((0, 1), (1, 0)):
            n = hmap.get((r + dr, c + dc))
            if n is not None:
                grains.append(abs(h - n))
    grain = sum(grains) / len(grains) if grains else 0.0
    water = sum(waters) / len(waters) if waters else 0.0
    land_fraction = len(heights) / 9.0
    survey = rows.get("_terrain")
    if survey and "water" in survey:
        water = float(survey["water"])
        # land_fraction only distinguishes void/island worlds from solid ones, so
        # the survey's wider look is strictly better there too and it is on the
        # same 0-1 scale.
        land_fraction = float(survey.get("land", land_fraction))
    # Guarded on the key that only the capped survey writes. An older cached
    # survey has no reliefSpan, and its relief was measured over the full
    # radius — a different figure on a different window, which the re-fitted
    # windows would score as if it were this one. Falling back to the 3x3 for
    # those is wrong by a known bounded amount; using them would be wrong by an
    # unknown one. In practice the fingerprint carries relief_span, so every
    # survey is recomputed once and this branch is transitional.
    if survey and survey.get("reliefSpan") is not None:
        if survey.get("relief") is not None:
            relief = float(survey["relief"])
        if survey.get("grain") is not None:
            grain = float(survey["grain"])
    return relief, grain, water, land_fraction


#: A configured biome holding less than this share of the play area is a
#: sliver, not a presence. 2% of a 512-radius pocket is ~33k blocks — a patch
#: you can walk across, which is the smallest thing worth calling "present".
VARIETY_PRESENCE_FLOOR = 0.02

#: The rarest configured biome earns full credit at this fraction of an even
#: share — 0.5 means "half of what it would hold in a perfectly even split".
#: With five biomes listed that is 10% of the play area.
VARIETY_BALANCE_TARGET = 0.5

#: How much of the play area one biome may hold before the dimension stops
#: being a mixture. Deliberately generous: an author is entitled to a headline
#: biome, and several shipped dimensions band one deliberately wider than the
#: rest. This exists to catch a world that has effectively become single-biome,
#: not to force an even split.
VARIETY_DOMINANCE_CAP = 0.75


def variety_shares(profile, rows):
    """{biome: share of the play area} for the dimension's CONFIGURED biomes.

    Empty when there is no survey, no biome list, or a survey predating
    shares — each falls back to the proximity model.
    """
    survey = rows.get("_terrain") or {}
    shares = survey.get("shares")
    if not isinstance(shares, dict):
        return {}
    configured = [b for b in (profile.get("variety_biomes") or []) if b]
    if not configured:
        return {}
    picked = {b: float(shares.get(b, 0.0)) for b in configured if b in shares}
    # A survey naming none of the configured biomes describes another config.
    return picked if picked else {}


#: The range `parts["structures"]` can occupy, used to bound a candidate's
#: total before its census exists (see ensure_censuses' top-N pruning).
#: The floor is negative because want_score punishes a structure found right
#: on top of spawn (-0.5 at distance 0) and the `dense` density bias has no
#: lower clamp; the ceiling is the 0.1 comfort bonus want_score adds inside a
#: wanted band, which blend() and the dense bias both carry through.
STRUCTURES_MIN = -0.5
STRUCTURES_MAX = 1.1


def score_candidate(profile, rows, structures_override=None):
    """rows: {metric: value} for one (dim, seed). Returns (total, parts).

    `structures_override` substitutes a fixed value for the structures
    component and skips computing it. Passing STRUCTURES_MIN/STRUCTURES_MAX
    yields a lower/upper bound on the total for a candidate whose census has
    not been computed — the rest of the score does not depend on it.
    """
    parts = {}

    # Spawn-filter rejects carry only their spawn_biome row.
    if rows.get("rejected") == "1":
        return 0.0, {"namesake": 0.0, "variety": 0.0, "terrain": 0.0, "structures": 0.0}

    # Namesake: spawn biome in the spawn filter. Widened-gate acceptances
    # (spawn_filter_dist banked by the worker) earn proximity credit —
    # capped below 1.0 so a true namesake spawn always outranks them.
    spawn = rows.get("spawn_biome", "unknown")
    if spawn in profile["namesake"]:
        parts["namesake"] = 1.0
    else:
        base = 0.55 if spawn != "unknown" else 0.0
        fdist = rows.get("spawn_filter_dist")
        if fdist is not None and float(fdist) >= 0:
            prox = max(0.0, 1.0 - float(fdist) / 1024.0)
            parts["namesake"] = max(base, 0.3 + 0.6 * prox)
        else:
            parts["namesake"] = base

    # Variety: biome diversity within the playable area.
    #
    # PROXIMITY: distance to the nearest instance of each listed biome,
    # quadratically decayed, plus a balance term for non-namesake biomes near
    # spawn. Blind to proportion — a 96% monoculture answers it as well as an
    # even split (see T21).
    #
    # AREA SHARE carries most of the component when a terrain survey exists.
    # Proximity remains the whole component for unsurveyed candidates, so a
    # partially-surveyed bank still ranks.
    found, total = 0.0, 0
    radius = profile["radius"]
    half_r = radius / 2
    namesake_set = set(profile["namesake"])
    non_namesake_close = 0
    non_namesake_total = 0
    for metric, value in rows.items():
        if metric.startswith("biome_") and metric.endswith("_dist"):
            total += 1
            d = float(value)
            biome_id = metric[6:-5]
            is_namesake = biome_id in namesake_set
            if not is_namesake:
                non_namesake_total += 1
            if d >= 0:
                if d <= radius:
                    # Quadratic decay approximates the area fraction a biome
                    # at distance d can occupy in a circle of radius r.
                    contrib = max(0.25, 1.0 - (d / radius) ** 2)
                    if d <= half_r and not is_namesake:
                        non_namesake_close += 1
                else:
                    contrib = 0.15
                found += contrib
    base_variety = (found / total) if total else 0.5
    if non_namesake_total > 1:
        balance = non_namesake_close / non_namesake_total
        proximity = 0.7 * base_variety + 0.3 * balance
    else:
        proximity = base_variety
    if total > 2 and found < 1.5:
        proximity *= 0.7

    shares = variety_shares(profile, rows)
    if shares:
        present = sum(1 for s in shares.values() if s >= VARIETY_PRESENCE_FLOOR)
        presence = present / len(shares)
        # The rarest configured biome's share — the continuous form of
        # presence, which is a threshold and so stops ranking once most
        # candidates clear it. Full credit at half an even share.
        ideal_floor = VARIETY_BALANCE_TARGET / max(len(shares), 1)
        balance = min(1.0, min(shares.values()) / ideal_floor) if ideal_floor else 1.0
        # A cap, not a demand for an even split: a dimension is entitled to a
        # headline biome. Bites only when one has eaten the world.
        dominance = at_most(max(shares.values()), VARIETY_DOMINANCE_CAP)
        parts["variety"] = (0.40 * presence + 0.25 * balance
                            + 0.10 * dominance + 0.25 * proximity)
    else:
        parts["variety"] = proximity

    # Terrain.
    relief, grain, water, land = terrain_metrics(rows)
    t = profile["terrain"]
    if profile["is_void"]:
        # A proper void has NO surface anywhere on the grid.
        parts["terrain"] = 1.0 if land == 0 else max(0.0, 1.0 - land * 2)
    elif profile["is_islands"]:
        # Floating islands: want real gaps AND real land.
        island = window_score(land, 0.25, 0.8)
        parts["terrain"] = 0.5 * island + 0.3 * window_score(relief, *t["relief"]) \
            + 0.2 * window_score(grain, *t["grain"])
    else:
        parts["terrain"] = (0.45 * window_score(relief, *t["relief"])
                            + 0.30 * window_score(grain, *t["grain"])
                            + 0.25 * window_score(water, *t["water"]))
        if land < 0.5 and profile["terrain"]["water"][1] < 0.5:
            parts["terrain"] *= 0.5  # unexpectedly voidy/ocean-swallowed

    # Structures. Two views, combined by census_scoring.blend():
    #
    #   census  — the layout the seed's noise field actually produces, group
    #             by group (spike F2). Present for every dimension noise
    #             placement owns; None for suppressed/void/base-world dims,
    #             which fall through to the grid battery exactly as before.
    #   battery — the author's wants and shuns. Still positional (and still
    #             exact) for forced placements and for the custom-placement
    #             sets that kept grid placement; answered from the owning
    #             group's exact sidecar positions for everything noise took
    #             over, because a grid distance for a noise-placed set is
    #             fiction.
    if structures_override is not None:
        parts["structures"] = structures_override
        w = profile["weights"]
        wsum = sum(w.values()) or 1
        total_score = sum(parts[k] * w[k] for k in parts) / wsum * 100.0
        errs = float(rows.get("errors", 0) or 0)
        total_score -= min(10.0, errs * 0.5)
        return (round(max(0.0, min(100.0, total_score)), 2),
                {k: round(v, 3) for k, v in parts.items()})

    census = rows.get("_census")
    # Shape is binned from the exact sidecar positions at score time — the
    # summary's stored hist is display-only and never scored.
    _spawn_origin = (int(float(rows.get("spawn_x") or 0)) >> 4,
                     int(float(rows.get("spawn_z") or 0)) >> 4)
    census_part = census_scoring.distribution_component(
        census, positions_by_group=rows.get("_census_positions") or {},
        origin=_spawn_origin)
    census_groups = (census or {}).get("groups") or {}
    census_radius_chunks = (census or {}).get("radiusChunks") or 0
    lookup = profile.get("_battery_groups")
    found_count = 0

    if profile["battery"]:
        ss, n = 0.0, 0
        clear_r = profile.get("clear_spawn_radius", 0)
        for name, sid, spec, kind in profile["battery"]:
            v = rows.get(f"structure_{name}_dist")
            d = float(v) if v is not None else None
            group = battery_group_for(sid, lookup, profile.get("_seedtest_path")) if (lookup and census_part is not None) else None
            forced = any(f.get("structure") == sid.lstrip("#")
                         for f in profile.get("forced_structures") or [])
            if group is not None and not forced:
                if sid.startswith("#"):
                    continue
                entry = census_groups.get(group)
                by_struct = (entry or {}).get("byStructure") or {}
                clean_sid = sid.lstrip("#")
                pool_ids = set(by_struct.keys())
                in_pool = clean_sid in pool_ids or not pool_ids
                census_positions = (rows.get("_census_positions") or {}).get(group) or []
                scx, scz = _spawn_chunks(rows)
                if kind == "shun":
                    s = census_scoring.census_shun_score(
                        clean_sid, spec, census_positions,
                        spawn_cx=scx, spawn_cz=scz, in_pool=in_pool)
                else:
                    s = census_scoring.census_want_score(
                        clean_sid, spec, census_positions,
                        spawn_cx=scx, spawn_cz=scz, in_pool=in_pool)
                ss += s
                n += 1
                if entry and entry.get("count"):
                    found_count += 1
                continue
            # Grid-placed battery entry. Exclude sets whose placement type
            # is not on the VERIFIED_PASSTHROUGH_TYPES allowlist — the Python
            # grid maths is only proven correct for verified types, so an
            # unverified type's distance is "not exactly measurable" (§6.3).
            unverified = profile.get("_unverified_passthrough_sets") or set()
            struct_to_set_map = (lookup or ({}, {}))[0]
            entry_set = struct_to_set_map.get(sid.lstrip("#"))
            if entry_set and entry_set in unverified:
                continue
            if kind == "shun":
                s = shun_score(d, profile["radius"], spec)
            else:
                s = want_score(d, spec[0], spec[1], profile["radius"])
            if clear_r > 0 and d is not None and d >= 0 and d < clear_r:
                s *= max(0.0, d / clear_r)
            ss += s
            n += 1
            if d is not None and d >= 0:
                found_count += 1
        battery_part = ss / n if n else None
    else:
        battery_part = None

    if battery_part is not None:
        # Density bias: how cluttered this world is versus its siblings.
        #
        # Enriched candidates carry a total INSTANCE count, which scales with
        # the dimension's area — a 512-radius pocket and an 8192 overworld are
        # not comparable in absolute terms. So the bias is taken against the
        # median of this dimension's own enriched candidates: 1.0 is typical,
        # and the adjustment is clamped to the same +/-0.12 band the old
        # type-count version could reach, so no dimension is upended by it.
        #
        # Without enrichment there is no median to compare against, and the
        # battery-found-count keeps its original absolute coefficients.
        enriched = rows.get("_enriched_structure_count")
        density = profile.get("density")
        median = profile.get("_clutter_median")
        if enriched is not None and median:
            rel = int(enriched) / median
            offset = max(-1.0, min(1.0, rel - 1.0))
            coeff = {"sparse": -0.12, "dense": 0.08}.get(density, -0.03)
            battery_part = max(0.0, min(1.1, battery_part + coeff * offset))
        else:
            struct_count = int(enriched) if enriched is not None else found_count
            if density == "sparse":
                battery_part = max(0.0, battery_part - struct_count * 0.012)
            elif density == "dense":
                battery_part = min(1.1, battery_part + struct_count * 0.008)
            else:
                battery_part = max(0.0, battery_part - struct_count * 0.003)

    combined = census_scoring.blend(census_part, battery_part)
    parts["structures"] = 0.0 if combined is None else combined

    w = profile["weights"]
    wsum = sum(w.values()) or 1
    total_score = sum(parts[k] * w[k] for k in parts) / wsum * 100.0

    # Errors are a straight penalty.
    errs = float(rows.get("errors", 0) or 0)
    total_score -= min(10.0, errs * 0.5)

    return round(max(0.0, min(100.0, total_score)), 2), {k: round(v, 3) for k, v in parts.items()}


#: Byte offset each spool has been folded into the bank up to, keyed by path.
#: `.seedtest/.absorbed.json`.
ABSORBED_FILE = ".absorbed.json"


def load_absorbed(seedtest):
    try:
        with open(Path(seedtest) / ABSORBED_FILE) as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def save_absorbed(seedtest, offsets):
    if not offsets:
        return
    path = Path(seedtest) / ABSORBED_FILE
    tmp = path.with_suffix(".tmp")
    with open(tmp, "w") as fh:
        json.dump(offsets, fh, indent=1, sort_keys=True)
    tmp.replace(path)


def _spool_head(path, limit):
    """First min(64, limit) bytes, hex — the content half of a spool's
    identity.

    An inode number alone cannot identify a spool: a deleted-and-recreated
    file can REUSE the inode (ext4 does; CI runners proved it), and a fresh
    spool that grew past the old offset would be seeked into and its
    measurements skipped. The signature covers only the absorbed prefix —
    the spool is append-only, so bytes below the recorded offset never
    change on the same file and any difference means a replacement.
    """
    with open(path, "rb") as fh:
        return fh.read(min(64, max(0, limit))).hex()


def _absorbed_offset(absorbed, path):
    """How far `path` has been folded in, or 0 if the record cannot apply."""
    entry = absorbed.get(str(path))
    if not isinstance(entry, dict):
        return 0
    offset = entry.get("offset") or 0
    try:
        st = Path(path).stat()
        head = _spool_head(path, offset)
    except OSError:
        return 0
    if entry.get("inode") != st.st_ino or offset > st.st_size:
        return 0
    if entry.get("head") is not None and entry.get("head") != head:
        return 0
    return offset


def iter_measurements(csv_path, wanted=None, start=0, reached=None):
    """Stream (target, seed, metric, value) from one long-format CSV.

    A generator rather than a dict because the spool is 27.5M rows / 1.5 GB
    and every fold re-reads it: materialising a second copy to merge from
    doubles the peak for no reason. `wanted` drops other targets before their
    rows cost anything — a --dims-scoped run has no use for the other eighty
    dimensions' measurements and should not pay to build them.

    `start` skips bytes already folded into the bank. The spool is append-only
    and the bank is the primary store, so everything below a recorded offset is
    already there and re-parsing it buys nothing. `reached` collects the offset
    this pass ended at, which is what persist_candidates records once the
    stores are safely written.
    """
    if not csv_path or not Path(csv_path).exists():
        return
    with open(csv_path, newline="") as fh:
        if start:
            try:
                fh.seek(start)
            except OSError:
                fh.seek(0)
        for row in csv.reader(fh):
            if len(row) != 4 or row[0] == "target":
                continue
            if wanted is not None and row[0] not in wanted:
                continue
            yield row
        # Only on a clean run to EOF. csv.reader buffers ahead, so tell() part
        # way through is past what the caller has actually seen; recording it
        # after an early exit would mark unread rows absorbed.
        if reached is not None:
            end = fh.tell()
            reached[str(csv_path)] = {"offset": end,
                                      "inode": Path(csv_path).stat().st_ino,
                                      "head": _spool_head(csv_path, end)}


def load_measurements(csv_path, wanted=None):
    """-> {dim: {seed: {metric: value}}} from one long-format CSV."""
    data = defaultdict(lambda: defaultdict(dict))
    for target, seed, metric, value in iter_measurements(csv_path, wanted):
        data[target][seed][metric] = value
    return data


def gather_measurements(args, wanted=None):
    """Canonical measurement view -> {dim: {seed: rows}}.

    Directory mode reads the candidate store first, then folds in any
    un-merged worker spools (worker-*.csv) and the legacy
    .seedtest/measurements.csv (one-time import path: persist_candidates
    writes everything back to the store, after which the CSVs are inert).
    Legacy monolith mode uses just the CSVs.

    `wanted` restricts the whole gather to a set of target names. Passed
    explicitly by the scoring commands rather than read from args.dims,
    because `world-manifest` reads "overworld" out of the result regardless
    of the scope it was invoked with — an implicit filter would silently
    empty it.
    """
    data = defaultdict(lambda: defaultdict(dict))
    cfg = Path(args.config) if getattr(args, "config", None) else None
    if cfg is not None and cfg.is_dir():
        cdir = candidates.candidates_dir(cfg)
        if cdir.is_dir():
            for f in sorted(cdir.glob("*.json")):
                slug = f.stem
                if wanted is not None and slug not in wanted:
                    continue
                store = candidates.load_store(f)
                for seed, cand in store["candidates"].items():
                    data[slug][seed].update(cand.get("measurements", {}))
                    sa = cand.get("structure_all")
                    if sa:
                        # INSTANCES, not structure types. The keys of
                        # structure_all are the battery set names, which are
                        # identical for every seed in a dimension — counting
                        # them made this a per-dimension constant, so the
                        # density bias below shifted every candidate by the
                        # same amount and never reordered anything.
                        data[slug][seed]["_enriched_structure_count"] = str(
                            sum(len(v) for v in sa.values()))
                for seed in store["rejected"]:
                    data[slug][seed].setdefault("rejected", "1")
    sources = [Path(args.csv)] if getattr(args, "csv", None) else []
    sources += sorted(Path(args.seedtest).glob("worker-*.csv"))
    absorbed = load_absorbed(args.seedtest)
    reached = {}
    for src in sources:
        start = _absorbed_offset(absorbed, src)
        for target, seed, metric, value in iter_measurements(
                src, wanted, start=start, reached=reached):
            data[target][seed][metric] = value
    # Only an unscoped gather may advance the marker. A --dims run skipped
    # every other target's rows without banking them, so recording how far it
    # read would strand them. `wanted` is not the test: the scoring commands
    # always pass it, set to every rollable target.
    if not getattr(args, "dims", None):
        _pending_absorbed(args).update(reached)
    return data


_ABSORBED_PENDING = {}


def _pending_absorbed(args):
    """Offsets this process has read but not yet committed to the marker."""
    return _ABSORBED_PENDING.setdefault(str(getattr(args, "seedtest", "")), {})


# ---------------------------------------------------------------------------
# Noise census (spike F2) — the structure layout a seed actually produces
# ---------------------------------------------------------------------------
_STRUCT_LOOKUP_CACHE = {}


def structure_group_lookup(seedtest, config_dir):
    """-> (structure_id -> set_id, set_id -> group), for noise ownership.

    A battery entry names a STRUCTURE (or a tag); noise placement owns whole
    SETS. The set list comes from the warmup extraction under
    `<seedtest>/.structure_sets` (present whenever anything has been rolled)
    and the set -> group map from `structure-groups.json`. Sets missing from
    the extraction are the ones whose placement is not a plain random_spread
    — YUNG's and friends — which is exactly the population noise never takes
    over, so "unknown" correctly means "still on the grid".
    """
    key = (str(seedtest), str(config_dir))
    cached = _STRUCT_LOOKUP_CACHE.get(key)
    if cached is not None:
        return cached
    from structure_placement import load_structure_sets
    set_to_group = {sid: meta.get("group")
                    for sid, meta in
                    noise_placement.load_structure_groups(config_dir).items()}
    struct_to_set = {}
    sets_dir = Path(seedtest) / ".structure_sets"
    if sets_dir.is_dir():
        extracted = load_structure_sets(str(sets_dir))
        # Noise ownership is an EXACT placement-type question, mirroring
        # NoisePoolBuilder.noiseManaged: the exact vanilla class plus the
        # allowlisted absorbed types (Moog's advanced_random_spread since the
        # Phase 3 conversion). Types outside the list (YUNG's, Supplementaries
        # — real cross-set exclusion zones) are extracted for grid measurement
        # but own no group; claiming one here scored 108 battery entries a
        # permanent, unsatisfiable 0.0%. Their set ids are also scrubbed from
        # set_to_group so a battery entry naming the set directly cannot
        # re-claim a group. This list, the Java allowlist and
        # structure-groups.json move together, in the same release (T20).
        from structure_placement import NOISE_MANAGED_PLACEMENT_TYPES
        custom_placement = {
            set_id for set_id, cfg in extracted.items()
            if cfg.get("placement_type") not in NOISE_MANAGED_PLACEMENT_TYPES}
        set_to_group = {sid: g for sid, g in set_to_group.items()
                        if sid not in custom_placement}
        for set_id, cfg in extracted.items():
            if set_id in custom_placement:
                continue
            known = set_id in set_to_group
            for s in cfg.get("structures") or []:
                # A set extracted from a NESTED datapack path
                # (…/data/structures/data/dungeons_arise/…) mints its
                # namespace from the first "data" segment and comes out as
                # `structures:major_structures`. Whichever copy the
                # filesystem yields first would otherwise win and the real
                # set id would never be seen — every Dungeons Arise want
                # silently fell back to grid scoring. A classified set id
                # always beats an unclassified one.
                if s["id"] not in struct_to_set or (
                        known and struct_to_set[s["id"]] not in set_to_group):
                    struct_to_set[s["id"]] = set_id
    result = (struct_to_set, set_to_group)
    _STRUCT_LOOKUP_CACHE[key] = result
    return result


def battery_group_for(sid, lookup, seedtest_path=None):
    """Which noise group owns a battery entry's structure, or None for
    "noise never touched it" (grid placement, or an unclassified set).

    Tags (#ns:tag) are resolved to their exact member list via
    structure_tags.resolve_tag. Returns None when the tag data is
    unavailable (the caller skips the entry, never falls back to
    substring).
    """
    struct_to_set, set_to_group = lookup
    clean = sid.lstrip("#")
    set_id = struct_to_set.get(clean)
    if set_id is None and sid.startswith("#"):
        from structure_tags import resolve_tag
        members = resolve_tag(seedtest_path, sid)
        if members is not None:
            for member in members:
                if member in struct_to_set:
                    set_id = struct_to_set[member]
                    break
    if set_id is None and clean in set_to_group:
        set_id = clean
    if set_id is None:
        return None
    return set_to_group.get(set_id)


def attach_battery_groups(profiles, seedtest, config_dir):
    """Stamp the structure -> group lookup and the pool weights onto every
    profile once.

    Both are per-run constants read from disk, so doing it here rather than in
    score_candidate saves a file read per candidate — and there are tens of
    thousands of candidates.

    The pools carry each structure's weight in its group — the pick algorithm
    (resolve_structure) uses these weights to assign exactly one structure per
    noise site, so byStructure counts and distances are exact facts.

    Also stamps the set of unverified pass-through set ids: sets whose
    placement type is not in VERIFIED_PASSTHROUGH_TYPES. Battery measurement
    uses vanilla grid maths for these, which is only correct for verified
    types; unverified types are excluded and banked -1 (not exactly
    measurable). The allowlist lives in structure_placement.py and the
    parity test (test_passthrough_parity.py) is the only way onto it.
    """
    if not Path(config_dir).is_dir():
        return
    lookup = structure_group_lookup(seedtest, config_dir)
    pools = census_scoring.load_structure_pools(seedtest)
    # Identify pass-through sets with unverified placement types
    from structure_placement import (load_structure_sets,
                                     VERIFIED_PASSTHROUGH_TYPES,
                                     NOISE_MANAGED_PLACEMENT_TYPES)
    unverified_sets = set()
    sets_dir = Path(seedtest) / ".structure_sets"
    if sets_dir.is_dir():
        extracted = load_structure_sets(str(sets_dir))
        for set_id, cfg in extracted.items():
            ptype = cfg.get("placement_type", "")
            if ptype not in NOISE_MANAGED_PLACEMENT_TYPES \
                    and ptype not in VERIFIED_PASSTHROUGH_TYPES:
                unverified_sets.add(set_id)
            # A set with frequency < 1.0 gates every position through the
            # frequency reducer, which the Python maths does not mirror —
            # the live census (shouldGenerate) proved the hand-rolled draw
            # wrong for adventure:exit_shrines. Such a set's distances are
            # not exactly measurable until the reducer is mirrored and
            # parity-proven, whatever its placement type.
            elif cfg.get("frequency", 1.0) < 1.0:
                unverified_sets.add(set_id)
    for profile in profiles.values():
        profile["_battery_groups"] = lookup
        profile["_structure_pools"] = pools
        profile["_seedtest_path"] = str(seedtest)
        profile["_unverified_passthrough_sets"] = unverified_sets


def _with_group_settings(summary, group_settings):
    """Re-attach the per-DIMENSION placement config to a banked summary.

    The stored summary is per candidate and carries counts, the radial
    histogram (display-only), and byStructure (exact per-structure counts
    and nearest distances); the radial curve a group was given is the same
    for every candidate, so it is resolved from the config here rather
    than repeated a few hundred times on disk.
    """
    groups = {}
    for group, entry in (summary.get("groups") or {}).items():
        settings = (group_settings or {}).get(group) or {}
        merged = dict(entry)
        merged["radial"] = settings.get("radial")
        profile = settings.get("profile")
        if profile is not None:
            merged["profile"] = profile.id
            merged["exclusion"] = settings.get("exclusion")
        groups[group] = merged
    return {"radiusChunks": summary.get("radiusChunks"), "groups": groups}


# The pool worker lives in noise_placement, NOT here — this file's name is
# not a legal module identifier, so a child process cannot import it to
# unpickle a function defined in it. See noise_placement.census_task.
_census_task = noise_placement.census_task


def _spawn_chunks(rows):
    """The candidate's chosen spawn as CHUNK coordinates; (0, 0) when unset."""
    try:
        sx = int(float(rows.get("spawn_x") or 0))
        sz = int(float(rows.get("spawn_z") or 0))
    except (TypeError, ValueError):
        return 0, 0
    return sx >> 4, sz >> 4


def _spawn_site_task(task):
    """Pool worker: one candidate's site selection. Primitives in, primitives
    out — the sampler is rebuilt in the worker process."""
    name, seed, spec, site_profile, params_path = task
    from fast_roller import select_spawn_site
    from biome_sampler import build_from_spec
    try:
        sampler = build_from_spec(int(seed), spec, params_path)
    except (ValueError, OSError, KeyError):
        return name, seed, None, -1, 0, 0
    biome, dist, x, z = select_spawn_site(sampler, site_profile)
    return name, seed, biome, dist, x, z


def ensure_spawn_sites(args, profiles, data, quiet=False):
    """Upgrade banked spawns to site-selected ones (spawn-site-selection).

    Roll-time selection lives in fast_roller.select_spawn_site; this is the
    post-hoc pass over already-banked candidates, versioned by
    the stack stamp so it runs once per candidate per stack.
    Deterministic (pure function of seed + config), so NOT a re-roll —
    `spawn` is volatile and absent from generation_payload(). Run BEFORE
    ensure_spawn_distances: a moved spawn re-anchors battery distances.

    Scoped to fully-measured candidates only (`"errors" in rows`, the same
    scoreable test ensure_censuses uses) — the bank also holds tens of
    thousands of tier-1 screens whose spawn nothing ever reads.
    """
    from fast_roller import spawn_site_stamp
    stamp = spawn_site_stamp()
    from biome_sampler import sampler_spec
    params_path = str(Path(args.seedtest) / "biome_params.json")
    if not Path(params_path).exists():
        return
    tasks = []
    rows_by_key = {}
    for name, profile in profiles.items():
        if not profile.get("namesake"):
            continue
        spec = None
        site_profile = None
        for seed, rows in data.get(name, {}).items():
            if rows.get("rejected") == "1" or "errors" not in rows:
                continue
            if str(rows.get("spawn_site_v") or "") == str(stamp):
                continue
            if spec is None:
                spec = sampler_spec(profile)
                site_profile = {k: profile.get(k) for k in (
                    "namesake", "player_border", "clear_spawn_radius",
                    "forced_structures", "family", "anchor_spawn")}
            tasks.append((name, seed, spec, site_profile, params_path))
            rows_by_key[(name, seed)] = rows
    if not tasks:
        return
    workers = max(1, getattr(args, "census_workers", 0) or default_workers())
    if not quiet:
        print(f"spawn sites: re-selecting {len(tasks)} candidate(s) on "
              f"{workers} worker(s) ({stamp})", flush=True)
    started = time.time()
    step = max(1, len(tasks) // 20)
    results = []
    if workers > 1 and len(tasks) > 1:
        with multiprocessing.Pool(workers) as pool:
            for done, result in enumerate(
                    pool.imap_unordered(_spawn_site_task, tasks, chunksize=4), 1):
                results.append(result)
                if not quiet and (done % step == 0 or done == len(tasks)):
                    rate = done / max(1e-6, time.time() - started)
                    eta = (len(tasks) - done) / max(rate, 1e-6)
                    print(f"  spawn sites: {done}/{len(tasks)} "
                          f"(~{eta / 60.0:.0f} min left)", flush=True)
    else:
        results = [_spawn_site_task(t) for t in tasks]
    upgraded = 0
    for name, seed, biome, dist, x, z in results:
        rows = rows_by_key[(name, seed)]
        if biome is not None and dist >= 0:
            rows["spawn_x"] = x
            rows["spawn_z"] = z
            rows["spawn_filter_dist"] = dist
            rows["spawn_biome"] = biome if dist <= 48 else rows.get(
                "spawn_biome", biome)
        rows["spawn_site_v"] = stamp
        upgraded += 1
    if upgraded and not quiet:
        print(f"spawn sites: {upgraded} candidate(s) re-selected "
              f"({stamp})", flush=True)


def ensure_spawn_distances(args, profiles, data, quiet=False):
    """Re-anchor banked battery distances at each candidate's chosen spawn.

    The screening tier measures from the world origin (the spawn is not
    known yet), but the player stands at the candidate's spawn, up to 768
    blocks away (spawn-site-selection §5) — so an un-anchored distance
    leaves clearSpawnRadius and every want/shun judging a point nobody
    occupies. Placement is pure maths, so this is a recompute at scoring
    time, not a re-roll: origin-spawn candidates are left byte-identical
    and the recompute is deterministic, hence idempotent.
    """
    battery_dims = [n for n, p in profiles.items() if p.get("battery")]
    if not battery_dims:
        return
    sets_dir = Path(args.seedtest) / ".structure_sets"
    if not sets_dir.is_dir():
        return
    from fast_roller import tier1_score
    from structure_placement import load_structure_sets
    struct_sets = load_structure_sets(str(sets_dir))
    struct_to_sets = {}
    for set_id, cfg in struct_sets.items():
        for s in cfg["structures"]:
            struct_to_sets.setdefault(s["id"], []).append(set_id)
    recomputed = 0
    for name in battery_dims:
        profile = profiles[name]
        for seed, rows in data.get(name, {}).items():
            if rows.get("rejected") == "1":
                continue
            try:
                sx = int(float(rows.get("spawn_x") or 0))
                sz = int(float(rows.get("spawn_z") or 0))
                seed_val = int(seed)
            except (TypeError, ValueError):
                continue
            if sx == 0 and sz == 0:
                continue
            _score, dists = tier1_score(seed_val, profile, struct_sets,
                                        struct_to_sets, origin_x=sx, origin_z=sz)
            for sname, _sid, _spec, _kind in profile["battery"]:
                rows[f"structure_{sname}_dist"] = dists.get(sname, -1)
            recomputed += 1
    if recomputed and not quiet:
        print(f"spawn-anchored distances: {recomputed} candidate(s) recomputed")


def ensure_censuses(args, config, profiles, data, quiet=False):
    """Attach a noise census summary to every scoreable candidate.

    Cached in the candidate store under `noiseCensus`, keyed by the
    dimension's NOISE fingerprint — the census is a pure function of the seed
    and the placement config, so it survives biome edits, seedRoll rewrites
    and rescoring, and only a real placement change invalidates it.

    Computing one is 30ms for a pocket dimension and ~5s for an 8192-border
    one, so a cold bank is a genuinely long job; it is parallelised and it
    reports progress rather than looking hung.
    """
    cfg = Path(args.config)
    if not cfg.is_dir():
        return  # legacy monolith mode has no per-dimension config to resolve
    type_defaults = noise_placement.load_type_defaults(cfg)
    if not type_defaults:
        return
    sources = {d["name"]: d for d in config.get("dimensions", [])}
    # Base worlds are here too: one that opts in to structure management (a
    # "type" in its config) has a noise fingerprint and a real layout to
    # score. One that has not opts out through noise_fingerprint returning
    # None, exactly like a suppressed dimension.
    sources.update({w["name"]: w for w in config.get("worlds", [])})
    cdir = candidates.candidates_dir(cfg)

    pools = census_scoring.load_structure_pools(args.seedtest)
    stores = {}
    settings = {}
    pool_hashes = {}
    tasks = []
    for name in profiles:
        src = sources.get(name)
        if src is None:
            continue
        fp = noise_fingerprint(src)
        if fp is None:
            continue
        radius_chunks = noise_placement.census_radius_chunks(src)
        store = candidates.load_store(cdir / f"{name}.json")
        stores[name] = (store, fp)
        settings[name] = noise_placement.resolve_groups(src, type_defaults)
        dim_pools = (pools or {}).get(name) or {}
        ph = noise_placement.pool_hash(dim_pools)
        pool_hashes[name] = ph
        for seed, rows in data.get(name, {}).items():
            if rows.get("rejected") == "1" or "errors" not in rows:
                continue
            bcx, bcz = _spawn_chunks(rows)
            cached = (store["candidates"].get(seed) or {}).get("noiseCensus")
            if cached \
                    and cached.get("schemaVersion") == noise_placement.NOISE_CENSUS_SCHEMA_VERSION \
                    and cached.get("fp") == fp \
                    and cached.get("poolHash") == ph \
                    and noise_placement.census_sidecar_exists(
                        args.seedtest, name, seed):
                rows["_census"] = _with_group_settings(cached, settings[name])
                rows["_census_positions"] = noise_placement.load_census_positions(
                    args.seedtest, name, seed)
                continue
            tasks.append((name, seed, src, type_defaults, radius_chunks,
                          dim_pools, bcx, bcz, str(args.seedtest), fp, ph))

    if not tasks:
        return
    # A cold bank is hours of CPU across every core. Without a progress line
    # it looks hung; `--census-workers N` is how you leave room for something
    # else on the machine.
    workers = getattr(args, "census_workers", 0) or default_workers()
    workers = max(1, workers)

    def absorb(result):
        name, seed, summary = result
        store, fp = stores[name]
        summary["fp"] = fp
        summary["schemaVersion"] = noise_placement.NOISE_CENSUS_SCHEMA_VERSION
        summary["poolHash"] = pool_hashes.get(name, "")
        cand = store["candidates"].setdefault(
            seed, {"measurements": {}, "scores": {}})
        cand["noiseCensus"] = summary
        data[name][seed]["_census"] = _with_group_settings(
            summary, settings[name])
        data[name][seed]["_census_positions"] = noise_placement.load_census_positions(
            args.seedtest, name, seed)

    def save(names):
        for name in sorted(names):
            candidates.save_store(cdir / f"{name}.json", stores[name][0])

    top_n = getattr(args, "census_top", 0) or 0
    if top_n > 0:
        _census_rounds(profiles, data, tasks, top_n, workers,
                       absorb, save, quiet)
        return
    _run_census(tasks, workers, absorb, save, quiet)


def _run_census(tasks, workers, absorb, save, quiet=False, label="noise census"):
    """One pool pass over `tasks`, checkpointing as it goes.

    Every census is a pure function of the seed and the placement config and
    is skipped on the next run once banked, so the job is resumable — but only
    as far as the last write. Flushing on an interval is what makes an
    interrupted run resume instead of restarting: this used to save nothing
    until all 648,971 tasks had finished, so a Ctrl+C at 24% discarded
    fourteen hours and left `candidates/` not merely stale but absent.
    """
    # Sorted by dimension so a worker walks a contiguous run of one
    # dimension's candidates and its geometry memo (noise_placement.geometry)
    # stays warm. With chunksize=1 the pool round-robins instead, and the memo
    # is rebuilt for nearly every candidate — the change that makes it pay.
    tasks = sorted(tasks, key=lambda t: t[0])
    total = len(tasks)
    if not total:
        return 0
    if not quiet:
        print(f"{label}: computing {total} candidate layout(s) on "
              f"{workers} worker(s) — cached thereafter, so this is a one-off "
              f"per candidate", flush=True)

    t0 = time.time()
    step = max(1, total // 20)
    dirty = set()
    done = 0
    last_flush = t0

    def checkpoint():
        save(dirty)
        dirty.clear()

    try:
        if workers > 1 and total > 1:
            with multiprocessing.Pool(workers) as pool:
                for result in pool.imap_unordered(_census_task, tasks,
                                                  chunksize=CENSUS_CHUNKSIZE):
                    absorb(result)
                    dirty.add(result[0])
                    done += 1
                    now = time.time()
                    if done % CENSUS_FLUSH_EVERY == 0 or now - last_flush >= 60:
                        checkpoint()
                        last_flush = now
                    if not quiet and (done % step == 0 or done == total):
                        elapsed = now - t0
                        rate = done / elapsed if elapsed > 0 else 0
                        remaining = (total - done) / rate if rate > 0 else 0
                        print(f"  {done}/{total} ({done * 100 // total}%) "
                              f"— {elapsed / 60:.1f} min elapsed, "
                              f"~{remaining / 60:.1f} min left", flush=True)
        else:
            for task in tasks:
                result = _census_task(task)
                absorb(result)
                dirty.add(result[0])
                done += 1
                now = time.time()
                if done % CENSUS_FLUSH_EVERY == 0 or now - last_flush >= 60:
                    checkpoint()
                    last_flush = now
    finally:
        # Reached on KeyboardInterrupt too — Ctrl+C is documented as
        # "finalises with whatever has been measured so far", and for the
        # census that promise was false.
        checkpoint()
    if not quiet:
        print(f"{label}: {done} computed in {time.time() - t0:.1f}s")
    return done


#: Candidates censused in the seeding round of the adaptive pass, per
#: dimension. Generous on purpose: the round's only job is to establish real
#: totals high enough to exclude the tail, and a seed round too small leaves
#: the floor at the Nth best LOWER bound, which is the weak static bound.
CENSUS_SEED_MULTIPLE = 4
CENSUS_SEED_FLOOR = 200


def score_bounds(profile, rows):
    """(lower, upper) on a candidate's total, without computing its census.

    The census feeds only the `structures` component, so pinning that
    component to each end of its range brackets the total. Everything else in
    the score is already measured.
    """
    lo, _ = score_candidate(profile, rows, STRUCTURES_MIN)
    hi, _ = score_candidate(profile, rows, STRUCTURES_MAX)
    return lo, hi


def _census_rounds(profiles, data, tasks, top_n, workers, absorb, save,
                   quiet=False):
    """Census only what can still reach a dimension's top N, adaptively.

    The static bound — drop anything whose ceiling is under the Nth best
    FLOOR — is exact but weak: STRUCTURES spans 1.6, so against a structures
    weight of 20 of 100 the band is 32 points wide and a well-tuned dimension
    prunes nothing. This raises the bar with real numbers instead.

      Round 1  censuses a generous slice by lower bound, which yields EXACT
               totals for those candidates.
      Round 2  drops every remaining candidate whose upper bound is under the
               Nth best exact total, and censuses the rest.

    Exact for top-N membership: the floor is a score N censused candidates
    actually achieve, so a candidate whose ceiling is below it cannot displace
    any of them. Skipped candidates keep no census and score with structures
    0 — below a cut they could not have reached.

    Converges in two effective rounds. Round 2 censuses everything above
    floor_1; the floor can then only rise, and every remaining candidate was
    already below floor_1, so nothing new qualifies and the loop ends.
    """
    by_dim = defaultdict(list)
    for task in tasks:
        by_dim[task[0]].append(task)

    batch, pending = [], {}
    for name, dim_tasks in by_dim.items():
        profile = profiles.get(name)
        rows_for = data.get(name, {})
        if profile is None or len(dim_tasks) <= top_n:
            batch.extend(dim_tasks)
            continue
        ranked = []
        for task in dim_tasks:
            rows = rows_for.get(task[1])
            if rows is None:
                ranked.append((0.0, 100.0, task))   # unknown: never pruned
                continue
            lo, hi = score_bounds(profile, rows)
            ranked.append((lo, hi, task))
        ranked.sort(key=lambda b: -b[0])
        seed_n = max(top_n * CENSUS_SEED_MULTIPLE, CENSUS_SEED_FLOOR)
        batch.extend(b[2] for b in ranked[:seed_n])
        rest = ranked[seed_n:]
        if rest:
            pending[name] = rest

    total_computed = 0
    dropped = 0
    round_no = 0
    while batch:
        round_no += 1
        label = ("noise census" if round_no == 1
                 else f"noise census (round {round_no})")
        total_computed += _run_census(batch, workers, absorb, save,
                                      quiet, label=label)
        batch = []
        for name, rest in list(pending.items()):
            profile = profiles[name]
            # The floor is the Nth best total among candidates that now carry
            # a real census — a score genuinely achieved, not a bound.
            totals = sorted(
                (score_candidate(profile, rows)[0]
                 for rows in data.get(name, {}).values()
                 if rows.get("_census") is not None),
                reverse=True)
            floor = totals[top_n - 1] if len(totals) >= top_n else None
            if floor is None:
                keep = rest            # not enough censused to bound anything
            else:
                keep = [b for b in rest if b[1] >= floor]
            dropped += len(rest) - len(keep)
            del pending[name]
            batch.extend(b[2] for b in keep)

    if dropped and not quiet:
        print(f"noise census: {dropped} candidate(s) skipped — their best "
              f"possible total is below the {top_n}th best already measured",
              flush=True)
    return total_computed


_terrain_task = terrain_survey.survey_task


def ensure_terrain_surveys(args, config, profiles, data, quiet=False):
    """Attach a full-radius terrain survey to every scoreable candidate.

    The 3x3 climate grid the roller measures sits at
    grid_pitch(radius) = max(64, min(512, radius/4)), so for an 8192-radius
    dimension its three columns are at -512, 0, +512 — a 1024-block box against a
    32768-block render, 0.1% of the area, all of it at spawn. That is why an
    overworld winner could report `water 0%` beside a render a third ocean, and
    it makes relief a statement about the hill next to spawn.

    This re-measures relief, grain and water on a 9x9 lattice spanning the whole
    playable radius, cached under `terrainSurvey`. A RESCORE, not a re-roll:
    nothing the roller measured changes, and an unsurveyed candidate keeps the
    3x3 numbers as a fallback.

    Every candidate, not the top N. It moves the SCORE, so partial coverage would
    have the ranking compare surveyed candidates against unsurveyed ones.

    Keyed on the generation fingerprint plus the radius and grid, which is
    conservative — see terrain_survey.fingerprint.
    """
    cfg = Path(args.config)
    if not cfg.is_dir():
        return  # legacy monolith mode has no per-dimension config to resolve
    sources = {d["name"]: d for d in config.get("dimensions", [])}
    sources.update({w["name"]: w for w in config.get("worlds", [])})
    cdir = candidates.candidates_dir(cfg)
    biome_params = str(Path(args.seedtest) / "biome_params.json")
    if not Path(biome_params).exists():
        return  # no sampler table; the 3x3 fallback still scores

    stores, tasks = {}, []
    for name, profile in profiles.items():
        src = sources.get(name)
        if src is None:
            continue
        radius = int(profile.get("radius") or 0)
        if radius <= 0:
            continue
        fam = profile.get("family") or "overworld"
        fp = terrain_survey.fingerprint(generation_fingerprint(src), radius)
        store = candidates.load_store(cdir / f"{name}.json")
        stores[name] = (store, fp)
        # Built exactly as the roller builds it — the water fraction feeds the
        # terrain score, so it must use the dimension's real biome source (T20).
        sampler = biome_sampler.sampler_spec(profile)
        spec = {
            "biome_params": biome_params,
            "sampler": sampler,
            "configured_biomes": list(sampler["biomes"]),
            "radius": radius,
            "is_void": bool(profile.get("is_void")),
            # Only the overworld router carries a meaningful continentalness;
            # fast_roller reads erosion for everything else, and the surveyed
            # height has to be on the same scale to stay comparable.
            "has_continentalness": fam == "overworld" and not profile.get("is_void"),
            # Effective sea level: water is surveyed height below this OR a
            # water biome — a dimension with no listed ocean can still be
            # wet where its terrain dips (Phase 8). Voids have no terrain
            # to dip; biome identity stays their only water signal.
            "sea_level": None if profile.get("is_void") else int(
                (profile.get("settings_overrides") or {}).get("seaLevel") or 63),
        }
        for seed, rows in data.get(name, {}).items():
            if rows.get("rejected") == "1" or "errors" not in rows:
                continue
            cached = (store["candidates"].get(seed) or {}).get("terrainSurvey")
            if cached and cached.get("fp") == fp:
                rows["_terrain"] = cached
                continue
            tasks.append((name, seed, spec))

    if not tasks:
        return
    workers = getattr(args, "census_workers", 0) or default_workers()
    workers = max(1, workers)
    tasks.sort(key=lambda t: t[0])
    if not quiet:
        print(f"terrain survey: measuring {len(tasks)} candidate(s) over the full "
              f"radius on {workers} worker(s) — 37ms each, cached thereafter",
              flush=True)

    t0 = time.time()
    total = len(tasks)
    step = max(1, total // 10)
    dirty = set()

    def absorb(result):
        name, seed, survey = result
        store, fp = stores[name]
        survey["fp"] = fp
        cand = store["candidates"].setdefault(
            seed, {"measurements": {}, "scores": {}})
        cand["terrainSurvey"] = survey
        data[name][seed]["_terrain"] = survey
        dirty.add(name)

    def flush():
        for name in sorted(dirty):
            candidates.save_store(cdir / f"{name}.json", stores[name][0])
        dirty.clear()

    # Same resumability contract as the census above: cached per candidate
    # and keyed by fingerprint, so what is written is never recomputed.
    done = 0
    last_flush = t0
    try:
        if workers > 1 and total > 1:
            with multiprocessing.Pool(workers) as pool:
                for result in pool.imap_unordered(_terrain_task, tasks,
                                                  chunksize=CENSUS_CHUNKSIZE):
                    absorb(result)
                    done += 1
                    now = time.time()
                    if done % CENSUS_FLUSH_EVERY == 0 or now - last_flush >= 60:
                        flush()
                        last_flush = now
                    if not quiet and (done % step == 0 or done == total):
                        print(f"  terrain: {done}/{total} "
                              f"({done * 100 // total}%)", flush=True)
        else:
            for task in tasks:
                absorb(_terrain_task(task))
                done += 1
                now = time.time()
                if done % CENSUS_FLUSH_EVERY == 0 or now - last_flush >= 60:
                    flush()
                    last_flush = now
    finally:
        flush()
    if not quiet:
        print(f"terrain survey: {done} measured in {time.time() - t0:.1f}s")


def load_abandoned(seedtest):
    """abandoned-worker-*.csv (target,seed,reason) -> {dim: {seed: reason}}."""
    out = defaultdict(dict)
    for f in sorted(Path(seedtest).glob("abandoned-worker-*.csv")):
        with open(f, newline="") as fh:
            for row in csv.reader(fh):
                if len(row) >= 3 and row[0] != "target":
                    out[row[0]][row[1]] = row[2]
    return out


def persist_candidates(args, config, profiles, results, data, winners=None):
    """Directory mode: fold everything into candidates/{slug}.json — raw
    measurements, rejects, abandoned seeds, scores keyed by the current
    config hash, and the winner (pinned flag preserved for human picks)."""
    cfg = Path(args.config)
    cdir = candidates.candidates_dir(cfg)
    sources = {d["name"]: d for d in config.get("dimensions", [])}
    sources.update({w["name"]: w for w in config.get("worlds", [])})
    abandoned = load_abandoned(args.seedtest)
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    for name in profiles:
        store = candidates.load_store(cdir / f"{name}.json")
        store["configHash"] = candidates.config_hash(sources.get(name))
        # New candidates are stamped with the generation fingerprint they
        # were measured under (drift vs the current config is warned at
        # status/finalise time); existing candidates keep their stamp.
        fp = generation_fingerprint(sources[name]) if name in sources else None
        for seed, rows in data.get(name, {}).items():
            # Underscore keys are derived views injected at gather/score time
            # (the census summary, the enriched structure count) — they have
            # their own homes in the store and must not bloat `measurements`.
            candidates.merge_rows(
                store, seed,
                {k: v for k, v in rows.items() if not k.startswith("_")},
                fingerprint=fp)
        # Legacy candidates (measured before stamping existed) get an ASSUMED
        # stamp at the current fingerprint, so the drift check is live for
        # the whole bank from this fold on — see candidates.ensure_fingerprints.
        assumed = candidates.ensure_fingerprints(store, fp)
        if assumed:
            print(f"  {name}: {assumed} pre-existing candidate(s) assumed measured "
                  f"under the current generation fingerprint — any later "
                  f"generation-affecting change now reads as DRIFTED")
        for seed, reason in abandoned.get(name, {}).items():
            store["abandoned"].setdefault(str(seed), reason)
        for c in results.get(name, []):
            candidates.record_score(store, c["seed"], store["configHash"],
                                    c["score"], c["parts"], now)
        if winners and name in winners:
            store["winner"] = winners[name]["seed"]
            store["winnerPinned"] = bool(winners[name].get("pinned"))
        candidates.save_store(cdir / f"{name}.json", store)

    # After every store is on disk, never before: the marker's promise is
    # "these bytes are in the bank", and a crash between the two would make it
    # a lie that silently drops measurements from every later fold.
    pending = _pending_absorbed(args)
    if pending:
        absorbed = load_absorbed(args.seedtest)
        absorbed.update(pending)
        save_absorbed(args.seedtest, absorbed)
        pending.clear()


def results_from_store(store, chash):
    """One dimension's ranked candidates, read back from its banked scores.

    No rescoring, no census or survey work — what the store already says.
    Fills the viewer in for dimensions outside a `--dims` scope, so a scoped
    roll does not pay for the whole bank to publish a complete page.
    """
    out = []
    for seed, cand in store["candidates"].items():
        score = (cand.get("scores") or {}).get(chash)
        if not score:
            continue
        rows = dict(cand.get("measurements") or {})
        entry = {
            "seed": seed,
            "score": score.get("total", 0.0),
            "parts": {k: v for k, v in score.items()
                      if k not in ("total", "timestamp")},
            "spawn_biome": rows.get("spawn_biome", "unknown"),
            "metrics": rows,
        }
        for key in ("structure_all", "biome_survey"):
            if key in cand:
                entry[key] = cand[key]
        out.append(entry)
    out.sort(key=lambda c: c["score"], reverse=True)
    return out


def widen_for_viewer(args, sources, page_profiles, results, winners, rejected):
    """Fill the viewer in for every target outside the `--dims` scope.

    The viewer is a whole-bank artefact: `--dims` scopes what gets rolled and
    what gets a winner written, never what the page contains. Out-of-scope
    targets are read straight from their stores, so the page agrees with the
    bank by construction and costs nothing.
    """
    if not Path(args.config).is_dir():
        return results, winners, rejected  # monolith mode has no stores
    cdir = candidates.candidates_dir(Path(args.config))
    results = dict(results)
    winners = dict(winners)
    rejected = dict(rejected)
    for name in page_profiles:
        if name in results:
            continue
        store = candidates.load_store(cdir / f"{name}.json")
        cands = results_from_store(store, candidates.config_hash(sources.get(name)))
        results[name] = cands
        rejected[name] = len(store["rejected"])
        if not cands:
            continue
        pinned = next((c for c in cands if c["seed"] == store["winner"]), None)
        pick = pinned or cands[0]
        winners[name] = dict(pick, pinned=bool(store["winnerPinned"] and pinned))
    return results, winners, rejected


def score_all(profiles, data):
    """-> (results {dim: [accepted candidates ranked]}, rejected {dim: n}).
    Spawn-filter rejects are banked (their seeds never re-roll) but they
    are not candidates."""
    results, rejected = {}, {}
    for name, profile in profiles.items():
        cands = []
        rej = 0
        # Clutter is judged against this dimension's own enriched candidates,
        # so the median has to be known before any of them is scored. Only
        # enriched candidates contribute — an unenriched majority would drag
        # the median toward zero and make every enriched world look cluttered.
        counts = [int(r["_enriched_structure_count"])
                  for r in data.get(name, {}).values()
                  if r.get("_enriched_structure_count") is not None]
        profile["_clutter_median"] = statistics.median(counts) if counts else None
        for seed, rows in data.get(name, {}).items():
            if rows.get("rejected") == "1" or "errors" not in rows:
                rej += 1
                continue
            total, parts = score_candidate(profile, rows)
            cands.append({"seed": seed, "score": total, "parts": parts,
                          "spawn_biome": rows.get("spawn_biome", "unknown"),
                          "metrics": rows})
        cands.sort(key=lambda c: c["score"], reverse=True)
        results[name] = cands
        rejected[name] = rej
    return results, rejected


def cmd_score(args, config, profiles):
    data = gather_measurements(args, wanted=set(profiles))
    attach_battery_groups(profiles, args.seedtest, args.config)
    ensure_spawn_sites(args, profiles, data)
    ensure_spawn_distances(args, profiles, data)
    ensure_censuses(args, config, profiles, data)
    ensure_terrain_surveys(args, config, profiles, data)
    results, rejected = score_all(profiles, data)
    out = Path(args.seedtest) / "scores.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    slim = {d: [{k: c[k] for k in ("seed", "score", "parts", "spawn_biome")}
                for c in cands] for d, cands in results.items()}
    out.write_text(json.dumps(slim, indent=2))
    if Path(args.config).is_dir():
        persist_candidates(args, config, profiles, results, data)
    print_summary(results, profiles, rejected)
    print(f"\nscores written to {out}")


def cmd_rescore(args, config, profiles):
    """Recompute every candidate's score against the CURRENT configs —
    no Docker, no RCON, no re-rolling. Measurements are seed-specific and
    stay valid across config changes; scores are keyed by config hash, so
    a config edit only makes scores stale, and this refreshes them."""
    cfg = Path(args.config)
    if not cfg.is_dir():
        sys.exit("rescore needs the v4 config directory (config/custom-dimensions)")
    data = gather_measurements(args, wanted=set(profiles))
    attach_battery_groups(profiles, args.seedtest, args.config)
    ensure_spawn_sites(args, profiles, data)
    ensure_spawn_distances(args, profiles, data)
    ensure_censuses(args, config, profiles, data)
    ensure_terrain_surveys(args, config, profiles, data)
    results, rejected = score_all(profiles, data)
    persist_candidates(args, config, profiles, results, data)
    print_summary(results, profiles, rejected)
    total = sum(len(c) for c in results.values())
    print(f"\nrescored {total} candidate(s) across {len(profiles)} target(s) "
          f"into {candidates.candidates_dir(cfg)}")



def cmd_status(args, config, profiles):
    """Candidate-bank status per target: counts, winner, score freshness."""
    cfg = Path(args.config)
    if not cfg.is_dir():
        sys.exit("status needs the v4 config directory (config/custom-dimensions)")
    cdir = candidates.candidates_dir(cfg)
    sources = {d["name"]: d for d in config.get("dimensions", [])}
    sources.update({w["name"]: w for w in config.get("worlds", [])})
    print(f"{'dimension':30} {'cands':>5} {'rej':>4} {'aband':>5} "
          f"{'winner':>21} {'score':>6}  state")
    print("-" * 96)
    stale_count = 0
    drifted_count = 0
    for name in profiles:
        store = candidates.load_store(cdir / f"{name}.json")
        chash = candidates.config_hash(sources.get(name))
        winner = store["winner"]
        wscore = "-"
        state = "no candidates"
        if store["candidates"]:
            state = "fresh" if store["configHash"] == chash else "STALE (config changed — run seed-rescore)"
            if state != "fresh":
                stale_count += 1
        if winner and winner in store["candidates"]:
            score = store["candidates"][winner].get("scores", {}).get(chash)
            if score:
                wscore = f"{score['total']:.1f}"
            else:
                state = "STALE (winner unscored for current config — run seed-rescore)"
            # Generation-fingerprint drift is stronger than score staleness:
            # the winner's MEASUREMENTS describe a world the current config
            # no longer generates — rescoring can't fix that, only re-rolling.
            cur_fp = generation_fingerprint(sources.get(name) or {})
            cand_fp = candidates.effective_fingerprint(store["candidates"][winner])
            if cur_fp and cand_fp and cand_fp != cur_fp:
                state = "DRIFTED (generation config changed since measurement — re-roll)"
                drifted_count += 1
        pin = " 📌" if store["winnerPinned"] else ""
        print(f"{name:30} {len(store['candidates']):>5} {len(store['rejected']):>4} "
              f"{len(store['abandoned']):>5} {winner or '-':>21} {wscore:>6}  {state}{pin}")
    # Check for missing renders in the current top-10
    renders_dir = Path(args.seedtest) / "renders"
    missing_render_count = 0
    for name in profiles:
        store = candidates.load_store(cdir / f"{name}.json")
        chash = candidates.config_hash(sources.get(name))
        scored = []
        for seed, cand in store["candidates"].items():
            s = cand.get("scores", {}).get(chash)
            if s:
                scored.append((s["total"], seed))
        scored.sort(reverse=True)
        missing = sum(1 for _, seed in scored[:10]
                      if not (renders_dir / name / f"{seed}.png").exists())
        if missing:
            missing_render_count += 1

    if stale_count:
        print(f"\n{stale_count} target(s) have stale scores — ./dev seed-rescore refreshes "
              "them from banked measurements (no re-rolling)")
    if drifted_count:
        print(f"{drifted_count} target(s) have winners measured under a different "
              "generation fingerprint — their measurements no longer describe the "
              "configured world; re-roll those dimensions")
    if missing_render_count:
        print(f"{missing_render_count} target(s) have top-10 candidates missing renders — "
              "./dev seed-rescore or ./dev seed-viewer backfills them")


def print_summary(results, profiles, rejected=None):
    rejected = rejected or {}
    print(f"\n{'dimension':30} {'cands':>5} {'rej':>4} {'best seed':>21} {'score':>6}  spawn")
    print("-" * 97)
    for name in profiles:
        cands = results.get(name, [])
        rej = rejected.get(name, 0)
        if not cands:
            print(f"{name:30} {0:>5} {rej:>4} {'-':>21} {'-':>6}")
            continue
        best = cands[0]
        print(f"{name:30} {len(cands):>5} {rej:>4} {best['seed']:>21} {best['score']:>6.1f}  {best['spawn_biome']}")


# ---------------------------------------------------------------------------
# Finalise: write winners + viewer
# ---------------------------------------------------------------------------
def load_overrides(seedtest):
    """Human winner picks from the viewer server: {dim: seed-string}."""
    p = Path(seedtest) / "winner-overrides.json"
    if not p.exists():
        return {}
    try:
        return {k: str(v) for k, v in json.loads(p.read_text()).items() if v}
    except (json.JSONDecodeError, AttributeError):
        return {}


def session_backup(seedtest, make_backup):
    """One timestamped backup per roll session (live auto-write runs every
    45s — marker cleared by roll-all at start), not hundreds."""
    marker = Path(seedtest) / ".config-backed-up"
    if marker.exists():
        return None
    backup = make_backup(time.strftime("%Y%m%d-%H%M%S"))
    marker.touch()
    return backup


def apply_spawn(data, sx, sz):
    """Write a measured spawn onto a config dict — unless the measurement is
    the origin default (0, 0), which means "no site was chosen", not "spawn
    at the origin": tier2's spawn_x/spawn_z start at 0 and only move when a
    namesake match is found. Writing [0, 64, 0] made deploy.sh defer to it
    (it skips SPAWN_X/Y/Z for any 3-element spawn list), silently disabling
    hand-picked env spawns. A stale [0, 64, 0] placeholder from an earlier
    roll is removed; any other existing spawn is a real choice and is never
    touched by an origin measurement."""
    if sx is None or sz is None:
        return
    x, z = int(float(sx)), int(float(sz))
    if (x, z) == (0, 0):
        if data.get("spawn") == [0, 64, 0]:
            del data["spawn"]
        return
    data["spawn"] = [x, 64, z]


def write_winner(data, winner):
    """Apply one winner's seed + spawn to a config dict. -> changed?"""
    changed = False
    new_seed = int(winner["seed"])
    if data.get("seed") != new_seed:
        data["seed"] = new_seed
        changed = True
    apply_spawn(data, winner["metrics"].get("spawn_x"),
                winner["metrics"].get("spawn_z"))
    return changed


def write_winners_to_overlay(overlay_root, winners, seedtest,
                             platform_sources=None, platform_dir=None):
    """Consumer mode: winners land in the consumer repo's overlay. New
    files get the FULL platform default (seed + spawn updated); existing
    files are patched in place — 'overrides' files keep their shape,
    full-replace files get top-level seed/spawn, empty {} (disabled) are
    left alone.

    New files copy the RAW v4 platform file ({platform_dir}/dimensions/
    {name}.json) when available — the synthesised monolith entry in
    platform_sources drops portal/borders/structures, and an overlay file
    is a FULL REPLACE at boot, so cloning from the monolith shape silently
    stripped those blocks from the dimension."""
    dims_dir = Path(overlay_root) / "dimensions"
    backup = None
    if dims_dir.is_dir():
        backup = session_backup(seedtest, lambda ts: shutil.copytree(
            dims_dir, Path(seedtest) / f"overlay-dimensions.bak.{ts}"))
    dims_dir.mkdir(parents=True, exist_ok=True)
    changed = 0
    for name, w in winners.items():
        f = dims_dir / f"{name}.json"
        if f.exists():
            try:
                data = json.loads(f.read_text())
            except json.JSONDecodeError:
                data = {}
            if data == {}:
                continue  # consumer disabled this dimension — never resurrect
            if "overrides" in data:
                target = data["overrides"]
            else:
                target = data
        else:
            # New overlay files are "overrides" (seed/spawn only), never full
            # copies: a full replace freezes the platform config at write
            # time, silently masking every later platform-side change (type
            # conversions, spawnFilter tweaks).
            has_platform = (platform_dir is not None
                            and (Path(platform_dir) / "dimensions" / f"{name}.json").exists()) \
                or name in (platform_sources or {})
            if has_platform:
                data = {"overrides": {}}
                target = data["overrides"]
            else:
                # Consumer-added dim with no platform default: the overlay IS
                # the config — keep the full definition.
                data = {k: v for k, v in (platform_sources or {}).get(name, {}).items()}
                target = data
        if write_winner(target, w):
            changed += 1
        f.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    return changed, backup


def write_winners_to_dir(config_dir, winners, seedtest):
    """v4 directory mode: each winner lands in its own dimensions/{slug}.json
    (base worlds included — overworld.json carries the overworld seed)."""
    dims_dir = Path(config_dir) / "dimensions"
    backup = session_backup(seedtest, lambda ts: shutil.copytree(
        dims_dir, Path(seedtest) / f"dimensions.bak.{ts}"))
    changed = 0
    for name, w in winners.items():
        f = dims_dir / f"{name}.json"
        if not f.exists():
            continue
        data = json.loads(f.read_text())
        if write_winner(data, w):
            changed += 1
        f.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    return changed, backup


def write_winners_to_monolith(cfg_path, winners, seedtest):
    """Legacy mode: dimensions[] get seed+spawn, worlds[] likewise, and the
    overworld winner lands on the top-level worldSeed — the mod's
    ServerWorldSeedMixin drives ALL of them (config-driven multiverse;
    .env SEED only seeds level.dat as a legacy fallback)."""
    def make_backup(ts):
        dst = cfg_path.with_name(cfg_path.name + f".bak.{ts}")
        shutil.copy2(cfg_path, dst)
        return dst

    backup = session_backup(seedtest, make_backup)
    fresh = json.loads(cfg_path.read_text())
    changed = 0
    for dim in fresh["dimensions"]:
        w = winners.get(dim["name"])
        if w and write_winner(dim, w):
            changed += 1
    for world in fresh.get("worlds", []):
        w = winners.get(world["name"])
        if w and world["name"] != "overworld" and write_winner(world, w):
            changed += 1
    ow = winners.get("overworld")
    if ow is not None:
        fresh["worldSeed"] = int(ow["seed"])
        ow_entry = next((w for w in fresh.get("worlds", [])
                         if w["name"] == "overworld"), None)
        if ow_entry is not None:
            apply_spawn(ow_entry, ow["metrics"].get("spawn_x"),
                        ow["metrics"].get("spawn_z"))
    cfg_path.write_text(json.dumps(fresh, indent=2) + "\n")
    return changed, backup, ow


def cmd_finalise(args, config, profiles, world_profiles=None, page_profiles=None):
    # Scoped to the --dims set. The viewer still covers every target, but it
    # is filled from the stores by widen_for_viewer rather than from `data`.
    data = gather_measurements(args, wanted=set(profiles))
    attach_battery_groups(profiles, args.seedtest, args.config)
    ensure_spawn_sites(args, profiles, data)
    ensure_spawn_distances(args, profiles, data)
    ensure_censuses(args, config, profiles, data)
    ensure_terrain_surveys(args, config, profiles, data)
    results, rejected = score_all(profiles, data)
    world_profiles = world_profiles or {}
    dir_mode = Path(args.config).is_dir()
    # Every target — dimensions AND the four real worlds — has an
    # independent winner (worlds are rolled as fake_* clones; each real
    # world gets its own best seed rather than one coupled compromise).
    winners = {d: c[0] for d, c in results.items() if c}
    # Pinned winners in the candidate files (previous human picks) beat the
    # score ranking; a FRESH pick from the viewer (below) beats both.
    if dir_mode:
        cdir = candidates.candidates_dir(Path(args.config))
        for d in profiles:
            store = candidates.load_store(cdir / f"{d}.json")
            if store["winnerPinned"] and store["winner"]:
                cand = next((c for c in results.get(d, [])
                             if c["seed"] == store["winner"]), None)
                if cand is not None:
                    cand = dict(cand)
                    cand["pinned"] = True
                    winners[d] = cand
    # Human picks (viewer server) pin over the score ranking.
    overrides = load_overrides(args.seedtest)
    for d, seed in overrides.items():
        cand = next((c for c in results.get(d, []) if c["seed"] == seed), None)
        if cand is not None:
            cand = dict(cand)
            cand["pinned"] = True
            winners[d] = cand

    all_sources = {d["name"]: d for d in config.get("dimensions", [])}
    all_sources.update({w["name"]: w for w in config.get("worlds", [])})

    # Seed-group rolling: two members of a generation-fingerprint group
    # with the same seed are LITERAL WORLD CLONES — winners within a group
    # must be distinct seeds. Greedy best-fit: pins claim their seed first,
    # then members in best-score order walk down their own ranking past
    # taken seeds (fine at these group sizes, ≤6 members).
    fp_groups = {}
    for name in profiles:
        src = all_sources.get(name)
        fp = generation_fingerprint(src) if src else None
        if fp is not None:
            fp_groups.setdefault(fp, []).append(name)
    for names in fp_groups.values():
        if len(names) < 2:
            continue
        taken = {}
        pinned = [n for n in names if winners.get(n, {}).get("pinned")]
        ranked = sorted((n for n in names
                         if n in winners and not winners[n].get("pinned")),
                        key=lambda n: -winners[n]["score"])
        for n in pinned:
            seed = winners[n]["seed"]
            if seed in taken:
                print(f"WARNING: pinned winners for {taken[seed]} and {n} share seed "
                      f"{seed} — identical generation config makes them literal "
                      f"world clones; re-pin one of them")
            taken.setdefault(seed, n)
        for n in ranked:
            top_seed = winners[n]["seed"]
            if top_seed not in taken:
                taken[top_seed] = n
                continue
            alt = next((c for c in results.get(n, [])
                        if c["seed"] not in taken), None)
            if alt is None:
                print(f"WARNING: no distinct seed left for {n} in its generation "
                      f"group — keeping shared seed {top_seed} (world clone of "
                      f"{taken[top_seed]}); roll more candidates")
                continue
            print(f"group assignment: {n} takes {alt['seed']} "
                  f"(score {alt['score']:.1f}; top seed {top_seed} "
                  f"already won by {taken[top_seed]})")
            winners[n] = alt
            taken[alt["seed"]] = n

    if dir_mode:
        persist_candidates(args, config, profiles, results, data, winners)
        # Fingerprint drift: a winner measured under a different generation
        # fingerprint describes a world the current config no longer
        # generates. Warn, never delete (the mod's fingerprint-drift tone).
        cdir_fp = candidates.candidates_dir(Path(args.config))
        for name, w in winners.items():
            src = all_sources.get(name)
            cur_fp = generation_fingerprint(src) if src else None
            if cur_fp is None:
                continue
            store = candidates.load_store(cdir_fp / f"{name}.json")
            cand_fp = candidates.effective_fingerprint(
                store["candidates"].get(str(w["seed"]), {}))
            if not cand_fp:
                # An unstamped winner cannot be shown to describe this config,
                # and silence reads as "checked and fine".
                stamped = sum(1 for c in store["candidates"].values()
                              if candidates.effective_fingerprint(c) == cur_fp)
                print(f"WARNING: {name} winner {w['seed']} carries no generation "
                      f"fingerprint, so it cannot be shown to describe the "
                      f"current config — it may have been measured against an "
                      f"older one. {stamped} of {len(store['candidates'])} "
                      f"candidate(s) in this bank are stamped with {cur_fp}"
                      + ("; re-roll to replace the unstamped ones"
                         if stamped else "; re-roll this dimension"))
            elif cand_fp != cur_fp:
                print(f"WARNING: {name} winner {w['seed']} was measured under "
                      f"generation fingerprint {cand_fp}, but the config now "
                      f"fingerprints {cur_fp} — its measurements describe a world "
                      f"this config no longer generates; re-roll {name}")

    if args.write_config and winners:
        cfg_path = Path(args.config)
        if cfg_path.is_dir():
            if getattr(args, "winner_overlay", None):
                changed, backup = write_winners_to_overlay(
                    args.winner_overlay, winners, args.seedtest,
                    platform_sources=all_sources, platform_dir=cfg_path)
                print(f"overlay updated: {changed} seeds changed "
                      f"({Path(args.winner_overlay) / 'dimensions'} — \"overrides\" files)"
                      + (f"; backup: {backup}" if backup else ""))
            else:
                changed, backup = write_winners_to_dir(cfg_path, winners, args.seedtest)
                print(f"config updated: {changed} seeds changed ({cfg_path / 'dimensions'})"
                      + (f"; backup: {backup}" if backup else ""))
            ow = winners.get("overworld")
        else:
            changed, backup, ow = write_winners_to_monolith(cfg_path, winners, args.seedtest)
            print(f"config updated: {changed} seeds changed ({cfg_path})"
                  + (f"; backup: {backup.name}" if backup else ""))
        if ow is not None:
            print(f"overworld winner (score {ow['score']:.1f}): {ow['seed']} — "
                  "config-driven, applies at next boot")
            print("  NEW chunks generate on it; existing overworld chunks keep the old "
                  "terrain (wipe the world / ./ops reset-seed ritual to regenerate)")

    # Inject enriched data (structure_all, biome_survey) into scored results
    if dir_mode:
        cdir_enrich = candidates.candidates_dir(Path(args.config))
        for name in profiles:
            store = candidates.load_store(cdir_enrich / f"{name}.json")
            for c in results.get(name, []):
                cand_entry = store["candidates"].get(c["seed"], {})
                if "structure_all" in cand_entry:
                    c["structure_all"] = cand_entry["structure_all"]
                if "biome_survey" in cand_entry:
                    c["biome_survey"] = cand_entry["biome_survey"]

    if args.viewer:
        # The page covers every target, not just the --dims scope it was
        # finalised under — see widen_for_viewer.
        page = page_profiles or profiles
        page_results, page_winners, page_rejected = widen_for_viewer(
            args, all_sources, page, results, winners, rejected)
        install_web_assets(Path(args.seedtest))
        viewer = Path(args.seedtest) / "index.html"
        viewer.write_text(render_viewer(
            page_results, page, page_winners, page_rejected,
            seedtest=args.seedtest, dim_configs=all_sources))
        print(f"viewer: {viewer}")
        if args.open_viewer and sys.platform == "darwin":
            subprocess.run(["open", str(viewer)], check=False)

    print_summary(results, profiles, rejected)
    return 0


WEB_DIR = Path(__file__).resolve().parent / "web"
#: Everything served alongside index.html. Kept as a list rather than a glob
#: so the build cache (.cache/, a 100MB Tailwind binary) can never be copied
#: into a consumer's .seedtest by accident.
#: source name in scripts/seed/web/ -> name served beside index.html.
WEB_ASSETS = (("app.built.css", "app.css"),
              ("project.js", "project.js"), ("route.js", "route.js"),
              ("app.js", "app.js"),
              ("compare.js", "compare.js"),
              ("dartboard.js", "dartboard.js"),
              ("structicons.js", "structicons.js"),
              ("scatter.js", "scatter.js"))


def install_web_assets(seedtest):
    """Copy the viewer's stylesheet and script into <seedtest>/assets/.

    The page is opened two ways — over http://127.0.0.1:8765/ from
    viewer-server.py, and as a plain file:// open of .seedtest/index.html
    (roll-all.sh prints that path). Copying beside index.html is the only
    arrangement that works for both with one relative href, and it needs no
    extra route in the server. Copy is unconditional: a stale stylesheet
    beside a fresh page is a debugging trap nobody would suspect.
    """
    dest = Path(seedtest) / "assets"
    dest.mkdir(parents=True, exist_ok=True)
    for src_name, served_name in WEB_ASSETS:
        src = WEB_DIR / src_name
        if src.exists():
            shutil.copy2(src, dest / served_name)
    return dest


def range_label(profile, spec):
    lo, hi = spec
    return f"{int(lo)}–{int(min(hi, profile['radius']))} blocks"


def candidate_tooltip(c):
    """Raw measurements for the title-attribute tooltip."""
    lines = []
    for metric, value in sorted(c["metrics"].items()):
        if metric.startswith(("structure_", "biome_")):
            pretty = metric.replace("structure_", "").replace("biome_", "").replace("_dist", "")
            lines.append(f"{pretty}: {'not found' if str(value) == '-1' else value}")
    return " | ".join(lines)


_NETHER_BIOMES = {"minecraft:nether_wastes", "minecraft:soul_sand_valley",
                   "minecraft:crimson_forest", "minecraft:warped_forest",
                   "minecraft:basalt_deltas"}
_END_BIOMES = {"minecraft:the_end", "minecraft:end_highlands",
               "minecraft:end_midlands", "minecraft:end_barrens",
               "minecraft:small_end_islands"}


def _biome_groups(profile):
    """Derive biome group tags from a dimension's actual biome content."""
    groups = set()
    all_biomes = set(profile.get("namesake", []))
    all_biomes.update(profile.get("variety_biomes", []))
    all_biomes.update(profile.get("spawn_probes", []))
    for b in all_biomes:
        if b.startswith("paradise_lost:"):
            groups.add("paradise_lost")
        elif b.startswith("incendium:") or b in _NETHER_BIOMES:
            groups.add("nether")
        elif b.startswith("nullscape:") or b in _END_BIOMES:
            groups.add("end")
        elif b.startswith(("terralith:", "natures_spirit:", "minecraft:")):
            groups.add("overworld")
    if not groups:
        groups.add(profile.get("family") or "overworld")
    return sorted(groups)


# Shown on hover over any score, replacing the permanent legend bar: the
# scale is something you read once, and it was competing with the grid for
# the top of the page every time you loaded it.
SCORE_HELP = ("Score 0–100 · spawn (correct starting biome), variety (biome "
              "diversity), terrain (landscape shape), structures (right sets "
              "at the right distances), each weighted per dimension. "
              "good >70 · OK 50–70 · weak 30–50 · poor <30")


def _score_colour(score):
    if score > 70:
        return "#6ec96e"
    if score >= 50:
        return "#e6e6e6"
    if score >= 30:
        return "#e8a735"
    return "#e05252"



def render_viewer(results, profiles, winners, rejected=None,
                  seedtest=None, dim_configs=None):
    rejected = rejected or {}
    dim_configs = dim_configs or {}
    # Shortlist: persistent set from shortlist.json (managed by viewer-server)
    shortlist_set = set()
    if seedtest:
        sl_path = Path(seedtest) / "shortlist.json"
        if sl_path.exists():
            try:
                sl = json.loads(sl_path.read_text())
                for key in sl:
                    parts = key.split("/", 1)
                    if len(parts) == 2:
                        shortlist_set.add((parts[0], parts[1]))
            except (json.JSONDecodeError, OSError):
                pass
    template = (Path(__file__).resolve().parent / "viewer_template.html").read_text()

    total_dims = len(profiles)
    total_cands = sum(len(c) for c in results.values())
    all_groups = set()
    for p in profiles.values():
        all_groups.update(_biome_groups(p))
    biome_groups = sorted(all_groups)
    types = sorted({p["type"] for p in profiles.values()})
    moods = sorted({p["mood"] for p in profiles.values()})

    family_btns = "".join(
        "<button class='family-btn{}' data-family='{}'>{}</button>".format(
            " active" if f == "All" else "",
            html.escape(f, quote=True), html.escape(f))
        for f in ["All"] + biome_groups)
    type_opts = "<option value=''>All types</option>" + "".join(
        "<option>{}</option>".format(html.escape(t)) for t in types)
    mood_opts = "<option value=''>All moods</option>" + "".join(
        "<option>{}</option>".format(html.escape(m)) for m in moods)
    summary = ("<b>{}</b> dimensions &middot; <b>{}</b> seeds tested &middot; "
               "Generated {}").format(total_dims, total_cands,
                                      time.strftime("%Y-%m-%d %H:%M"))
    dims_html = []
    for name, profile in profiles.items():
        dims_html.append(_render_dim_section(
            name, profile, results.get(name, []),
            winners, rejected.get(name, 0),
            shortlist_set=shortlist_set,
            dim_config=dim_configs.get(name)))

    return (template
            .replace("{{FAMILY_BUTTONS}}", family_btns)
            .replace("{{TYPE_OPTIONS}}", type_opts)
            .replace("{{MOOD_OPTIONS}}", mood_opts)
            .replace("{{SUMMARY_STATS}}", summary)
            .replace("{{DIMENSIONS_HTML}}", "\n".join(dims_html)))


def _render_dim_section(name, profile, cands, winners, rej_count,
                        shortlist_set=None, dim_config=None):
    """Render one dimension as a card (compact) + expandable detail panel."""
    best_score = cands[0]["score"] if cands else 0
    n_cands = len(cands)
    groups = _biome_groups(profile)
    family = html.escape(" ".join(groups), quote=True)
    ptype = html.escape(profile["type"], quote=True)
    pmood = html.escape(profile["mood"], quote=True)
    # Flagged = anything below green (score < 70) or no candidates
    flagged = "1" if (n_cands == 0 or best_score < 70) else "0"
    score_col = _score_colour(best_score) if n_cands else "#e05252"
    esc_name = html.escape(name, quote=True)

    # Flag dot colour
    flag_dot = ""
    if n_cands == 0 or best_score < 30:
        flag_dot = "<div class='flag-dot red'></div>"
    elif best_score < 50:
        flag_dot = "<div class='flag-dot amber'></div>"

    # Winner/best candidate for the compact card face
    winner_seed = winners.get(name, {}).get("seed")
    best = next((c for c in cands if c["seed"] == winner_seed), cands[0] if cands else None)
    shortlist_set = shortlist_set or set()
    is_hidden = bool(dim_config and dim_config.get("hidden"))
    # Dim card is shortlisted if ANY seed for this dimension is in the shortlist
    any_shortlisted = any(d == name for d, _ in shortlist_set) if shortlist_set else False
    best_shortlisted = "1" if any_shortlisted else "0"
    img_html = ""
    spawn_html = ""
    if best:
        img = "renders/{}/{}.png".format(name, best["seed"])
        hires = "renders/{}/{}_hires.png".format(name, best["seed"])
        img_html = ("<img src='{}' data-hires='{}' loading='lazy' "
                    "alt='Map render — {} best seed' "
                    "onerror=\"this.onerror=null;var d=document.createElement('div');d.className='no-render '+this.className;d.textContent='render queued';this.replaceWith(d)\">").format(img, hires, esc_name)
        spawn_html = "<div class='dim-spawn'>spawn: <b>{}</b></div>".format(
            html.escape(best.get("spawn_biome", "")))

    pinned_dim = "1" if winners.get(name, {}).get("pinned") else "0"
    out = []
    # The card is a plain container. The expand affordance is a REAL button
    # covering the compact face only, and the detail panel is its sibling.
    # Previously role=button sat on the card itself, so once expanded that
    # single button contained the close button, five action buttons and all
    # ten candidate tiles — an interactive element containing other focusable
    # elements, which is invalid ARIA, and its computed name was a ~700-word
    # concatenation of everything inside it. Screen readers could not use the
    # compare-and-pick flow at all.
    panel_id = "detail-{}".format(esc_name)
    out.append(
        "<div class='dim-card' "
        "data-family='{}' data-type='{}' "
        "data-mood='{}' data-flagged='{}' data-name='{}' "
        "data-score='{:.1f}' data-cands='{}' data-shortlisted='{}' "
        "data-pinned='{}' data-radius='{}' data-dim-scale='{}'{}>".format(
            family, ptype, pmood, flagged, esc_name, best_score, n_cands,
            best_shortlisted, pinned_dim,
            int(profile["radius"]), profile.get("scale", 1.0),
            " data-hidden='1'" if is_hidden else ""))
    out.append(flag_dot)

    # Compact face (visible when not expanded)
    out.append("<div class='compact'>")
    out.append(img_html)
    out.append("<div class='dim-name'>{}</div>".format(html.escape(name)))
    out.append("<div class='dim-meta'>"
               "<span class='dim-score' title='{}' style='color:{}'>{:.1f}</span>"
               "<span class='badge'>{}</span>"
               "<span class='badge'>{}</span>"
               "<span>{} seeds</span>"
               "</div>".format(html.escape(SCORE_HELP, quote=True),
                                score_col, best_score, ptype, pmood, n_cands))
    blurb = profile.get("blurb", "")
    if blurb:
        out.append("<div class='dim-blurb'>{}</div>".format(
            html.escape(blurb[:80] + ("…" if len(blurb) > 80 else ""))))
    out.append(spawn_html)
    out.append("<button type='button' class='compact-trigger' aria-expanded='false' "
               "aria-controls='{}'><span class='sr-only'>Show candidates for "
               "{}</span></button>".format(panel_id, html.escape(name)))
    out.append("</div>")

    # Detail panel (visible when expanded) — a SIBLING of the trigger, never a
    # descendant of it.
    out.append("<div class='detail' id='{}'>".format(panel_id))
    out.append("<button type='button' class='close-btn' "
               "aria-label='Close details'>&times;</button>")

    # Detail header: winner image + info side by side
    out.append("<div class='detail-header'>")
    if best:
        out.append("<img class='winner-img' src='renders/{}/{}.png' loading='lazy' "
                   "alt='Map render — {} seed {}' "
                   "onerror=\"this.onerror=null;var d=document.createElement('div');d.className='no-render '+this.className;d.textContent='render queued';this.replaceWith(d)\">".format(
                       name, best["seed"], esc_name, best["seed"]))
    out.append("<div class='detail-info'>")
    out.append("<h2>{}</h2>".format(html.escape(name)))
    out.append("<div class='blurb'>{}</div>".format(html.escape(profile["blurb"])))

    # Badges
    badges = "<span class='badge'>{}</span>".format(profile["type"])
    badges += "<span class='badge'>{}</span>".format(profile["mood"])
    badges += "<span class='badge'>{}b play area</span>".format(int(profile["radius"]))
    if profile["density"]:
        badges += "<span class='badge'>{}</span>".format(profile["density"])
    if profile["peaceful"]:
        badges += "<span class='badge'>peaceful</span>"
    if rej_count:
        badges += "<span class='badge'>{} spawn-rejected</span>".format(rej_count)
    out.append("<div class='meta'>{}</div>".format(badges))

    # Criteria
    w = profile["weights"]
    wants = ", ".join("{} ({})".format(n, range_label(profile, spec))
                      for n, _sid, spec, kind in profile["battery"]
                      if kind == "want")
    shuns = ", ".join(n for n, _sid, _spec, kind in profile["battery"]
                      if kind == "shun")
    spawn_filter = ", ".join(profile["namesake"]) or "any"
    criteria = "<b>Target spawn biomes</b> {}<br>".format(html.escape(spawn_filter))
    criteria += "<b>Structures nearby</b> {}<br>".format(
        html.escape(wants) or "none")
    if shuns:
        criteria += "<b>Avoids</b> {}<br>".format(html.escape(shuns))
    criteria += ("<b>Score mix</b> spawn {}% · variety {}% · terrain {}% · structures {}%").format(
        w["namesake"], w["variety"], w["terrain"], w["structures"])
    out.append("<div class='criteria'>{}</div>".format(criteria))

    out.append(
        "<div class='dim-actions'>"
        "<button class='action-btn reroll' data-dim='{}'>Re-roll</button>"
        "<button class='action-btn edit' data-dim='{}'>Edit</button>"
        "<button class='action-btn configure' data-dim='{}'>Configure</button>"
        "<button class='action-btn hide' data-dim='{}'>Hide</button>"
        "<button class='action-btn remove' data-dim='{}'>Remove</button>"
        "</div>".format(esc_name, esc_name, esc_name, esc_name, esc_name))
    out.append("</div></div>")  # close detail-info + detail-header

    # All candidates
    if cands:
        out.append("<div class='all-cands'>")
        for idx, c in enumerate(cands[:10]):
            out.append(_render_candidate(idx, c, name, profile, winners, 10,
                                         shortlist_set, ref_cand=best,
                                         dim_config=dim_config))
        out.append("</div>")
        if n_cands > 10:
            out.append("<p class='cand-count meta'>Top 10 by score · "
                       "{} more banked below this dimension's criteria</p>".format(
                           n_cands - 10))
    else:
        out.append("<p class='meta'>No seeds tested yet. Re-roll to generate candidates.</p>")

    out.append("</div>")  # close detail
    out.append("</div>")  # close dim-card
    return "\n".join(out)



# ---------------------------------------------------------------------------
# Candidate detail rendering
#
# The guiding rule: never show a verdict without the criterion that produced
# it. A tick standing alone says nothing: "too close" needs to say what it
# was close to, and "relief 16" needs to say the target was 18-90. Scoring is
# good enough that almost everything passes, which makes a column of ticks
# pure noise — signal has to come from how far a value sits from its target,
# not from whether it cleared it.
# ---------------------------------------------------------------------------

def _band_bar(value, lo, hi, cap=None, marks=None):
    """A proportional bar with the target band shaded and `value` marked.

    cap sets the axis maximum (defaults to a little past the band or the
    value, whichever is larger); marks is [(pos, label, colour)] for extra
    reference lines such as the clear-spawn radius.
    """
    span = cap if cap else max(hi * 1.25, (value or 0) * 1.1, 1)
    span = max(span, 1e-9)
    pct = lambda v: max(0.0, min(100.0, (v / span) * 100.0))
    band_l, band_r = pct(lo), pct(hi)
    out = ["<span class='bar' aria-hidden='true'>"]
    out.append("<span class='bar-band' style='left:{:.1f}%;width:{:.1f}%'></span>".format(
        band_l, max(band_r - band_l, 0.6)))
    for mpos, mlabel, mcol in (marks or []):
        out.append("<span class='bar-mark' title='{}' style='left:{:.1f}%;background:{}'></span>".format(
            html.escape(str(mlabel), quote=True), pct(mpos), mcol))
    if value is not None and value >= 0:
        inside = lo <= value <= hi
        col = "#6ec96e" if inside else ("#e8a735" if value < lo else "#7aa7d8")
        out.append("<span class='bar-val' style='left:{:.1f}%;background:{}'></span>".format(
            pct(value), col))
    out.append("</span>")
    return "".join(out)


def _deviation(value, lo, hi, fmt="{:.0f}"):
    """(severity, human phrase) for a value against a target band.

    `fmt` must match the units the value is displayed in. Water is a
    fraction, so formatting its 0.28 shortfall with the default "{:.0f}"
    rendered "0 below" — a value a quarter of the way off target reading as
    exactly on it.
    """
    if value is None or value < 0:
        return 2, "not found"
    if lo <= value <= hi:
        return 0, "in range"
    if value < lo:
        return 1, fmt.format(lo - value) + " below target"
    return 1, fmt.format(value - hi) + " above target"


# What a terrain number MEANS, in the language of the world it describes.
# "relief 38, target 18-90, in range" tells you the check passed and nothing
# about whether you are looking at rolling hills or a cliff face.
def _terrain_words(key, value):
    if key == "relief":
        # Peak-to-trough height across the sampled grid, in blocks.
        for limit, word in ((12, "almost flat"), (30, "gently rolling"),
                            (60, "hilly"), (110, "mountainous")):
            if value < limit:
                return word
        return "extreme cliffs and peaks"
    if key == "grain":
        # Mean height change between neighbouring samples: how abruptly the
        # landscape changes rather than how tall it gets.
        for limit, word in ((3, "very smooth"), (7, "smooth"),
                            (12, "broken up"), (20, "jagged")):
            if value < limit:
                return word
        return "chaotic"
    if key == "water":
        for limit, word in ((0.05, "almost no water"), (0.2, "a few lakes"),
                            (0.45, "lakes and coast"), (0.7, "mostly sea")):
            if value < limit:
                return word
        return "open ocean"
    return ""


def _terrain_section(c, profile):
    relief, grain, water, land = terrain_metrics(c["metrics"])
    t = profile.get("terrain") or {}
    if not t or (relief == 0 and grain == 0):
        return ""
    rows = []
    for key, value, fmt, unit in (("relief", relief, "{:.0f}", " blocks"),
                                  ("grain", grain, "{:.1f}", ""),
                                  ("water", water, "{:.0%}", "")):
        band = t.get(key)
        if not band:
            continue
        lo, hi = band
        sev, phrase = _deviation(value, lo, hi, fmt)
        rows.append(
            "<div class='mrow sev{}'>"
            "<span class='mname'>{}<span class='ns'>{}</span></span>"
            "<span class='mval'>{}</span>"
            "{}"
            "<span class='mtarget'>wants {}–{}</span>"
            "<span class='mdev'>{}</span>"
            "</div>".format(sev, key,
                            html.escape(_terrain_words(key, value)),
                            fmt.format(value) + unit,
                            _band_bar(value, lo, hi), fmt.format(lo),
                            fmt.format(hi), phrase))
    if not rows:
        return ""
    note = ("<div class='meta'>relief is peak-to-trough height, grain is how "
            "abruptly it changes between samples, water is the share of the "
            "grid under sea level{}</div>".format(
                "" if land >= 1.0 else
                " · {:.0f}% of the grid had any surface at all".format(land * 100)))
    return ("<div class='section-header'>Terrain <span class='meta'>"
            "sampled on a 3×3 grid across the play area</span></div>"
            "<div class='mlist'>{}</div>{}".format("".join(rows), note))


def _map_coverages(profile):
    """(low-res, hi-res) blocks covered by this dimension's two renders.

    MIRRORS biome_renderer.batch_render: it renders at `size` pixels and
    `max(1, int(base_scale / dim_scale))` blocks per pixel, low pass
    1024px/base 8 and hi pass 2048px/base 16. Every overlay drawn over one
    of those images has to divide by the coverage of the image ACTUALLY on
    screen, and the lightbox shows the low-res one until the hi-res probe
    lands — which is most candidates, most of the time.

    The old single value here was `32768 / scale`, which happens to equal
    the hi-res coverage for scales 1/4/8/16 and is wrong for 12 (2731 vs
    2048), and was four times too small for every low-res render.
    """
    s = float(profile.get("scale", 1.0) or 1.0) or 1.0
    return (1024 * max(1, int(8 / s)), 2048 * max(1, int(16 / s)))


def _coverage_attrs(profile):
    """The data-coverage pair every .mlist carries for the map overlays."""
    low, hi = _map_coverages(profile)
    return "data-coverage='{:.0f}' data-coverage-low='{:.0f}'".format(hi, low)


def _hist_bar(hist, radius_blocks, lo=None, hi=None):
    """The group's radial histogram, with the wanted band shaded behind it.

    This is the whole census in one glyph: which rings the noise field
    actually populated, against the ring the dimension asked for.
    """
    if not hist:
        return ""
    peak = max(hist) or 1
    bins = len(hist)
    cells = []
    for i, count in enumerate(hist):
        bin_lo = i * radius_blocks / bins
        bin_hi = (i + 1) * radius_blocks / bins
        inband = (lo is not None and hi is not None
                  and min(bin_hi, hi) - max(bin_lo, lo) > 0)
        cells.append(
            "<span class='hcell{}' style='--h:{:.0f}%' "
            "title='{:.0f}–{:.0f} blocks from spawn: {} site(s)'></span>".format(
                " inband" if inband else "", count / peak * 100,
                bin_lo, bin_hi, count))
    return "<span class='hist'>{}</span>".format("".join(cells))


def _structure_section(c, profile):
    """Structures as the SCORER sees them, which is not as a grid distance.

    Noise placement owns a whole group behind one field: the mod builds one
    StructureSet per group holding every eligible structure, so the field
    decides where and vanilla's weighted entry list decides which. A grid
    distance for such a set is fiction — score_candidate already discards it
    and scores the group's layout instead, so showing it here was showing a
    number nothing depends on. Worse than meaningless: a dungeons group whose
    placements all sit beyond 4900 blocks was being reported as a dungeon 355
    blocks away.

    Grid-placed and forced sets keep their positional row, because for those
    the old model is still exactly true.
    """
    battery = profile.get("battery") or []
    forced = profile.get("forced_structures") or []
    if not battery and not forced:
        return ""
    census = c["metrics"].get("_census") or {}
    groups = census.get("groups") or {}
    radius_chunks = census.get("radiusChunks") or 0
    radius_blocks = radius_chunks * 16.0
    lookup = profile.get("_battery_groups")
    radius = float(profile.get("radius") or 0) or 1.0
    clear_r = float(profile.get("clear_spawn_radius") or 0)
    struct_all = c.get("structure_all") or {}
    forced_ids = {f.get("structure") for f in (profile.get("forced_structures") or [])}

    noise_rows, grid_rows, seen_groups = [], [], {}
    for sname, sid, spec, kind in battery:
        pretty = html.escape(sname.replace("_", " ").title())
        sattr = " data-struct='{}'".format(html.escape(sname, quote=True))
        group = (battery_group_for(sid, lookup, profile.get("_seedtest_path"))
                 if (lookup and groups) else None)
        if group is not None and sid.lstrip("#") not in forced_ids:
            entry = groups.get(group)
            seen_groups.setdefault(group, []).append(pretty)
            sattr += " data-group='{}'".format(html.escape(group, quote=True))
            if not entry or not entry.get("count"):
                noise_rows.append((2, "<div class='mrow sev2'{}>"
                    "<span class='mname'>{} <span class='kind'>{}</span></span>"
                    "<span class='mval'>—</span><span></span>"
                    "<span class='mtarget'>{}</span>"
                    "<span class='mdev'>group absent here</span></div>".format(
                        sattr, pretty, group,
                        "avoid" if kind == "shun" else "want")))
                continue
            count = entry["count"]
            by_struct = entry.get("byStructure") or {}
            clean_sid = sid.lstrip("#")

            if kind == "shun":
                threshold = float(spec) if isinstance(spec, (int, float)) else radius
                struct_entry = by_struct.get(clean_sid) or {}
                assigned_count = struct_entry.get("count", 0)
                nearest = struct_entry.get("nearest", -1)
                inside = assigned_count if (nearest >= 0 and nearest < threshold) else 0
                sev = 2 if inside > 0 else 0
                noise_rows.append((sev, "<div class='mrow sev{}' data-band='0,{}'{}>"
                    "<span class='mname'>{} <span class='kind'>avoid</span></span>"
                    "<span class='mval'>{}<span class='ns'>of {} sites</span></span>{}"
                    "<span class='mtarget'>wants none within {:.0f}</span>"
                    "<span class='mdev'>{}</span>"
                    "<span class='mspread'><b>{}</b> {} sites &middot; "
                    "nearest <b>{}</b> blocks</span></div>".format(
                        sev, int(threshold), sattr, pretty,
                        assigned_count, count,
                        _hist_bar(entry.get("hist"), radius_blocks, 0, threshold),
                        threshold,
                        "{} inside threshold".format(inside) if inside > 0
                        else "none present",
                        assigned_count, pretty,
                        "{:.0f}".format(nearest) if nearest >= 0 else "—")))
                continue

            # Tag wants: not exactly measurable yet
            if sid.startswith("#"):
                noise_rows.append((1, "<div class='mrow sev1'{}>"
                    "<span class='mname'>{} <span class='kind'>{}</span></span>"
                    "<span class='mval'>—</span><span></span>"
                    "<span class='mtarget'>tag want</span>"
                    "<span class='mdev'>not exactly measurable</span></div>".format(
                        sattr, pretty, group)))
                continue

            lo, hi = spec
            hi_eff = min(hi, radius)
            struct_entry = by_struct.get(clean_sid) or {}
            assigned_count = struct_entry.get("count", 0)
            nearest = struct_entry.get("nearest", -1)

            # Compute exact in-band count from sidecar positions.
            census_positions = (c["metrics"].get("_census_positions") or {}).get(group) or []
            scx = int(float(c["metrics"].get("spawn_x") or 0)) >> 4
            scz = int(float(c["metrics"].get("spawn_z") or 0)) >> 4
            in_band = 0
            for pcx, pcz, psid in census_positions:
                if psid != clean_sid:
                    continue
                dx = (pcx - scx) * 16
                dz = (pcz - scz) * 16
                dist = (float(dx) * dx + float(dz) * dz) ** 0.5
                if lo <= dist <= hi_eff:
                    in_band += 1

            if assigned_count <= 0 and not by_struct:
                sev = 2
                verdict = "cannot generate here on any seed"
            elif assigned_count <= 0:
                sev = 2
                verdict = "not in this pool"
            elif in_band <= 0:
                sev = 1
                verdict = "wrong ring"
            else:
                sev = 0
                verdict = "{} in the wanted band".format(in_band)

            noise_rows.append((sev, "<div class='mrow sev{}' data-band='{},{}'{}>"
                "<span class='mname'>{}</span>"
                "<span class='mval'>{}<span class='ns'>sites</span></span>{}"
                "<span class='mtarget'>wants {:.0f}–{:.0f} blocks</span>"
                "<span class='mdev'>{}</span>"
                "<span class='mspread'><b>{}</b> {} sites &middot; "
                "nearest <b>{}</b> blocks &middot; "
                "<b>{}</b> in the wanted band</span></div>".format(
                    sev, int(lo), int(hi_eff), sattr, pretty,
                    assigned_count, count,
                    _hist_bar(entry.get("hist"), radius_blocks, lo, hi_eff),
                    lo, hi_eff, verdict,
                    assigned_count, pretty,
                    "{:.0f}".format(nearest) if nearest >= 0 else "—",
                    in_band)))
            continue

        # Grid or forced: the positional model still holds.
        v = c["metrics"].get("structure_{}_dist".format(sname))
        d = float(v) if v is not None else -1.0
        hits = sorted(struct_all.get(sname) or [], key=lambda h: h[0])
        nearest = hits[0][0] if hits else (d if d >= 0 else None)
        if kind == "shun":
            threshold = float(spec) if isinstance(spec, (int, float)) else radius
            if nearest is None or nearest < 0:
                sev, value, verdict = 0, "absent", "ok"
            elif nearest < threshold:
                sev, value = 2, "{:.0f}".format(nearest)
                verdict = "{:.0f} inside the exclusion".format(threshold - nearest)
            else:
                sev, value, verdict = 0, "{:.0f}".format(nearest), "clear"
            grid_rows.append((sev, "<div class='mrow sev{} shun'{}>"
                "<span class='mname'>{} <span class='kind'>avoid</span></span>"
                "<span class='mval'>{}</span>"
                "<span class='mtarget'>none within {:.0f}</span>"
                "<span class='mdev'>{}</span></div>".format(
                    sev, sattr, pretty, value, threshold, verdict)))
            continue
        lo, hi = spec
        hi_eff = min(hi, radius)
        sev, phrase = _deviation(nearest, lo, hi_eff)
        if nearest is not None and 0 <= nearest < clear_r:
            sev, phrase = 2, "inside the {:.0f} clear-spawn radius".format(clear_r)
        marks = [(clear_r, "clear-spawn", "#e05252")] if clear_r else []
        spread = ""
        if len(hits) > 1:
            typical = (3.14159 * radius * radius / len(hits)) ** 0.5
            spread = ("<span class='mspread'>{} found · {:.0f}–{:.0f} out · "
                      "~{:.0f} apart</span>".format(
                          len(hits), hits[0][0], hits[-1][0], typical))
        # Exact positions only for grid-placed sets — the map overlay plots
        # these as points, and plotting a noise-placed set's grid position
        # would draw a structure where none exists.
        pos_attr = ""
        if hits:
            pos_attr = " data-pos='{}'".format(
                ";".join("{:.0f},{:.0f}".format(h[1], h[2]) for h in hits[:400]))
        grid_rows.append((sev, "<div class='mrow sev{}' data-band='{},{}'{}{}>"
            "<span class='mname'>{}</span>"
            "<span class='mval'>{}</span>{}"
            "<span class='mtarget'>want {:.0f}–{:.0f}</span>"
            "<span class='mdev'>{}</span>{}</div>".format(
                sev, int(lo), int(hi_eff), sattr, pos_attr, pretty,
                "{:.0f}".format(nearest) if nearest is not None and nearest >= 0 else "—",
                _band_bar(nearest, lo, hi_eff, cap=radius, marks=marks),
                lo, hi_eff, phrase, spread)))

    noise_rows.sort(key=lambda r: -r[0])
    grid_rows.sort(key=lambda r: -r[0])
    out = []
    cov_attrs = _coverage_attrs(profile)
    if noise_rows:
        out.append("<div class='sub-header'>Noise-placed <span class='meta'>"
                   "one field per group decides where; the group's pool decides "
                   "which. Counts are placements in the whole GROUP</span></div>")
        out.append("<div class='mlist' {}>{}</div>".format(
            cov_attrs, "".join(r[1] for r in noise_rows)))
    if grid_rows:
        # The grid list is also where a row lands when battery_group_for()
        # returns nothing — no census, no groups, no group for this set. An
        # unconditional "an exact position is real" caption would be a claim
        # about placement that is simply false for a fallback row. Say which
        # case this is.
        gridded = bool(groups)
        out.append("<div class='sub-header'>Grid-placed <span class='meta'>{}"
                   "</span></div>".format(
                       "still on vanilla spacing/separation, so an exact "
                       "position is real" if gridded else
                       "no noise census for this dimension, so these fall "
                       "back to a positional check"))
        out.append("<div class='mlist' {}>{}</div>".format(
            cov_attrs, "".join(r[1] for r in grid_rows)))
    if forced:
        # Hand-placed at fixed coordinates, identical for every seed — which
        # is exactly why they are not in the battery and score nothing. They
        # are still the only structures some dimensions have, and showing
        # nothing at all read as "this world is empty".
        #
        # structures.force x/z are BLOCK coordinates: the mod does
        # `new ChunkPos(f.x >> 4, f.z >> 4)` in
        # DimensionStructures.appendForcedPlacements, and its field doc says
        # so. Multiplying by 16 and labelling the pair "chunk" would report
        # every fixed placement at sixteen times its real distance.
        # structure_placement.forced_distance (the scorer's side) has it
        # right.
        frows = []
        for f in forced:
            fid = str(f.get("structure", ""))
            fx, fz = f.get("x"), f.get("z")
            try:
                bx, bz = float(fx), float(fz)
                dist = (bx * bx + bz * bz) ** 0.5
            except (TypeError, ValueError):
                bx = bz = dist = None
            ns, _, short = fid.rpartition(":")
            pretty = short.replace("_", " ").title() or fid
            # A fixed placement outside the player border is unreachable, and
            # the row is the only place that would ever say so. Display only:
            # forced placements score nothing either way.
            sev, verdict = 0, "placed by hand"
            if dist is not None and dist > radius:
                sev = 1
                verdict = "outside the {:.0f} border".format(radius)
            attrs = " data-struct='{}'".format(html.escape(fid, quote=True))
            if dist is not None:
                attrs += " data-band='0,{:.0f}' data-pos='{:.0f},{:.0f}'".format(
                    dist, bx, bz)
            frows.append(
                "<div class='mrow sev{} census-row'{}>"
                "<span class='mname' title='{}'>{}<span class='ns'>{}</span></span>"
                "<span class='mval'>{}<span class='ns'>blocks</span></span>"
                "<span class='mtarget'>block {}, {}</span>"
                "<span class='mdev'>{}</span></div>".format(
                    sev, attrs, html.escape(fid, quote=True),
                    html.escape(pretty), html.escape(ns),
                    "{:.0f}".format(dist) if dist is not None else "—",
                    html.escape(str(fx)), html.escape(str(fz)), verdict))
        out.append("<div class='sub-header'>Fixed placements <span class='meta'>"
                   "written into the config at these block coordinates, so they "
                   "are identical for every seed and score nothing</span></div>")
        out.append("<div class='mlist' {}>{}</div>".format(
            cov_attrs, "".join(frows)))

    if groups:
        rows = []
        for group, entry in sorted(groups.items(),
                                   key=lambda kv: -(kv[1].get("count") or 0)):
            members = ", ".join(seen_groups.get(group, [])) or "—"
            # data-group here as well as on the battery rows: this is the row
            # that exists for EVERY group, including the ones no want or shun
            # names, so it is what lets the marker layer draw a dimension's
            # whole layout rather than only the parts something asked about.
            rows.append("<div class='mrow sev0 census-row' data-band='0,{:.0f}' "
                        "data-group='{}'>"
                        "<span class='mname'>{}</span>"
                        "<span class='mval'>{}<span class='ns'>sites</span></span>{}"
                        "<span class='mtarget' style='grid-column:span 2'>"
                        "asked for here: {}</span>"
                        "</div>".format(
                            radius_blocks, html.escape(group, quote=True),
                            html.escape(group),
                            entry.get("count", 0),
                            _hist_bar(entry.get("hist"), radius_blocks),
                            html.escape(members)))
        out.append("<div class='sub-header'>Full census <span class='meta'>"
                   "every site the noise field produces, by group and radial "
                   "decile out to {:.0f} blocks. A site hosts ONE structure "
                   "drawn from its group's pool — these are not per-structure "
                   "counts</span></div>"
                   "<div class='mlist' {}>{}</div>".format(
                       radius_blocks, cov_attrs, "".join(rows)))
    if not out:
        return ""
    return ("<div class='section-header'>Structures</div>" + "".join(out))


#: Frame parts, in the order a builder stacks them. MIRRORS
#: DimensionConfig.FRAME_PARTS — "sides" is both left and right.
FRAME_PARTS = ("top", "sides", "bottom")


def _accept_form(value):
    """A frame accept form as a builder would read it.

    Handles every shape the config takes: a plain id, a `#tag`, a list of
    either, or {"colorGroup": "<dye>"}.
    """
    if isinstance(value, dict):
        group = value.get("colorGroup")
        return "any {} block".format(group) if group else "—"
    if isinstance(value, list):
        return ", ".join(_accept_form(v) for v in value) or "—"
    if not isinstance(value, str) or not value:
        return "—"
    if value.startswith("#"):
        return "any {}".format(value)
    return value


def _portal_section(dim_config):
    """How the portal into this dimension is built.

    Constant for every seed, like the fixed placements above it — but it is the
    one thing a player has to know before they can reach the world at all, and
    it lived only in the raw config.
    """
    portal = (dim_config or {}).get("portal") or {}
    if not portal:
        return ""
    rows = []

    def row(label, value, note=""):
        rows.append(
            "<div class='mrow sev0'>"
            "<span class='mname'>{}</span>"
            "<span class='mval'>{}</span>"
            "<span class='mtarget' style='grid-column:span 2'>{}</span>"
            "</div>".format(html.escape(label), html.escape(value),
                            html.escape(note)))

    materials = portal.get("frameMaterials")
    if isinstance(materials, dict) and any(p in materials for p in FRAME_PARTS):
        for part in FRAME_PARTS:
            if part in materials:
                row(part.title(), _accept_form(materials[part]),
                    "left and right" if part == "sides" else "")
    elif portal.get("frameBlock") is not None:
        row("Frame", _accept_form(portal["frameBlock"]), "every part")

    place = portal.get("framePlaceBlock")
    if place is not None:
        row("Arrival frame", _accept_form(place),
            "what the far side is built from")

    row("Orientation", portal.get("orientation") or "any",
        "which axes ignite")
    if portal.get("shape"):
        row("Shape", str(portal["shape"]))
    row("Igniter", _accept_form(portal.get("igniterItem")), "right-click to light")

    return ("<div class='sub-header'>Portal <span class='meta'>how you build "
            "the way in — identical for every seed</span></div>"
            "<div class='mlist'>{}</div>".format("".join(rows)))


def _survey_biomes(c):
    """{biome: [dist, x, z]} from a candidate's cached biome survey.

    Only fingerprinted records are read; a bare map cannot be shown to describe
    the current config. MIRRORS viewer-server.survey_biomes.
    """
    survey = c.get("biome_survey")
    if isinstance(survey, dict) and "biomes" in survey:
        return survey.get("biomes") or {}
    return {}


def _biome_label(biome_id):
    ns, _, path = biome_id.partition(":")
    pretty = (path or ns).replace("_", " ").title()
    return "{}<span class='ns'>{}</span>".format(
        html.escape(pretty), html.escape(ns) if path else "")


def _biome_section(c, profile):
    """Biomes split by the ROLE the config gives them.

    One flat alphabet of sixty ticks says nothing: a spawn-filter biome, a
    requested variety biome and a biome the noise map happened to produce are
    three different facts. Only the first two are things the dimension asked
    for, and only those two move the score.
    """
    survey = _survey_biomes(c)
    spawn_targets = list(profile.get("namesake") or [])
    variety = [b for b in (profile.get("variety_biomes") or [])
               if b not in set(spawn_targets)]
    spawn_biome = c.get("spawn_biome")
    radius = float(profile.get("radius") or 0) or 1.0
    cov_attrs = _coverage_attrs(profile)
    shares = ((c["metrics"].get("_terrain") or {}).get("shares") or {})

    # The measured distances are the floor. A denser survey may FIND a biome
    # the 256-block locate grid missed; losing one means the two were built
    # from different biome sources (see T20).
    measured = {}
    for metric, value in c["metrics"].items():
        if metric.startswith("biome_") and metric.endswith("_dist"):
            measured[metric[6:-5]] = float(value)
    dists = dict(measured)
    disagreed = []
    for biome, entry in survey.items():
        found = float(entry[0])
        known = dists.get(biome)
        if known is None or known < 0 or found < known:
            dists[biome] = found
    # Only when a survey exists: absent is the normal state for anything
    # outside the enriched top N, and absent is not wrong.
    if survey:
        for biome, known in measured.items():
            if known >= 0 and biome not in survey:
                disagreed.append(biome)

    def row(bid, note=""):
        d = dists.get(bid)
        found = d is not None and d >= 0
        share = shares.get(bid)
        if share is not None:
            sev = 0 if share >= 0.02 else (1 if share > 0 else 2)
        else:
            sev = 0 if found else 2
        if share is not None and share > 0:
            near = "{:.0%}<span class='ns'>of area</span>".format(share)
        elif found:
            near = "{:.0f}<span class='ns'>blocks</span>".format(d)
        else:
            near = "not found"
        if share is not None and share > 0 and found:
            note = note or "nearest {:.0f} blocks".format(d)
        flag = ""
        if bid == spawn_biome:
            flag = "<span class='kind spawned'>you spawn here</span>"
        band = " data-band='0,{:.0f}'".format(d) if found else ""
        return ("<div class='mrow biome-row sev{}'{}>"
                "<span class='mname'>{} {}</span>"
                "<span class='mval'>{}</span>"
                "<span class='mdev'>{}</span>"
                "</div>".format(sev, band, _biome_label(bid), flag, near, note))

    out = []
    if disagreed:
        out.append(
            "<div class='mrow sev2'><span class='mname'>survey disagrees"
            "</span><span class='mval'>{}</span><span class='mdev'>the biome "
            "survey is missing {} the measurements located — it was built from "
            "a different biome source; re-run the viewer to rebuild it"
            "</span></div>".format(
                len(disagreed),
                "a biome" if len(disagreed) == 1 else "biomes"))
    if spawn_targets:
        hit = spawn_biome in set(spawn_targets)
        out.append("<div class='sub-header'>Spawn targets <span class='meta'>"
                   "any of these qualifies a spawn — {}</span></div>".format(
                       "matched" if hit else "none matched, partial credit only"))
        out.append("<div class='mlist' {}>{}</div>".format(
            cov_attrs, "".join(row(b) for b in sorted(spawn_targets,
                                                     key=lambda b: dists.get(b, 1e9)))))
    if variety:
        found_n = sum(1 for b in variety if dists.get(b, -1) >= 0)
        out.append("<div class='sub-header'>Requested variety <span class='meta'>"
                   "{} of {} present within {:.0f} blocks</span></div>".format(
                       found_n, len(variety), radius))
        out.append("<div class='mlist' {}>{}</div>".format(
            cov_attrs, "".join(row(b) for b in sorted(variety,
                                                     key=lambda b: dists.get(b, 1e9)))))
    requested = set(spawn_targets) | set(variety)
    incidental = sorted(((d, b) for b, d in dists.items()
                         if b not in requested and d >= 0))
    if incidental:
        out.append(
            "<details class='incidental'><summary>{} incidental biomes "
            "<span class='meta'>present from the noise map, not requested — "
            "they do not affect the score</span></summary>"
            "<div class='mlist' {}>{}</div></details>".format(
                len(incidental), cov_attrs,
                "".join("<div class='mrow biome-row sev0' data-band='0,{:.0f}'>"
                        "<span class='mname'>{}</span>"
                        "<span class='mval'>{:.0f}<span class='ns'>blocks</span>"
                        "</span></div>".format(d, _biome_label(b), d)
                        for d, b in incidental)))
    if not out:
        return ""
    return "<div class='section-header'>Biomes</div>" + "".join(out)


def _score_section(c, profile):
    """What each component actually contributed, in points, against its weight.

    A raw percentage per axis cannot explain a total: variety at 40% costs
    six points when its weight is 10, while terrain at 99% earns forty at a
    weight of 40. The weights are the missing half of that sentence.
    """
    w = profile.get("weights") or {}
    wsum = sum(w.values()) or 1
    labels = {"namesake": "spawn biome", "variety": "biome variety",
              "terrain": "terrain shape", "structures": "structures"}
    rows = []
    segs = []
    for key in SCORE_COMPONENTS:
        if key not in c["parts"]:
            continue
        frac = c["parts"][key]
        weight = w.get(key, 0)
        earned = frac * weight / wsum * 100
        possible = weight / wsum * 100
        lost = possible - earned
        rows.append(
            "<div class='srow comp-{}'>"
            "<span class='sname'><i class='swatch'></i>{}</span>"
            "<span class='sbar'><span style='width:{:.0f}%'></span></span>"
            "<span class='spts'>{:.1f}<span class='meta'>/{:.0f}</span></span>"
            "<span class='sloss'>{}</span>"
            "</div>".format(key, labels.get(key, key),
                            max(0.0, min(100.0, frac * 100)),
                            earned, possible,
                            "&minus;{:.1f}".format(lost) if lost >= 0.05 else ""))
        segs.append((key, earned, possible))
    return ("<div class='section-header'>Score {:.1f} <span class='meta'>"
            "points earned of each component's weight</span></div>"
            "{}<div class='slist'>{}</div>".format(
                c["score"], _contribution_bar(segs), "".join(rows)))


#: The four score components, in the order they are always presented. Each
#: owns one colour (--chart-1..4 via .comp-<key>) used for its swatch, its
#: bar, its segment of the contribution bar and its delta — everywhere. A
#: component's colour IS its name, so "structures carried this seed" is
#: readable without reading a label.
SCORE_COMPONENTS = ("namesake", "variety", "terrain", "structures")


def _contribution_bar(segs):
    """One bar, four segments: where this seed's points actually came from.

    Four separate progress bars answer "how did each component do". They do
    not answer "what shape is this candidate", which is the question you ask
    when choosing between two of them — a seed carried by structures and a
    seed carried by terrain can share a total and be nothing alike. The
    segment widths are points earned; the unfilled tail is points lost.
    """
    total = sum(p for _k, _e, p in segs) or 1
    parts = []
    for key, earned, _possible in segs:
        if earned <= 0:
            continue
        parts.append(
            "<span class='cseg comp-{}' style='width:{:.2f}%' title='{} {:.1f} pts'></span>".format(
                key, earned / total * 100, key, earned))
    return "<div class='cbar' role='img' aria-hidden='true'>{}</div>".format("".join(parts))



def _relief_swatch(c, profile):
    """A 3x3 relief chip from the height/water grid already measured.

    Most cards, most of the time, say "render queued" — a real render is
    minutes of CPU and the bank is thousands of candidates deep, so the
    unrendered state is the DEFAULT, not the exception. But every candidate
    already carries a 3x3 grid of sampled heights and a water flag per cell,
    measured at roll time. That is enough to say "high and dry in the north,
    ocean in the south-west" without rendering anything, which is a real
    answer to "is this worth opening" instead of a grey box.

    Nine cells, sea-blue where the sample is water, and a green-to-stone ramp
    by height elsewhere, normalised across this candidate's own range so the
    shape reads even on a flat world.
    """
    rows = c.get("metrics") or {}
    hmap, wmap = {}, {}
    for metric, value in rows.items():
        try:
            if metric.startswith("height_r"):
                hmap[(int(metric[8]), int(metric[10]))] = float(value)
            elif metric.startswith("water_r"):
                wmap[(int(metric[7]), int(metric[9]))] = float(value)
        except (ValueError, IndexError):
            continue
    if len(hmap) < 9:
        return ""
    lo, hi = min(hmap.values()), max(hmap.values())
    span = (hi - lo) or 1.0
    cells = []
    for r in range(3):
        for col in range(3):
            h = hmap.get((r, col), lo)
            if wmap.get((r, col), 0.0) >= 0.5:
                cells.append("var(--relief-water)")
                continue
            t = (h - lo) / span
            # low ground green, high ground pale stone — the same reading as
            # the real renders, so the chip and the render agree.
            cells.append(
                "color-mix(in oklab, var(--relief-high) {:.0f}%, var(--relief-low))".format(t * 100))
    return ("<span class='relief-chip' role='img' aria-label='Terrain shape, "
            "3 by 3 samples'>{}</span>".format(
                "".join("<i style='background:{}'></i>".format(x) for x in cells)))


def _delta_vs(c, ref, profile):
    """Which components this candidate wins and loses on against `ref`.

    The scores are already computed for both; asking someone to open two
    candidates and subtract four pairs of numbers in their head is the
    single most avoidable cost in the compare loop.
    """
    if not ref or ref is c:
        return ""
    w = profile.get("weights") or {}
    wsum = sum(w.values()) or 1
    bits = []
    for key in SCORE_COMPONENTS:
        a = c.get("parts", {}).get(key)
        b = ref.get("parts", {}).get(key)
        if a is None or b is None:
            continue
        d = (a - b) * w.get(key, 0) / wsum * 100
        if abs(d) < 0.5:
            continue
        bits.append("<span class='delta comp-{}'>{}{:.1f}</span>".format(
            key, "+" if d > 0 else "&minus;", abs(d)))
    if not bits:
        return "<div class='deltas meta'>same as winner</div>"
    return "<div class='deltas' title='vs the current winner'>{}</div>".format("".join(bits))


def _render_candidate(idx, c, dim_name, profile, winners, default_show,
                      shortlist_set=None, ref_cand=None, dim_config=None):
    esc_dim = html.escape(dim_name, quote=True)
    shortlisted = (dim_name, c["seed"]) in (shortlist_set or set())
    win = winners.get(dim_name, {}).get("seed") == c["seed"]
    img = "renders/{}/{}.png".format(dim_name, c["seed"])
    hires = "renders/{}/{}_hires.png".format(dim_name, c["seed"])
    terrain_html = _terrain_section(c, profile)
    struct_html = _structure_section(c, profile) + _portal_section(dim_config)
    spawn = c["spawn_biome"]
    spawn_html = ("<b>{}</b>".format(html.escape(spawn))
                  if spawn in profile["namesake"]
                  else html.escape(spawn))
    sx = c["metrics"].get("spawn_x")
    sz = c["metrics"].get("spawn_z")
    if sx is not None and sz is not None:
        spawn_html += " <span class='meta'>({}, {})</span>".format(
            int(float(sx)), int(float(sz)))
    fdist = c["metrics"].get("spawn_filter_dist")
    if (spawn not in profile["namesake"]
            and fdist is not None and float(fdist) >= 0):
        spawn_html += (" <span class='meta'>(filter biome "
                       "{} blocks away)</span>".format(int(float(fdist))))
    pinned = bool(winners.get(dim_name, {}).get("pinned")) and win
    crown = (" &#x1F4CC;" if pinned else " &#x1F3C6;") if win else ""
    sc = _score_colour(c["score"])
    hidden = ' style="display:none"' if idx >= default_show else ""
    pick_btn = ("" if win else
                "<button class='pick' data-dim='{}' "
                "data-seed='{}'>Use this seed <kbd>U</kbd></button>".format(esc_dim, c["seed"]))
    sl_label = "Remove from shortlist" if shortlisted else "Shortlist"
    shortlist_btn = ("<button class='action-btn shortlist' "
                     "data-dim='{}' data-seed='{}'>{} <kbd>X</kbd></button>".format(
                         esc_dim, c["seed"], sl_label))
    # Lives on the TILE, not in the lightbox: choosing two things to compare
    # is something you do while looking at the grid of them.
    compare_btn = ("<button type='button' class='cmp-pick' "
                   "aria-label='Compare seed {}'>compare</button>".format(c["seed"]))
    create_dim_btn = ("<button class='action-btn create-dim' "
                      "data-dim='{}' data-seed='{}'>Fork dimension <kbd>F</kbd></button>".format(
                          esc_dim, c["seed"]))
    biome_html = _biome_section(c, profile)

    # Dimension meta badges for the lightbox
    meta_parts = []
    meta_parts.append("<span class='badge'>{}</span>".format(profile["type"]))
    meta_parts.append("<span class='badge'>{}</span>".format(profile["mood"]))
    if profile.get("noise"):
        meta_parts.append("<span class='badge'>{}</span>".format(profile["noise"]))
    # build_profile sets this to None when a dimension has no difficulty
    # block, and .get()'s default does not apply to a present None. 0.0 is a
    # real value (peaceful), so `or 1.0` is wrong.
    mob_d = profile.get("mob_difficulty")
    if mob_d is not None and mob_d != 1.0:
        col = "#e05252" if mob_d >= 2.0 else ("#e8a735" if mob_d > 1.0 else "#6ec96e")
        meta_parts.append("<span class='badge' style='color:{}'>{:.1f}x mobs</span>".format(col, mob_d))
    if profile.get("peaceful"):
        meta_parts.append("<span class='badge' style='color:#6ec96e'>peaceful</span>")
    if profile.get("density"):
        meta_parts.append("<span class='badge'>{}</span>".format(profile["density"]))
    meta_parts.append("<span class='badge'>{}b border</span>".format(int(profile["radius"] * 2)))
    if profile.get("scale", 1.0) != 1.0:
        meta_parts.append("<span class='badge'>{:.0f}x scale</span>".format(profile["scale"]))
    meta_badges = " ".join(meta_parts)

    shortlisted_attr = " data-shortlisted='1'" if shortlisted else ""
    # Inline score summary for the detail panel header
    score_parts = _score_section(c, profile)
    return (
        "<div class='cand{} cand-item' data-idx='{}' data-score='{:.1f}' "
        "data-dim='{}'{}{} data-seed='{}' data-parts='{}' data-spawn='{}' "
        "data-render='{}' title='{}'>"
        "<img src='{}' data-hires='{}' loading='lazy' style='background:{}' "
        "alt='Map render — {} seed {}' onerror=\"this.onerror=null;var d=document.createElement('div');d.className='no-render '+this.className;d.textContent='render queued';this.replaceWith(d)\">"
        "<div class='hires-badge'>HD</div>"
        "<div class='cand-dim-label'>{}</div>"
        "<div class='score' title='{}' style='color:{}'>{:.1f}{}</div>"
        "{}"
        "<div class='seed'>{}</div>"
        "{}"
        "{}"
        "<div class='cand-detail' style='display:none'>"
        "<div class='lb-header'>"
        "<div class='lb-title'><span class='dim-label'>{}</span>"
        " <span class='score' style='color:{}'>{:.1f}{}</span>"
        " <span class='seed'>{}</span></div>"
        "<div class='spawn'><b>{}</b></div>"
        "<div class='score-parts'>{}</div>"
        "<div class='lb-meta'>{}</div>"
        "</div>"
        "{}"
        "{}"
        "<div>{}</div>"
        "<div class='lb-actions'>{}{}{}</div>"
        "</div>"
        "</div>").format(
            " winner" if win else "", idx, c["score"],
            esc_dim, hidden, shortlisted_attr, c["seed"],
            # The compare, dartboard and scatter views all need the numbers,
            # not the rendered markup. Scraping a formatted "3.5/10" back out
            # of the DOM to subtract it is how those views rot.
            html.escape(json.dumps({k: round(v, 4) for k, v in
                                    (c.get("parts") or {}).items()}), quote=True),
            html.escape(c.get("spawn_biome", ""), quote=True),
            img,
            html.escape(candidate_tooltip(c), quote=True),
            img, hires, placeholder_colour(c.get("spawn_biome")),
            esc_dim, c["seed"],
            html.escape(dim_name),
            html.escape(SCORE_HELP, quote=True), sc, c["score"], crown,
            _relief_swatch(c, profile), c["seed"],
            _delta_vs(c, ref_cand, profile),
            compare_btn,
            html.escape(dim_name),
            sc, c["score"], crown,
            c["seed"],
            html.escape(spawn),
            score_parts,
            meta_badges,
            terrain_html, struct_html, biome_html,
            pick_btn, shortlist_btn, create_dim_btn)


# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("command", choices=["manifest", "world-manifest", "render-manifest",
                                        "score", "finalise", "rescore", "status"])
    ap.add_argument("--config", required=True,
                    help="config/custom-dimensions/ directory")
    ap.add_argument("--winner-overlay",
                    help="consumer mode: write winners as {\"overrides\"} files "
                         "into this overlay/config/custom-dimensions/ directory "
                         "instead of editing the platform dimension files")
    ap.add_argument("--seedtest", required=True)
    ap.add_argument("--csv", help="measurements CSV (default <seedtest>/measurements.csv)")
    ap.add_argument("--workers", type=int, default=1)
    ap.add_argument("--candidates", type=int, default=16)
    ap.add_argument("--top", type=int, default=3, help="render-manifest: renders per dim")
    ap.add_argument("--spawn-attempts", type=int, default=10,
                    help="manifest: seeds per slot for spawn-filter re-rolls "
                         "(the final attempt is always kept)")
    ap.add_argument("--dims", help="comma-separated subset of dimension names")
    ap.add_argument("--no-worlds", action="store_true",
                    help="manifest: no @worlds slots (skip world-seed rolling)")
    ap.add_argument("--census-workers", type=int, default=0,
                    help="processes for the noise-census and terrain backfills "
                         "(default: two thirds of cores, floor 2). Lower it to "
                         "leave room for a local server — a cold bank runs for "
                         "hours on every core it is given")
    ap.add_argument("--census-top", type=int, default=0,
                    help="only census candidates that could still reach a "
                         "dimension's top N (exact bound, so the winner and "
                         "the viewer's top ten are unchanged). 0 = census "
                         "every candidate")
    ap.add_argument("--write-config", action="store_true")
    ap.add_argument("--viewer", action="store_true")
    ap.add_argument("--open-viewer", action="store_true")
    args = ap.parse_args()
    if not args.csv:
        args.csv = os.path.join(args.seedtest, "measurements.csv")
    candidates.set_bank_root(args.seedtest)

    config = load_config(args.config)
    difficulty = load_difficulty(args.config)
    dims = [d for d in config["dimensions"] if rollable(d)]
    worlds = config.get("worlds", [])
    # Every rollable target, before --dims narrows anything. The viewer is a
    # whole-bank artefact and is built from this; scoring and winner-writing
    # use the narrowed set below.
    all_targets = [(w["name"], w) for w in worlds] + [(d["name"], d) for d in dims]
    if args.dims:
        wanted = {d.strip() for d in args.dims.split(",")}
        known = {d["name"] for d in dims} | {w["name"] for w in worlds}
        missing = wanted - known
        if missing:
            sys.exit(f"unknown/unrollable dimensions: {', '.join(sorted(missing))}")
        dims = [d for d in dims if d["name"] in wanted]
        worlds = [w for w in worlds if w["name"] in wanted]
    # Worlds first — they share ONE world seed and lead the viewer.
    profiles = {w["name"]: build_profile(w, config, difficulty) for w in worlds}
    profiles.update({d["name"]: build_profile(d, config, difficulty) for d in dims})
    dim_profiles = {d["name"]: profiles[d["name"]] for d in dims}
    world_profiles = {w["name"]: profiles[w["name"]] for w in worlds}

    if args.command == "manifest":
        cmd_manifest(args, config, dim_profiles, world_profiles)
    elif args.command == "world-manifest":
        cmd_world_manifest(args, config, world_profiles)
    elif args.command == "render-manifest":
        cmd_render_manifest(args, config, profiles)
    elif args.command == "score":
        cmd_score(args, config, profiles)
    elif args.command == "rescore":
        cmd_rescore(args, config, profiles)
    elif args.command == "status":
        cmd_status(args, config, profiles)
    else:
        page_profiles = {name: profiles.get(name)
                         or build_profile(entry, config, difficulty)
                         for name, entry in all_targets}
        sys.exit(cmd_finalise(args, config, profiles, world_profiles,
                              page_profiles=page_profiles))


if __name__ == "__main__":
    main()
