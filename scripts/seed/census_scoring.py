#!/usr/bin/env python3
"""census_scoring.py — score a seed's noise-placed structure layout.

Spike task F2. Noise placement (2026-07-26) replaced the vanilla grid for
whole structure GROUPS, so "how far is the nearest village" stopped being a
statement about the world: the villages set no longer has its own grid, it
has a share of the `settlements` group's noise field. `want_score(nearest)`
in score-dimensions.py answers a question the world no longer asks.

What replaces it, per the spike:

  - **distribution match** — bin a group's census positions by radial decile,
    convert to a per-annulus DENSITY (equal-width bins cover unequal areas),
    and compare that against the group's own radial curve by cosine
    similarity. This is the direct measure of "settlements ended up near
    spawn" / "endgame ended up at the border".
  - **count satisfaction** — a group that resolved but placed almost nothing
    is the real failure mode (`the_overgrowth` shipped with zero settlements
    before the frequency fix), so counts are scored against a small
    radius-aware floor rather than a modelled expectation.
  - **wants / shuns as existence checks** — a want's band is answered from
    its GROUP's histogram (band occupancy), not from a grid distance. Sets
    that noise never took over — forced placements, and everything whose
    runtime placement is not RandomSpreadStructurePlacement — keep the old
    positional scoring, because for them it is still true.

DEVIATIONS from the spike, and why (see NOISE-IMPL-LOG.md):

  1. `expected_count(profile, radius, group)` is not modelled from the
     profile's hit rate. Placement density is eligibility (a nonlinear
     function of the noise value distribution) thinned by a rank filter whose
     strength depends on the local eligible density — any closed form would
     be a fitted constant wearing a derivation. The floor below targets the
     failure the spike actually cares about and says so.
  2. Scoring lives here rather than inside score-dimensions.py: that file is
     already 1400 lines, and a module is importable by the tests and by
     fast_roller without the importlib dance score-dimensions.py needs.
  3. The census component does not fully REPLACE the want battery, it
     outweighs it (0.6/0.4). Forced placements and the ~155 sets that keep
     grid placement are still positionally real, and an author's
     `structures.force` is the strongest statement of intent in the schema.
"""

import math

# Weight of the group-distribution term against the want/shun battery when a
# dimension has both. The census describes the world; the battery describes
# what the author asked for and is still exact for forced and grid-placed
# sets.
CENSUS_WEIGHT = 0.6
BATTERY_WEIGHT = 0.4

# Within the census term: shape first, presence second.
SHAPE_WEIGHT = 0.7
COUNT_WEIGHT = 0.3

# A group is "populated enough" at this many placements. Deliberately a
# FLOOR, not an expectation: 1 per 16 chunks of radius, at least 3. A
# 64-chunk pocket dimension wants 4 settlements, a 512-chunk world wants 32 —
# both are trivially cleared by a healthy group and both catch the "resolved
# but empty" failure the frequency scaling was introduced to fix.
COUNT_FLOOR_DIVISOR = 16
COUNT_FLOOR_MIN = 3

# A group that resolved and placed nothing scores this, not zero: the
# dimension is still playable, it is just missing a category (spike F2).
EMPTY_GROUP_SCORE = 0.2

# Placements inside a want's band that count as full satisfaction.
WANT_BAND_TARGET = 2.0

# A want whose group exists but put nothing in the wanted band still earns
# this — the structure IS reachable, just not where the author asked.
WANT_WRONG_RING_SCORE = 0.25


def annulus_areas(bins):
    """Relative area of each equal-width radial bin (proportional to 2i+1)."""
    return [float(2 * i + 1) for i in range(bins)]


def desired_profile(radial, bins):
    """The radial curve sampled at each bin's centre.

    `None` (no curve configured) is a flat 1.0, matching
    noise_placement.radial_weight.
    """
    if not radial:
        return [1.0] * bins
    out = []
    last = len(radial) - 1
    for i in range(bins):
        fraction = (i + 0.5) / bins
        scaled = fraction * last
        lo = int(scaled)
        hi = min(lo + 1, last)
        t = scaled - lo
        out.append(radial[lo] + t * (radial[hi] - radial[lo]))
    return out


