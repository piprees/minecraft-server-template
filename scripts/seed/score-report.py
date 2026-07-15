#!/usr/bin/env python3
"""score-report.py - profile-driven scoring + report over seed measurements.

Purpose:  The judgement half of the measure/score split. roll-seeds.sh banks
          raw facts in a long-format CSV (target,seed,metric,value); this
          script applies a named profile (weights + directions + tier lists)
          at report time — re-weighting or new profiles never require
          re-rolling.

Usage:    score-report.py --profile classic [--csv seed-measurements.csv]
                          [--target world] [--top 25] [--out report.md]
          --target world       score the world-seed rolls (default)
          --target <dimension> score a dimension's candidate seeds
          --target all         one report section per measured target

Context:  Profile format is documented in scripts/seed/profiles/classic.profile.
          Derived metrics computed here from raw rows:
            terrain_relief   = max - min of the height grid
            terrain_grain    = mean |dh| between 4-neighbour grid points
            water_fraction   = mean of the water grid (0..1)
            <a>_<b>_proximity= manhattan distance between two locates
          Direction semantics (see classic.profile header).

Gotchas:  Python 3 is a repo dependency already (resolve-mods.py); this stays
          report-side only — measurement remains pure bash + RCON.
"""

import argparse
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

PROFILE_DIR = Path(__file__).resolve().parent / "profiles"


def load_profile(name):
    path = Path(name)
    if not path.exists():
        path = PROFILE_DIR / f"{name}.profile"
    if not path.exists():
        sys.exit(f"profile not found: {name} (looked in {PROFILE_DIR})")
    prof = {"metrics": [], "green": set(), "ok": set(), "options": {}, "locates": []}
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        kind = parts[0]
        if kind == "metric":
            prof["metrics"].append({"name": parts[1], "direction": parts[2],
                                    "weight": float(parts[3]), "params": parts[4:]})
        elif kind == "biome":
            prof[parts[1]].add(parts[2])
        elif kind == "option":
            prof["options"][parts[1]] = parts[2:]
        elif kind == "locate":
            prof["locates"].append({"name": parts[1], "where": parts[2], "id": parts[3]})
    total = sum(m["weight"] for m in prof["metrics"])
    if abs(total - 100.0) > 0.01:
        print(f"warning: profile weights sum to {total}, not 100", file=sys.stderr)
    return prof


def load_measurements(csv_path):
    """-> {(target, seed): {metric: value}}"""
    rows = defaultdict(dict)
    with open(csv_path, newline="") as fh:
        reader = csv.reader(fh)
        header = next(reader, None)
        if header != ["target", "seed", "metric", "value"]:
            sys.exit(f"{csv_path}: not a long-format measurements CSV "
                     f"(header {header}) — re-roll with the v3 roll-seeds.sh")
        for row in reader:
            if len(row) != 4:
                continue
            target, seed, metric, value = row
            rows[(target, seed)][metric] = value
    return rows


def grid_values(m, prefix):
    """Collect grid rows like height_r0c2 -> {(r,c): float}."""
    out = {}
    for k, v in m.items():
        match = re.match(rf"^{prefix}_r(\d+)c(\d+)$", k)
        if match:
            try:
                out[(int(match.group(1)), int(match.group(2)))] = float(v)
            except ValueError:
                pass
    return out


def derive(m):
    """Add derived metrics (terrain grid + proximity handled per-metric)."""
    heights = grid_values(m, "height")
    if heights:
        vals = list(heights.values())
        m["terrain_relief"] = max(vals) - min(vals)
        deltas = []
        for (r, c), h in heights.items():
            for nr, nc in ((r + 1, c), (r, c + 1)):
                if (nr, nc) in heights:
                    deltas.append(abs(heights[(nr, nc)] - h))
        if deltas:
            m["terrain_grain"] = sum(deltas) / len(deltas)
    waters = grid_values(m, "water")
    if waters:
        m["water_fraction"] = sum(waters.values()) / len(waters)
    return m


