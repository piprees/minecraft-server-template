#!/usr/bin/env python3
# =============================================================================
# check-noise-regression.py — assert shipped dimensions still place structures
#                             the way their configs say they should
# =============================================================================
#
# Purpose:
#   Spike task G2. Noise structure placement (mods/AGENTS.md § Noise structure
#   placement) sorts structure sets into groups, biome-filters them against the
#   dimension's own biome source and places each group with its own noise
#   field. Nothing about that is visible in a boot log line, and `/locate` only
#   ever proves ONE instance — so this reads `/customdim structure-census`
#   output, which is dumped from the LIVE placement calculator, and asserts the
#   properties a human would otherwise eyeball.
#
# Context:
#   Run it against a consumer's census directory after dumping censuses:
#     docker exec -i mc rcon-cli "customdim load <slug>"
#     docker exec -i mc rcon-cli "customdim structure-census <ns>:<slug>"
#   The command needs a FULLY-QUALIFIED dimension id; a bare name resolves
#   into `minecraft:` and answers "Dimension not loaded".
#
# Usage:
#   ./scripts/check-noise-regression.py --census <consumer>/data/config/custom-dimensions/census \
#                                       --config <consumer>/data/config/custom-dimensions
#   ./scripts/check-noise-regression.py --list        # what it checks, and why
#
# Gotchas:
#   - WORLDGEN IS CREATION-TIME-ONLY (TROUBLESHOOTING.md#d2). A dimension
#     created before its current biome list was written keeps the generator
#     baked into level.dat, so its biome source — and therefore its structure
#     pool — describes the OLD config. Assertions here are only meaningful on
#     a world where the dimension was created under the config being checked.
#     A stale world is the single most likely reason for a failure.
#   - Dimensions idle-unload after `idleUnloadMinutes` (default 5). Pair each
#     load with its census immediately.
#   - `structures.noise` (and the rest of the structures block) is read at
#     BOOT, not per census. Editing it and re-dumping without restarting mc
#     gives you the old groups and looks like the edit did nothing. It does
#     NOT need a world wipe though — unlike biomes/type/noiseSettings, which
#     are creation-time-only (D2), placement is rebuilt from config every
#     time a world loads.
#   - Over half of all structure sets never enter a noise group: anything
#     whose runtime placement is not exactly RandomSpreadStructurePlacement
#     (YUNG's, and what Cristel Lib rewrites) keeps grid placement. Absence
#     from a pool is NOT absence from the world.
# =============================================================================
import argparse
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import stack_version  # noqa: E402

