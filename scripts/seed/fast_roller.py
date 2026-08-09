#!/usr/bin/env python3
"""fast_roller.py — Pure-Python seed candidate generator.

Two-tier screening:
  Tier 1 (instant): structure placement only — 100k+ seeds/sec.
         Scores structures, rejects seeds with zero battery hits.
  Tier 2 (fast):    biome sampling on tier-1 survivors — ~15 seeds/sec.
         Spawn filter, biome variety, terrain proxy.

Seed-group rolling: dimensions whose generation-affecting config is
byte-identical (dimension_profiles.generation_fingerprint) share every
seed's measurements — they are grouped, each group's tier-2 survivors are
measured ONCE through a memoised sampler, and every member banks rows for
every group seed. Per-member rows are bit-identical to a solo run
(sampling is deterministic); winners within a group are made distinct at
finalise time (same fingerprint + same seed = literal world clones).

No server, no RCON, no Docker. Writes the same CSV format that
score-dimensions.py consumes.

Usage:
    python3 fast_roller.py --config <dir> --seedtest <dir> [--dims a,b,c]
                           [--count 500] [--workers 4] [--tier1-pool 5000]
"""

import argparse
import importlib.util
import math
import multiprocessing
import os
import struct
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from biome_sampler import (  # noqa: E402
    FAMILY_NOISE as biome_sampler_family_noise,
    TYPE_NOISE_OVERRIDE as biome_sampler_type_override,
    BiomeSampler, build_for_dimension, load_noise_configs,
)
from dimension_profiles import (  # noqa: E402
    build_profile, generation_fingerprint, load_config, load_difficulty,
    measure_horizon, rollable,
)
from structure_placement import load_structure_sets, nearest_structure  # noqa: E402

#: Seconds between progress ticks while groups are still running. Completion
#: is the only other signal and a tier-1 group on a large pool takes minutes,
#: so without a tick the roller looks identical to a hang.
PROGRESS_TICK_SECONDS = 30

#: Seeds a worker screens between counter updates. The counter is shared
#: across processes, so incrementing per seed would serialise the tier-1 loop
#: on its lock; per batch the cost is unmeasurable.
PROGRESS_BATCH = 2000

#: Shared counters, installed in each worker by the pool initialiser.
#: Group completion is far too coarse to estimate from — a run over 4 groups
#: can only ever report 0/25/50/75% — so workers publish their inner-loop
#: position and the parent reports THAT.
_PROGRESS = None


def _init_progress(counters):
    global _PROGRESS
    _PROGRESS = counters


def _bump(key, n=1):
    if _PROGRESS is None:
        return
    counter = _PROGRESS[key]
    with counter.get_lock():
        counter.value += n


class MemoSampler:
    """Point-caching wrapper for seed-group rolling: within a fingerprint
    group every member shares ONE sampler per seed (the sampler is built
    purely from fingerprint fields), so biome/climate lookups repeat across
    members — spawn scans hit the same 49 points, variety locates walk the
    same rings. Caching by coordinate makes the 2nd..Nth member's
    measurement near-free while staying EXACT: sampling is deterministic,
    so per-member rows are bit-identical to a solo run."""

    def __init__(self, sampler):
        self._sampler = sampler
        self._entries = sampler._entries  # tier2's representability check
        self._points = {}
        self._locates = {}

    def biome_and_climate(self, x, z):
        got = self._points.get((x, z))
        if got is None:
            got = self._sampler.biome_and_climate(x, z)
            self._points[(x, z)] = got
        return got

    def biome_at(self, x, z):
        return self.biome_and_climate(x, z)[0]

    def sample_climate(self, x, z):
        return self.biome_and_climate(x, z)[1]

    # Ring search borrowed unbound (PatchedBiomeSampler's pattern) — it
    # only needs biome_at, which hits the point cache.
    _ring_locate = BiomeSampler.locate_biome

    def locate_biome(self, biome_id, radius=6400, step=64, origin_x=0, origin_z=0):
        key = (biome_id, radius, step, origin_x, origin_z)
        if key not in self._locates:
            self._locates[key] = self._ring_locate(
                biome_id, radius=radius, step=step,
                origin_x=origin_x, origin_z=origin_z)
        return self._locates[key]

    spawn_filter = BiomeSampler.spawn_filter


def _window_score(value, lo, hi):
    if value is None:
        return 0.0
    width = max(hi - lo, 1e-9)
    if lo <= value <= hi:
        return 1.0
    if value < lo:
        return max(0.0, 1.0 - (lo - value) / width)
    return max(0.0, 1.0 - (value - hi) / width)


