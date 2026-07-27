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

CURVES = {
    "inner": [1.5, 1.3, 1.0, 0.8, 0.5, 0.3, 0.1, 0.0, 0.0, 0.0],
    "outer": [0.0, 0.0, 0.1, 0.3, 0.6, 0.8, 1.0, 1.3, 1.5, 2.0],
    "even": [1.0] * 10,
    "mid": [0.3, 0.5, 1.0, 1.2, 1.2, 1.0, 0.8, 0.5, 0.3, 0.1],
}


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


# The artefact shape this checker understands (Artefacts.SCHEMA_VERSION on the
# Java side). A census written by a newer mod is NOT read on a best-effort
# basis: a silently mis-read artefact produces a green run over a world nobody
# has actually checked, which is worse than no checker at all.
SUPPORTED_SCHEMA_VERSION = 1


class SchemaMismatch(Exception):
    pass


def load_census(census_dir, slug):
    matches = sorted(Path(census_dir).glob(f"*__{slug}.json"))
    if not matches:
        return None
    census = json.loads(matches[0].read_text())
    version = census.get("schemaVersion")
    if version is None:
        # Pre-contract dumps carry no version. They are still readable, but
        # say so — an old artefact is a stale artefact more often than not.
        print(f"  NOTE {matches[0].name} predates schemaVersion — "
              f"re-dump it if anything below looks wrong")
    elif version != SUPPORTED_SCHEMA_VERSION:
        raise SchemaMismatch(
            f"{matches[0].name} is schemaVersion {version}, this checker "
            f"understands {SUPPORTED_SCHEMA_VERSION} — update "
            f"scripts/check-noise-regression.py alongside the mod")
    return census


def pool_union(census):
    out = {}
    for entry in (census.get("groups") or {}).values():
        out.update(entry.get("structures") or {})
    return out


def positions_of(census, group=None):
    groups = census.get("groups") or {}
    if group is not None:
        groups = {group: groups[group]} if group in groups else {}
    out = []
    for entry in groups.values():
        out.extend(entry.get("positions") or [])
    return out


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
        report.check(slug, f"{group} biased toward spawn (>60% inside half-radius)",
                     inner / len(positions) > 0.6,
                     f"{inner}/{len(positions)}")

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
        for spec in CHECKS:
            print(f"{spec['slug']:26} {spec['why']}")
        return 0
    if not args.census:
        ap.error("--census is required")

    report = Report()
    for spec in CHECKS:
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
