#!/usr/bin/env python3
"""Report biome bands that overlap, starve a dimension's natives, or slice the schema's axis.

Purpose:  A dimension's explicit bands are meant to partition its climate axis.
          Two entries claiming the same region both generate there and the split
          between them is no longer what the config says — which is how a
          hand-tuned layout quietly stops meaning anything.

          A biome may be banded more than once — vanilla's parameter table gives
          minecraft:plains a hypercube in every climate region it belongs to.
          What is never meant is a repeat entry carrying no new hypercube: an
          identical `parameters` block, or a bare-string entry beside a banded
          one. The biome list is deduplicated to a set before placement, so such
          an entry places nothing and states nothing.

Context:  The shipped configs had zero overlapping pairs. Two editing passes
          appending bands to the same files produced 393, none of which lint or
          the boot log flags: overlap is legal worldgen, just not intended.

          A bare-string biome keeps its point from the underlying source, but an
          explicit entry withdraws from native placement and wins wherever its
          stated axis matches — an unstated axis spans the whole range. So
          explicit bands covering an axis end to end leave the natives
          unreachable, and the dimension silently stops containing them.

          -2.0 is where the SCHEMA starts, not where a world does. A partition
          cut from the schema's floor in equal steps puts most of its bands
          outside the world — 17 dead bands of 25 in one dimension. The shape
          that actually ships is a run of equal steps FROM -2.0 that stops
          wherever the author ran out of biomes, with a wide catch-all tail:
          measured at 7f5c5e98, the_highland_crossing is 27 x 0.0889 and
          the_frozen_hearth 21 x 0.1143, both ending at +0.4003 to four
          decimals. Two band counts, one endpoint, is computed output.
          So the run is the signal and the tail is noise. check-band-reach.py
          measures the consequence and needs climate-axes.json to do it, so it
          can only report indicatively on a dimension nobody has measured yet.
          This check needs no measurement.

Not covered: whether a correctly fitted band is worth having. A band can pass
          all three arms here, pass check-band-reach.py, and still hand its
          biome one cell of 1257 — the_claymarsh's minecraft:swamp does exactly
          that. Encounterability is a different question and nothing gates it,
          so three green band checks do not mean the bands are good.

          A band can also be unable to WIN. `ParameterRange.getDistance` returns
          0 anywhere in [min, max], both ends included, and `SearchTree` replaces
          its incumbent only on a strictly SMALLER distance — so two bands
          sharing a boundary are both at distance zero there and neither can
          displace the other. Where a band's whole reachable territory is one
          such value it never outranks its rival anywhere, and whether it
          appears at all is settled outside the config.

          WHICH of the two wins is not knowable from here, and this check does
          not guess. `SearchTree.get` seeds each lookup with `previousResultNode`
          — a ThreadLocal holding what the PREVIOUS lookup returned — so a tied
          band that won last time is the incumbent and wins again; otherwise
          traversal order decides. Generation order, not authorship.

          Weirdness saturates at +/-1.0, which is why a partition cut on round
          numbers lands its boundaries exactly where the noise piles up.

Usage:    scripts/check-biome-bands.py            # exits 1 on any of the five arms

Gotchas:  - The tie arm needs climate-axes.json to know what a world reaches, so
            it is silent for a dimension nobody has measured. That is the one
            arm a new dimension can trip without anyone noticing until it is
            measured.
          - It reports the HAZARD, never which band dies. Naming a loser would be
            a guess: the winner depends on the previous lookup's result and on
            traversal order, neither of which a config can be read for.
          - A repeated biome id is NOT itself a fault, and gating on one would
            fail the shipped configs that band a biome at two points on an axis
            deliberately. Only a repeat with nothing new to say is reported.
          - Hypercubes intersect only if they intersect on EVERY axis, so two
            entries sharing a weirdness band but sitting in different `depth`
            bands do not overlap. Comparing one axis alone over-reports.
          - An unconstrained axis spans the whole -2..2 range, which is why an
            entry carrying a single axis overlaps almost everything.
          - Judging equal widths across a WHOLE chain has no discriminating
            power: a defective chain has a fat clamped tail exactly like a
            correct one. the_crucible's tail is 1.5215 against a 0.0694 body.
            Only the run from the floor separates them.
          - The run is found over every band on the axis, NOT within a
            signature group. A dimension carrying a per-band filter on a second
            axis puts each band in a group of one, and a grouped search sees no
            run at all.
          - WIDTH_ABS_TOL covers a partition written to one decimal place
            (0.05) and a hand-nudged boundary. Tightening it below either
            re-opens a false-negative class that is trivial to author.
          - `depth` is excluded: it is a surface/underground split at a fixed
            boundary, not a partition fitted to a range.
"""
import json, glob, sys
from itertools import combinations
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from consumer_path import ENV_VAR, optional_consumer_dir  # noqa: E402

AXES = ["temperature","humidity","continentalness","erosion","depth","weirdness"]
FULL = (-2.0, 2.0)

