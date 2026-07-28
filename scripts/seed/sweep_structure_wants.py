#!/usr/bin/env python3
"""sweep_structure_wants.py — find structure wants a dimension can never satisfy.

READ ONLY. It changes nothing: not the scoring, not a dimension config, not
the candidate bank. It prints what it finds and exits.

    ./scripts/seed/sweep_structure_wants.py \
        --config <config/custom-dimensions> --seedtest <consumer/.seedtest>
    ... --format csv          # one row per finding, for a spreadsheet
    ... --dims a,b,c          # a subset
    ... --all                 # include dimensions with no findings

WHY THIS EXISTS
---------------
`build_profile` drops the whole battery for a dimension that can place
NOTHING organic (structureDensity "none", structures.mode "none", or mode
"allow" with an empty list) and redistributes the structures weight, because
three shipped dimensions were losing the full structures weight on every
candidate for structures they had deliberately switched off. A constant
penalty on every candidate does not merely deflate a score — it contributes
nothing to the RANKING while capping the ceiling, so it hides better seeds.

This sweep is the softer version of that trap, which `places_nothing` cannot
see: a dimension that CAN place things, asking for one that will never turn
up inside its own border. Vanilla village spacing is 34 chunks (544 blocks),
so a 512-block pocket dimension asking for a Village is asking for something
it will essentially never get — and pays for it on every candidate.

WHAT IT CHECKS, per want in the resolved battery
------------------------------------------------
  BAND-OUTSIDE-BORDER  the wanted range starts beyond the playable radius, so
                       want_score's `hi = min(hi, radius)` collapses it to an
                       empty or inverted window.
  SET-FILTERED-OUT     structures.mode/list drops the organic set the want
                       resolves to (structure_placement.mode_drops), so the
                       roller measures it as absent every time.
  GROUP-SUPPRESSED     noise owns the set, but this dimension's world type,
                       density or difficulty shift never enables that group
                       (noise_placement.resolve_groups), so its census entry
                       is always missing.
  CURVE-BELOW-THRESHOLD
                       the group IS enabled, but its radial curve never rises
                       above its noise profile's threshold anywhere in the
                       want's band, so no seed can put anything there. This is
                       the big one: the shipped `dungeons` and `endgame` curves
                       start [0.0, 0.0, 0.1, 0.3, ...] and `settlements` ends
                       [..., 0.1, 0.0, 0.0, 0.0], and a `sparse` profile needs
                       weight > 0.85 — so a near_spawn dungeon want, or a
                       near_border village want, is asking for something the
                       placement rules forbid outright.
  SPARSE-FOR-BORDER    a grid-placed set whose spacing means fewer than one
                       expected placement inside the playable radius.
  SET-NOT-EXTRACTED    the want resolves to no structure set at all in
                       <seedtest>/.structure_sets, and to no noise group, so
                       the roller has nothing to measure and banks -1.

The expected-count maths for a RandomSpread set is the honest one: exactly
one placement per spacing x spacing chunk region, thinned by `frequency`, so
inside a radius R blocks you expect
    pi * R^2 / (spacing * 16)^2 * frequency
placements. Below 1.0 the want is a coin-flip at best; below ~0.25 it is a
standing deduction. Both thresholds are printed, never applied.

Everything it reports is a QUESTION for a human. Some of these are
deliberate — an author may want a structure that is only reachable by
flying, or may be relying on a forced placement. Nothing here is
automatically wrong; it is a list of places where the score says "you did
not get what you asked for" and the config says the ask was impossible.

--bank: THE EMPIRICAL PASS (recommended)
----------------------------------------
Since noise placement landed, the config-only checks above cover a
shrinking share of the ground: a want no longer resolves to a grid whose
spacing you can divide by a border, it resolves to a share of a GROUP's
noise field. So `--bank` asks the question the other way round, from the
candidate store: replay `census_scoring.census_want_score` for every want
against every banked candidate's census, and report the wants whose BEST
score across the whole bank is still capped.

That is the exact shape of the bug this exists to catch. A want that scores
0.25 on all 400 candidates is not discriminating between them — it is a
fixed deduction, invisible to the ranking, lowering the ceiling. It costs
nothing to compute: the censuses are already banked (keyed on the noise
fingerprint) by the scoring pipeline.
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
    build_profile, load_config, load_difficulty, rollable,
)
from structure_placement import load_structure_sets, mode_drops  # noqa: E402

#: census_want_score's band_mass sums pro-rata bin overlaps, so a want that
#: is fully satisfied can land a few ulps under 1.0. Compare against this,
#: not against 1.0, or every such want is reported as "best score 1.00".
FULL_CREDIT = 1.0 - 1e-9

#: Expected placements inside the border below which a want is flagged.
EXPECTED_UNLIKELY = 1.0
#: ...and below which it is effectively a constant deduction.
EXPECTED_HOPELESS = 0.25

SEVERITY = {
    "NEVER-SATISFIED": 3,
    "ALWAYS-WRONG-RING": 3,
    "NEVER-FULL": 1,
    "BAND-OUTSIDE-BORDER": 2,
    "SET-FILTERED-OUT": 2,
    "GROUP-SUPPRESSED": 2,
    "SET-NOT-EXTRACTED": 2,
    "CURVE-BELOW-THRESHOLD": 3,
    "SPARSE-FOR-BORDER": 1,
}


def resolve_set(sid, struct_sets, struct_to_sets):
    """The structure set a battery entry resolves to, or None.

    MIRRORS fast_roller._resolve_struct_set — this must agree with what the
    roller actually measured, or the sweep describes a different world.
    """
    clean = sid.lstrip("#")
    if clean in struct_to_sets:
        return struct_sets[struct_to_sets[clean][0]]
    if sid.startswith("#"):
        tag_path = clean.split(":")[-1] if ":" in clean else clean
        for cfg in struct_sets.values():
            for s in cfg["structures"]:
                if tag_path in s["id"]:
                    return cfg
    if clean in struct_sets:
        return struct_sets[clean]
    return None


def group_for(sid, struct_to_set, set_to_group):
    """MIRRORS score-dimensions.battery_group_for."""
    clean = sid.lstrip("#")
    set_id = struct_to_set.get(clean)
    if set_id is None and sid.startswith("#"):
        tag_path = clean.split(":")[-1] if ":" in clean else clean
        for struct_id, candidate_set in struct_to_set.items():
            if tag_path in struct_id:
                set_id = candidate_set
                break
    if set_id is None and clean in set_to_group:
        set_id = clean
    if set_id is None:
        return None
    return set_to_group.get(set_id)


def max_curve_weight(radial, lo, hi, radius):
    """The largest radial weight anywhere in a block band.

    THIS IS THE CHECK THAT MATTERS, and it is exact rather than heuristic.
    NoiseFieldIndex's eligibility pass is

        if weight <= threshold: continue     # before sampling anything
        ...
        if noise * weight > threshold: eligible

    and `sample` is clamped to [0, 1]. So a chunk can host a placement only
    where `weight > threshold` — a band whose entire radial weight sits at or
    below its profile's threshold generates NOTHING, whatever the seed.

    The curve is piecewise-linear through `len(radial)` control points spanning
    [0, radius] (radial_weight puts point i at fraction i/(n-1)), so the
    maximum over an interval is the max of the two endpoints and every control
    point strictly inside it.
    """
    if not radial:
        return 1.0  # no curve means a flat 1.0 everywhere
    if radius <= 0:
        return radial[0]
    weights = [noise_placement.radial_weight(radial, lo, radius),
               noise_placement.radial_weight(radial, hi, radius)]
    last = len(radial) - 1
    for i, w in enumerate(radial):
        d = (i / last) * radius if last else 0.0
        if lo < d < hi:
            weights.append(w)
    return max(weights)


def effective_placement(set_cfg, profile):
    """(spacing, separation, frequency) after every override the roller applies.

    MIRRORS fast_roller.tier1_score's override block, including the
    exitShrines frequency raise and derived spacing.
    """
    spacing = set_cfg["spacing"]
    separation = set_cfg["separation"]
    frequency = set_cfg.get("frequency", 1.0)
    explicit = False
    ov = (profile.get("spacing_overrides") or {}).get(set_cfg.get("id"))
    if isinstance(ov, dict):
        new_spacing = ov.get("spacing", spacing)
        new_sep = ov.get("separation", separation)
        if isinstance(new_spacing, int) and isinstance(new_sep, int) \
                and 2 <= new_spacing <= 4096 and 0 <= new_sep < new_spacing:
            spacing, separation, explicit = new_spacing, new_sep, True
    if set_cfg.get("id") == "adventure:exit_shrines" and profile.get("exit_shrines"):
        frequency = 1.0
        if not explicit:
            spacing = max(12, min(48, int(profile.get("player_border", 8192)) // 32))
            separation = spacing // 2
    return spacing, separation, frequency


def expected_in_radius(spacing, frequency, radius_blocks):
    """One placement per spacing x spacing chunk region, thinned by frequency."""
    region = spacing * 16.0
    if region <= 0:
        return 0.0
    return math.pi * radius_blocks * radius_blocks / (region * region) * frequency


def sweep_dimension(name, entry, profile, struct_sets, struct_to_sets,
                    struct_to_set, set_to_group, type_defaults):
    """-> list of findings for one dimension."""
    findings = []
    radius = float(profile["radius"])
    forced_ids = {f.get("structure")
                  for f in (profile.get("forced_structures") or [])}
    resolved_groups = (noise_placement.resolve_groups(entry, type_defaults)
                       if type_defaults else {})

    for sname, sid, spec, kind in profile["battery"]:
        if kind != "want":
            continue
        lo, hi = spec
        hi_eff = min(hi, radius)
        base = {"dimension": name, "want": sname, "structure": sid,
                "radius": int(radius), "band": "{:.0f}-{:.0f}".format(lo, hi)}

        if lo >= radius:
            findings.append(dict(base, code="BAND-OUTSIDE-BORDER", detail=(
                "wants {:.0f}-{:.0f} blocks but the playable radius is {:.0f}"
                .format(lo, hi, radius))))
            continue

        # A forced placement is a constant the author put there on purpose.
        if sid.lstrip("#") in forced_ids:
            continue

        group = group_for(sid, struct_to_set, set_to_group)
        if group is not None:
            if group not in resolved_groups:
                findings.append(dict(base, code="GROUP-SUPPRESSED", detail=(
                    "noise owns this set through the '{}' group, which this "
                    "dimension never enables".format(group))))
                continue
            settings = resolved_groups[group]
            radial = settings["radial"]
            profile_obj = settings["profile"]
            peak = max_curve_weight(radial, lo, hi_eff, radius)
            if peak <= profile_obj.threshold:
                findings.append(dict(
                    base, code="CURVE-BELOW-THRESHOLD",
                    expected="{:.2f}".format(peak), detail=(
                        "the '{}' group peaks at radial weight {:.2f} across "
                        "{:.0f}-{:.0f}, and its '{}' profile needs more than "
                        "{:.2f} — nothing can generate there on any seed"
                        .format(group, peak, lo, hi_eff, profile_obj.id,
                                profile_obj.threshold))))
            continue

        set_cfg = resolve_set(sid, struct_sets, struct_to_sets)
        if set_cfg is not None and mode_drops(set_cfg.get("id"), profile):
            findings.append(dict(base, code="SET-FILTERED-OUT", detail=(
                "structures.mode '{}' drops {}".format(
                    profile.get("structures_mode"), set_cfg.get("id")))))
            continue
        if set_cfg is None:
            findings.append(dict(base, code="SET-NOT-EXTRACTED", detail=(
                "no structure set in .structure_sets and no noise group — the "
                "roller banks -1 for this want on every seed")))
            continue

        spacing, separation, frequency = effective_placement(set_cfg, profile)
        expected = expected_in_radius(spacing, frequency, radius)
        if expected < EXPECTED_UNLIKELY:
            findings.append(dict(
                base, code="SPARSE-FOR-BORDER", detail=(
                    "{} is spacing {} / separation {}{} = {:.0f} blocks "
                    "between regions; {:.2f} expected inside a {:.0f} radius"
                    .format(set_cfg.get("id"), spacing, separation,
                            "" if frequency >= 1.0
                            else " at frequency {:g}".format(frequency),
                            spacing * 16, expected, radius)),
                expected="{:.2f}".format(expected)))
    return findings


def bank_pass(name, entry, profile, struct_to_set, set_to_group,
              type_defaults, cdir):
    """Replay every want against every banked census. -> list of findings.

    MIRRORS score-dimensions.ensure_censuses' cache contract: the summary is
    stored per candidate under `noiseCensus` keyed on the dimension's noise
    fingerprint, and the per-dimension radial curve is re-attached from the
    config rather than repeated on disk.
    """
    import candidates as cmod
    from dimension_profiles import noise_fingerprint

    fp = noise_fingerprint(entry)
    if fp is None:
        return []
    store = cmod.load_store(Path(cdir) / "{}.json".format(name))
    censuses = [c["noiseCensus"] for c in store["candidates"].values()
                if (c.get("noiseCensus") or {}).get("fp") == fp]
    if not censuses:
        return []
    settings = noise_placement.resolve_groups(entry, type_defaults)
    radius = float(profile["radius"])
    forced_ids = {f.get("structure")
                  for f in (profile.get("forced_structures") or [])}

    findings = []
    for sname, sid, spec, kind in profile["battery"]:
        if kind != "want" or sid.lstrip("#") in forced_ids:
            continue
        group = group_for(sid, struct_to_set, set_to_group)
        if group is None:
            continue
        lo, hi = spec
        hi_eff = min(hi, radius)
        best = 0.0
        for summary in censuses:
            raw = (summary.get("groups") or {}).get(group)
            if raw is None:
                continue
            merged = dict(raw)
            merged["radial"] = (settings.get(group) or {}).get("radial")
            best = max(best, census_scoring.census_want_score(
                merged, (lo, hi_eff), summary.get("radiusChunks") or 0))
            if best >= FULL_CREDIT:
                break
        if best >= FULL_CREDIT:
            continue
        code = ("NEVER-SATISFIED" if best <= 0.0 else
                "ALWAYS-WRONG-RING" if best <= census_scoring.WANT_WRONG_RING_SCORE
                else "NEVER-FULL")
        findings.append({
            "dimension": name, "want": sname, "structure": sid,
            "radius": int(radius), "band": "{:.0f}-{:.0f}".format(lo, hi_eff),
            "code": code, "expected": "{:.2f}".format(best),
            "detail": ("best score {:.2f} of 1.00 across {} banked candidate(s) "
                       "— the '{}' group never puts {} placement(s) in "
                       "{:.0f}-{:.0f}".format(
                           best, len(censuses), group,
                           census_scoring.WANT_BAND_TARGET, lo, hi_eff))})
    return findings


def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", required=True,
                    help="config/custom-dimensions/ directory")
    ap.add_argument("--seedtest", required=True,
                    help="<consumer>/.seedtest — supplies .structure_sets")
    ap.add_argument("--dims", help="comma-separated subset")
    ap.add_argument("--bank", action="store_true",
                    help="also replay every want against every banked census "
                         "— the empirical pass, and the one that finds the "
                         "constant deductions noise placement introduced")
    ap.add_argument("--all", action="store_true",
                    help="list clean dimensions too")
    ap.add_argument("--format", choices=("text", "csv"), default="text")
    args = ap.parse_args()

    config = load_config(args.config)
    difficulty = load_difficulty(args.config)
    type_defaults = noise_placement.load_type_defaults(args.config)
    set_to_group = {sid: meta.get("group") for sid, meta
                    in noise_placement.load_structure_groups(args.config).items()}

    sets_dir = Path(args.seedtest) / ".structure_sets"
    if not sets_dir.is_dir():
        sys.exit("no {} — run a roll (or ./dev seed-viewer) once to warm up"
                 .format(sets_dir))
    struct_sets = load_structure_sets(str(sets_dir))
    struct_to_sets, struct_to_set = {}, {}
    for set_id, cfg in struct_sets.items():
        known = set_id in set_to_group
        for s in cfg["structures"]:
            struct_to_sets.setdefault(s["id"], []).append(set_id)
            # A classified set id always beats an unclassified one — same rule
            # as score-dimensions.structure_group_lookup.
            if s["id"] not in struct_to_set or (
                    known and struct_to_set[s["id"]] not in set_to_group):
                struct_to_set[s["id"]] = set_id

    cdir = None
    if args.bank:
        import candidates as cmod
        cmod.set_bank_root(args.seedtest)
        cdir = cmod.candidates_dir(Path(args.config))

    targets = {w["name"]: w for w in config.get("worlds", [])}
    targets.update({d["name"]: d for d in config["dimensions"] if rollable(d)})
    if args.dims:
        wanted = {d.strip() for d in args.dims.split(",")}
        targets = {k: v for k, v in targets.items() if k in wanted}

    rows = []
    clean = []
    for name, entry in targets.items():
        profile = build_profile(entry, config, difficulty)
        found = sweep_dimension(name, entry, profile, struct_sets,
                                struct_to_sets, struct_to_set, set_to_group,
                                type_defaults)
        if args.bank and type_defaults:
            found += bank_pass(name, entry, profile, struct_to_set,
                               set_to_group, type_defaults, cdir)
        if found:
            rows.extend(found)
        else:
            wants = sum(1 for _n, _s, _sp, k in profile["battery"] if k == "want")
            clean.append((name, wants))

    if args.format == "csv":
        w = csv.DictWriter(sys.stdout, fieldnames=[
            "dimension", "want", "structure", "radius", "band", "code",
            "expected", "detail"], extrasaction="ignore")
        w.writeheader()
        for r in sorted(rows, key=lambda r: (r["dimension"], r["want"])):
            w.writerow(r)
        return 0

    by_dim = {}
    for r in rows:
        by_dim.setdefault(r["dimension"], []).append(r)
    print("Unsatisfiable-want sweep over {} rollable target(s)\n".format(len(targets)))
    for name in sorted(by_dim):
        found = sorted(by_dim[name],
                       key=lambda r: (-SEVERITY.get(r["code"], 0), r["want"]))
        print("{}  (playable radius {}b)".format(name, found[0]["radius"]))
        for r in found:
            print("  {:21} {:22} {}".format(r["code"], r["want"], r["detail"]))
        print()

    counts = {}
    for r in rows:
        counts[r["code"]] = counts.get(r["code"], 0) + 1
    print("-" * 78)
    print("{} finding(s) across {} of {} target(s)".format(
        len(rows), len(by_dim), len(targets)))
    for code in sorted(counts, key=lambda c: (-SEVERITY.get(c, 0), c)):
        print("  {:21} {}".format(code, counts[code]))
    if args.all and clean:
        print("\nno findings ({}):".format(len(clean)))
        for name, wants in sorted(clean):
            print("  {:32} {} want(s)".format(name, wants))
    print("\nNothing was changed. A scoring or config change is a RESCORE, not "
          "a re-roll\n(./dev seed-rescore --dims ...) — but note that "
          "./dev seed-status cannot see it:\nit compares configHash, which "
          "covers the dimension config, so editing the SCORER\nleaves the hash "
          "identical and the banked scores stale while status says fresh.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