# Expectations per dimension. Each is a property the CONFIG implies, chosen so
# a failure means the placement pipeline changed behaviour — not that a mod
# updated. `present`/`absent` match structure ids by substring against the
# union of every group's pool.
CHECKS = [
    {
        "slug": "the_dustbowl",
        "why": "structureDensity none + force: the escape hatch must stay exact",
        "groups_exactly": [],
        "forced_contains": ["explorify:farmstead"],
    },
    {
        "slug": "the_gilded_pit",
        "why": "dense nether pocket with nine forced placements",
        "groups_include": ["deco", "settlements", "dungeons"],
        "absent": ["minecraft:village_", "minecraft:igloo", "minecraft:end_city"],
        "forced_min": 5,
        "forced_excluded_from_pool": True,
    },
    {
        "slug": "the_overgrowth",
        "why": "jungle multi_biome: the biome filter is the zero-config feature",
        "groups_include": ["deco", "settlements", "dungeons", "landmarks"],
        "absent": ["minecraft:igloo", "minecraft:desert_pyramid"],
        "radial": {"settlements": "inner", "dungeons": "outer", "landmarks": "mid"},
        "inner_bias": ["settlements"],
    },
    {
        "slug": "the_burning_archipelago",
        "why": "large nether_islands: spread across the world, not huddled at spawn",
        "groups_include": ["deco", "settlements", "dungeons"],
        "present": ["minecraft:fortress"],
        "absent": ["minecraft:igloo", "minecraft:village_plains", "minecraft:end_city"],
        "not_all_near_spawn": 500,
    },
    {
        "slug": "the_frozen_strait",
        "why": "pocket maritime: ocean content present, and it is a small world",
        "groups_include": ["maritime", "deco"],
        "present": ["minecraft:shipwreck"],
        "absent": ["minecraft:end_city", "minecraft:fortress"],
    },
    {
        "slug": "the_blackstone_keep",
        "why": "nether: nether content only, bastion forced so absent from the pool",
        "groups_include": ["deco", "dungeons", "settlements"],
        "present": ["minecraft:fortress"],
        "absent": ["minecraft:igloo", "minecraft:shipwreck", "minecraft:end_city"],
        "forced_contains": ["minecraft:bastion_remnant"],
        "forced_excluded_from_pool": True,
    },
    {
        "slug": "the_end_citadel",
        "why": "end + dense: end content only, at the largest border we ship",
        "groups_include": ["deco", "dungeons", "endgame"],
        "present": ["minecraft:end_city"],
        "absent": ["minecraft:igloo", "minecraft:village_", "minecraft:fortress"],
        "min_positions": 10000,
    },
    {
        "slug": "the_luminous_caverns",
        "why": "peaceful cave: the shift suppresses endgame, but an explicit structures.noise.dungeons outranks the shift",
        "groups_include": ["deco", "loot", "dungeons", "landmarks"],
        "groups_absent": ["endgame", "settlements", "maritime"],
    },
    {
        "slug": "the_shattered_skies",
        "why": "sky_islands: no maritime group at all for this world type",
        "groups_include": ["deco", "landmarks", "settlements", "loot", "endgame"],
        "groups_absent": ["maritime"],
        "absent": ["minecraft:end_city", "minecraft:fortress"],
    },
    {
        "slug": "the_sunken_temple",
        "why": "paradise_lost clone: its own mod's structures reach the pool",
        "groups_include": ["deco", "landmarks", "settlements", "dungeons", "maritime"],
        "present": ["paradise_lost:"],
        "groups_absent": ["endgame", "loot"],
    },
]

# The four base worlds. They are managed exactly like custom dimensions, so
# they are checked exactly like them — the census comes from the same command
# and the same live placement calculator.
#
# `reachable` is the PROGRESSION FLOOR. Vanilla places fortresses and end
# cities on a dense grid on purpose, because blaze rods and elytra are behind
# them; dissolving those sets into a noise group must not push them out of
# reach. Re-run this after any change to either world's groups.
BASE_WORLD_CHECKS = [
    {
        "slug": "overworld",
        "base_world": True,
        "why": "the world everyone is standing in: villages near spawn, nothing alien",
        "groups_include": ["deco", "settlements", "dungeons", "landmarks", "maritime"],
        "absent": ["minecraft:end_city", "minecraft:fortress"],
        "radial": {"settlements": "inner", "dungeons": "outer"},
        "reachable": [
            # A village is the overworld's opening move, not progression — but
            # a spawn with no settlement within 1500 blocks is a bad world.
            {"structure": "minecraft:village_", "within": 1500, "min_expected": 1.0},
        ],
    },
    {
        "slug": "the_nether",
        "base_world": True,
        "why": "PROGRESSION: blaze rods come from fortresses and nothing else",
        "groups_include": ["deco", "dungeons"],
        "absent": ["minecraft:village_plains", "minecraft:igloo", "minecraft:end_city"],
        "reachable": [
            # borders.player is 1024 here (nether scale 8), so the whole
            # playable world is 64 chunks of radius. A floor of one expected
            # fortress inside HALF of that is deliberately strict: a player
            # who has to sweep the entire nether for blaze rods has been
            # regressed even if the structure technically exists.
            {"structure": "minecraft:fortress", "within": 512, "min_expected": 1.0},
        ],
    },
    {
        "slug": "the_end",
        "base_world": True,
        "why": "PROGRESSION: elytra come from end cities and nothing else",
        "groups_include": ["deco", "endgame"],
        "absent": ["minecraft:village_plains", "minecraft:fortress", "minecraft:igloo"],
        "reachable": [
            {"structure": "minecraft:end_city", "within": 2048, "min_expected": 1.0},
        ],
    },
    {
        "slug": "paradise_lost",
        "base_world": True,
        "why": "the skylands base world: its own mod's content, no hostile mega-content",
        # `settlements` is enabled by the type and still does not appear: no
        # settlement-themed set's biomes intersect the paradise biome source,
        # so the pool comes out empty and the group is skipped. That is the
        # documented normal case, and asserting the type's list here instead
        # of the world's actual pools would be asserting the wish.
        "groups_include": ["deco", "landmarks"],
        "groups_absent": ["endgame", "dungeons", "maritime", "loot"],
        "present": ["paradise_lost:"],
        "absent": ["minecraft:end_city", "minecraft:fortress"],
    },
]

