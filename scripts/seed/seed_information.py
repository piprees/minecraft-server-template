#!/usr/bin/env python3
"""seed_information.py — what a dimension's criteria cost, in bits of search.

    Fi = -log2(a / b)

      b   unique seeds DRAWN for this dimension
      a   how many of them satisfied the criteria

One bit means half the seeds qualify; ten bits means one in a thousand. It is
a statement about the CONFIG, not about any candidate: a dimension whose ask
costs 3 bits is easy to satisfy, one that costs 18 needs a quarter of a
million draws, and one that costs infinity is asking for something that does
not exist.

WHY THIS AND NOT THE SCORE
--------------------------
A score answers "how good is this seed". It cannot answer "is this dimension's
ask reasonable", because a criterion nothing can satisfy shows up as the same
small constant deduction on every candidate — invisible in the ranking, and
indistinguishable from a criterion that is merely strict. Bits separate the
two: a criterion met by 1 seed in 400 costs 8.6 bits and is doing real work; a
criterion met by 0 of 400 costs at least 9.6 and is doing none.

The denominator is free. `.seedtest/candidates/{slug}.json` already banks
`candidates` and `rejected`, rejects are kept forever precisely so they are
never re-drawn, and the union is therefore the exact count of distinct seeds
ever tried — monotonic across sessions, no new plumbing.

THE FOUR THINGS THAT WILL BITE, AND WHAT IS DONE ABOUT THEM
-----------------------------------------------------------
1. `a = 0` is infinite. Reported as a LOWER BOUND ("> 9.6 bits, 0 of 412")
   rather than inf or an error. The bound is the finding.

2. The denominator is conditioned by the spawn gate. `fast_roller.tier2_measure`
   rejects a seed whose spawn filter matches nowhere in its 768-block window,
   and those seeds carry no other measurement. Counting only `candidates`
   silently divides out the spawn filter's cost — which for a fussy dimension
   is most of the cost. So the total is decomposed by the chain rule:

       Fi_total = Fi_spawn + Fi_rest_given_spawn

   `Fi_spawn` is what the spawn gate costs; `Fi_rest_given_spawn` is what
   everything else costs among the seeds that got past it. They add exactly,
   which is also a self-check on the arithmetic.

3. Small `a` is noisy. Rates are Laplace-smoothed, `(a + 0.5) / (b + 1)`, and
   `b` is printed beside every figure — 3 bits from 8 draws is not 3 bits.

4. Per-criterion bits are NOT independent and must not be read as if they
   were. `sum(Fi_i)` against `Fi_joint` is the useful comparison: roughly
   equal means the criteria are independent, and a joint much larger than the
   sum means they conflict with each other. That comparison is reported.

Usage:
    ./scripts/seed/seed_information.py --config <config/custom-dimensions> \
        --seedtest <consumer/.seedtest> [--dims a,b,c] [--format csv]
"""
import argparse
import csv
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import census_scoring  # noqa: E402
import noise_placement  # noqa: E402
from dimension_profiles import (  # noqa: E402
    build_profile, load_config, load_difficulty, noise_fingerprint, rollable,
)

#: Laplace smoothing, so a=0 and a=1 are distinguishable and neither is a
#: division by zero.
SMOOTH_A = 0.5
SMOOTH_B = 1.0

#: A want counts as MET at full census credit. census_want_score caps at 1.0,
#: and band_mass's pro-rata bin arithmetic can land a few ulps under.
FULL_CREDIT = 1.0 - 1e-9


def bits(a, b):
    """(bits, is_lower_bound) for `a` successes out of `b` draws.

    `is_lower_bound` is True when nothing succeeded: the true cost is
    unbounded above, and the figure returned is the most that `b` draws can
    evidence. Report it as "> x", never as x.
    """
    if b <= 0:
        return 0.0, False
    rate = (a + SMOOTH_A) / (b + SMOOTH_B)
    return -math.log2(rate), a <= 0


def _terrain_ok(key, value, band):
    lo, hi = band
    return lo <= value <= hi


def _terrain_metrics(rows):
    """relief/grain/water from the banked 3x3 grid.

    Duplicated from score-dimensions.terrain_metrics rather than imported:
    that file's name is not a legal module identifier, so importing it costs
    an importlib dance for eight lines of arithmetic.
    """
    heights, waters, hmap = [], [], {}
    for metric, value in rows.items():
        if metric.startswith("height_r"):
            hmap[(int(metric[8]), int(metric[10]))] = float(value)
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
    return relief, grain, water


