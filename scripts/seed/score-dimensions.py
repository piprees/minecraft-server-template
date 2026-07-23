#!/usr/bin/env python3
"""score-dimensions.py — plan, score, and finalise parallel dimension seed rolls.

Subcommands (all take --config and --seedtest <dir>). --config accepts the
v4 config directory (config/custom-dimensions/) or, backwards-compatibly,
the deprecated monolithic multiverse_config.json; directory mode writes
winners into the individual dimensions/{slug}.json files.

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
            generate .seedtest/viewer.html, print the summary table.
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
import os
import shutil
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import candidates  # noqa: E402
from dimension_profiles import build_profile, load_config, load_difficulty, rollable  # noqa: E402

LOCATE_HORIZON = 1600  # locate's practical search radius (~100 chunks)


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
def window_score(value, lo, hi):
    """1.0 inside [lo,hi], linear falloff over one window-width outside."""
    if value is None:
        return 0.0
    width = max(hi - lo, 1e-9)
    if lo <= value <= hi:
        return 1.0
    if value < lo:
        return max(0.0, 1.0 - (lo - value) / width)
    return max(0.0, 1.0 - (value - hi) / width)


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
    return relief, grain, water, land_fraction


def score_candidate(profile, rows):
    """rows: {metric: value} for one (dim, seed). Returns (total, parts)."""
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

    # Variety: biome diversity within the playable area. Proximity-weighted
    # so that fringe slivers (biomes found only near or beyond the world
    # border) don't inflate the score — a monoculture with token far-away
    # biomes no longer rates as "diverse". The balance component checks
    # that non-namesake biomes genuinely exist close to spawn; namesake
    # biomes already earn credit in the namesake component.
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
        parts["variety"] = 0.7 * base_variety + 0.3 * balance
    else:
        parts["variety"] = base_variety
    if total > 2 and found < 1.5:
        parts["variety"] *= 0.7

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

    # Structures: wants judged by their block range, shuns by distance.
    # clearSpawnRadius penalises ANY battery structure found too close to
    # spawn — the player needs breathing room regardless of the want range.
    # Density bias: found structures nudge the score by structureDensity —
    # sparse dims slightly prefer fewer hits, dense dims slightly prefer more.
    if profile["battery"]:
        ss, n = 0.0, 0
        found_count = 0
        clear_r = profile.get("clear_spawn_radius", 0)
        for name, _sid, spec, kind in profile["battery"]:
            v = rows.get(f"structure_{name}_dist")
            d = float(v) if v is not None else None
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
        parts["structures"] = ss / n if n else 0.0
        # Density bias: each found structure nudges the score. When
        # enrichment data exists (structure_all from a render pass), use
        # the TOTAL structure count — it includes unlisted structures
        # that the battery never asked about. This lets later roll rounds
        # dethrone earlier winners when enrichment reveals a cluttered or
        # empty world. Without enrichment, fall back to battery-found-count.
        enriched = rows.get("_enriched_structure_count")
        struct_count = int(enriched) if enriched is not None else found_count
        density = profile.get("density")
        if density == "sparse":
            parts["structures"] = max(0.0, parts["structures"] - struct_count * 0.012)
        elif density == "dense":
            parts["structures"] = min(1.1, parts["structures"] + struct_count * 0.008)
        else:
            parts["structures"] = max(0.0, parts["structures"] - struct_count * 0.003)
    else:
        parts["structures"] = 0.0

    w = profile["weights"]
    wsum = sum(w.values()) or 1
    total_score = sum(parts[k] * w[k] for k in parts) / wsum * 100.0

    # Errors are a straight penalty.
    errs = float(rows.get("errors", 0) or 0)
    total_score -= min(10.0, errs * 0.5)

    return round(max(0.0, min(100.0, total_score)), 2), {k: round(v, 3) for k, v in parts.items()}


def load_measurements(csv_path):
    """-> {dim: {seed: {metric: value}}} from one long-format CSV."""
    data = defaultdict(lambda: defaultdict(dict))
    if not csv_path or not Path(csv_path).exists():
        return data
    with open(csv_path, newline="") as fh:
        reader = csv.reader(fh)
        for row in reader:
            if len(row) != 4 or row[0] == "target":
                continue
            target, seed, metric, value = row
            data[target][seed][metric] = value
    return data


def gather_measurements(args):
    """Canonical measurement view -> {dim: {seed: rows}}.

    Directory mode reads the candidate store first, then folds in any
    un-merged worker spools (worker-*.csv) and the legacy
    .seedtest/measurements.csv (one-time import path: persist_candidates
    writes everything back to the store, after which the CSVs are inert).
    Legacy monolith mode uses just the CSVs."""
    data = defaultdict(lambda: defaultdict(dict))
    cfg = Path(args.config) if getattr(args, "config", None) else None
    if cfg is not None and cfg.is_dir():
        cdir = candidates.candidates_dir(cfg)
        if cdir.is_dir():
            for f in sorted(cdir.glob("*.json")):
                store = candidates.load_store(f)
                slug = f.stem
                for seed, cand in store["candidates"].items():
                    data[slug][seed].update(cand.get("measurements", {}))
                    sa = cand.get("structure_all")
                    if sa:
                        data[slug][seed]["_enriched_structure_count"] = str(len(sa))
                for seed in store["rejected"]:
                    data[slug][seed].setdefault("rejected", "1")
    sources = [Path(args.csv)] if getattr(args, "csv", None) else []
    sources += sorted(Path(args.seedtest).glob("worker-*.csv"))
    for src in sources:
        for dim, seeds in load_measurements(src).items():
            for seed, rows in seeds.items():
                data[dim][seed].update(rows)
    return data


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
        for seed, rows in data.get(name, {}).items():
            candidates.merge_rows(store, seed, rows)
        for seed, reason in abandoned.get(name, {}).items():
            store["abandoned"].setdefault(str(seed), reason)
        for c in results.get(name, []):
            candidates.record_score(store, c["seed"], store["configHash"],
                                    c["score"], c["parts"], now)
        if winners and name in winners:
            store["winner"] = winners[name]["seed"]
            store["winnerPinned"] = bool(winners[name].get("pinned"))
        candidates.save_store(cdir / f"{name}.json", store)


def score_all(profiles, data):
    """-> (results {dim: [accepted candidates ranked]}, rejected {dim: n}).
    Spawn-filter rejects are banked (their seeds never re-roll) but they
    are not candidates."""
    results, rejected = {}, {}
    for name, profile in profiles.items():
        cands = []
        rej = 0
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
    data = gather_measurements(args)
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
    data = gather_measurements(args)
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
        pin = " 📌" if store["winnerPinned"] else ""
        print(f"{name:30} {len(store['candidates']):>5} {len(store['rejected']):>4} "
              f"{len(store['abandoned']):>5} {winner or '-':>21} {wscore:>6}  {state}{pin}")
    if stale_count:
        print(f"\n{stale_count} target(s) have stale scores — ./dev seed-rescore refreshes "
              "them from banked measurements (no re-rolling)")


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


def write_winner(data, winner):
    """Apply one winner's seed + spawn to a config dict. -> changed?"""
    changed = False
    new_seed = int(winner["seed"])
    if data.get("seed") != new_seed:
        data["seed"] = new_seed
        changed = True
    sx = winner["metrics"].get("spawn_x")
    sz = winner["metrics"].get("spawn_z")
    if sx is not None and sz is not None:
        data["spawn"] = [int(float(sx)), 64, int(float(sz))]
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
            # conversions, spawnFilter tweaks) — an overlay copy of
            # the_starwell ate a spawnFilter fix exactly this way 2026-07-22.
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
        sx = ow["metrics"].get("spawn_x")
        sz = ow["metrics"].get("spawn_z")
        if sx is not None and sz is not None:
            ow_entry = next((w for w in fresh.get("worlds", [])
                             if w["name"] == "overworld"), None)
            if ow_entry is not None:
                ow_entry["spawn"] = [int(float(sx)), 64, int(float(sz))]
    cfg_path.write_text(json.dumps(fresh, indent=2) + "\n")
    return changed, backup, ow