# A sliver narrower than this is not a real home for a biome.
FREE_EPSILON = 0.05
# Float slack for "starts at -2.0" and "these bands touch".
EDGE = 1e-6
# Where the schema's axis starts. A partition cut from here was cut from the
# schema rather than from the world.
AXIS_FLOOR = -2.0
# Absolute width slack. A width is the difference of two boundaries, so a
# partition written to one decimal place moves one by up to 0.1 — and one
# decimal place is the most natural way to write a partition by hand.
WIDTH_ABS_TOL = 0.11
# Proportional slack, for runs whose step is wide enough that 0.06 is tight.
WIDTH_REL_TOL = 0.02
# Two equal steps can be a deliberate split; three is a partition.
MIN_RUN = 3
# A surface/underground split, not a partition fitted to a measured range.
NOT_SLICED = {"depth"}

def rng(params, axis):
    v = params.get(axis)
    if v is None: return FULL
    if isinstance(v, (int, float)): return (float(v), float(v))
    return (float(v[0]), float(v[1]))

def overlaps(a, b):
    # Hypercubes intersect only if they intersect on EVERY axis.
    for ax in AXES:
        lo1, hi1 = rng(a, ax); lo2, hi2 = rng(b, ax)
        if hi1 <= lo2 or hi2 <= lo1:
            return False
    return True

def covered(spans):
    """Total width the given [lo,hi] spans cover, overlaps counted once."""
    out = 0.0; end = None
    for lo, hi in sorted(spans):
        if end is None or lo > end:
            out += hi - lo; end = hi
        elif hi > end:
            out += hi - end; end = hi
    return out

def bands_on(entries, axis):
    """Every distinct [lo, hi] stated on one axis, ascending.

    Deliberately not grouped by the other axes a band constrains: a dimension
    with a per-band filter on a second axis has one band per group, and a
    grouped search finds no run.
    """
    out = set()
    for _, params in entries:
        v = params.get(axis)
        if isinstance(v, list):
            out.add((float(v[0]), float(v[1])))
    return sorted(out)


def anchored_equal_run(bands):
    """(length, width) of the longest equal-step run from the axis floor.

    Returns (0, 0.0) when nothing reaches MIN_RUN. The walk takes its step from
    the first band and follows it, so a run that stops early and hands the rest
    of the axis to one wide catch-all is still caught by its body.
    """
    by_lo = {}
    for lo, hi in bands:
        by_lo.setdefault(round(lo, 6), []).append(hi)
    best, best_w = 0, 0.0
    for first_hi in by_lo.get(AXIS_FLOOR, []):
        step = first_hi - AXIS_FLOOR
        if step <= 0:
            continue
        tol = max(WIDTH_ABS_TOL, WIDTH_REL_TOL * step)
        run, cur = 1, first_hi
        while True:
            nxt = [h for h in by_lo.get(round(cur, 6), []) if abs((h - cur) - step) <= tol]
            if not nxt:
                break
            cur = nxt[0]
            run += 1
        if run > best:
            best, best_w = run, step
    return (best, best_w) if best >= MIN_RUN else (0, 0.0)


# The axis key each parameters axis is sampled under in climate-axes.json.
SAMPLE_KEY = {"temperature": "temp", "humidity": "humid", "continentalness": "cont",
              "erosion": "eros", "weirdness": "weird", "depth": "depth"}


def contains(outer, inner):
    """Whether `outer` covers `inner`, so its distance can never be the larger."""
    return outer[0] <= inner[0] and outer[1] >= inner[1]


def tie_hazards(entries, samples):
    """(id, axis, value, rival, cells) for every band that can never outrank.

    `ParameterRange.getDistance` returns 0 anywhere in [min, max], both ends
    included, and `SearchTree` replaces its incumbent only on a strictly smaller
    distance. So where a band's whole reachable territory on an axis is one
    value a rival also reaches at distance 0, and that rival's other axes cover
    this band's, the rival's total distance is <= this band's for every sample
    there: the band ties at best and can never take a cell on merit.

    Which of the two actually generates is NOT decided here and is not reported.
    `SearchTree.get` carries the previous lookup's result in as the incumbent,
    so the winner turns on generation order and traversal, not on the config.
    """
    out = []
    for axis, key in SAMPLE_KEY.items():
        drawn = samples.get(key)
        if not drawn:
            continue
        lo_s, hi_s = min(drawn), max(drawn)
        for bid, params in entries:
            if not isinstance(params.get(axis), list):
                continue
            lo, hi = rng(params, axis)
            # The only value of this axis the band can ever be asked about.
            point = max(lo, lo_s)
            if point != min(hi, hi_s):
                continue
            cells = sum(1 for x in drawn if x == point)
            if not cells:
                continue
            for rid, rival in entries:
                if rival is params:
                    continue
                r_lo, r_hi = rng(rival, axis)
                if r_lo <= point <= r_hi and all(
                        contains(rng(rival, a), rng(params, a))
                        for a in AXES if a != axis):
                    out.append((bid, axis, point, rid, cells))
                    break
    return out