def _load_curves():
    """The radial curve presets, read from the file that actually ships them.

    Reading the shipped values rather than hardcoding a copy here makes
    drift between this checker and the presets impossible: a hardcoded copy
    goes stale the moment the presets change and starts failing dimensions
    for matching the new presets correctly.
    """
    here = Path(__file__).resolve().parent.parent
    for candidate in (here / "config/custom-dimensions/structure-type-defaults.json",
                      here / "mods/custom-dimensions/src/main/resources"
                             "/structure_type_defaults.json"):
        if candidate.exists():
            curves = json.loads(candidate.read_text()).get("curves")
            if curves:
                return {k: v for k, v in curves.items() if not k.startswith("_")}
    raise SystemExit(
        "check-noise-regression: no structure-type-defaults.json found — "
        "cannot resolve the radial curve presets")


CURVES = _load_curves()


def curve_inner_share(curve, frac=0.5, steps=2000):
    """Fraction of a group's placements the curve puts inside `frac` of the
    radius. The curve is a 10-point piecewise-linear DENSITY multiplier over
    spawn->border, so the share is its area-weighted integral (2r dr), not the
    plain mean. `even` gives 25% at frac=0.5 — the flat-disc answer."""
    total = inside = 0.0
    last = len(curve) - 1
    for i in range(steps):
        r = (i + 0.5) / steps
        pos = r * last
        lo = int(pos)
        hi = min(lo + 1, last)
        t = pos - lo
        area = (curve[lo] * (1 - t) + curve[hi] * t) * 2 * r
        total += area
        if r <= frac:
            inside += area
    return inside / total if total else 0.0


class Report:
    def __init__(self):
        self.passed = 0
        self.failed = []
        self.skipped = []

    def check(self, slug, name, ok, detail=""):
        if ok:
            self.passed += 1
            print(f"  PASS {name}")
        else:
            self.failed.append((slug, name, detail))
            print(f"  FAIL {name}{(' — ' + detail) if detail else ''}")

    def skip(self, slug, reason):
        self.skipped.append((slug, reason))
        print(f"  SKIP {slug} — {reason}")


class SchemaMismatch(Exception):
    pass


def load_census(census_dir, slug):
    """Read a census, refusing one written by a different stack or schema.

    The mod and this checker ship in the same bundle, so an artefact stamped
    with another stack's version was left by an earlier one and describes a
    world this stack no longer generates. Checking it is worse than not
    checking it: a green run over stale data reads as proof.

    schemaVersion 2 is required: every position in a group with a non-empty
    pool carries [chunkX, chunkZ, "ns:structure_id"], and the checker's
    reachability floors use exact assigned counts instead of weight-share
    arithmetic.
    """
    matches = sorted(Path(census_dir).glob(f"*__{slug}.json"))
    if not matches:
        return None
    census = json.loads(matches[0].read_text())
    schema_version = census.get("schemaVersion")
    if schema_version != 2:
        raise SchemaMismatch(
            f"{matches[0].name} has schemaVersion {schema_version!r}, "
            f"expected 2 — re-dump it with the current jar "
            f"(/customdim structure-census <dimension>)")
    stamped = census.get("stackVersion")
    running = stack_version.stack_version()
    if stamped is None:
        print(f"  NOTE {matches[0].name} predates the stack stamp — "
              f"re-dump it if anything below looks wrong")
    elif stack_version.is_dev(stamped) or stack_version.is_dev(running):
        # A dev build carries no release identity — nothing to compare.
        pass
    elif stamped != running:
        raise SchemaMismatch(
            f"{matches[0].name} was written by stack {stamped}, this one is "
            f"{running} — re-dump it "
            f"(/customdim structure-census <dimension>)")
    return census