def candidate_criteria(profile, rows, census, group_of, group_settings):
    """{criterion name: met?} for one measured candidate."""
    out = {}
    radius = float(profile.get("radius") or 0) or 1.0

    # Spawn identity. Partial proximity credit is NOT "met" — the criterion
    # is "you spawn in one of these biomes".
    out["spawn"] = rows.get("spawn_biome") in set(profile.get("namesake") or [])

    # Terrain: inside the target band on all three axes, which is exactly the
    # severity-0 condition the detail panel paints green.
    terrain = profile.get("terrain") or {}
    if terrain:
        relief, grain, water = _terrain_metrics(rows)
        for key, value in (("relief", relief), ("grain", grain), ("water", water)):
            band = terrain.get(key)
            if band:
                out["terrain:" + key] = _terrain_ok(key, value, band)

    # Variety: each requested biome present inside the playable radius.
    survey = rows.get("_biome_survey") or {}
    for biome in profile.get("variety_biomes") or []:
        if biome in set(profile.get("namesake") or []):
            continue
        d = None
        if survey.get(biome):
            d = float(survey[biome][0])
        else:
            v = rows.get("biome_{}_dist".format(biome))
            if v is not None:
                d = float(v)
        out["biome:" + biome] = d is not None and 0 <= d <= radius

    # Structures, scored the way score_candidate scores them: through the
    # group's census where noise owns the set, positionally otherwise.
    # Tag wants (#ns:tag) are skipped — not exactly measurable.
    groups = (census or {}).get("groups") or {}
    census_positions = rows.get("_census_positions") or {}
    forced = {f.get("structure") for f in (profile.get("forced_structures") or [])}
    try:
        scx = int(float(rows.get("spawn_x") or 0)) >> 4
        scz = int(float(rows.get("spawn_z") or 0)) >> 4
    except (TypeError, ValueError):
        scx, scz = 0, 0
    for sname, sid, spec, kind in profile.get("battery") or []:
        group = group_of(sid) if groups else None
        label = ("shun:" if kind == "shun" else "want:") + sname
        if group is not None and sid.lstrip("#") not in forced:
            if sid.startswith("#"):
                continue
            entry = groups.get(group)
            by_struct = (entry or {}).get("byStructure") or {}
            clean_sid = sid.lstrip("#")
            pool_ids = set(by_struct.keys())
            in_pool = clean_sid in pool_ids or not pool_ids
            positions = census_positions.get(group) or []
            if kind == "shun":
                threshold = float(spec) if isinstance(spec, (int, float)) else radius
                out[label] = census_scoring.census_shun_score(
                    clean_sid, threshold, positions,
                    spawn_cx=scx, spawn_cz=scz, in_pool=in_pool) >= 1.0
            else:
                out[label] = census_scoring.census_want_score(
                    clean_sid, spec, positions,
                    spawn_cx=scx, spawn_cz=scz, in_pool=in_pool) >= FULL_CREDIT
            continue
        v = rows.get("structure_{}_dist".format(sname))
        d = float(v) if v is not None else -1.0
        if kind == "shun":
            threshold = float(spec) if isinstance(spec, (int, float)) else radius
            out[label] = not (0 <= d < threshold)
        else:
            lo, hi = spec
            out[label] = 0 <= d and lo <= d <= min(hi, radius)
    return out


def dimension_report(name, entry, profile, store, type_defaults, group_of):
    """Bits for one dimension: the joint cost and the per-criterion breakdown."""
    drawn = len(store["candidates"]) + len(store["rejected"])
    measured = [(seed, c) for seed, c in store["candidates"].items()
                if "errors" in (c.get("measurements") or {})]
    if not drawn:
        return None

    fp = noise_fingerprint(entry)
    settings = (noise_placement.resolve_groups(entry, type_defaults)
                if type_defaults else {})

    per_criterion = {}
    joint_met = 0
    import candidates as cmod
    seedtest = cmod.bank_root()
    for _seed, cand in measured:
        rows = dict(cand.get("measurements") or {})
        survey = cand.get("biome_survey")
        if isinstance(survey, dict) and survey.get("biomes"):
            rows["_biome_survey"] = survey["biomes"]
        census = cand.get("noiseCensus")
        if census is not None and census.get("fp") != fp:
            census = None
        if seedtest and census:
            rows["_census_positions"] = noise_placement.load_census_positions(
                seedtest, name, _seed)
        met = candidate_criteria(profile, rows, census, group_of, settings)
        if met and all(met.values()):
            joint_met += 1
        for k, ok in met.items():
            hit, total = per_criterion.get(k, (0, 0))
            per_criterion[k] = (hit + int(ok), total + 1)

    # Chain rule: the spawn gate costs what it costs, and everything else is
    # measured among the survivors. The two add to the total exactly.
    fi_spawn, spawn_bound = bits(len(measured), drawn)
    fi_rest, rest_bound = bits(joint_met, len(measured))
    return {
        "dimension": name,
        "drawn": drawn,
        "measured": len(measured),
        "met": joint_met,
        "fi_spawn": fi_spawn,
        "fi_rest": fi_rest,
        "fi_total": fi_spawn + fi_rest,
        "lower_bound": spawn_bound or rest_bound,
        "criteria": {k: (h, t) + bits(h, t) for k, (h, t) in per_criterion.items()},
    }


def _fmt(value, lower_bound):
    return ("> " if lower_bound else "  ") + "{:5.1f}".format(value)