def dead_repeats(arr):
    """(id, why) for every entry repeating a biome without adding a hypercube.

    Two bands for one biome are legitimate. A second entry states nothing new
    when it carries an identical `parameters` block, or when it is a bare
    string beside a banded entry — the list is deduplicated to a set before
    placement, so that entry cannot reach the layout at all.
    """
    seen_params = {}
    banded = set()
    plain = set()
    out = []
    for e in arr:
        if isinstance(e, str):
            plain.add(e.strip())
        elif isinstance(e, dict) and isinstance(e.get("id"), str):
            bid = e["id"].strip()
            params = e.get("parameters")
            if not isinstance(params, dict):
                plain.add(bid)
                continue
            banded.add(bid)
            key = json.dumps(params, sort_keys=True)
            if key in seen_params.setdefault(bid, set()):
                out.append((bid, "a second band with identical parameters"))
            seen_params[bid].add(key)
    for bid in sorted(plain & banded):
        out.append((bid, "listed bare as well as banded, so the bare entry is dead"))
    return out


def measured():
    """Per-dimension sample grids from climate-axes.json; empty when absent."""
    path = Path("config/custom-dimensions/climate-axes.json")
    if not path.is_file():
        return {}
    return json.loads(path.read_text()).get("perDimension", {})


def main():
    total_files = total_pairs = total_starved = total_sliced = total_dead = 0
    total_tied = 0
    grids = measured()
    consumer = optional_consumer_dir()
    sources = [("platform", "config/custom-dimensions/dimensions/*.json")]
    if consumer:
        sources.append(("overlay",
                        str(consumer / "overlay/config/custom-dimensions/dimensions/*.json")))

    scanned = 0
    for where, pat in sources:
        for f in sorted(glob.glob(pat)):
            scanned += 1
            doc = json.load(open(f)); cfg = doc.get("overrides", doc)
            arr = cfg.get("biomes") or []
            ex = [(e["id"], e["parameters"]) for e in arr
                  if isinstance(e, dict) and isinstance(e.get("parameters"), dict)]
            natives = [e for e in arr if isinstance(e, str)]
            name = f.split('/')[-1][:-5]

            drawn = (grids.get(name) or {}).get("samples") or {}
            tied = tie_hazards(ex, drawn) if drawn else []
            if tied:
                total_files += 1; total_tied += len(tied)
                for bid, axis, point, rid, cells in tied:
                    print(f"  {name:26s} [{where:8s}] {bid} can never outrank {rid}: the only"
                          f" {axis} it reaches is {point}, where both are exactly as close"
                          f" ({cells} cells at stake)")
                print(f"      a range contains both its endpoints, so a shared boundary is a tie"
                      f" that generation order settles, not a split this config decides —"
                      f" move the boundary off {point}, or widen the band so it can win outright")

            dead = dead_repeats(arr)
            if dead:
                total_files += 1; total_dead += len(dead)
                for bid, why in dead:
                    print(f"  {name:26s} [{where:8s}] {bid} is repeated: {why}")
                print(f"      delete the repeat, or give it a parameters block that"
                      f" states a different region")

            bad = [(a[0], b[0]) for a, b in combinations(ex, 2) if overlaps(a[1], b[1])]
            if bad:
                total_files += 1; total_pairs += len(bad)
                print(f"  {name:26s} [{where:8s}] {len(bad):2d} overlapping pair(s) of {len(ex)} explicit")

            # Natives are only reachable where no explicit band claims the axis.
            for ax in AXES:
                stated = [rng(p, ax) for _, p in ex if p.get(ax) is not None]
                if not natives or not stated:
                    continue
                free = 4.0 - covered(stated)
                if free <= FREE_EPSILON:
                    total_files += 1; total_starved += len(natives)
                    print(f"  {name:26s} [{where:8s}] {len(natives):2d} native biome(s) unreachable"
                          f" — explicit bands cover {ax} end to end")
                    for n in natives[:4]:
                        print(f"      {n}")
                    break

            # -2.0 is where the schema starts, not where this world does.
            for ax in AXES:
                if ax in NOT_SLICED:
                    continue
                run, step = anchored_equal_run(bands_on(ex, ax))
                if not run:
                    continue
                total_files += 1; total_sliced += run
                print(f"  {name:26s} [{where:8s}] {run:2d} band(s) step {ax} by a constant"
                      f" {step:.4f} from {AXIS_FLOOR:+.1f} — cut from the schema's floor, not"
                      f" from the range this world crosses")
                print(f"      fit the boundaries to the dimension's own measured range in"
                      f" config/custom-dimensions/climate-axes.json instead")

    print(f"\nscanned {scanned} dimension file(s) "
          f"({'platform + overlay' if consumer else 'platform only'})")
    if not consumer:
        print(f"  overlay not checked - set {ENV_VAR} to include a consumer repo")
    print(f"{total_files} files, {total_pairs} overlapping pairs, {total_starved} starved natives, "
          f"{total_sliced} schema-stepped bands, {total_dead} dead repeats, "
          f"{total_tied} bands that can never outrank a rival")
    if scanned == 0:
        sys.exit("nothing scanned - run this from the platform repo root")
    return 1 if (total_pairs or total_starved or total_sliced or total_dead
                 or total_tied) else 0

if __name__ == "__main__":
    sys.exit(main())