def pool_union(census):
    out = {}
    for entry in (census.get("groups") or {}).values():
        out.update(entry.get("structures") or {})
    return out


def positions_of(census, group=None):
    """Chunk positions from a census, as (cx, cz) pairs.

    schemaVersion 2 positions are [cx, cz, "ns:id"] triples; the id is
    stripped here so callers that only need coordinates work unchanged.
    """
    groups = census.get("groups") or {}
    if group is not None:
        groups = {group: groups[group]} if group in groups else {}
    out = []
    for entry in groups.values():
        for pos in entry.get("positions") or []:
            out.append((pos[0], pos[1]))
    return out


def validate_position_format(census, report, slug):
    """Every position in a group with pool total > 0 must be a 3-element
    array [chunkX, chunkZ, "ns:structure_id"].
    """
    for group, entry in (census.get("groups") or {}).items():
        pool = entry.get("structures") or {}
        total_weight = sum(pool.values())
        positions = entry.get("positions") or []
        if total_weight <= 0:
            continue
        bad = [i for i, pos in enumerate(positions) if len(pos) != 3]
        if bad:
            report.check(slug, f"{group}: every position is [cx, cz, id]",
                         False, f"{len(bad)} position(s) have wrong length "
                                f"(first at index {bad[0]})")
        else:
            report.check(slug, f"{group}: every position is [cx, cz, id]",
                         True)


def check_reachable(slug, census, groups, forced, req, report):
    """The progression floor: exact assigned instances of one structure
    within a radius.

    schemaVersion 2 positions carry [cx, cz, "ns:id"], so the count of a
    specific structure within a radius is exact — no weight-share arithmetic.
    Forced placements count in full.
    """
    needle = req["structure"]
    within = req["within"]
    floor = req.get("min_expected", 1.0)
    exact_count = 0
    detail = []
    for group, entry in groups.items():
        positions = entry.get("positions") or []
        assigned = sum(1 for pos in positions
                       if len(pos) >= 3 and needle in pos[2]
                       and math.hypot(pos[0], pos[1]) * 16 <= within)
        if assigned:
            exact_count += assigned
            detail.append(f"{group}: {assigned} assigned")
    certain = sum(1 for k, v in forced.items() if needle in k
                  for cx, cz in v if math.hypot(cx, cz) * 16 <= within)
    exact_count += certain
    if certain:
        detail.append(f"forced: {certain}")
    report.check(slug,
                 f"reachable: >= {floor} {needle} within {within} blocks",
                 exact_count >= floor,
                 f"found {exact_count} ({'; '.join(detail) or 'not assigned anywhere'})")


