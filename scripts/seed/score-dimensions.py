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
    """A structure that BELONGS, judged by its placement range in blocks
    (v4 Phase 6: explicit {min,max} ranges; band names convert to ranges in
    build_profile). Clamped to the playable radius. Ranges beyond locate's
    search horizon can't be confirmed — absence is compatible, presence
    hugging spawn is not."""
    hi = min(hi, radius)
    if lo >= LOCATE_HORIZON:
        if dist is None or dist < 0:
            return 0.8
        return 0.2 if dist < radius * 0.3 else 1.0
    if dist is None or dist < 0:
        return 0.0 if hi <= LOCATE_HORIZON else 0.6
    return window_score(dist, lo, hi)


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

    # Variety: fraction of listed biomes locatable nearby. Closer = better.
    found, total = 0.0, 0
    for metric, value in rows.items():
        if metric.startswith("biome_") and metric.endswith("_dist"):
            total += 1
            d = float(value)
            if d >= 0:
                found += 1.0 if d <= profile["radius"] else 0.6
    parts["variety"] = (found / total) if total else 0.5
    if total and found == 1 and total > 2:
        parts["variety"] *= 0.7  # verging on single-biome — penalise

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
    if profile["battery"]:
        ss, n = 0.0, 0
        for name, _sid, spec, kind in profile["battery"]:
            v = rows.get(f"structure_{name}_dist")
            d = float(v) if v is not None else None
            if kind == "shun":
                ss += shun_score(d, profile["radius"], spec)
            else:
                ss += want_score(d, spec[0], spec[1], profile["radius"])
            n += 1
        parts["structures"] = ss / n if n else 0.0
    else:
        parts["structures"] = 0.0

    w = profile["weights"]
    wsum = sum(w.values()) or 1
    total_score = sum(parts[k] * w[k] for k in parts) / wsum * 100.0

    # Errors are a straight penalty.
    errs = float(rows.get("errors", 0) or 0)
    total_score -= min(10.0, errs * 0.5)

    # Endgame proximity penalty: structures too close to spawn.
    safe_r = profile.get("endgame_safe_radius", 0)
    if safe_r > 0:
        for metric, value in rows.items():
            if metric.startswith("endgame_") and metric.endswith("_dist"):
                d = float(value)
                if 0 <= d < safe_r:
                    total_score -= 8.0 * (1.0 - d / safe_r)

    return round(max(total_score, 0.0), 2), {k: round(v, 3) for k, v in parts.items()}


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


def write_winners_to_overlay(overlay_root, winners, seedtest):
    """Consumer mode: winners land in the consumer repo's overlay as
    {"overrides": {"seed", "spawn"}} files — the durable consumer-owned
    artefact (bundle platform files are replaced on every update). Existing
    overlay files keep their shape: an "overrides" file gets its keys
    updated; a full-replace file gets top-level seed/spawn; an empty {}
    (dimension disabled) is left alone."""
    dims_dir = Path(overlay_root) / "dimensions"
    backup = None
    if dims_dir.is_dir():
        backup = session_backup(seedtest, lambda ts: shutil.copytree(
            dims_dir, Path(seedtest) / f"overlay-dimensions.bak.{ts}"))
    dims_dir.mkdir(parents=True, exist_ok=True)
    changed = 0
    for name, w in winners.items():
        f = dims_dir / f"{name}.json"
        data = {}
        if f.exists():
            try:
                data = json.loads(f.read_text())
            except json.JSONDecodeError:
                data = {}
            if data == {}:
                continue  # consumer disabled this dimension — never resurrect
        target = data.setdefault("overrides", {}) \
            if ("overrides" in data or not data) else data
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

    if args.write_config and winners:
        cfg_path = Path(args.config)
        if cfg_path.is_dir():
            if getattr(args, "winner_overlay", None):
                changed, backup = write_winners_to_overlay(args.winner_overlay, winners, args.seedtest)
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

    if args.viewer:
        viewer = Path(args.seedtest) / "viewer.html"
        viewer.write_text(render_viewer(results, profiles, winners, rejected))
        print(f"viewer: {viewer}")
        if args.open_viewer and sys.platform == "darwin":
            subprocess.run(["open", str(viewer)], check=False)

    print_summary(results, profiles, rejected)
    return 0


def range_label(profile, spec):
    lo, hi = spec
    return f"{int(lo)}–{int(min(hi, profile['radius']))}"


