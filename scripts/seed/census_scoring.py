#!/usr/bin/env python3
"""census_scoring.py — score a seed's noise-placed structure layout.

Exact scoring (precision plan §3.4). Every number this module produces is
derived from the exact assigned-structure positions the pick algorithm places.
No probability model, no pro-rata bin arithmetic: the census knows which
structure lands on every site, so counts and distances are facts.

Properties that are load-bearing, and why:

  1. The census component does not fully REPLACE the want battery, it
     outweighs it (0.6/0.4). Forced placements and the custom-placement sets
     that keep grid placement are still positionally real, and an author's
     `structures.force` is the strongest statement of intent in the schema.
  2. Scoring lives here rather than inside score-dimensions.py: that file is
     already 1400 lines, and a module is importable by the tests and by
     fast_roller without the importlib dance score-dimensions.py needs.
"""

import math

# Weight of the group-distribution term against the want/shun battery when a
# dimension has both. The census describes the world; the battery describes
# what the author asked for and is still exact for forced and grid-placed
# sets.
CENSUS_WEIGHT = 0.6
BATTERY_WEIGHT = 0.4

# Radial annulus count when a summary carries no display hist to size from.
# MIRRORS noise_placement.CENSUS_BINS — the two must agree or a re-binned
# shape compares against a curve resampled at a different resolution.
DEFAULT_HIST_BINS = 10

# Within the census term: shape first, presence second.
SHAPE_WEIGHT = 0.7
COUNT_WEIGHT = 0.3

# A group is "populated enough" at this many placements. Deliberately a
# FLOOR, not an expectation: 1 per 16 chunks of radius, at least 3.
COUNT_FLOOR_DIVISOR = 16
COUNT_FLOOR_MIN = 3

# A group that resolved and placed nothing scores this, not zero: the
# dimension is still playable, it is just missing a category.
EMPTY_GROUP_SCORE = 0.2

# Assigned positions inside a want's band that count as full satisfaction.
WANT_BAND_TARGET = 2.0

# A want whose structure is assigned somewhere but zero assigned positions
# sit in the wanted band.
WANT_WRONG_RING_SCORE = 0.25


def load_structure_pools(seedtest):
    """{dimension: {group: {structure_id: weight}}} from the warmup dump.

    Which structures are eligible for a group is decided server-side by
    NoisePoolBuilder, using each structure's own biome list against the
    dimension's biome source — so the roller cannot derive it and has to be
    told. The dump is a warmup artefact alongside biome_params.json, and it
    is cheap: pools only, no positions.

    -> {} when absent, and every caller then falls back gracefully.
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
    from structure_placement import NOISE_MANAGED_PLACEMENT_TYPES
    stamped = doc.get("placementTypes") if isinstance(doc, dict) else None
    if sorted(stamped or []) != sorted(NOISE_MANAGED_PLACEMENT_TYPES):
        return {}
    return doc.get("dimensions") or doc


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


def radial_hist(positions, origin_cx, origin_cz, radius_chunks, bins):
    """Radial annulus counts for chunk positions around an origin.

    The single binning definition: the census writers (noise_placement) and
    the scorer both call this, so the shape a score sees is by construction
    the shape the exact positions have. Positions may be (cx, cz) pairs or
    (cx, cz, id) triples; extra elements are ignored.
    """
    hist = [0] * bins
    scale = float(radius_chunks) if radius_chunks > 0 else 1.0
    for pos in positions:
        dx = pos[0] - origin_cx
        dz = pos[1] - origin_cz
        b = int(math.sqrt(float(dx) * dx + float(dz) * dz) / scale * bins)
        if b < 0:
            b = 0
        elif b >= bins:
            b = bins - 1
        hist[b] += 1
    return hist


def group_score(entry, radius_chunks, positions=None, origin=None):
    """One group's contribution: shape, then presence.

    Shape is computed from the EXACT sidecar positions binned at score time
    (radial_hist) — the summary's stored hist is display-only and is never
    scored. A caller without positions for a populated group gets a loud
    error rather than a silent fallback to the display summary.
    """
    if entry.get("count", 0) <= 0:
        return EMPTY_GROUP_SCORE
    if positions is None:
        raise ValueError(
            "group_score requires exact sidecar positions for a populated "
            "group — the stored hist is display-only (run ensure_censuses)")
    ox, oz = origin or (0, 0)
    bins = len(entry.get("hist") or []) or DEFAULT_HIST_BINS
    hist = radial_hist(positions, ox, oz, radius_chunks, bins)
    shape = distribution_match(hist, entry.get("radial"))
    presence = count_satisfaction(entry["count"], radius_chunks)
    return SHAPE_WEIGHT * shape + COUNT_WEIGHT * presence


def distribution_component(census, positions_by_group=None, origin=None):
    """Mean group score across every group the dimension actually resolved.

    positions_by_group carries the exact sidecar positions per group
    (noise_placement.load_census_positions); origin is the candidate's spawn
    chunk. -> None when the dimension has no noise groups (suppressed, void,
    superflat, base world) so the caller can fall back to grid scoring.
    """
    if not census:
        return None
    groups = census.get("groups") or {}
    if not groups:
        return None
    radius_chunks = census.get("radiusChunks") or 0
    scores = [
        group_score(e, radius_chunks,
                    positions=(positions_by_group or {}).get(g)
                    if e.get("count", 0) > 0 else [],
                    origin=origin)
        for g, e in groups.items()
    ]
    return sum(scores) / len(scores)


# ---------------------------------------------------------------------------
# DISPLAY-ONLY: band_mass exists for the viewer's histogram overlays.
# Nothing in scoring reads it. It must never feed a score.
# ---------------------------------------------------------------------------
def band_mass(entry, lo_blocks, hi_blocks, radius_chunks):
    """DISPLAY-ONLY. Pro-rata bin overlap for the viewer's histogram bars.

    Never feeds a score — exact counts from byStructure replace it everywhere
    scoring cares.
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


