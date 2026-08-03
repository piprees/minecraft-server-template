#!/usr/bin/env python3
"""terrain_survey.py — relief, grain and water measured over the whole world.

WHAT WAS WRONG

The roller's terrain metrics came from a 3x3 climate grid at
`grid_pitch(radius) = max(64, min(512, radius/4))`. For an 8192-radius dimension
that pitch is 512, so the three sample columns sit at -512, 0 and +512: a
1024-block box centred on spawn, against a render covering 32768 blocks. That is
**0.1% of the picture's area**, and all of it in the middle.

The visible symptom was an overworld winner reporting `water 0% — almost no
water` beside a render that is a third ocean. It was not a broken water set —
every one of the 13 WATER_BIOMES entries is present in the overworld sampler and
nothing name-suggestive is missing. The grid simply never looked at the ocean.

And it is not only water. Relief measured as max-minus-min over nine points
inside 6% of the radius describes the hill next to spawn, not the world.

WHAT THIS DOES

Re-measures all three metrics on a 9x9 grid spanning the FULL playable radius,
post-hoc from the banked seed. Costs 37ms per candidate (measured, radius 8192),
so a 13k-candidate bank is about a minute across eight cores, cached per
candidate afterwards.

WHY POST-HOC RATHER THAN A WIDER GRID IN THE ROLLER

Changing what fast_roller measures means every banked measurement describes a
grid that no longer exists, so every one of 81 dimensions has to be re-rolled to
benefit. This is a RESCORE: it reads the banked seed, recomputes, and caches. The
existing rows stay valid as the fallback for anything not yet surveyed.

WHY EVERY CANDIDATE AND NOT JUST THE TOP N

Because it changes the SCORE. `biome_survey` enriches the top N only, which is
fine for something displayed; an enrichment that moves a candidate's score has to
cover the whole bank or the ranking compares surveyed candidates against
unsurveyed ones and quietly favours whichever the estimate happened to flatter.

Lives in its own module, not in score-dimensions.py, for the reason
noise_placement.census_task documents: pickling a function pickles a (module,
qualname) pair and the child re-imports that module BY NAME, so anything handed
to a multiprocessing Pool must live in a file whose name is a legal identifier.
`score-dimensions.py` is not.
"""

# 9x9 = 81 samples spanning the whole radius. Chosen against measurement: 3x3
# costs 4ms and sees 0.1% of the area, 9x9 costs 37ms, 17x17 costs 131ms for
# little more signal. 81 samples over the full extent beats 289 over the same
# extent by far less than it beats 9 over a sliver of it.
GRID = 9

# How far RELIEF and GRAIN are allowed to look. Water is not capped.
#
# Relief is max-minus-min over the sampled grid, so it grows with the area
# sampled whatever the terrain does — a wider window simply catches more of the
# continentalness range. Measured across the bank at full radius: median relief
# 15 blocks at radius 256 and 96 at radius 8192, a ~6x spread produced by the
# window and not by the worlds. One target window cannot describe both, and
# TERRAIN_TARGETS is a single set of absolute block figures.
#
# Capping the window at 2048 blocks makes every dimension of radius >= 2048 —
# which is most of them, and all four base worlds — measure relief and grain
# over the SAME 4096-block box, so their numbers are directly comparable and a
# single window means one thing. Below 2048 a dimension is genuinely smaller
# than the window and is measured over its own extent, which is the honest
# reading: a 256-radius pocket has no terrain at 2048 blocks to have an opinion
# about.
#
# WATER DELIBERATELY KEEPS THE FULL RADIUS. It is a FRACTION, so it does not
# scale with the window, and the whole reason the full-radius survey exists is
# that the 3x3 box at spawn could not see the ocean (an overworld winner
# reported 0% water beside a render a third sea). Capping it would walk that
# back for every dimension larger than the cap.
RELIEF_SPAN = 2048

# Mirrors fast_roller.WATER_BIOMES. Duplicated rather than imported: fast_roller
# pulls in the whole rolling pipeline, and this module is imported by a
# multiprocessing child that only needs a biome sampler. Verified complete for
# the overworld family — all 13 present, no ocean/river/lake biome missing.
WATER_BIOMES = {
    "minecraft:ocean", "minecraft:deep_ocean", "minecraft:cold_ocean",
    "minecraft:deep_cold_ocean", "minecraft:frozen_ocean",
    "minecraft:deep_frozen_ocean", "minecraft:lukewarm_ocean",
    "minecraft:deep_lukewarm_ocean", "minecraft:warm_ocean",
    "minecraft:river", "minecraft:frozen_river",
    "terralith:deep_warm_ocean", "terralith:warm_river",
}