def candidate_tooltip(c):
    """Raw measurements for the title-attribute tooltip."""
    lines = []
    for metric, value in sorted(c["metrics"].items()):
        if metric.startswith(("structure_", "biome_")):
            pretty = metric.replace("structure_", "").replace("biome_", "").replace("_dist", "")
            lines.append(f"{pretty}: {'not found' if str(value) == '-1' else value}")
    return " | ".join(lines)


def render_viewer(results, profiles, winners, rejected=None):
    rejected = rejected or {}
    css = """
    :root { color-scheme: dark; }
    body { font: 14px/1.5 -apple-system, sans-serif; background: #14161a; color: #e6e6e6; margin: 2rem; }
    h1 { font-size: 1.4rem; } h2 { font-size: 1.15rem; margin: 2.2rem 0 .3rem; border-bottom: 1px solid #333; padding-bottom: .3rem; }
    .meta { color: #9aa; font-size: .85rem; margin: .2rem 0; }
    .blurb { color: #cbd; font-size: .88rem; margin: .3rem 0; max-width: 70rem; }
    .criteria { font-size: .78rem; color: #9aa; background: #191c21; border: 1px solid #262b33;
                border-radius: 8px; padding: .5rem .8rem; margin: .5rem 0 .8rem; max-width: 70rem; }
    .criteria b { color: #c8d2dc; font-weight: 600; }
    .cands { display: flex; flex-wrap: wrap; gap: .8rem; }
    .cand { background: #1d2026; border: 1px solid #2c313a; border-radius: 8px; padding: .6rem; width: 250px; }
    .cand.winner { border-color: #d4a020; box-shadow: 0 0 0 1px #d4a020; }
    .cand img { width: 100%; aspect-ratio: 1; border-radius: 4px; background: #101216; object-fit: cover; image-rendering: pixelated; }
    .score { font-size: 1.2rem; font-weight: 700; } .winner .score { color: #ffd850; }
    .seed { font-family: ui-monospace, monospace; font-size: .78rem; word-break: break-all; color: #8fb4d8; }
    .bars { margin-top: .35rem; }
    .bar { display: grid; grid-template-columns: 5.2rem 1fr 2.2rem; gap: .4rem; align-items: center;
           font-size: .72rem; color: #9aa; margin-bottom: .15rem; }
    .bar .track { height: 5px; background: #2c313a; border-radius: 3px; overflow: hidden; }
    .bar .fill { height: 100%; background: #5b8dd0; }
    .winner .bar .fill { background: #d4a020; }
    .spawn { font-size: .75rem; color: #9aa; margin-top: .3rem; }
    .spawn b { color: #a8d8a0; font-weight: 600; }
    .badge { display: inline-block; font-size: .7rem; padding: .05rem .45rem; border-radius: 99px; background: #2c313a; margin-right: .3rem; }
    .pick { display: none; margin-top: .4rem; font-size: .72rem; padding: .2rem .6rem; border-radius: 6px;
            border: 1px solid #3a4150; background: #232833; color: #c8d2dc; cursor: pointer; }
    .pick:hover { border-color: #d4a020; color: #ffd850; }
    body.live .pick { display: inline-block; }
    """
    out = ["<!doctype html><meta charset='utf-8'><title>Seed roll results</title>",
           # Live report: the roller regenerates this file during the run.
           "<meta http-equiv='refresh' content='30'>",
           f"<style>{css}</style>", "<h1>Multiverse seed roll — results</h1>",
           f"<p class='meta'>Generated {time.strftime('%Y-%m-%d %H:%M')}. Scores are 0–100, "
           "per-dimension (a great gauntlet seed would be a terrible garden seed — that's the point). "
           "Components: <b>namesake</b> = spawn biome sells the dimension's name; <b>variety</b> = listed "
           "biomes actually present nearby; <b>terrain</b> = relief/grain/water vs the dimension's target "
           "shape; <b>structures</b> = each structure lands in its placement band (distances relative to "
           "the playable radius = world border ÷ portal scale). Winners (gold) are written into "
           "multiverse_config.json. Hover a candidate for raw locate distances. Thumbnails are the "
           "spawn-area top-down render (144×144 blocks from 0,0).</p>"]
    for name, profile in profiles.items():
        cands = results.get(name, [])
        out.append(f"<h2>{html.escape(name)}</h2>")
        out.append("<div class='meta'>"
                   f"<span class='badge'>{profile['type']}</span>"
                   f"<span class='badge'>scale {profile['scale']:g} → playable radius {int(profile['radius'])}</span>"
                   f"<span class='badge'>{profile['mood']}</span>"
                   + (f"<span class='badge'>{profile['density']} structures</span>" if profile['density'] else "")
                   + ("<span class='badge'>peaceful</span>" if profile['peaceful'] else "")
                   + (f"<span class='badge'>mob difficulty ×{profile['mob_difficulty']:g}</span>"
                      if profile.get('mob_difficulty') is not None else "")
                   + ("<span class='badge'>world seed</span>" if profile.get('is_world') else "")
                   + (f"<span class='badge'>{profile['noise']}</span>" if profile['noise'] else "")
                   + (f"<span class='badge'>{rejected[name]} spawn-rejected</span>" if rejected.get(name) else "")
                   + "</div>")
        out.append(f"<div class='blurb'>{html.escape(profile['blurb'])}</div>")

        t = profile["terrain"]
        w = profile["weights"]
        wants = ", ".join(f"{n} ({range_label(profile, spec)})"
                          for n, _sid, spec, kind in profile["battery"] if kind == "want")
        shuns = ", ".join(n for n, _sid, _spec, kind in profile["battery"] if kind == "shun")
        spawn_filter = ", ".join(profile["namesake"]) or "any"
        out.append(
            "<div class='criteria'>"
            f"<b>Spawn filter</b> {html.escape(spawn_filter)}<br>"
            f"<b>Wants (blocks from 0,0)</b> {html.escape(wants) or 'none (void — biomes only)'}<br>"
            + (f"<b>Should not appear</b> {html.escape(shuns)}<br>" if shuns else "")
            + f"<b>Weights</b> namesake {w['namesake']} · variety {w['variety']} · terrain {w['terrain']} · structures {w['structures']}<br>"
            f"<b>Terrain targets</b> relief {int(t['relief'][0])}–{int(t['relief'][1])} · "
            f"grain {t['grain'][0]:.0f}–{t['grain'][1]:.0f} · water {t['water'][0]:.0%}–{t['water'][1]:.0%}"
            "</div>")
        if not cands:
            out.append("<p class='meta'>No candidates measured.</p>")
            continue
        out.append("<div class='cands'>")
        for c in cands:
            win = winners.get(name, {}).get("seed") == c["seed"]
            img = f"renders/{name}/{c['seed']}.png"
            bars = "".join(
                f"<div class='bar'><span>{k}</span><span class='track'>"
                f"<span class='fill' style='width:{v * 100:.0f}%'></span></span>"
                f"<span>{v:.2f}</span></div>"
                for k, v in c["parts"].items())
            spawn = c["spawn_biome"]
            spawn_html = (f"<b>{html.escape(spawn)}</b>" if spawn in profile["namesake"]
                          else html.escape(spawn))
            fdist = c["metrics"].get("spawn_filter_dist")
            if spawn not in profile["namesake"] and fdist is not None and float(fdist) >= 0:
                spawn_html += f" <span class='meta'>(filter biome {int(float(fdist))} blocks away)</span>"
            pinned = bool(winners.get(name, {}).get("pinned")) and win
            crown = (" 📌" if pinned else " 🏆") if win else ""
            pick_btn = ("" if win else
                        f"<button class='pick' data-dim='{html.escape(name, quote=True)}' "
                        f"data-seed='{c['seed']}'>☆ make winner</button>")
            out.append(
                f"<div class='cand{' winner' if win else ''}' title='{html.escape(candidate_tooltip(c), quote=True)}'>"
                f"<img src='{img}' loading='lazy' onerror=\"this.style.display='none'\">"
                f"<div class='score'>{c['score']:.1f}{crown}</div>"
                f"<div class='seed'>{c['seed']}</div>"
                f"<div class='bars'>{bars}</div>"
                f"<div class='spawn'>spawn: {spawn_html}</div>"
                f"{pick_btn}"
                "</div>")
        out.append("</div>")
    # Winner picking works when served by viewer-server.py (POST /pick →
    # winner-overrides.json → finalise --write-config). On file:// the
    # buttons stay hidden — there's nothing to POST to.
    out.append("""
<script>
if (location.protocol !== 'file:') {
  document.body.classList.add('live');
  document.body.addEventListener('click', async (e) => {
    const b = e.target.closest('.pick');
    if (!b) return;
    b.disabled = true; b.textContent = 'saving…';
    const res = await fetch('/pick', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({dim: b.dataset.dim, seed: b.dataset.seed}),
    }).catch(() => null);
    if (res && res.ok) location.reload();
    else { b.disabled = false; b.textContent = '☆ make winner (failed)'; }
  });
}
</script>""")
    return "\n".join(out)


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