def run_dimension(spec, census_dir, report):
    slug = spec["slug"]
    print(f"\n{slug} — {spec['why']}")
    try:
        census = load_census(census_dir, slug)
    except SchemaMismatch as e:
        report.check(slug, "census schema is readable", False, str(e))
        return
    if census is None:
        report.skip(slug, "no census file (load the dimension and dump one)")
        return
    groups = census.get("groups") or {}
    pool = pool_union(census)
    forced = census.get("forced") or {}

    validate_position_format(census, report, slug)

    if "groups_exactly" in spec:
        report.check(slug, f"groups == {spec['groups_exactly']}",
                     sorted(groups) == sorted(spec["groups_exactly"]),
                     f"got {sorted(groups)}")
    for g in spec.get("groups_include", []):
        report.check(slug, f"group {g} active", g in groups, f"groups: {sorted(groups)}")
    for g in spec.get("groups_absent", []):
        report.check(slug, f"group {g} NOT active", g not in groups)

    for needle in spec.get("present", []):
        hits = [s for s in pool if needle in s]
        report.check(slug, f"pool contains {needle}", bool(hits))
    for needle in spec.get("absent", []):
        hits = sorted(s for s in pool if needle in s)
        report.check(slug, f"pool excludes {needle}", not hits, f"found {hits[:4]}")

    for group, curve_name in (spec.get("radial") or {}).items():
        entry = groups.get(group)
        if entry is None:
            report.check(slug, f"{group} radial == {curve_name}", False, "group missing")
            continue
        report.check(slug, f"{group} radial == {curve_name}",
                     entry.get("radial") == CURVES[curve_name],
                     f"got {entry.get('radial')}")

    for group in spec.get("inner_bias", []):
        entry = groups.get(group)
        positions = positions_of(census, group)
        if entry is None or not positions:
            report.check(slug, f"{group} biased toward spawn", False,
                         "group missing or empty")
            continue
        radius = entry.get("radiusChunks") or 1
        inner = sum(1 for cx, cz in positions
                    if math.hypot(cx, cz) <= radius * 0.5)
        # Assert against what the group's OWN curve predicts, not a fixed
        # share: a fixed threshold encodes one curve shape and silently
        # starts asserting a stale behaviour the moment the presets change.
        curve_name = (spec.get("radial") or {}).get(group)
        expected = curve_inner_share(CURVES[curve_name]) if curve_name in CURVES else 0.25
        n = len(positions)
        observed = inner / n
        # Two binomial standard deviations of headroom: a real census is a
        # sample, and these groups are small in a sparse dimension.
        floor = expected - 2.0 * math.sqrt(expected * (1 - expected) / n)
        report.check(slug,
                     f"{group} biased toward spawn (curve '{curve_name}' "
                     f"predicts {expected:.0%} inside half-radius)",
                     observed >= floor,
                     f"{inner}/{n} = {observed:.0%}, floor {floor:.0%}")

    if "not_all_near_spawn" in spec:
        blocks = spec["not_all_near_spawn"]
        positions = positions_of(census)
        far = sum(1 for cx, cz in positions if math.hypot(cx, cz) * 16 > blocks)
        report.check(slug, f"structures reach beyond {blocks} blocks",
                     far > 0 and len(positions) > 0,
                     f"{far}/{len(positions)} beyond")

    if "min_positions" in spec:
        total = len(positions_of(census))
        report.check(slug, f"at least {spec['min_positions']} placements",
                     total >= spec["min_positions"], f"got {total}")

    for req in spec.get("reachable", []):
        check_reachable(slug, census, groups, forced, req, report)

    for needle in spec.get("forced_contains", []):
        report.check(slug, f"forced placement {needle}",
                     any(needle in k for k in forced), f"forced: {sorted(forced)}")
    if "forced_min" in spec:
        count = sum(len(v) for v in forced.values())
        report.check(slug, f"at least {spec['forced_min']} forced placements",
                     count >= spec["forced_min"], f"got {count}")
    if spec.get("forced_excluded_from_pool"):
        clash = sorted(k for k in forced if k in pool)
        report.check(slug, "forced structures are removed from the noise pool",
                     not clash, f"still pooled: {clash}")