def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", required=True)
    ap.add_argument("--seedtest", required=True)
    ap.add_argument("--dims", help="comma-separated subset")
    ap.add_argument("--top", type=int, default=6,
                    help="costliest criteria to list per dimension (0 = none)")
    ap.add_argument("--format", choices=("text", "csv"), default="text")
    args = ap.parse_args()

    import candidates as cmod
    cmod.set_bank_root(args.seedtest)
    config = load_config(args.config)
    difficulty = load_difficulty(args.config)
    type_defaults = noise_placement.load_type_defaults(args.config)
    cdir = cmod.candidates_dir(Path(args.config))

    from structure_placement import load_structure_sets
    set_to_group = {sid: meta.get("group") for sid, meta
                    in noise_placement.load_structure_groups(args.config).items()}
    struct_to_set = {}
    sets_dir = Path(args.seedtest) / ".structure_sets"
    if sets_dir.is_dir():
        for set_id, cfg in load_structure_sets(str(sets_dir)).items():
            known = set_id in set_to_group
            for s in cfg["structures"]:
                if s["id"] not in struct_to_set or (
                        known and struct_to_set[s["id"]] not in set_to_group):
                    struct_to_set[s["id"]] = set_id

    def group_of(sid):
        import sweep_structure_wants as sw
        return sw.group_for(sid, struct_to_set, set_to_group)

    targets = {w["name"]: w for w in config.get("worlds", [])}
    targets.update({d["name"]: d for d in config["dimensions"] if rollable(d)})
    if args.dims:
        wanted = {d.strip() for d in args.dims.split(",")}
        targets = {k: v for k, v in targets.items() if k in wanted}

    reports = []
    for name, entry in targets.items():
        store = cmod.load_store(cdir / f"{name}.json")
        if not store["candidates"] and not store["rejected"]:
            continue
        rep = dimension_report(name, entry, build_profile(entry, config, difficulty),
                               store, type_defaults, group_of)
        if rep:
            reports.append(rep)
    reports.sort(key=lambda r: -r["fi_total"])

    if args.format == "csv":
        w = csv.writer(sys.stdout)
        w.writerow(["dimension", "criterion", "met", "of", "bits", "lower_bound"])
        for r in reports:
            w.writerow([r["dimension"], "(joint)", r["met"], r["measured"],
                        "{:.3f}".format(r["fi_total"]), int(r["lower_bound"])])
            for k, (h, t, b, lb) in sorted(r["criteria"].items(), key=lambda kv: -kv[1][2]):
                w.writerow([r["dimension"], k, h, t, "{:.3f}".format(b), int(lb)])
        return 0

    print("Seed information — what each dimension's criteria cost, in bits\n")
    print("{:28} {:>6} {:>6} {:>5} {:>5}  {:>7} {:>7}".format(
        "dimension", "drawn", "past", "crit", "DEAD", "spawn", "TOTAL"))
    print("-" * 78)
    for r in reports:
        dead = sum(1 for v in r["criteria"].values() if v[3])
        print("{:28} {:>6} {:>6} {:>5} {:>5}  {} {}".format(
            r["dimension"], r["drawn"], r["measured"], len(r["criteria"]), dead,
            _fmt(r["fi_spawn"], False), _fmt(r["fi_total"], r["lower_bound"])))
    print("\n'past' = seeds that cleared the spawn filter. 'DEAD' = criteria NO "
          "banked candidate\nhas ever met — those are the constant deductions, "
          "and they are the actionable\nnumber here: with twenty-odd criteria a "
          "strict conjunction is zero almost\neverywhere, so TOTAL is a lower "
          "bound ('>') on nearly every dimension and\ndiscriminates between "
          "none of them. 'spawn' is what the spawn filter alone costs.")

    if args.top:
        print("\n" + "=" * 78)
        print("Costliest criteria per dimension (bits to satisfy that one alone)")
        for r in reports:
            items = sorted(r["criteria"].items(), key=lambda kv: -kv[1][2])[:args.top]
            if not items or items[0][1][2] <= 0.05:
                continue
            total_alone = sum(v[2] for v in r["criteria"].values())
            print("\n{}  — joint {:.1f} bits, criteria summed {:.1f}".format(
                r["dimension"], r["fi_rest"], total_alone))
            # Independence check. Roughly equal means the criteria are
            # independent; a joint far above the sum means they fight.
            if r["lower_bound"]:
                print("  no candidate met every criterion, so the joint is a "
                      "lower bound and cannot be compared with the sum")
            elif total_alone > 0.05:
                ratio = r["fi_rest"] / total_alone
                print("  {}".format(
                    "criteria look independent" if 0.7 <= ratio <= 1.4 else
                    "criteria CONFLICT — the joint costs {:.1f}x the sum".format(ratio)
                    if ratio > 1.4 else
                    "criteria overlap — satisfying one tends to satisfy others"))
            for k, (h, t, b, lb) in items:
                print("  {}{:6.1f} bits  {:>5}/{:<5} {}".format(
                    ">" if lb else " ", b, h, t, k))
    return 0


if __name__ == "__main__":
    sys.exit(main())