# Mirrors fast_roller._CONT_TO_HEIGHT so the surveyed relief is on the same scale
# as the 3x3 relief it replaces — otherwise the terrain targets in
# dimension_profiles.TERRAIN_TARGETS would be comparing against a different unit.
_CONT_TO_HEIGHT = [
    (-1.2, 40), (-0.455, 55), (-0.19, 62), (-0.11, 63),
    (0.03, 70), (0.3, 100), (0.55, 140), (0.8, 190), (1.0, 256),
]


def cont_to_height(cont):
    """Continentalness -> an approximate surface height, piecewise-linear.

    MIRRORS fast_roller._cont_to_height exactly — change both together, or a
    surveyed candidate and an unsurveyed one stop being comparable, which is
    worse than either number being slightly wrong.

    Including the quirk: below the table's first point this EXTRAPOLATES the
    first segment rather than clamping, so cont = -2.0 gives 23.9 rather than 40.
    Continentalness is nominally [-1, 1] so that branch should never fire, but a
    mirror that "fixed" it would diverge from the thing it mirrors. An earlier
    draft clamped here and the mirror test caught it.
    """
    table = _CONT_TO_HEIGHT
    for i in range(len(table) - 1):
        c0, h0 = table[i]
        c1, h1 = table[i + 1]
        if cont <= c1:
            t = (cont - c0) / (c1 - c0) if c1 != c0 else 0
            return h0 + t * (h1 - h0)
    return table[-1][1]


def metrics_from_grid(heights, waters, grid=GRID):
    """(relief, grain, water, land_fraction) from a square grid of samples.

    Same three definitions score-dimensions.terrain_metrics uses on the 3x3, so
    the numbers stay interchangeable:
      relief = max - min over the grid
      grain  = mean |dh| between orthogonally adjacent points
      water  = fraction of samples in a water biome

    `heights` is a {(row, col): height} map so a void dimension — which records
    no height at all — comes out with land_fraction 0 rather than a fake floor.
    """
    values = list(heights.values())
    relief = (max(values) - min(values)) if len(values) >= 2 else 0.0
    grains = []
    for (r, c), h in heights.items():
        for dr, dc in ((0, 1), (1, 0)):
            n = heights.get((r + dr, c + dc))
            if n is not None:
                grains.append(abs(h - n))
    grain = sum(grains) / len(grains) if grains else 0.0
    water = (sum(waters) / len(waters)) if waters else 0.0
    land = len(values) / float(grid * grid) if grid else 0.0
    return relief, grain, water, land


def _walk(sampler, span, grid, is_void, has_continentalness,
          want_water=True, want_heights=True, sea_level=None):
    """One `grid` x `grid` lattice over [-span, span]. -> (heights, waters, seen).

    Corner-inclusive: the first and last columns sit exactly on the edge of the
    span, so the samples cover the whole box rather than one inside it.

    The two `want_` flags exist because the two windows need different halves of
    the answer: the wide walk is for water only and the capped one for
    relief/grain only. Asking for both on both windows doubles the climate
    lookups — the expensive half — to compute heights that are then discarded.

    `seen` counts the biome at every sampled point — free, since the water
    test already resolves one per sample. See `shares` in survey().
    """
    step = (2 * span) // (grid - 1) if grid > 1 and span > 0 else 0
    heights, waters, seen = {}, [], {}

    def height_of(climate):
        if has_continentalness:
            return cont_to_height(climate.get("continentalness", 0.0))
        # Nether/end/paradise have no continentalness worth reading;
        # fast_roller uses erosion on the same 64 + 30x scale.
        return 64.0 + climate.get("erosion", 0.0) * 30.0

    for r in range(grid):
        for c in range(grid):
            x = -span + c * step
            z = -span + r * step
            climate = None
            if want_water:
                # Water is what the terrain does, not what the biome is
                # called: a column whose surveyed height sits below the
                # dimension's effective sea level is water even when the
                # biome list names no ocean — relying on the list alone
                # would describe the CONFIG, not the world. Biome identity
                # stays as the floor — an ocean biome is water whatever the
                # model says.
                if is_void or sea_level is None:
                    biome = sampler.biome_at(x, z)
                else:
                    biome, climate = sampler.biome_and_climate(x, z)
                seen[biome] = seen.get(biome, 0) + 1
                wet = biome in WATER_BIOMES
                if not wet and climate is not None:
                    wet = height_of(climate) < sea_level
                waters.append(1 if wet else 0)
            if is_void or not want_heights:
                continue
            if climate is None:
                climate = sampler.sample_climate(x, z)
            heights[(r, c)] = height_of(climate)
    return heights, waters, seen