def cmd_finalise(args, config, profiles, world_profiles=None):
    data = gather_measurements(args)
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
    if dir_mode:
        persist_candidates(args, config, profiles, results, data, winners)

    all_sources = {d["name"]: d for d in config.get("dimensions", [])}
    all_sources.update({w["name"]: w for w in config.get("worlds", [])})

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
        viewer = Path(args.seedtest) / "index.html"
        viewer.write_text(render_viewer(
            results, profiles, winners, rejected,
            seedtest=args.seedtest, dim_configs=all_sources))
        print(f"viewer: {viewer}")
        if args.open_viewer and sys.platform == "darwin":
            subprocess.run(["open", str(viewer)], check=False)

    print_summary(results, profiles, rejected)
    return 0


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
    legend = ("<div style='font-size:.75rem;color:#9aa;background:#191c21;"
              "border:1px solid #262b33;border-radius:6px;padding:.35rem .7rem;"
              "margin:.4rem 0 .2rem;line-height:1.6'>"
              "<b style=\"color:#c8d2dc\">Score</b> (0–100) rates each seed: "
              "<b style=\"color:#c8d2dc\">spawn</b> (correct starting biome), "
              "<b style=\"color:#c8d2dc\">variety</b> (biome diversity), "
              "<b style=\"color:#c8d2dc\">terrain</b> (landscape shape), "
              "<b style=\"color:#c8d2dc\">structures</b> (buildings at right distances). "
              "<span style='color:#6ec96e'>&#9632;</span>&nbsp;good&nbsp;(&gt;70) "
              "<span style='color:#e6e6e6'>&#9632;</span>&nbsp;OK&nbsp;(50–70) "
              "<span style='color:#e8a735'>&#9632;</span>&nbsp;weak&nbsp;(30–50) "
              "<span style='color:#e05252'>&#9632;</span>&nbsp;poor&nbsp;(&lt;30)"
              "</div>")

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
            .replace("{{SUMMARY_STATS}}", summary + legend)
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
                    "onerror=\"this.onerror=null\">").format(img, hires, esc_name)
        spawn_html = "<div class='dim-spawn'>spawn: <b>{}</b></div>".format(
            html.escape(best.get("spawn_biome", "")))

    pinned_dim = "1" if winners.get(name, {}).get("pinned") else "0"
    out = []
    out.append(
        "<div class='dim-card' role='button' tabindex='0' aria-expanded='false' "
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
               "<span class='dim-score' style='color:{}'>{:.1f}</span>"
               "<span class='badge'>{}</span>"
               "<span class='badge'>{}</span>"
               "<span>{} seeds</span>"
               "</div>".format(score_col, best_score, ptype, pmood, n_cands))
    blurb = profile.get("blurb", "")
    if blurb:
        out.append("<div class='dim-blurb'>{}</div>".format(
            html.escape(blurb[:80] + ("…" if len(blurb) > 80 else ""))))
    out.append(spawn_html)
    out.append("</div>")

    # Detail panel (visible when expanded)
    out.append("<div class='detail'>")
    out.append("<button type='button' class='close-btn' "
               "aria-label='Close details'>&times;</button>")

    # Detail header: winner image + info side by side
    out.append("<div class='detail-header'>")
    if best:
        out.append("<img class='winner-img' src='renders/{}/{}.png' loading='lazy' "
                   "alt='Map render — {} seed {}' "
                   "onerror=\"this.onerror=null\">".format(
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
        "<button class='action-btn hide' data-dim='{}'>Hide</button>"
        "<button class='action-btn remove' data-dim='{}'>Remove</button>"
        "</div>".format(esc_name, esc_name, esc_name, esc_name))
    out.append("</div></div>")  # close detail-info + detail-header

    # All candidates
    if cands:
        out.append("<div class='all-cands'>")
        for idx, c in enumerate(cands[:10]):
            out.append(_render_candidate(idx, c, name, profile, winners, 10,
                                         shortlist_set))
        out.append("</div>")
        if n_cands > 10:
            out.append("<p class='cand-count'>Showing 10 of {}</p>".format(n_cands))
    else:
        out.append("<p class='meta'>No seeds tested yet. Re-roll to generate candidates.</p>")

    out.append("</div>")  # close detail
    out.append("</div>")  # close dim-card
    return "\n".join(out)


def _render_candidate(idx, c, dim_name, profile, winners, default_show,
                      shortlist_set=None):
    esc_dim = html.escape(dim_name, quote=True)
    shortlisted = (dim_name, c["seed"]) in (shortlist_set or set())
    win = winners.get(dim_name, {}).get("seed") == c["seed"]
    img = "renders/{}/{}.png".format(dim_name, c["seed"])
    hires = "renders/{}/{}_hires.png".format(dim_name, c["seed"])
    _axis_labels = {"namesake": "spawn", "variety": "variety",
                    "terrain": "terrain", "structures": "structures"}
    # Terrain summary
    relief, grain, water, _land = terrain_metrics(c["metrics"])
    t = profile.get("terrain", {})
    terrain_html = ""
    if relief > 0 or grain > 0:
        terrain_html = ("<div class='section-header'>Terrain</div>"
                        "<div class='terrain-summary'>"
                        "<span>relief <b>{:.0f}</b></span>"
                        "<span>grain <b>{:.1f}</b></span>"
                        "<span>water <b>{:.0%}</b></span>"
                        "</div>".format(relief, grain, water))
    # Structure hit/miss list — sorted by distance from spawn
    # Use enriched structure_all data if available, fall back to nearest-only
    struct_all = c.get("structure_all", {})
    struct_items = []
    for sname, _sid, spec, kind in profile.get("battery", []):
        v = c["metrics"].get(f"structure_{sname}_dist")
        d = float(v) if v is not None else -1
        pretty = sname.replace("_", " ").title()
        all_hits = struct_all.get(sname, [])
        count_str = " <span class='meta'>({})</span>".format(len(all_hits)) if len(all_hits) > 1 else ""
        if kind == "shun":
            tip = "Avoid: should not appear nearby"
            if d < 0 and not all_hits:
                struct_items.append((9999, "<span title='{}'>{}</span> {} — absent".format(
                    tip, "&#x2705;", html.escape(pretty))))
            else:
                n = len(all_hits) if all_hits else 1
                struct_items.append((d if d >= 0 else 0, "<span title='{}'>{}</span> {} ({}){}".format(
                    tip, "&#x274C;", html.escape(pretty), int(d) if d >= 0 else "found",
                    " <span class='meta'>({} total)</span>".format(n) if n > 1 else "")))
        else:
            lo, hi = spec
            tip = "Wanted: {}-{} blocks from spawn".format(int(lo), int(hi))
            if d < 0 and not all_hits:
                struct_items.append((9998, "<span title='{}'>{}</span> {} — not found".format(
                    tip, "&#x274C;", html.escape(pretty))))
            elif d >= 0 and lo <= d <= hi:
                struct_items.append((d, "<span title='{}'>{}</span> {} ({}){}".format(
                    tip, "&#x2705;", html.escape(pretty), int(d), count_str)))
            elif d >= 0 and d < lo:
                struct_items.append((d, "<span title='{}'>{}</span> {} ({}, too close){}".format(
                    tip, "&#x26A0;&#xFE0F;", html.escape(pretty), int(d), count_str)))
            else:
                struct_items.append((d if d >= 0 else 9998,
                    "<span title='{}'>{}</span> {} ({}{}){}".format(
                    tip, "&#x26A0;&#xFE0F;", html.escape(pretty),
                    int(d) if d >= 0 else "?", ", too far" if d >= 0 else "",
                    count_str)))
    struct_items.sort(key=lambda x: x[0])
    struct_html = ("<div class='section-header'>Structures</div>"
                   "<div class='struct-list'>{}</div>".format(
        "".join("<div>{}</div>".format(s) for _, s in struct_items))
        if struct_items else "")
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
    create_dim_btn = ("<button class='action-btn create-dim' "
                      "data-dim='{}' data-seed='{}'>Fork dimension <kbd>F</kbd></button>".format(
                          esc_dim, c["seed"]))
    # Biome list — use survey data (all unique biomes found) if available,
    # fall back to the variety-biome distance metrics
    biome_survey = c.get("biome_survey", {})
    biome_items = []
    if biome_survey:
        for biome_id, (dist, bx, bz) in biome_survey.items():
            pretty = biome_id.split(":")[-1].replace("_", " ").title()
            ns = biome_id.split(":")[0].title() if ":" in biome_id else ""
            label = f"{ns}:{pretty}" if ns else pretty
            biome_items.append((dist, "<div>&#x2705; {} ({})</div>".format(
                html.escape(label), int(dist))))
    else:
        for metric, value in c["metrics"].items():
            if metric.startswith("biome_") and metric.endswith("_dist"):
                bname = metric[6:-5].replace("_", " ").title()
                d = float(value)
                if d >= 0:
                    biome_items.append((d, "<div>&#x2705; {} ({})</div>".format(
                        html.escape(bname), int(d))))
                else:
                    biome_items.append((9999, "<div>&#x274C; {} — not found</div>".format(
                        html.escape(bname))))
    biome_items.sort(key=lambda x: x[0])
    biome_html = ("<div class='section-header'>Biomes ({})</div>"
                  "<div class='struct-list'>"
                  "{}</div>".format(len(biome_items),
                      "".join(s for _, s in biome_items))
                  if biome_items else "")

    # Dimension meta badges for the lightbox
    meta_parts = []
    meta_parts.append("<span class='badge'>{}</span>".format(profile["type"]))
    meta_parts.append("<span class='badge'>{}</span>".format(profile["mood"]))
    if profile.get("noise"):
        meta_parts.append("<span class='badge'>{}</span>".format(profile["noise"]))
    mob_d = profile.get("mob_difficulty", 1.0)
    if mob_d != 1.0:
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
    score_parts = " | ".join("{} {:.0%}".format(
        _axis_labels.get(k, k).title(), v) for k, v in c["parts"].items())
    return (
        "<div class='cand{} cand-item' data-idx='{}' data-score='{:.1f}' "
        "data-dim='{}'{}{} title='{}'>"
        "<img src='{}' data-hires='{}' loading='lazy' "
        "alt='Map render — {} seed {}' onerror=\"this.onerror=null\">"
        "<div class='hires-badge'>HD</div>"
        "<div class='cand-dim-label'>{}</div>"
        "<div class='score' style='color:{}'>{:.1f}{}</div>"
        "<div class='seed'>{}</div>"
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
            esc_dim, hidden, shortlisted_attr,
            html.escape(candidate_tooltip(c), quote=True),
            img, hires,
            esc_dim, c["seed"],
            html.escape(dim_name),
            sc, c["score"], crown, c["seed"],
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
                    help="config/custom-dimensions/ directory (v4) or the "
                         "deprecated monolithic multiverse_config.json")
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
    ap.add_argument("--write-config", action="store_true")
    ap.add_argument("--viewer", action="store_true")
    ap.add_argument("--open-viewer", action="store_true")
    args = ap.parse_args()
    if not args.csv:
        args.csv = os.path.join(args.seedtest, "measurements.csv")

    config = load_config(args.config)
    difficulty = load_difficulty(args.config)
    dims = [d for d in config["dimensions"] if rollable(d)]
    worlds = config.get("worlds", [])
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
        sys.exit(cmd_finalise(args, config, profiles, world_profiles))


if __name__ == "__main__":
    main()