def fnum(v, default=None):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def score_metric(metric, m, prof):
    """-> (points, display_value)"""
    name, direction, w = metric["name"], metric["direction"], metric["weight"]
    p = metric["params"]

    if direction == "tier":
        biome = m.get("spawn_biome", "")
        disp = biome or "-"
        if biome in prof["green"]:
            return w, disp
        if biome in prof["ok"]:
            return w / 2, disp
        return 0.0, disp

    if direction == "proximity":
        cap = float(p[0])
        a, b = p[1], p[2]
        coords = [fnum(m.get(f"structure_{a}_x")), fnum(m.get(f"structure_{a}_z")),
                  fnum(m.get(f"structure_{b}_x")), fnum(m.get(f"structure_{b}_z"))]
        if any(c is None for c in coords):
            return 0.0, "-"
        dist = abs(coords[0] - coords[2]) + abs(coords[1] - coords[3])
        return w * max(0.0, cap - dist) / cap, f"{dist:.0f}"

    v = fnum(m.get(name))
    if direction == "near":
        cap = float(p[0])
        if v is None or v < 0:
            return 0.0, "miss"
        return w * max(0.0, cap - v) / cap, f"{v:.0f}"
    if direction == "far":
        cap = float(p[0])
        if v is None or v < 0:
            return w, "miss(+)"  # bounded /locate miss = positively sparse
        return w * min(cap, v) / cap, f"{v:.0f}"
    if v is None:
        return 0.0, "-"
    if direction == "window":
        lo, hi, fall = float(p[0]), float(p[1]), float(p[2])
        if lo <= v <= hi:
            return w, f"{v:.2f}"
        outside = (lo - v) if v < lo else (v - hi)
        return w * max(0.0, 1 - outside / fall), f"{v:.2f}"
    if direction == "low":
        good, zero = float(p[0]), float(p[1])
        if v <= good:
            return w, f"{v:.2f}"
        if v >= zero:
            return 0.0, f"{v:.2f}"
        return w * (zero - v) / (zero - good), f"{v:.2f}"
    if direction == "high":
        zero, good = float(p[0]), float(p[1])
        if v >= good:
            return w, f"{v:.2f}"
        if v <= zero:
            return 0.0, f"{v:.2f}"
        return w * (v - zero) / (good - zero), f"{v:.2f}"
    sys.exit(f"unknown direction '{direction}' for metric {name}")


def score_target(measurements, prof, target):
    scored = []
    for (t, seed), m in measurements.items():
        if t != target:
            continue
        m = derive(dict(m))
        parts = {}
        total = 0.0
        for metric in prof["metrics"]:
            pts, disp = score_metric(metric, m, prof)
            parts[metric["name"]] = (pts, disp)
            total += pts
        scored.append((seed, total, parts, m))
    scored.sort(key=lambda s: -s[1])
    return scored


def emit_report(out, prof, prof_name, csv_path, sections, top_n):
    lines = [f"# Seed report — profile `{prof_name}`", ""]
    lines.append(f"Measurements: `{csv_path}` · scored at report time — re-run "
                 f"with another `--profile` without re-rolling.")
    lines.append("")
    for target, scored in sections:
        lines.append(f"## {target} — top {min(top_n, len(scored))} of {len(scored)} candidates")
        lines.append("")
        cols = [m["name"] for m in prof["metrics"]]
        header = "| Rank | Seed | Score | " + " | ".join(
            c.replace("structure_", "").replace("_dist", "") for c in cols) + " |"
        lines.append(header)
        lines.append("|" + "---|" * (3 + len(cols)))
        for rank, (seed, total, parts, _m) in enumerate(scored[:top_n], 1):
            cells = " | ".join(
                f"{parts[c][1]} ({parts[c][0]:.1f})" for c in cols)
            lines.append(f"| {rank} | `{seed}` | **{total:.2f}** | {cells} |")
        lines.append("")
    lines.append("Cell format: measured value (points). A HUMAN picks the "
                 "winners; nothing is applied automatically.")
    lines.append("")
    Path(out).write_text("\n".join(lines))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", required=True)
    ap.add_argument("--csv", default="seed-measurements.csv")
    ap.add_argument("--target", default="world")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--out")
    args = ap.parse_args()

    prof = load_profile(args.profile)
    measurements = load_measurements(args.csv)
    prof_slug = Path(args.profile).stem

    if args.target == "all":
        targets = sorted({t for (t, _s) in measurements})
    else:
        targets = [args.target]

    sections = []
    for t in targets:
        scored = score_target(measurements, prof, t)
        if not scored:
            print(f"warning: no measurements for target '{t}'", file=sys.stderr)
            continue
        sections.append((t, scored))
    if not sections:
        sys.exit("nothing to report")

    out = args.out or (f"seed-report-{prof_slug}.md" if args.target in ("world", "all")
                       else f"seed-report-{prof_slug}-{args.target}.md")
    emit_report(out, prof, prof_slug, args.csv, sections, args.top)
    print(f"report written: {out}")
    for target, scored in sections:
        best = scored[0]
        print(f"  {target}: best seed {best[0]} ({best[1]:.2f}) of {len(scored)}")


if __name__ == "__main__":
    main()
