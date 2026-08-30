#!/usr/bin/env python3
"""Report explicit biome bands that win no ground in their own dimension's climate.

Purpose:  A band can sit inside the range its world crosses and still generate
          nothing. check-band-reach.py asks whether a band is reachable at all;
          this asks whether it wins any of the world against the bands beside
          it. Both faults are silent in game and neither answers the other.

Context:  Fitting a partition by equal area over one axis assumes that axis is a
          band's only constraint. Two shipped shapes break it. A clamped axis
          collapses several boundaries onto one rail value and the minimum-width
          floor pushes them apart into hairlines that catch nothing. A band
          carrying a filter on a second axis wins only where the filter passes,
          and on a fine partition an unfiltered neighbour is nearer by an order
          of magnitude, so the filter acts as a gate rather than a preference.
          Before this check, 75 bands over 26 dimensions produced nothing while
          check-band-reach.py read 0 dead of 821.

Usage:    scripts/check-band-share.py            # exits 1 on a band with no ground
          scripts/check-band-share.py --verbose  # every dimension, biggest share

Gotchas:  - This is vanilla's nearest-hypercube lookup, not a per-axis
            comparison. An unstated axis spans -2..2 and costs nothing, which is
            why a band constraining one axis beats a native point almost
            everywhere.
          - 121 samples over the playable square. A band winning none of them
            holds under about 0.8% of the world, which is a strong signal in a
            10-band partition (fair share 12 samples) and a weak one in a
            30-band partition (fair share 4). It is not proof of impossibility.
          - depth is evaluated at one representative per layer, surface and
            cave. Bands within a layer share their depth range exactly, so the
            ranking inside a layer does not depend on the value chosen.
          - A dimension with plain-string natives is NOT JUDGED: the natives
            keep hypercubes from the base source that this script cannot read,
            and they compete for the same points.
          - Samples come from climate-axes.json. A measured dimension missing
            its samples block FAILS rather than being skipped — a check that
            quietly stops checking is worse than no check.
"""
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DIMS = REPO / "config/custom-dimensions/dimensions"
AXES_FILE = REPO / "config/custom-dimensions/climate-axes.json"

AXES = ["temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"]
KEY = {"temperature": "temp", "humidity": "humid", "continentalness": "cont",
       "erosion": "eros", "weirdness": "weird"}
FULL = (-2.0, 2.0)
# One representative depth per layer: below and above every shipped split.
LAYERS = {"surface": -0.5, "cave": 0.5}
# One biome holding this many times an even share of its own partition.
CROWDED = 3.0

# Bands with no ground that no fit or axis switch has cleared. A ratchet, not a
# licence: a band absent from here that wins nothing fails the run, and an entry
# here that starts winning fails it too, so the list can only shrink. Every one
# carries a filter on a second axis whose passing region sits inside a
# neighbour's slot, so sizing the slot cannot reach it. See K7.
KNOWN_EMPTY = {
    ("the_blossom_gardens", "minecraft:old_growth_birch_forest"),
    ("the_blossom_gardens", "minecraft:sunflower_plains"),
    ("the_blossom_gardens", "terralith:sakura_valley"),
    ("the_blossom_gardens", "terralith:lavender_forest"),
    ("the_blossom_gardens", "natures_spirit:lavender_fields"),
    ("the_blossom_gardens", "natures_spirit:floral_ridges"),
    ("the_frozen_hearth", "minecraft:frozen_peaks"),
    ("the_gritlands", "terralith:ashen_savanna"),
    ("the_highland_crossing", "minecraft:plains"),
    ("the_highland_crossing", "terralith:highlands"),
    ("the_highland_crossing", "regions_unexplored:spires"),
    ("the_highland_crossing", "regions_unexplored:fen"),
    ("the_roothold", "incendium:withered_forest"),
    ("the_sun_kingdoms", "minecraft:badlands"),
    ("the_sun_kingdoms", "minecraft:eroded_badlands"),
    ("the_sun_kingdoms", "terralith:lush_desert"),
    ("the_sun_kingdoms", "terralith:desert_oasis"),
    ("the_sun_kingdoms", "terralith:desert_canyon"),
    ("the_sun_kingdoms", "natures_spirit:oak_savanna"),
    ("the_whispering_wilds", "terralith:moonlight_grove"),
}


def rng(params, axis):
    v = params.get(axis)
    if v is None:
        return FULL
    if isinstance(v, (int, float)):
        return (float(v), float(v))
    return (float(v[0]), float(v[1]))


