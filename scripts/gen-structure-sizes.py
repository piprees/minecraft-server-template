#!/usr/bin/env python3
"""Generate the per-structure footprint table from measured bounding boxes.

Purpose:  The placement model gives every structure in a group the same
          exclusion radius, so a 1-block cave marker and a 244-block castle
          claim identical ground. This produces the footprint each one
          actually occupies, so a radius can be scaled by it.

Input:    one or more `/customdim structure-sizes` artefacts — real
          `StructureStart.getBoundingBox()` measurements, assembled headlessly
          with no chunks generated:

            docker exec -i mc rcon-cli "customdim structure-sizes minecraft:overworld 3 900"
            docker exec -i mc rcon-cli "customdim structure-sizes-progress"
            docker exec mc cat /.seed-rolling/structure-sizes__minecraft_overworld.json > <file>

          plus scripts/data/structure-sizes-extracted.json for the id list, so
          a structure a pin bump added but nobody has measured is visible
          rather than silently absent.

Output:   config/custom-dimensions/structure-sizes.json
            Human-readable table shipped in the stack bundle.
          mods/custom-dimensions/src/main/resources/structure_sizes.json
            The jar resource the placement model reads. Jar-baked for the same
            reason structure_themes.json is: a stale config directory must
            never be able to change worldgen.

Usage:    scripts/gen-structure-sizes.py <artefact.json> [more.json ...]
          scripts/gen-structure-sizes.py --report <artefact.json> [...]
            Print the evidence instead of writing: how well the declared
            jigsaw fields predict the measured span, and the footprint spread
            inside each placement group.
          scripts/gen-structure-sizes.py --check <artefact.json> [...]
            Exit 1 if the committed outputs are stale (CI gate).

Gotchas:  - Run the sweep on an OVERWORLD. It reaches 781 of 783 structures
            against the nether's 727, and a low ceiling truncates a jigsaw
            expansion — `minecraft:ancient_city` spans 242 blocks in an
            overworld and 65 in a nether. Where both measure one, the medians
            agree (73% identical, p90 within 11%), and two sweeps of one
            dimension are byte-identical. One artefact is normally enough;
            feed more only to fill a hole, and the larger median wins.
          - `size` and `max_distance_from_center` from the jars DO NOT predict
            the footprint (Spearman 0.48 and 0.36). Do not reintroduce them as
            a proxy — --report is what says so.
          - A structure with no measurement is left OUT of the table, not
            given a zero. The mod falls back to its group's median; a zero
            would silently give it no personal space at all.
"""

import argparse
import collections
import json
import sys
from pathlib import Path

PLATFORM_DIR = Path(__file__).resolve().parent.parent
EXTRACTED = PLATFORM_DIR / "scripts" / "data" / "structure-sizes-extracted.json"
GROUPS = PLATFORM_DIR / "config" / "custom-dimensions" / "structure-groups.json"
SETS = PLATFORM_DIR / "config" / "custom-dimensions" / "extractors" / "structures.json"
TABLE_OUT = PLATFORM_DIR / "config" / "custom-dimensions" / "structure-sizes.json"
RESOURCE_OUT = (PLATFORM_DIR / "mods" / "custom-dimensions" / "src" / "main"
                / "resources" / "structure_sizes.json")

# The per-group minimum gap the current model applies, in blocks: the group's
# base exclusion times its profile multiplier times 16. Read from
# docs/mod-internals/worldgen-structures.md's own table; --report compares
# every measured footprint against the gap its group would give it.
GROUP_GAP = {"deco": 96, "loot": 128, "settlements": 192, "maritime": 192,
             "dungeons": 336, "landmarks": 496, "endgame": 832}


def load_artefacts(paths):
    """Merge measured artefacts, the widest median per structure winning."""
    merged = {}
    dimensions = []
    for p in paths:
        body = json.loads(Path(p).read_text())
        if body.get("kind") != "structure-sizes":
            raise SystemExit(f"{p}: not a structure-sizes artefact "
                             f"(kind={body.get('kind')!r})")
        dimensions.append(body["dimension"])
        for sid, row in body["structures"].items():
            best = merged.get(sid)
            if best is None or row["medianSpan"] > best["medianSpan"]:
                merged[sid] = dict(row, dimension=body["dimension"])
    return merged, dimensions


def structure_groups():
    """structure id -> placement group, via the set that contains it."""
    groups = json.loads(GROUPS.read_text())["sets"]
    raw = json.loads(SETS.read_text())["structure_sets"]
    sets = raw if isinstance(raw, dict) else {r["id"]: r for r in raw}
    out = {}
    for set_id, rec in sets.items():
        group = groups.get(set_id, {}).get("group")
        if not group:
            continue
        for sid in rec.get("structures", []):
            out.setdefault(sid, group)
    return out


def quantiles(values):
    v = sorted(values)
    n = len(v)
    if not n:
        return None

    def q(p):
        return v[int(p * (n - 1))]
    return {"n": n, "min": v[0], "p25": q(0.25), "median": q(0.5),
            "p75": q(0.75), "max": v[-1]}


def spearman(xs, ys):
    """Rank correlation, ties averaged. No scipy in the template toolchain."""
    def rank(vals):
        order = sorted(range(len(vals)), key=lambda i: vals[i])
        out = [0.0] * len(vals)
        i = 0
        while i < len(order):
            j = i
            while j + 1 < len(order) and vals[order[j + 1]] == vals[order[i]]:
                j += 1
            for k in range(i, j + 1):
                out[order[k]] = (i + j) / 2.0
            i = j + 1
        return out
    rx, ry = rank(xs), rank(ys)
    n = len(xs)
    mx, my = sum(rx) / n, sum(ry) / n
    num = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    den = (sum((a - mx) ** 2 for a in rx) * sum((b - my) ** 2 for b in ry)) ** 0.5
    return num / den if den else float("nan")