def audit_unsatisfiable_wants(config_dir, report, strict=False):
    """Wants for a group the dimension's world type never enables.

    Noise placement made `seedRoll.wants` checkable for the first time: a want
    names a structure, the structure belongs to a SET, the set belongs to a
    GROUP, and a world type enables only some groups. A want whose group is
    not enabled can never be satisfied — the structure does not generate in
    that dimension at all, so the roller scores it 0 for every seed and the
    dimension is permanently capped below its own ceiling.

    Before noise this was invisible: the roller computed a vanilla grid
    position for the set and cheerfully reported a distance, for a structure
    the world was never going to contain.

    Reported as a WARNING by default — it is an authoring smell in shipped
    config, not a broken world. `--strict` makes it fail.
    """
    try:
        import sys as _sys
        _sys.path.insert(0, str(Path(__file__).resolve().parent / "seed"))
        import noise_placement as npl
        from dimension_profiles import load_config, load_difficulty, build_profile
    except ImportError as e:
        print(f"\nSKIP unsatisfiable-want audit (roller modules unavailable: {e})")
        return
    type_defaults = npl.load_type_defaults(config_dir)
    if not type_defaults:
        print("\nSKIP unsatisfiable-want audit (no structure-type-defaults.json)")
        return

    # The structure -> set -> group map needs the warmup extraction; without
    # it every want looks unclassified and the audit would report nothing at
    # all, which is indistinguishable from a clean run. Say so instead.
    # Consumer layout is <consumer>/data/config/custom-dimensions with the
    # warmup at <consumer>/.seedtest; a platform checkout is
    # <repo>/config/custom-dimensions with <repo>/.seedtest. Try both rather
    # than guessing a depth.
    config_path = Path(config_dir).resolve()
    seedtest = None
    for parent in config_path.parents:
        if (parent / ".seedtest" / ".structure_sets").is_dir():
            seedtest = parent / ".seedtest"
            break
    if seedtest is None:
        print("\nSKIP unsatisfiable-want audit (no warmup extraction found "
              "above the config directory — run a seed roll first)")
        return

    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "score_dimensions", Path(__file__).resolve().parent / "seed" / "score-dimensions.py")
    sd = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sd)
    lookup = sd.structure_group_lookup(seedtest, config_dir)

    config = load_config(config_dir)
    difficulty = load_difficulty(config_dir)
    print("\nunsatisfiable wants — a want whose group this world type never enables")
    findings = 0
    for raw in config.get("dimensions", []):
        active = set(npl.resolve_groups(raw, type_defaults))
        if not active:
            continue
        profile = build_profile(raw, config, difficulty)
        dead = []
        for name, sid, _spec, kind in profile["battery"]:
            if kind != "want":
                continue
            group = sd.battery_group_for(sid, lookup)
            if group is not None and group not in active:
                dead.append((name, group))
        if not dead:
            continue
        findings += len(dead)
        detail = ", ".join(f"{n} (needs {g})" for n, g in dead)
        if strict:
            report.check(raw["name"], f"{raw['name']}: every want is reachable",
                         False, detail)
        else:
            print(f"  WARN {raw['name']:26} {detail}")
    if findings == 0:
        print("  none — every want names a group its dimension actually enables")
    elif not strict:
        print(f"  {findings} want(s) can never be satisfied. Either name a structure in an "
              f"active group, or enable the group explicitly with "
              f"structures.noise: {{\"<group>\": \"sparse\"}}.")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--census", help="directory of structure-census JSON dumps")
    ap.add_argument("--config", help="config/custom-dimensions directory — enables the "
                                     "unsatisfiable-want audit")
    ap.add_argument("--strict", action="store_true",
                    help="treat an unsatisfiable want as a failure, not a warning")
    ap.add_argument("--list", action="store_true", help="print what is checked and exit")
    args = ap.parse_args()

    if args.list:
        for spec in CHECKS + BASE_WORLD_CHECKS:
            print(f"{spec['slug']:26} {spec['why']}")
        return 0
    if not args.census:
        ap.error("--census is required")

    report = Report()
    for spec in CHECKS + BASE_WORLD_CHECKS:
        run_dimension(spec, args.census, report)

    if args.config:
        audit_unsatisfiable_wants(args.config, report, strict=args.strict)

    print(f"\n{report.passed} passed, {len(report.failed)} failed, "
          f"{len(report.skipped)} dimension(s) skipped")
    for slug, name, detail in report.failed:
        print(f"  FAIL {slug}: {name} {detail}")
    if report.skipped:
        print("  (a skipped dimension has no census dump — that is not a pass)")
    return 1 if report.failed or report.skipped else 0


if __name__ == "__main__":
    sys.exit(main())
