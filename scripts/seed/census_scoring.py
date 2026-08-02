#!/usr/bin/env python3
"""census_scoring.py — score a seed's noise-placed structure layout.

Spike task F2. Noise placement controls whole structure GROUPS rather than a
vanilla per-structure grid, so "how far is the nearest village" is not a
statement about the world: the villages set has no grid of its own, only a
share of the `settlements` group's noise field. `want_score(nearest)` in
score-dimensions.py answers a question the world does not ask.

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

Properties that are load-bearing, and why:

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
     outweighs it (0.6/0.4). Forced placements and the 227 custom-placement sets that keep
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

# Placements inside a want's band that count as full satisfaction, for the
# group-level reading. Retained because the detail panel still explains a
# want in these terms and because a share of 1.0 makes it moot.
WANT_BAND_TARGET = 2.0

# A want whose group exists but put nothing in the wanted band still earns
# this — the structure IS reachable, just not where the author asked.
WANT_WRONG_RING_SCORE = 0.25


def load_structure_pools(seedtest):
    """{dimension: {group: {structure_id: weight}}} from the warmup dump.

    Which structures are eligible for a group is decided server-side by
    NoisePoolBuilder, using each structure's own biome list against the
    dimension's biome source — so the roller cannot derive it and has to be
    told. The dump is a warmup artefact alongside biome_params.json, and it
    is cheap: pools only, no positions.

    -> {} when absent, and every caller then falls back to share 1.0 and the
    old group-level reading. The attribution improves scoring where the data
    exists; it never blocks it.
    """
    import json
    from pathlib import Path
    p = Path(seedtest) / "structure_pools.json"
    if not p.exists():
        return {}
    try:
        doc = json.loads(p.read_text())
    except (OSError, ValueError):
        return {}
    # A dump is only meaningful under the absorption rules it was made with:
    # a stale pre-conversion dump lacks newly absorbed sets and would score
    # them 0.0% forever. Mismatched (or unstamped legacy) dumps are ignored —
    # every share then falls back to 1.0, the pre-pool behaviour — until the
    # next warmup re-dumps under the current list.
    from structure_placement import NOISE_MANAGED_PLACEMENT_TYPES
    stamped = doc.get("placementTypes") if isinstance(doc, dict) else None
    if sorted(stamped or []) != sorted(NOISE_MANAGED_PLACEMENT_TYPES):
        return {}
    return doc.get("dimensions") or doc


def weight_share(pools, dim_name, group, structure_id):
    """This structure's weight as a fraction of its group's pool.

    -> 1.0 when the pool is UNKNOWN, which is the "behave as before" answer: a
    share of 1 makes presence_probability collapse to the group-level
    present/absent test, so a bank scored without pool data is unaffected.

    -> 0.0 when the pool is KNOWN and EMPTY. These two cases must not be
    conflated. An empty pool means the biome filter left this dimension with no
    structures in the group at all, so nothing there can generate — and the
    Python census mirror cannot discover that for itself, because
    resolve_groups() knows the group's config but not which structures survived
    the filter. The share is the only place that correction can happen: without
    it the mirror reports positions for a group the server never placed.
    """
    by_dim = (pools or {}).get(dim_name)
    if by_dim is None:
        return 1.0
    pool = by_dim.get(group)
    if pool is None:
        return 1.0
    total = float(sum(pool.values()))
    if total <= 0:
        return 0.0
    clean = structure_id.lstrip("#")
    if clean in pool:
        return float(pool[clean]) / total
    # Tag wants name no single structure. Their share is every pool member
    # whose id contains the tag's path — asking for "a village" when the pool
    # holds nine village variants is asking for any of the nine.
    if structure_id.startswith("#"):
        tag = clean.split(":")[-1] if ":" in clean else clean
        matched = sum(w for sid, w in pool.items() if tag in sid)
        if matched:
            return float(matched) / total
    # In the group but not in the pool: the biome filter dropped it, so this
    # dimension cannot generate it at all.
    return 0.0


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


def presence_probability(entry, lo_blocks, hi_blocks, radius_chunks, share):
    """P(at least one of THIS structure lands in the band).

    `share` is the structure's weight as a fraction of its group's pool. A
    noise position is one draw from that pool, so with `n` positions in the
    band the chance none of them is this structure is `(1 - share)^n`.

    THIS IS THE FIX FOR THE GROUP/STRUCTURE CONFLATION. The census knows how
    many positions a GROUP put in a ring; it does not know which structure
    landed on each. Scoring the group count directly is wrong in both
    directions:

      - a shun fails whenever its group is present, and an enabled group is
        populated by definition, so every satisfiable-looking shun scores
        zero on every seed;
      - a want is credited whenever ANY group member reaches the band, so
        asking for a Village is really asking for any one of forty
        settlement types.

    A share turns both into the same question asked from opposite ends: at
    `share = 1.0` (the structure is its group's only member) present means
    certain and absent means impossible — the group-level reading.
    """
    if entry is None or entry.get("count", 0) <= 0:
        return 0.0
    n = band_mass(entry, lo_blocks, hi_blocks, radius_chunks)
    if n <= 0.0:
        return 0.0
    if share >= 1.0:
        return 1.0
    if share <= 0.0:
        return 0.0
    return 1.0 - (1.0 - share) ** n


def census_want_score(entry, band, radius_chunks, share=1.0):
    """A want answered by its group's layout rather than a grid distance.

    `share` defaults to 1.0 so a caller with no pool data scores exactly as
    before — the attribution is an improvement where it is available, never a
    prerequisite.
    """
    if entry is None or entry.get("count", 0) <= 0:
        return 0.0
    lo, hi = band
    if band_mass(entry, lo, hi, radius_chunks) <= 0.0:
        # The group exists but put nothing in this ring. Distinct from "the
        # structure is not in this world", and scored as such.
        return WANT_WRONG_RING_SCORE
    return presence_probability(entry, lo, hi, radius_chunks, share)


def census_shun_score(entry, threshold_blocks, radius_chunks, share=1.0):
    """A shun: the probability the unwanted structure is NOT inside the
    threshold. The exact complement of the want."""
    if entry is None or entry.get("count", 0) <= 0:
        return 1.0
    return 1.0 - presence_probability(
        entry, 0.0, threshold_blocks, radius_chunks, share)


def blend(census_part, battery_part):
    """Combine the two structure views, using whichever exist."""
    if census_part is None:
        return battery_part
    if battery_part is None:
        return census_part
    return CENSUS_WEIGHT * census_part + BATTERY_WEIGHT * battery_part