def squared_distance(cube, point):
    total = 0.0
    for axis in AXES:
        lo, hi = cube[axis]
        v = point[axis]
        d = lo - v if v < lo else (v - hi if v > hi else 0.0)
        total += d * d
    return total


def wins(bands, samples, depth):
    """Sample count per band id at one depth, by nearest hypercube."""
    got = {bid: 0 for bid, _ in bands}
    n = len(next(iter(samples.values())))
    for i in range(n):
        point = {a: (depth if a == "depth" else samples[KEY[a]][i]) for a in AXES}
        best, bestd = None, None
        for bid, cube in bands:
            d = squared_distance(cube, point)
            if bestd is None or d < bestd:
                best, bestd = bid, d
        got[best] += 1
    return got, n


def main():
    verbose = "--verbose" in sys.argv
    table = json.loads(AXES_FILE.read_text())["perDimension"]
    silent = crowded = judged = 0
    natives_skipped = []
    unmeasured = []
    seen_empty = set()

    for path in sorted(DIMS.glob("*.json")):
        slug = path.stem
        if slug.endswith("_thumb"):
            continue
        cfg = json.loads(path.read_text())
        cfg = cfg.get("overrides", cfg)
        entries = cfg.get("biomes") or []
        bands = [(e["id"], {a: rng(e["parameters"], a) for a in AXES}) for e in entries
                 if isinstance(e, dict) and isinstance(e.get("parameters"), dict)]
        if not bands:
            continue
        if slug not in table:
            unmeasured.append(slug)
            continue
        samples = table[slug].get("samples")
        if not samples:
            print(f"{slug}: measured at its own border but climate-axes.json carries no "
                  f"samples block — this check cannot run and will not pretend to")
            return 1
        if any(isinstance(e, str) for e in entries):
            natives_skipped.append(slug)
            continue

        judged += len(bands)
        held = {bid: 0 for bid, _ in bands}
        worst = (0.0, None, 0)
        for depth in LAYERS.values():
            members = [b for b in bands if b[1]["depth"][0] <= depth <= b[1]["depth"][1]]
            if not members:
                continue
            got, n = wins(bands, samples, depth)
            for bid, c in got.items():
                held[bid] += c
            top = max(((got[bid], bid) for bid, _ in members), default=(0, None))
            if top[0] / n > worst[0]:
                worst = (top[0] / n, top[1], len(members))

        for bid, _cube in bands:
            if held[bid] == 0:
                seen_empty.add((slug, bid))
                if (slug, bid) in KNOWN_EMPTY:
                    continue
                silent += 1
                print(f"{slug}: {bid} wins none of {len(samples['temp'])} samples — the band is "
                      f"reachable but every point in the world is nearer to another")
        if worst[1] and worst[0] * worst[2] >= CROWDED:
            crowded += 1
            print(f"{slug}: {worst[1]} holds {worst[0] * 100:.0f}% of a {worst[2]}-band "
                  f"partition, {worst[0] * worst[2]:.1f}x an even share")
        elif verbose and worst[1]:
            print(f"{slug}: biggest share {worst[0] * 100:.0f}% ({worst[1]}), "
                  f"{worst[0] * worst[2]:.1f}x an even share of {worst[2]} bands")

    stale = sorted(KNOWN_EMPTY - seen_empty)
    for slug, bid in stale:
        print(f"{slug}: {bid} now wins ground — remove it from KNOWN_EMPTY in this script")

    print(f"\njudged {judged} band(s) across measured dimensions with no native biomes")
    print(f"  {silent} win no ground and are not on the known list")
    print(f"  {len(KNOWN_EMPTY) - len(stale)} known to win no ground (K7)")
    print(f"  {crowded} dimension(s) where one biome holds {CROWDED:.0f}x an even share — "
          f"reported, not a failure: an uneven partition can be the design")
    if natives_skipped:
        print(f"  {len(natives_skipped)} dimension(s) NOT JUDGED — they list plain-string native "
              f"biomes, whose hypercubes come from the base source and compete for the same "
              f"points: {', '.join(natives_skipped[:6])}"
              f"{'...' if len(natives_skipped) > 6 else ''}")
    if unmeasured:
        print(f"  {len(unmeasured)} dimension(s) have bands but no measurement — skipped, "
              f"not guessed: {', '.join(unmeasured[:6])}{'...' if len(unmeasured) > 6 else ''}")
    return 1 if (silent or stale) else 0


if __name__ == "__main__":
    sys.exit(main())