def cosine_similarity(a, b):
    """0.0 for orthogonal or degenerate input; 1.0 for parallel."""
    na = math.sqrt(sum(v * v for v in a))
    nb = math.sqrt(sum(v * v for v in b))
    if na <= 0.0 or nb <= 0.0:
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    return max(0.0, min(1.0, dot / (na * nb)))


def distribution_match(hist, radial):
    """How well a group's radial spread matches the curve it was given.

    `hist` is the equal-width radial histogram from
    noise_placement.census_summary. Counts are divided by the bin's annulus
    area first: a uniform layout puts MORE structures in the outer bins
    simply because they are bigger, and comparing raw counts to a flat curve
    would read that as a border bias.

    -> 0.0 (nothing placed, or the layout is orthogonal to the curve)
       through 1.0 (density profile parallel to the curve).
    """
    total = sum(hist)
    if total <= 0:
        return 0.0
    bins = len(hist)
    areas = annulus_areas(bins)
    density = [hist[i] / areas[i] for i in range(bins)]
    return cosine_similarity(density, desired_profile(radial, bins))


def count_floor(radius_chunks):
    """The "this group is actually populated" floor for a playable radius."""
    return max(COUNT_FLOOR_MIN, int(radius_chunks) // COUNT_FLOOR_DIVISOR)


def count_satisfaction(count, radius_chunks):
    """0.0 for an empty group, 1.0 once it clears the floor."""
    floor = count_floor(radius_chunks)
    if floor <= 0:
        return 1.0 if count > 0 else 0.0
    return max(0.0, min(1.0, count / float(floor)))


def group_score(entry, radius_chunks):
    """One group's contribution: shape, then presence."""
    if entry.get("count", 0) <= 0:
        return EMPTY_GROUP_SCORE
    shape = distribution_match(entry.get("hist") or [], entry.get("radial"))
    presence = count_satisfaction(entry["count"], radius_chunks)
    return SHAPE_WEIGHT * shape + COUNT_WEIGHT * presence


def distribution_component(census):
    """Mean group score across every group the dimension actually resolved.

    -> None when the dimension has no noise groups (suppressed, void,
    superflat, base world) so the caller can fall back to grid scoring.
    """
    if not census:
        return None
    groups = census.get("groups") or {}
    if not groups:
        return None
    radius_chunks = census.get("radiusChunks") or 0
    scores = [group_score(e, radius_chunks) for e in groups.values()]
    return sum(scores) / len(scores)


# ---------------------------------------------------------------------------
# Wants and shuns against the census
# ---------------------------------------------------------------------------
def band_mass(entry, lo_blocks, hi_blocks, radius_chunks):
    """How many of a group's placements fall inside a block-distance band.

    Bins are radial slices of the playable radius, so a band that cuts a bin
    takes that bin's share pro rata — the histogram has no finer resolution
    and pretending otherwise would invent precision.
    """
    hist = entry.get("hist") or []
    if not hist or radius_chunks <= 0:
        return 0.0
    radius_blocks = radius_chunks * 16.0
    bins = len(hist)
    width = radius_blocks / bins
    total = 0.0
    for i, count in enumerate(hist):
        if not count:
            continue
        bin_lo = i * width
        bin_hi = bin_lo + width
        overlap = min(bin_hi, hi_blocks) - max(bin_lo, lo_blocks)
        if overlap <= 0:
            continue
        total += count * (overlap / width)
    return total


def census_want_score(entry, band, radius_chunks):
    """A want answered by its group's layout rather than a grid distance."""
    if entry is None or entry.get("count", 0) <= 0:
        return 0.0
    lo, hi = band
    mass = band_mass(entry, lo, hi, radius_chunks)
    if mass <= 0.0:
        return WANT_WRONG_RING_SCORE
    return min(1.0, mass / WANT_BAND_TARGET)


def census_shun_score(entry, threshold_blocks, radius_chunks):
    """A shun: any placement inside the threshold costs the point."""
    if entry is None or entry.get("count", 0) <= 0:
        return 1.0
    return 0.0 if band_mass(entry, 0.0, threshold_blocks, radius_chunks) > 0.0 else 1.0


def blend(census_part, battery_part):
    """Combine the two structure views, using whichever exist."""
    if census_part is None:
        return battery_part
    if battery_part is None:
        return census_part
    return CENSUS_WEIGHT * census_part + BATTERY_WEIGHT * battery_part