# ---------------------------------------------------------------------------
# Exact wants and shuns (precision plan §3.4)
# ---------------------------------------------------------------------------
def census_want_score(structure_id, band, positions, spawn_cx=0, spawn_cz=0,
                      in_pool=True):
    """A want scored from exact assigned positions.

    positions: [(cx, cz, "ns:id"), ...] for the whole group (from sidecar).
    spawn_cx/cz: candidate's spawn in CHUNK coordinates.
    in_pool: whether structure_id appears in the group's pool weights.

    Returns:
      0.0                   if the structure is not in the pool
      WANT_WRONG_RING_SCORE if in pool but zero assigned positions in band
      min(1.0, count/TARGET) otherwise
    """
    if not in_pool:
        return 0.0
    lo, hi = band
    band_count = 0
    has_any = False
    for cx, cz, sid in (positions or []):
        if sid != structure_id:
            continue
        has_any = True
        dx = (cx - spawn_cx) * 16
        dz = (cz - spawn_cz) * 16
        dist = math.sqrt(float(dx) * dx + float(dz) * dz)
        if lo <= dist <= hi:
            band_count += 1
    if not has_any:
        return WANT_WRONG_RING_SCORE
    if band_count <= 0:
        return WANT_WRONG_RING_SCORE
    return min(1.0, band_count / WANT_BAND_TARGET)


def census_shun_score(structure_id, threshold_blocks, positions,
                      spawn_cx=0, spawn_cz=0, in_pool=True):
    """A shun: 1.0 iff no assigned position of this structure lies inside the
    threshold, else 0.0. Exact — computed from every position at score time.
    """
    if not in_pool:
        return 1.0
    for cx, cz, sid in (positions or []):
        if sid != structure_id:
            continue
        dx = (cx - spawn_cx) * 16
        dz = (cz - spawn_cz) * 16
        dist = math.sqrt(float(dx) * dx + float(dz) * dz)
        if dist < threshold_blocks:
            return 0.0
    return 1.0


def compute_by_structure(positions, sorted_pool, pick_seed_val,
                         spawn_cx, spawn_cz):
    """Derive byStructure from exact positions and the pick algorithm.

    positions:   [(cx, cz), ...] from NoiseFieldIndex
    sorted_pool: [(structure_id, weight), ...] sorted by id
    pick_seed_val: the pick seed for this group
    spawn_cx/cz: candidate's spawn in chunk coordinates

    Returns {structure_id: {count, nearest}} where:
      count   = exact number of positions assigned to this structure
      nearest = exact block distance from spawn to the closest position
    """
    import noise_placement
    by_struct = {}
    for cx, cz in positions:
        pv = noise_placement.priority(pick_seed_val, cx, cz)
        sid = noise_placement.resolve_structure(sorted_pool, pv)
        if sid is None:
            continue
        dx = (cx - spawn_cx) * 16
        dz = (cz - spawn_cz) * 16
        dist = math.sqrt(float(dx) * dx + float(dz) * dz)
        entry = by_struct.get(sid)
        if entry is None:
            entry = {"count": 0, "nearest": float("inf")}
            by_struct[sid] = entry
        entry["count"] += 1
        if dist < entry["nearest"]:
            entry["nearest"] = dist
    for entry in by_struct.values():
        if entry["nearest"] == float("inf"):
            entry["nearest"] = -1
    return by_struct


def blend(census_part, battery_part):
    """Combine the two structure views, using whichever exist."""
    if census_part is None:
        return battery_part
    if battery_part is None:
        return census_part
    return CENSUS_WEIGHT * census_part + BATTERY_WEIGHT * battery_part