def survey(sampler, radius, is_void=False, has_continentalness=True, grid=GRID,
           relief_span=RELIEF_SPAN, configured_biomes=(), sea_level=None):
    """Terrain metrics for one seed, on TWO windows for one reason each.

    - WATER (and land_fraction) over the full playable radius, because the
      question is "how much of this world is sea" and a box at spawn cannot
      answer it. This is the whole reason the survey replaced the 3x3.
    - RELIEF and GRAIN over min(radius, relief_span), because both are
      absolute block figures that grow with the window sampled: at full radius
      the bank's median relief runs 15 blocks at radius 256 to 96 at 8192, a
      spread the WINDOW produces rather than the worlds. See RELIEF_SPAN.

    When radius <= relief_span the two windows are identical and only one walk
    is done, which is the common case for pocket dimensions and keeps the cost
    at the measured 37ms. Larger dimensions pay for a second walk.
    """
    span = min(int(radius), int(relief_span)) if relief_span else int(radius)
    capped = span < int(radius)
    # The wide walk answers water. When a capped walk follows it, the wide one
    # needs no heights at all — computing them would be 81 discarded climate
    # lookups, which is the expensive half of a survey.
    heights, waters, seen = _walk(sampler, int(radius), grid, is_void,
                                  has_continentalness, want_water=True,
                                  want_heights=not capped,
                                  sea_level=sea_level)
    if capped:
        # Relief and grain get their own, tighter window; water keeps the wide
        # one measured above. land_fraction comes from this walk, which is
        # equivalent: every non-void sample records a height, so land is 1.0 for
        # any solid dimension and 0.0 for a void, whichever window is used.
        heights, _w, _s = _walk(sampler, span, grid, is_void,
                                has_continentalness, want_water=False,
                                want_heights=True)
    relief, grain, water, land = metrics_from_grid(heights, waters, grid)
    total = sum(seen.values()) or 1
    result = {
        "relief": round(relief, 3),
        "grain": round(grain, 4),
        "water": round(water, 5),
        "land": round(land, 5),
        "grid": grid,
        "radius": int(radius),
        # The window relief/grain were measured over. Recorded because it is
        # what makes them comparable across dimensions, and because a reader
        # of a cached survey needs to know which box the numbers describe.
        "reliefSpan": span,
        # How much of the play area each biome covers — the only measurement
        # that distinguishes a mixture from a monoculture (see T21).
        # Restricted to the configured biomes so the figure means "of what this
        # dimension asked for" and the record stays small.
        "shares": {b: round(seen.get(b, 0) / total, 5)
                   for b in (configured_biomes or ())},
    }
    if not configured_biomes:
        # No biome list (base worlds, single-biome dims): record what was
        # actually seen rather than nothing, capped so the record stays small.
        top = sorted(seen.items(), key=lambda kv: -kv[1])[:12]
        result["shares"] = {b: round(n / total, 5) for b, n in top}
    return result


def survey_task(task):
    """Pool worker: one candidate's terrain survey.

    `task` is (name, seed, spec) where `spec` carries only picklable primitives —
    the sampler is built HERE rather than passed in, because a BiomeSampler holds
    an open parameter table and would have to be re-read in the child anyway.

    `spec["sampler"]` is biome_sampler.sampler_spec(profile) — the water
    fraction is a scored input, so it has to be measured on the dimension's
    real biome source (see T20).
    """
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from biome_sampler import build_from_spec

    name, seed, spec = task
    sampler = build_from_spec(int(seed), spec["sampler"], spec["biome_params"])
    return (name, seed, survey(
        sampler, spec["radius"], is_void=spec["is_void"],
        has_continentalness=spec["has_continentalness"],
        configured_biomes=spec.get("configured_biomes") or (),
        sea_level=spec.get("sea_level")))


def _stack_key():
    """The stack that measured a survey, so a new one re-measures."""
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    import stack_version
    return stack_version.cache_key()


def fingerprint(generation_fp, radius, grid=GRID, relief_span=RELIEF_SPAN):
    """What a cached survey is valid FOR.

    The generation fingerprint already covers every input to the biome sampler
    (type, noise settings, the ordered biome list, per-biome parameters) plus
    more besides, so it is conservative: a change that cannot move a sample will
    occasionally invalidate a survey, which costs 37ms. The reverse — a stale
    survey read as current — is the failure that matters, and a superset cannot
    produce it.

    `radius`, `grid` and `relief_span` join it because they set WHERE the
    samples are taken and none of them is part of the generation payload.
    relief_span is in here so that changing the cap re-measures the bank rather
    than silently scoring new windows against cached old numbers — the whole
    point of the change is that the two are not the same figure.
    """
    return "%s:%s:%d:%d:%d" % (_stack_key(), generation_fp or "-",
                               int(radius), int(grid), int(relief_span))