def report(measured, dimensions):
    """The evidence: is the cheap proxy usable, and how uneven is a group?"""
    declared = json.loads(EXTRACTED.read_text())["structures"]
    print(f"Measured in: {', '.join(dimensions)}")
    print(f"{len(measured)} structures measured of {len(declared)} in the pinned jars\n")

    print("Do the declared jigsaw fields predict the measured footprint?")
    for field in ("size", "max_distance_from_center"):
        xs, ys = [], []
        for sid, row in measured.items():
            d = declared.get(sid)
            if d and field in d:
                xs.append(d[field])
                ys.append(row["medianSpan"])
        if xs:
            print(f"  {field:26} n={len(xs):4d}  Spearman {spearman(xs, ys):+.3f}")
    print("  Neither is usable. A declared size of 1 spans 5-72 blocks in practice\n"
          "  and a declared size of 6 spans 1-244. Measure, do not infer.\n")

    by_size = collections.defaultdict(list)
    for sid, row in measured.items():
        d = declared.get(sid)
        if d and "size" in d:
            by_size[d["size"]].append(row["medianSpan"])
    print("declared size -> measured span")
    for k in sorted(by_size):
        v = sorted(by_size[k])
        print(f"  size={k:<4d} n={len(v):4d}  median={v[len(v) // 2]:4d}"
              f"  range={v[0]}-{v[-1]}")

    groups = structure_groups()
    by_group = collections.defaultdict(list)
    for sid, row in measured.items():
        g = groups.get(sid)
        if g:
            by_group[g].append((row["medianSpan"], sid))

    print("\nFootprint spread inside each placement group, against the one gap"
          " the group gives them all")
    header = (f'{"group":12} {"gap":>5} {"n":>4} {"min":>5} {"med":>5} {"max":>5}'
              f' {"clearance min..max":>22}  wider than the gap')
    print(header)
    for g in ("deco", "loot", "settlements", "maritime", "dungeons",
              "landmarks", "endgame"):
        rows = sorted(by_group.get(g, []))
        if not rows:
            continue
        gap = GROUP_GAP[g]
        spans = [s for s, _ in rows]
        stat = quantiles(spans) or {}
        ratios = sorted(gap / max(1, s) for s in spans)
        over = [sid for s, sid in rows if s > gap]
        print(f"{g:12} {gap:5d} {stat['n']:4d} {stat['min']:5d} {stat['median']:5d}"
              f" {stat['max']:5d} {ratios[0]:9.1f}x..{ratios[-1]:8.1f}x  {len(over):3d}")
    print("\nA structure wider than its own group's gap overlaps the next copy of"
          " itself at minimum spacing. Clearance is gap / own span: inside one"
          " group it ranges over two orders of magnitude, which is the case for"
          " scaling exclusion by footprint.")


def render_table(measured, dimensions, declared_ids):
    unmeasured = sorted(set(declared_ids) - set(measured))
    structures = {}
    for sid in sorted(measured):
        row = measured[sid]
        structures[sid] = {
            "span": row["medianSpan"],
            "radius": row["medianSpan"] // 2,
            "y": row["medianY"],
            "pieces": row["medianPieces"],
        }
    spans = [v["span"] for v in structures.values()]
    return {
        "_comment": "Generated by scripts/gen-structure-sizes.py from "
                    "/customdim structure-sizes artefacts — do not hand-edit. "
                    "span is the measured StructureStart bounding box's horizontal "
                    "extent in blocks; radius is half of it. A structure absent here "
                    "has no measurement and must fall back to its group's median, "
                    "never to zero.",
        "_counts": {"structures": len(structures), "unmeasured": len(unmeasured)},
        "_measuredIn": sorted(set(dimensions)),
        "_spanQuantiles": quantiles(spans),
        "structures": structures,
        "unmeasured": unmeasured,
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("artefacts", nargs="+", help="/customdim structure-sizes artefact JSON")
    ap.add_argument("--report", action="store_true",
                    help="print the proxy comparison and per-group spread, write nothing")
    ap.add_argument("--check", action="store_true",
                    help="exit 1 if the committed outputs are stale")
    args = ap.parse_args()

    measured, dimensions = load_artefacts(args.artefacts)
    if args.report:
        report(measured, dimensions)
        return 0

    declared_ids = json.loads(EXTRACTED.read_text())["structures"].keys()
    body = render_table(measured, dimensions, declared_ids)
    table = json.dumps(body, indent=1) + "\n"
    resource = json.dumps({"structures": body["structures"]}, indent=1) + "\n"

    outputs = {TABLE_OUT: table, RESOURCE_OUT: resource}
    stale = [p for p, text in outputs.items()
             if (p.read_text() if p.exists() else None) != text]

    if args.check:
        if stale:
            for p in stale:
                print(f"STALE: {p.relative_to(PLATFORM_DIR)}", file=sys.stderr)
            print("Re-measure with /customdim structure-sizes, then run "
                  "scripts/gen-structure-sizes.py.", file=sys.stderr)
            return 1
        print(f"structure size table up to date ({len(body['structures'])} structures)")
        return 0

    for path, text in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        print(f"Wrote {len(body['structures'])} structures to "
              f"{path.relative_to(PLATFORM_DIR)}")
    if body["unmeasured"]:
        print(f"  {len(body['unmeasured'])} with no measurement: "
              f"{', '.join(body['unmeasured'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
