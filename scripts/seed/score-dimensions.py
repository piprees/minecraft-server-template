#!/usr/bin/env python3
"""score-dimensions.py — plan, score, and finalise parallel dimension seed rolls.

Subcommands (all take --config <multiverse_config.json> and --seedtest <dir>):

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
            Same outputs (work-r<w>.txt / mvconfig-r<w>.json) for the
            winners-render pass: the top --top candidates per dimension.
  score     Score every measured candidate; prints a ranked table and writes
            .seedtest/scores.json.
  finalise  score + pick winners + write them into the config (with .bak),
            generate .seedtest/viewer.html, print the summary table.
            Options: --write-config --viewer --open-viewer

Measurement CSV is long-format (target,seed,metric,value) merged from the
per-worker files by roll-all.sh. Metrics per candidate:
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
from dimension_profiles import BANDS, build_profile, rollable  # noqa: E402

LOCATE_HORIZON = 3200  # ~how far /locate reliably reports before "not found"


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
    ns = config.get("namespace", "adventure")
    for w, jobs in jobs_by_worker.items():
        (seedtest / f"work-{prefix}{w}.txt").write_text(
            "".join(f"{dim['name']}|{cand}|{seed}\n" for dim, cand, seed in jobs))
        roll = {k: v for k, v in config.items() if k != "dimensions" and k != "portals"}
        roll["portals"] = []
        roll["idleUnloadMinutes"] = 9999
        roll["dimensions"] = [candidate_entry(d, c, s, ns) for d, c, s in jobs]
        (seedtest / f"mvconfig-{prefix}{w}.json").write_text(json.dumps(roll, indent=2))


def cmd_manifest(args, config, profiles):
    seedtest = Path(args.seedtest)
    seedtest.mkdir(parents=True, exist_ok=True)
    measured = load_measurements(args.csv)
    dims_by_name = {d["name"]: d for d in config["dimensions"]}
    workers = max(1, args.workers)

    jobs_by_worker = {w: [] for w in range(workers)}
    total = 0
    for i, name in enumerate(profiles):
        done = sum(1 for rows in measured.get(name, {}).values() if "spawn_biome" in rows)
        needed = max(0, args.candidates - done)
        w = i % workers
        for c in range(needed):
            seed = random_signed_seed()
            while str(seed) in measured.get(name, {}):
                seed = random_signed_seed()
            jobs_by_worker[w].append((dims_by_name[name], f"{name}__c{c:02d}", seed))
            total += 1
    write_worker_files(seedtest, config, jobs_by_worker)
    print(f"manifest: {total} candidates across {workers} workers "
          f"({len(profiles)} dims, target {args.candidates}/dim, already-measured skipped)")


def cmd_render_manifest(args, config, profiles):
    seedtest = Path(args.seedtest)
    results = score_all(config, profiles, args.csv)
    dims_by_name = {d["name"]: d for d in config["dimensions"]}
    workers = max(1, args.workers)
    renders = seedtest / "renders"

    jobs = []
    for name, cands in results.items():
        for j, c in enumerate(cands[: args.top]):
            if (renders / name / f"{c['seed']}.png").exists():
                continue
            jobs.append((dims_by_name[name], f"{name}__r{j:02d}", int(c["seed"])))
    jobs_by_worker = {w: jobs[w::workers] for w in range(workers)}
    write_worker_files(seedtest, config, jobs_by_worker, prefix="r")
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


def band_blocks(band, radius):
    lo_f, hi_f = BANDS[band]
    return lo_f * radius, hi_f * radius


def structure_score(dist, band, radius):
    lo, hi = band_blocks(band, radius)
    if dist is None or dist < 0:
        # Not found. For far bands mostly out of locate range that's the
        # POINT (sparseness reads as quality); for near bands it's a miss.
        if lo >= LOCATE_HORIZON:
            return 1.0
        if band == "beyond_border":
            return 0.7
        return 0.0
    return window_score(dist, lo, min(hi, radius * 3))


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

    # Namesake: spawn biome in the namesake set.
    spawn = rows.get("spawn_biome", "unknown")
    if spawn in profile["namesake"]:
        parts["namesake"] = 1.0
    elif spawn != "unknown":
        parts["namesake"] = 0.55  # identified, on-list-but-not-iconic spawn
    else:
        parts["namesake"] = 0.0

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

    # Structures: banded placement.
    if profile["battery"]:
        ss, n = 0.0, 0
        for name, _sid, band in profile["battery"]:
            v = rows.get(f"structure_{name}_dist")
            d = float(v) if v is not None else None
            ss += structure_score(d, band, profile["radius"])
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
    return round(max(total_score, 0.0), 2), {k: round(v, 3) for k, v in parts.items()}


def load_measurements(csv_path):
    """-> {dim: {seed: {metric: value}}}"""
    data = defaultdict(lambda: defaultdict(dict))
    if not Path(csv_path).exists():
        return data
    with open(csv_path, newline="") as fh:
        reader = csv.reader(fh)
        for row in reader:
            if len(row) != 4 or row[0] == "target":
                continue
            target, seed, metric, value = row
            data[target][seed][metric] = value
    return data


def score_all(config, profiles, csv_path):
    data = load_measurements(csv_path)
    results = {}
    for name, profile in profiles.items():
        cands = []
        for seed, rows in data.get(name, {}).items():
            total, parts = score_candidate(profile, rows)
            cands.append({"seed": seed, "score": total, "parts": parts,
                          "spawn_biome": rows.get("spawn_biome", "unknown"),
                          "metrics": rows})
        cands.sort(key=lambda c: c["score"], reverse=True)
        results[name] = cands
    return results


def cmd_score(args, config, profiles):
    results = score_all(config, profiles, args.csv)
    out = Path(args.seedtest) / "scores.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    slim = {d: [{k: c[k] for k in ("seed", "score", "parts", "spawn_biome")}
                for c in cands] for d, cands in results.items()}
    out.write_text(json.dumps(slim, indent=2))
    print_summary(results, profiles)
    print(f"\nscores written to {out}")


def print_summary(results, profiles):
    print(f"\n{'dimension':30} {'cands':>5} {'best seed':>21} {'score':>6}  spawn")
    print("-" * 92)
    for name in profiles:
        cands = results.get(name, [])
        if not cands:
            print(f"{name:30} {0:>5} {'-':>21} {'-':>6}")
            continue
        best = cands[0]
        print(f"{name:30} {len(cands):>5} {best['seed']:>21} {best['score']:>6.1f}  {best['spawn_biome']}")


# ---------------------------------------------------------------------------
# Finalise: write winners + viewer
# ---------------------------------------------------------------------------
def cmd_finalise(args, config, profiles):
    results = score_all(config, profiles, args.csv)
    winners = {d: c[0] for d, c in results.items() if c}

    if args.write_config and winners:
        cfg_path = Path(args.config)
        backup = cfg_path.with_name(cfg_path.name + f".bak.{time.strftime('%Y%m%d-%H%M%S')}")
        shutil.copy2(cfg_path, backup)
        fresh = json.loads(cfg_path.read_text())
        changed = 0
        for dim in fresh["dimensions"]:
            w = winners.get(dim["name"])
            if w:
                new_seed = int(w["seed"])
                if dim.get("seed") != new_seed:
                    dim["seed"] = new_seed
                    changed += 1
        cfg_path.write_text(json.dumps(fresh, indent=2) + "\n")
        print(f"config updated: {changed} seeds changed ({cfg_path}); backup: {backup.name}")

    if args.viewer:
        viewer = Path(args.seedtest) / "viewer.html"
        viewer.write_text(render_viewer(results, profiles, winners))
        print(f"viewer: {viewer}")
        if args.open_viewer and sys.platform == "darwin":
            subprocess.run(["open", str(viewer)], check=False)

    print_summary(results, profiles)
    return 0


def band_label(profile, band):
    from dimension_profiles import BANDS
    lo, hi = BANDS[band]
    return f"{int(lo * profile['radius'])}–{int(hi * profile['radius'])}"


def candidate_tooltip(c):
    """Raw measurements for the title-attribute tooltip."""
    lines = []
    for metric, value in sorted(c["metrics"].items()):
        if metric.startswith(("structure_", "biome_")):
            pretty = metric.replace("structure_", "").replace("biome_", "").replace("_dist", "")
            lines.append(f"{pretty}: {'not found' if str(value) == '-1' else value}")
    return " | ".join(lines)


def render_viewer(results, profiles, winners):
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
    """
    out = ["<!doctype html><meta charset='utf-8'><title>Seed roll results</title>",
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
                   + (f"<span class='badge'>{profile['noise']}</span>" if profile['noise'] else "")
                   + "</div>")
        out.append(f"<div class='blurb'>{html.escape(profile['blurb'])}</div>")

        t = profile["terrain"]
        w = profile["weights"]
        battery = ", ".join(f"{n} ({band_label(profile, band)})"
                            for n, _sid, band in profile["battery"]) or "none (void — biomes only)"
        namesakes = ", ".join(sorted(profile["namesake"])) or "—"
        out.append(
            "<div class='criteria'>"
            f"<b>Weights</b> namesake {w['namesake']} · variety {w['variety']} · terrain {w['terrain']} · structures {w['structures']}<br>"
            f"<b>Namesake biomes</b> {html.escape(namesakes)}<br>"
            f"<b>Structure bands (blocks)</b> {html.escape(battery)}<br>"
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
            out.append(
                f"<div class='cand{' winner' if win else ''}' title='{html.escape(candidate_tooltip(c), quote=True)}'>"
                f"<img src='{img}' loading='lazy' onerror=\"this.style.display='none'\">"
                f"<div class='score'>{c['score']:.1f}{' 🏆' if win else ''}</div>"
                f"<div class='seed'>{c['seed']}</div>"
                f"<div class='bars'>{bars}</div>"
                f"<div class='spawn'>spawn: {spawn_html}</div>"
                "</div>")
        out.append("</div>")
    return "\n".join(out)


# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("command", choices=["manifest", "render-manifest", "score", "finalise"])
    ap.add_argument("--config", required=True)
    ap.add_argument("--seedtest", required=True)
    ap.add_argument("--csv", help="measurements CSV (default <seedtest>/measurements.csv)")
    ap.add_argument("--workers", type=int, default=1)
    ap.add_argument("--candidates", type=int, default=16)
    ap.add_argument("--top", type=int, default=3, help="render-manifest: renders per dim")
    ap.add_argument("--dims", help="comma-separated subset of dimension names")
    ap.add_argument("--write-config", action="store_true")
    ap.add_argument("--viewer", action="store_true")
    ap.add_argument("--open-viewer", action="store_true")
    args = ap.parse_args()
    if not args.csv:
        args.csv = os.path.join(args.seedtest, "measurements.csv")

    config = json.loads(Path(args.config).read_text())
    dims = [d for d in config["dimensions"] if rollable(d)]
    if args.dims:
        wanted = {d.strip() for d in args.dims.split(",")}
        missing = wanted - {d["name"] for d in dims}
        if missing:
            sys.exit(f"unknown/unrollable dimensions: {', '.join(sorted(missing))}")
        dims = [d for d in dims if d["name"] in wanted]
    profiles = {d["name"]: build_profile(d, config) for d in dims}

    if args.command == "manifest":
        cmd_manifest(args, config, profiles)
    elif args.command == "render-manifest":
        cmd_render_manifest(args, config, profiles)
    elif args.command == "score":
        cmd_score(args, config, profiles)
    else:
        sys.exit(cmd_finalise(args, config, profiles))


if __name__ == "__main__":
    main()