def want_score(dist, lo, hi, radius, horizon):
    """Tier-1 screening variant. `horizon` = profile locate_cap, the
    radius the battery search actually covers — same absence semantics
    as score-dimensions.want_score."""
    hi = min(hi, radius)
    if lo >= horizon:
        if dist is None or dist < 0:
            return 0.8
        return 0.2 if dist < radius * 0.3 else 1.0
    if dist is None or dist < 0:
        return 0.0 if hi <= horizon else 0.6
    return _window_score(dist, lo, hi)


def shun_score(dist, radius, min_distance=None):
    threshold = min_distance if min_distance else radius
    return 0.0 if (dist is not None and 0 <= dist < threshold) else 1.0


SCRIPT_DIR = Path(__file__).resolve().parent

WATER_BIOMES = {
    "minecraft:ocean", "minecraft:deep_ocean", "minecraft:cold_ocean",
    "minecraft:deep_cold_ocean", "minecraft:frozen_ocean",
    "minecraft:deep_frozen_ocean", "minecraft:lukewarm_ocean",
    "minecraft:deep_lukewarm_ocean", "minecraft:warm_ocean",
    "minecraft:river", "minecraft:frozen_river",
    "terralith:deep_warm_ocean", "terralith:warm_river",
}

def spawn_site_stamp():
    """The stack that chose a banked spawn, so a new one re-selects.

    Read by score-dimensions' ensure_spawn_sites to tell a current choice from
    one an older select_spawn_site made.
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    import stack_version
    return stack_version.cache_key()

# How far inside borders.player a spawn must sit. The mod's
# PortalSafetyValidator.ARRIVAL_MARGIN is 0, so this is the only thing
# keeping a rolled spawn off the world-border line.
SPAWN_BORDER_MARGIN = 16

_TERRAIN_EV = None


def _terrain_evaluator():
    global _TERRAIN_EV
    if _TERRAIN_EV is None:
        from terrain_height import TerrainEvaluator
        _TERRAIN_EV = TerrainEvaluator()
    return _TERRAIN_EV


def select_spawn_site(sampler, profile, radius=768, step=64):
    """Pick a spawn SITE, not just the nearest namesake point (X/Z only —
    Y stays with the mod, which recomputes it at arrival anyway).

    One grid pass over the ±radius window collects biome + climate per
    point. Hard filters on a site: namesake biome, inside borders.player
    less SPAWN_BORDER_MARGIN, not a water biome, and clear of every forced
    placement by clearSpawnRadius. Survivors score on dryness and openness
    of the 8-neighbour ring plus macro-flatness from the spline height
    model (macro shape only — the spline cannot see settlement-scale
    relief), with origin distance and then the grid key breaking ties —
    the choice is a pure deterministic function of seed + config.

    Returns (biome, dist, x, z) like BiomeSampler.spawn_filter. Falls back
    to the nearest bare namesake point when every site fails a hard filter
    (an all-water namesake list, a tiny border) — site quality must never
    reject a seed the old gate accepted.
    """
    namesake = set(profile.get("namesake") or [])
    if not namesake:
        return None, -1, 0, 0
    try:
        border = int(profile.get("player_border") or 0)
    except (TypeError, ValueError):
        border = 0
    clear = int(profile.get("clear_spawn_radius") or 0)
    forced = [(int(f["x"]), int(f["z"]))
              for f in (profile.get("forced_structures") or [])
              if f.get("x") is not None and f.get("z") is not None]
    family = profile.get("family") or "overworld"
    ev = _terrain_evaluator()
    use_heights = ev.has_family(family) or family == "paradise_lost"

    grid = {}
    for gx in range(-radius, radius + 1, step):
        for gz in range(-radius, radius + 1, step):
            biome, climate = sampler.biome_and_climate(gx, gz)
            height = ev.surface_height(
                climate["continentalness"], climate["erosion"],
                climate["weirdness"], family=family) if use_heights else 0
            grid[(gx, gz)] = (biome, height)

    best_key, best = None, None
    fallback = None            # nearest namesake point, old-gate semantics
    clear_sq = clear * clear
    for (gx, gz) in sorted(grid):
        biome, height = grid[(gx, gz)]
        if biome not in namesake:
            continue
        dist_sq = gx * gx + gz * gz
        if fallback is None or dist_sq < fallback[0]:
            fallback = (dist_sq, biome, gx, gz)
        if border > 0 and max(abs(gx), abs(gz)) > border - SPAWN_BORDER_MARGIN:
            continue
        if biome in WATER_BIOMES:
            continue
        if any((gx - fx) ** 2 + (gz - fz) ** 2 <= clear_sq for fx, fz in forced):
            continue
        neighbours = [grid[(gx + dx * step, gz + dz * step)]
                      for dx in (-1, 0, 1) for dz in (-1, 0, 1)
                      if (dx, dz) != (0, 0)
                      and (gx + dx * step, gz + dz * step) in grid]
        if neighbours:
            water = sum(1 for b, _ in neighbours if b in WATER_BIOMES)
            open_ = sum(1 for b, _ in neighbours if b in namesake)
            dry_frac = 1.0 - water / len(neighbours)
            open_frac = open_ / len(neighbours)
            drop = max(abs(h - height) for _, h in neighbours) if use_heights else 0
        else:
            dry_frac, open_frac, drop = 1.0, 1.0, 0
        flat = max(0.0, 1.0 - drop / 32.0)
        # Anchor-"spawn" dims: the site is a portal FOUNDATION (the frame
        # lands on it), so dryness dominates — a frame in a lake is worse
        # than one a little further from clean namesake ground.
        if profile.get("anchor_spawn"):
            score = 0.5 * dry_frac + 0.2 * open_frac + 0.3 * flat
        else:
            score = 0.4 * dry_frac + 0.3 * open_frac + 0.3 * flat
        key = (-round(score, 9), dist_sq, gx, gz)
        if best_key is None or key < best_key:
            best_key, best = key, (biome, gx, gz)

    if best is not None:
        biome, x, z = best
        return biome, int(math.sqrt(x * x + z * z)), x, z
    if fallback is not None:
        dist_sq, biome, x, z = fallback
        return biome, int(math.sqrt(dist_sq)), x, z
    return None, -1, 0, 0

_CONT_TO_HEIGHT = [
    (-1.2, 40), (-0.455, 55), (-0.19, 62), (-0.11, 63),
    (0.03, 70), (0.3, 100), (0.55, 140), (0.8, 190), (1.0, 256),
]

# Aliases of the canonical tables in biome_sampler — never second copies.
FAMILY_NOISE = biome_sampler_family_noise
_TYPE_NOISE_OVERRIDE = biome_sampler_type_override


def _cont_to_height(cont):
    for i in range(len(_CONT_TO_HEIGHT) - 1):
        c0, h0 = _CONT_TO_HEIGHT[i]
        c1, h1 = _CONT_TO_HEIGHT[i + 1]
        if cont <= c1:
            t = (cont - c0) / (c1 - c0) if c1 != c0 else 0
            return h0 + t * (h1 - h0)
    return _CONT_TO_HEIGHT[-1][1]


def random_seed():
    return struct.unpack("<q", os.urandom(8))[0]


def _resolve_struct_set(sid, struct_sets, struct_to_sets, seedtest_path=None):
    """Resolve a battery entry's structure id to its structure set config.

    Tags (#ns:tag) are resolved to their exact member list via
    structure_tags.resolve_tag. Returns None when the tag data is
    unavailable (the caller records -1, never falls back to substring).
    """
    clean = sid.lstrip("#")
    if clean in struct_to_sets:
        return struct_sets[struct_to_sets[clean][0]]
    if sid.startswith("#"):
        from structure_tags import resolve_tag
        members = resolve_tag(seedtest_path or ".seedtest", sid)
        if members is None:
            return None
        for member in members:
            if member in struct_to_sets:
                return struct_sets[struct_to_sets[member][0]]
        return None
    if clean in struct_sets:
        return struct_sets[clean]
    return None


# -----------------------------------------------------------------------
# Tier 1: structure-only screening (instant)
# -----------------------------------------------------------------------
def tier1_score(seed, profile, struct_sets, struct_to_sets,
                origin_x=0, origin_z=0, seedtest_path=None):
    """Structure battery score for a seed. Returns (score, distances dict).
    No biomes, no noise — pure math. <0.1ms per seed.

    `origin_x`/`origin_z` anchor every distance: the screening pass runs
    from the world origin (the spawn is not known yet), but the banked
    measurement pass re-runs from the candidate's chosen spawn — a distance
    from (0, 0) describes a point the player never stands on when the spawn
    sits up to 768 blocks away (spawn-site-selection §5)."""
    cap = profile.get("locate_cap")
    dists = {}
    if not profile["battery"]:
        return 0.5, dists

    spacing_overrides = profile.get("spacing_overrides") or {}
    ss, n = 0.0, 0
    for sname, sid, spec, kind in profile["battery"]:
        # Fixed placements (structures.force) are constants — known
        # distance regardless of seed; mode-filtered sets are absent.
        # Mirrors DimensionStructures; helpers live in structure_placement.
        from structure_placement import forced_distance, mode_drops
        forced = forced_distance(sid, profile, origin_x, origin_z)
        if forced is not None:
            dists[sname] = forced
            if kind == "shun":
                ss += shun_score(forced, profile["radius"], spec)
            else:
                ss += want_score(forced, spec[0], spec[1], profile["radius"],
                                 measure_horizon(profile))
            n += 1
            continue
        set_cfg = _resolve_struct_set(sid, struct_sets, struct_to_sets, seedtest_path)
        if set_cfg and mode_drops(set_cfg.get("id"), profile):
            set_cfg = None
        # The exclude union (dimension structures.exclude + the global
        # settings suppress list) removes the set from pools AND
        # pass-throughs server-side, so its battery reading is "absent".
        if set_cfg and str(set_cfg.get("id") or "").lower() \
                in (profile.get("structures_exclude") or ()):
            set_cfg = None
        if set_cfg:
            # Per-set placement overrides (structures.spacing, Tier 3):
            # same values DimensionStructures applies server-side. Same
            # invariants too (2 <= spacing, 0 <= separation < spacing) —
            # the mod falls back to the theme path on violation, we keep
            # the extracted values.
            spacing = set_cfg["spacing"]
            separation = set_cfg["separation"]
            frequency = set_cfg.get("frequency", 1.0)
            ov = spacing_overrides.get(set_cfg.get("id"))
            explicit_spacing = False
            if isinstance(ov, dict):
                new_spacing = ov.get("spacing", spacing)
                new_sep = ov.get("separation", separation)
                if isinstance(new_spacing, int) and isinstance(new_sep, int) \
                        and 2 <= new_spacing <= 4096 and 0 <= new_sep < new_spacing:
                    spacing, separation = new_spacing, new_sep
                    explicit_spacing = True
            # exitShrines parity: DimensionStructures raises the shrine
            # set's shipped 0.001 frequency to 1.0 for opted-in dims, and
            # (absent an explicit structures.spacing) derives the spacing
            # from the raw playable border: clamp(radius/32, 12, 48),
            # separation = spacing // 2. MIRRORS derivedShrineSpacing in
            # DimensionStructures — change both together.
            if set_cfg.get("id") == "adventure:exit_shrines" and profile.get("exit_shrines"):
                frequency = 1.0
                if not explicit_spacing:
                    spacing = max(12, min(48, int(profile.get("player_border", 8192)) // 32))
                    separation = spacing // 2
            result = nearest_structure(
                seed, spacing, separation,
                set_cfg["salt"], origin_x=origin_x, origin_z=origin_z,
                spread_type=set_cfg.get("spread_type", "linear"),
                frequency=frequency,
                search_radius=50)
            dist = result[0] if result else -1
            if cap is not None and dist > cap:
                dist = -1
        else:
            dist = -1
        dists[sname] = dist
        if kind == "shun":
            ss += shun_score(dist, profile["radius"], spec)
        else:
            ss += want_score(dist, spec[0], spec[1], profile["radius"],
                             measure_horizon(profile))
        n += 1
    return (ss / n if n else 0.0), dists


# -----------------------------------------------------------------------
# Tier 2: biome + terrain on survivors (coarse grid for speed)
# -----------------------------------------------------------------------
def tier2_measure(seed, profile, sampler):
    """Full measurement with biome sampler. Returns (rows, accepted)."""
    rows = []
    fam = profile["family"] or "overworld"

    # Spawn filter — coarse grid (step=256 for speed: 49 points, ~54ms)
    spawn = "unknown"
    spawn_x, spawn_z = 0, 0

    if profile["namesake"]:
        namesake_set = set(profile["namesake"])
        # Check if any namesake biome is representable by the sampler.
        # multi_biome dims with nether biomes on overworld noise can't
        # always place the expected biomes — accept them ungated rather
        # than rejecting every candidate.
        sampler_biomes = {e[0] for e in sampler._entries}
        # multi_biome dims mix families — namesake biomes may not exist in this noise config
        namesake_in_sampler = namesake_set & sampler_biomes
        if namesake_in_sampler:
            # Site selection, not nearest-point: hard filters + quality
            # scoring at 64-block resolution (select_spawn_site). Rejection
            # semantics are unchanged — a seed fails only when NO namesake
            # biome exists anywhere in the window, exactly as before.
            best_b, best_d, best_x, best_z = select_spawn_site(
                sampler, profile)
            if best_b is not None and best_d >= 0:
                if best_d <= 48:
                    spawn = best_b
                spawn_x, spawn_z = best_x, best_z
                rows.append(("spawn_filter_dist", best_d))
                rows.append(("spawn_site_v", spawn_site_stamp()))
            else:
                rows.append(("spawn_biome", "unknown"))
                rows.append(("rejected", 1))
                return rows, False
        else:
            rows.append(("spawn_filter_dist", 0))

    if spawn == "unknown":
        spawn = sampler.biome_at(0, 0)

    rows.append(("spawn_biome", spawn))
    rows.append(("spawn_x", spawn_x))
    rows.append(("spawn_z", spawn_z))

    # Biome variety — coarse locate (step=256, capped radius for speed)
    variety_radius = min(profile.get("locate_cap", 6400), 3200)
    for biome in profile["variety_biomes"]:
        result = sampler.locate_biome(biome, radius=variety_radius, step=256)
        dist = result[0] if result else -1
        rows.append((f"biome_{biome}_dist", dist))

    # Terrain proxy from climate
    pitch = profile["grid_pitch"]
    has_cont = fam == "overworld" and not profile.get("is_void")
    for r in range(3):
        for c in range(3):
            x, z = (c - 1) * pitch, (r - 1) * pitch
            if profile.get("is_void"):
                pass
            elif has_cont:
                climate = sampler.sample_climate(x, z)
                cont = climate.get("continentalness", 0.0)
                h = _cont_to_height(cont)
                rows.append((f"height_r{r}c{c}", int(h)))
                biome = sampler.biome_at(x, z)
                rows.append((f"water_r{r}c{c}", 1 if biome in WATER_BIOMES else 0))
            else:
                climate = sampler.sample_climate(x, z)
                ero = climate.get("erosion", 0.0)
                h = 64 + int(ero * 30)
                rows.append((f"height_r{r}c{c}", h))
                biome = sampler.biome_at(x, z)
                rows.append((f"water_r{r}c{c}", 1 if biome in WATER_BIOMES else 0))

    rows.append(("errors", 0))
    return rows, True


def _build_sampler(seed, profile, biome_params_path, noise_configs):
    """One seed's sampler from a profile's generation fields. Within a
    fingerprint group every member builds the identical sampler, which is what
    makes group sharing exact. Construction lives in biome_sampler so every
    caller builds the same world (see T20)."""
    return build_for_dimension(seed, profile, biome_params_path, noise_configs)


def _process_group(task):
    """Process one fingerprint group (singletons are groups of 1):
    tier-1 screen a SHARED seed pool per member, union the per-member
    top-N survivors, then tier-2-measure each survivor ONCE — a memoised
    sampler serves every member, and each member banks rows for every
    group seed (richer assignment pool at shared measurement cost)."""
    (members, pool_size, keep_count,
     struct_sets_path, biome_params_path, noise_configs, seen_set) = task

    t0 = time.time()
    # The seedtest root is the parent of the .structure_sets extraction dir.
    seedtest_path = str(Path(struct_sets_path).parent)

    # Load structure sets
    struct_sets = load_structure_sets(struct_sets_path)
    struct_to_sets = {}
    for set_id, cfg in struct_sets.items():
        for s in cfg["structures"]:
            struct_to_sets.setdefault(s["id"], []).append(set_id)

    # Tier 1: one shared pool; every member scores every seed.
    ranks = {name: [] for name, _profile in members}
    since_report = 0
    for _ in range(pool_size):
        seed = random_seed()
        while str(seed) in seen_set:
            seed = random_seed()
        seen_set.add(str(seed))
        for name, profile in members:
            score, _dists = tier1_score(seed, profile, struct_sets, struct_to_sets,
                                        seedtest_path=seedtest_path)
            ranks[name].append((score, seed))
        since_report += 1
        if since_report >= PROGRESS_BATCH:
            _bump("tier1", since_report)
            since_report = 0
    _bump("tier1", since_report)

    # Survivors: union of each member's top keep_count — every member gets
    # seeds that screened well FOR THEM; overlap between members is the
    # tier-2 saving.
    survivors = []
    chosen = set()
    for name, _profile in members:
        ranks[name].sort(reverse=True)
        for _score, seed in ranks[name][:keep_count]:
            if seed not in chosen:
                chosen.add(seed)
                survivors.append(seed)
    tier1_ms = (time.time() - t0) * 1000

    # Tier 2: one sampler per seed serves all members (fingerprint
    # invariant), memoised so repeated spawn/variety/terrain lookups
    # across members are near-free — and EXACT (deterministic sampling).
    results = {name: [] for name, _profile in members}
    accepted = {name: 0 for name, _profile in members}
    rejected = {name: 0 for name, _profile in members}
    rep_profile = members[0][1]
    # Survivor count is only known now — the parent's tier-2 total grows as
    # each group finishes tier 1 rather than being predictable up front.
    _bump("tier2_total", len(survivors))
    for seed in survivors:
        _bump("tier2")
        sampler = _build_sampler(seed, rep_profile, biome_params_path, noise_configs)
        if len(members) > 1:
            sampler = MemoSampler(sampler)
        for name, profile in members:
            rows, ok = tier2_measure(seed, profile, sampler)
            # Structure distances: recomputed per member (pure math,
            # <0.1ms) — each member's battery differs within a group, and
            # the banked distances are anchored at the candidate's CHOSEN
            # spawn, not the origin the screening pass used.
            spawn_x = next((v for k, v in rows if k == "spawn_x"), 0)
            spawn_z = next((v for k, v in rows if k == "spawn_z"), 0)
            _score, struct_dists = tier1_score(seed, profile,
                                               struct_sets, struct_to_sets,
                                               origin_x=int(spawn_x),
                                               origin_z=int(spawn_z),
                                               seedtest_path=seedtest_path)
            for sname, _sid, _band, _kind in profile["battery"]:
                rows.append((f"structure_{sname}_dist", struct_dists.get(sname, -1)))
            results[name].append((seed, rows, ok))
            if ok:
                accepted[name] += 1
            else:
                rejected[name] += 1

    tier2_ms = (time.time() - t0) * 1000 - tier1_ms
    total_ms = (time.time() - t0) * 1000

    return [(name, results[name], accepted[name], rejected[name],
             pool_size, len(survivors), tier1_ms, tier2_ms, total_ms)
            for name, _profile in members]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", required=True,
                    help="config/custom-dimensions/ directory")
    ap.add_argument("--seedtest", required=True,
                    help=".seedtest/ directory for output")
    ap.add_argument("--dims",
                    help="comma-separated subset of dimension names")
    ap.add_argument("--count", type=int, default=100,
                    help="accepted candidates to keep per dimension")
    ap.add_argument("--tier1-pool", type=int, default=5000,
                    help="seeds to screen in tier 1 per dimension")
    ap.add_argument("--workers", type=int, default=0,
                    help="parallel workers (0 = CPU count)")
    ap.add_argument("--output-csv",
                    help="output CSV path (default: <seedtest>/fast-roller.csv)")
    # The census and terrain backfills run in THIS process's fold step, not in
    # the separate score-dimensions finalise that follows it — so these have to
    # be accepted here or they never reach the pass they are meant to control.
    ap.add_argument("--census-workers", type=int, default=0,
                    help="processes for the census/terrain backfill "
                         "(default: CPU count minus 2, floor 2)")
    ap.add_argument("--census-top", type=int, default=0,
                    help="only census candidates that can still reach a "
                         "dimension's top N (0 = every candidate)")
    args = ap.parse_args()

    # FIRST, before anything reads or writes the bank. load_seen_seeds()
    # below reads the candidate store, and score-dimensions' persist step
    # writes it; both resolve through candidates.candidates_dir(), which
    # falls back to the legacy in-config location when no root is set. A
    # root set later than the first read means the roller sees an empty
    # bank and re-rolls seeds it already rejected, forever.
    import candidates as _cmod
    _cmod.set_bank_root(args.seedtest)

    config = load_config(args.config)
    difficulty = load_difficulty(args.config)
    noise_configs = load_noise_configs()

    dims = [d for d in config["dimensions"] if rollable(d)]
    worlds = config.get("worlds", [])

    if args.dims:
        wanted = {d.strip() for d in args.dims.split(",")}
        dims = [d for d in dims if d["name"] in wanted]
        worlds = [w for w in worlds if w["name"] in wanted]

    all_targets = []
    for w in worlds:
        all_targets.append((w["name"], build_profile(w, config, difficulty)))
    for d in dims:
        all_targets.append((d["name"], build_profile(d, config, difficulty)))

    if not all_targets:
        print("No rollable targets found")
        return 1

    # Load seen seeds (seed_worker.py uses a hyphen in other contexts but
    # the module itself is importable via sys.path)
    try:
        from seed_worker import load_seen_seeds
    except ImportError:
        spec = importlib.util.spec_from_file_location(
            "seed_worker", str(SCRIPT_DIR / "seed_worker.py"))
        sw = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(sw)
        load_seen_seeds = sw.load_seen_seeds
    seen = load_seen_seeds(args.seedtest, args.config)

    struct_sets_path = str(Path(args.seedtest) / ".structure_sets")
    if not Path(struct_sets_path).exists():
        print(f"ERROR: structure sets not extracted at {struct_sets_path}")
        print("Run ./dev seed-roll once to extract them.")
        return 1

    from seed_paths import biome_params_path as _resolve_params
    biome_params_path = str(_resolve_params(args.seedtest))
    if not Path(biome_params_path).exists():
        print(f"ERROR: biome params not found at {biome_params_path}")
        return 1

    # Seed-group rolling: dims with byte-identical generation config share
    # every seed's measurements — group them so each group's seeds are
    # measured once and banked for every member. Worlds and unique dims
    # stay singletons (fingerprint None never groups).
    fingerprints = {d["name"]: generation_fingerprint(d) for d in dims}
    groups = {}
    for name, profile in all_targets:
        fp = fingerprints.get(name)
        key = fp if fp is not None else f"solo:{name}"
        groups.setdefault(key, []).append((name, profile))

    tasks = []
    for members in groups.values():
        tasks.append((members, args.tier1_pool, args.count,
                      struct_sets_path, biome_params_path, noise_configs, set(seen)))

    num_workers = args.workers or min(multiprocessing.cpu_count(), len(tasks))
    csv_path = args.output_csv or str(Path(args.seedtest) / "fast-roller.csv")

    multi = [m for m in groups.values() if len(m) > 1]
    total_seeds = len(groups) * args.tier1_pool
    print(f"Fast roller: {len(all_targets)} targets in {len(groups)} groups")
    if multi:
        print(f"  Shared-generation groups: {len(multi)} covering "
              f"{sum(len(m) for m in multi)} dims (measured once per group)")
        for m in multi:
            print(f"    [{len(m)}] {', '.join(name for name, _p in m)}")
    print(f"  Tier 1: {args.tier1_pool} seeds/group (structure screening)")
    print(f"  Tier 2: top {args.count}/member, union per group (biome + terrain)")
    print(f"  Workers: {num_workers}, output: {csv_path}")
    t0 = time.time()

    t1_total = total_seeds
    progress = {
        "tier1": multiprocessing.Value("q", 0),
        "tier2": multiprocessing.Value("q", 0),
        "tier2_total": multiprocessing.Value("q", 0),
    }
    _init_progress(progress)      # the single-process path shares the parent's

    def _format_eta(done, total, elapsed):
        if done == 0:
            return "—"
        remaining = elapsed * (total - done) / done
        m, s = divmod(int(remaining), 60)
        h, m = divmod(m, 60)
        return f"{h}h{m:02d}m" if h else f"{m}m{s:02d}s"

    def _elapsed_str(elapsed):
        em, es = divmod(int(elapsed), 60)
        eh, em = divmod(em, 60)
        return f"{eh}h{em:02d}m" if eh else f"{em}m{es:02d}s"

    def _log_group(done, total, elapsed, group_results):
        pct = 100 * done / total
        eta = _format_eta(done, total, elapsed)
        names = [r[0] for r in group_results]
        acc = sum(r[2] for r in group_results)
        label = names[0] if len(names) == 1 else f"{len(names)} members"
        print(f"  [{done}/{total}] {pct:4.1f}% | {_elapsed_str(elapsed)} elapsed, "
              f"~{eta} remaining | {label}, {acc} accepted",
              flush=True)

    def _log_wait(done, total, elapsed):
        """Tick while groups are still running, reporting the INNER position.

        Group completion is far too coarse to estimate from: a run over 4
        groups can only ever report 0/25/50/75%, so an hour of tier 1 shows
        as `0/4` with no remaining time. The shared counters carry each
        worker's position within its own loop, which is what actually moves.
        """
        t1 = progress["tier1"].value
        t2 = progress["tier2"].value
        t2_total = progress["tier2_total"].value
        in_flight = min(num_workers, total - done)
        head = (f"  [{done}/{total} groups] {_elapsed_str(elapsed)} elapsed | "
                f"{in_flight} in flight")
        if t1 < t1_total:
            # ETA from the tier-1 fraction only: tier 2 has a different
            # per-item cost, so folding them into one number would be a
            # confident wrong answer rather than an honest partial one.
            pct = 100 * t1 / t1_total if t1_total else 0.0
            rate = t1 / elapsed if elapsed > 0 else 0
            print(f"{head} | tier 1 {t1:,}/{t1_total:,} ({pct:4.1f}%) "
                  f"~{_format_eta(t1, t1_total, elapsed)} left at {rate:,.0f} seeds/s",
                  flush=True)
        else:
            known = f"/{t2_total:,}" if t2_total else ""
            print(f"{head} | tier 2 {t2:,}{known} candidates measured",
                  flush=True)

    csv_new = not Path(csv_path).exists()
    csv_fh = open(csv_path, "a")
    if csv_new:
        csv_fh.write("target,seed,metric,value\n")

    def _flush_group_csv(group_results):
        acc = rej = 0
        for dim_name, results, a, r, *_ in group_results:
            acc += a
            rej += r
            for seed, rows, _ok in results:
                for metric, value in rows:
                    csv_fh.write(f"{dim_name},{seed},{metric},{value}\n")
        csv_fh.flush()
        return acc, rej

    grouped_results = []
    total_accepted = 0
    total_rejected = 0
    n_tasks = len(tasks)
    if num_workers > 1 and n_tasks > 1:
        with multiprocessing.Pool(num_workers, initializer=_init_progress,
                                  initargs=(progress,)) as pool:
            pending = pool.imap_unordered(_process_group, tasks)
            while True:
                try:
                    group = pending.next(timeout=PROGRESS_TICK_SECONDS)
                except multiprocessing.TimeoutError:
                    _log_wait(len(grouped_results), n_tasks, time.time() - t0)
                    continue
                except StopIteration:
                    break
                grouped_results.append(group)
                a, r = _flush_group_csv(group)
                total_accepted += a
                total_rejected += r
                _log_group(len(grouped_results), n_tasks,
                           time.time() - t0, group)
    else:
        for t in tasks:
            group = _process_group(t)
            grouped_results.append(group)
            a, r = _flush_group_csv(group)
            total_accepted += a
            total_rejected += r
            _log_group(len(grouped_results), n_tasks,
                       time.time() - t0, group)
    csv_fh.close()
    all_results = [r for group in grouped_results for r in group]

    elapsed = time.time() - t0

    # Fold into candidate store
    print("\nFolding into candidate store...")
    spec = importlib.util.spec_from_file_location(
        "score_dimensions",
        str(SCRIPT_DIR / "score-dimensions.py"))
    sd = importlib.util.module_from_spec(spec)
    # Register BEFORE exec: the census backfill hands `sd._census_task` to a
    # multiprocessing pool, and pickling a function is pickling a
    # (module, qualname) pair — so the module has to be importable by that
    # name in the child or the whole roll dies with
    # "Can't pickle <function _census_task>: No module named 'score_dimensions'".
    # Running score-dimensions.py directly hides this, because there the
    # function lives in __main__.
    sys.modules[spec.name] = sd
    spec.loader.exec_module(sd)

    # score-dimensions.main() is never called here, so the bank root it
    # normally sets is the one set at the top of this function.
    fargs = argparse.Namespace(
        config=args.config, seedtest=args.seedtest, csv=csv_path,
        census_workers=args.census_workers, census_top=args.census_top)

    profiles = {name: prof for name, prof in all_targets}
    data = sd.gather_measurements(fargs)
    # Noise census (spike F2): the structure layout each accepted seed
    # actually produces. Computed once per candidate and cached in the store,
    # so this is the slow part of the FIRST roll of a large dimension and
    # free on every rescore afterwards.
    sd.attach_battery_groups(profiles, args.seedtest, args.config)
    sd.ensure_spawn_distances(fargs, profiles, data)
    sd.ensure_censuses(fargs, config, profiles, data)
    sd.ensure_terrain_surveys(fargs, config, profiles, data)
    results_scored, rejected_counts = sd.score_all(profiles, data)
    sd.persist_candidates(fargs, config, profiles, results_scored, data)

    # Summary
    print(f"\n{'dimension':30} {'pool':>5} {'t1→':>4} {'acc':>4} "
          f"{'t1ms':>6} {'t2ms':>6} {'total':>7} {'best':>6}  spawn")
    print("-" * 105)
    for dim_name, results, acc, rej, pool, surv, t1ms, t2ms, tms in all_results:
        cands = results_scored.get(dim_name, [])
        best = cands[0] if cands else None
        bscore = f"{best['score']:.1f}" if best else "—"
        bspawn = best['spawn_biome'] if best else ""
        print(f"{dim_name:30} {pool:>5} {surv:>4} {acc:>4} "
              f"{t1ms:>5.0f}ms {t2ms:>5.0f}ms {tms:>6.0f}ms {bscore:>6}  {bspawn}")

    t1_total = sum(r[6] for r in all_results)
    t2_total = sum(r[7] for r in all_results)
    print(f"\nTotal: {elapsed:.1f}s wall, {t1_total/1000:.1f}s tier-1, {t2_total/1000:.1f}s tier-2")
    print(f"Accepted: {total_accepted}, Rejected: {total_rejected}")
    print(f"Tier-1 rate: {total_seeds / (t1_total/1000):.0f} seeds/sec (structure-only)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
