#!/usr/bin/env python3
"""Spatial statistics over a /customdim site-validity artefact.

Purpose:  Answer "do these structures form places, or noise" numerically.
          Reports Clark-Evans dispersion per group, the occupancy mix, and
          cross-class nearest-neighbour distances — the three measurements the
          placement redesign is judged on.

Context:  The mod's own `structures_form_places_not_noise` criterion scores
          Clark-Evans per group but keeps no baseline, and no cross-group
          statistic exists at all. This computes both offline from the
          artefact, so a before/after survives a seed-bank re-key.

Usage:    ./scripts/analyse-site-validity.py <artefact.json> [--json]
          Artefacts live at .seed-rolling/site-validity__<dim>__<seed>.json
          inside the mc container; copy one out with `docker exec mc cat`.

Gotchas:  Clark-Evans R: 1.0 is random, >1 dispersed (2.1491 = perfect
          lattice), <1 clustered. It is area-sensitive, so the disc radius is
          taken from the artefact's radiusBlocks, never assumed.
          Occupancy classes are inferred from structure ids by keyword here —
          the mod has no such table yet. Treat the class split as indicative
          until one exists.
"""
import argparse
import json
import math
import sys
from collections import Counter, defaultdict

ABANDONED = ("ruin", "abandoned", "wreck", "derelict", "crypt", "tomb", "forgotten",
             "lost", "decayed", "overgrown", "sunken", "shipwreck", "remnant")
HOSTILE = ("dungeon", "lair", "nest", "pillager", "outpost", "fortress", "bastion",
           "witch", "illager", "monster", "raider", "keep", "donjon")
INHABITED = ("village", "town", "settlement", "farm", "camp", "hamlet", "tavern",
             "market", "port", "city")


def occupancy(structure_id):
    """Rough occupancy class from the id. Order matters: abandoned wins."""
    s = structure_id.lower()
    for kw in ABANDONED:
        if kw in s:
            return "abandoned"
    for kw in HOSTILE:
        if kw in s:
            return "hostile"
    for kw in INHABITED:
        if kw in s:
            return "inhabited"
    return "neutral"


def nearest_neighbour_distances(points):
    """Mean nearest-neighbour distance, brute force over a bucketed grid."""
    if len(points) < 2:
        return []
    cell = 512
    grid = defaultdict(list)
    for x, z in points:
        grid[(x // cell, z // cell)].append((x, z))
    out = []
    for x, z in points:
        best = None
        gx, gz = x // cell, z // cell
        radius = 1
        while best is None or radius * cell < best:
            found = False
            for dx in range(-radius, radius + 1):
                for dz in range(-radius, radius + 1):
                    for ox, oz in grid.get((gx + dx, gz + dz), ()):
                        if ox == x and oz == z:
                            continue
                        d = math.hypot(ox - x, oz - z)
                        if best is None or d < best:
                            best = d
                        found = True
            if not found and radius > 4:
                break
            radius += 1
            if radius > 32:
                break
        if best is not None:
            out.append(best)
    return out


def clark_evans(points, area_blocks):
    """R = observed mean NN distance / expected under complete randomness."""
    d = nearest_neighbour_distances(points)
    if not d or area_blocks <= 0:
        return None, None
    observed = sum(d) / len(d)
    expected = 0.5 / math.sqrt(len(points) / area_blocks)
    return observed / expected, observed


def cross_class_nn(by_class, a, b):
    """Mean distance from each `a` point to its nearest `b` point."""
    src, dst = by_class.get(a, []), by_class.get(b, [])
    if not src or not dst:
        return None
    total = 0.0
    for x, z in src:
        total += min(math.hypot(ox - x, oz - z) for ox, oz in dst)
    return total / len(src)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("artefact")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args()

    with open(args.artefact) as fh:
        doc = json.load(fh)

    sites = [s for s in doc.get("sites", []) if isinstance(s, dict)]
    if not sites:
        sys.exit("no sites in artefact")

    radius = doc.get("radiusBlocks") or 8192
    area = math.pi * radius * radius

    by_group = defaultdict(list)
    by_class = defaultdict(list)
    occupants = Counter()
    for s in sites:
        pos = (s.get("x"), s.get("z"))
        if pos[0] is None:
            continue
        by_group[s.get("group")].append(pos)
        occ = s.get("structure")
        if occ:
            occupants[occ] += 1
            by_class[occupancy(occ)].append(pos)

    report = {"dimension": doc.get("dimension"), "seed": doc.get("seed"),
              "radiusBlocks": radius, "sites": len(sites),
              "distinctOccupants": len(occupants), "groups": {}, "crossClass": {}}

    for group, pts in sorted(by_group.items()):
        r, obs = clark_evans(pts, area)
        report["groups"][group] = {"sites": len(pts),
                                   "clarkEvans": round(r, 3) if r else None,
                                   "meanNearestBlocks": round(obs) if obs else None}

    for a in ("abandoned", "hostile", "neutral"):
        d = cross_class_nn(by_class, a, "inhabited")
        if d is not None:
            report["crossClass"][f"{a}_to_inhabited"] = round(d)
    report["classCounts"] = {k: len(v) for k, v in sorted(by_class.items())}

    if args.json:
        print(json.dumps(report, indent=1))
        return

    print(f"{report['dimension']}  seed {report['seed']}  radius {radius} blocks")
    print(f"{len(sites)} sites, {len(occupants)} distinct occupants\n")
    print(f"{'group':14s} {'sites':>6s} {'Clark-Evans':>12s} {'mean NN':>9s}  reading")
    print("-" * 62)
    for g, v in report["groups"].items():
        r = v["clarkEvans"]
        reading = "-" if r is None else (
            "clustered" if r < 0.9 else "random" if r < 1.15 else
            "dispersed" if r < 1.6 else "lattice-like")
        print(f"{str(g):14s} {v['sites']:6d} {str(r):>12s} "
              f"{str(v['meanNearestBlocks']):>9s}  {reading}")

    print(f"\noccupancy mix (inferred from ids): {report['classCounts']}")
    if report["crossClass"]:
        print("mean distance to the nearest inhabited structure:")
        for k, v in report["crossClass"].items():
            print(f"  {k:24s} {v:6d} blocks")

    print("\nmost repeated occupants:")
    for k, n in occupants.most_common(8):
        print(f"  {n:5d}  ({100 * n / max(sum(occupants.values()), 1):4.1f}%)  {k}")


if __name__ == "__main__":
    main()
