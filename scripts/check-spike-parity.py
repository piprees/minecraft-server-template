#!/usr/bin/env python3
# =============================================================================
# check-spike-parity.py — assert the headless sampler agrees with the live
#                         server, costs what it claims, and creates no worlds
# =============================================================================
#
# Purpose:
#   Phase 1 of the seed-roll rewrite asks one question: can a seeded biome
#   source and terrain sampler be built for a dimension CONFIG with no
#   ServerWorld, and does it agree exactly with the running server? The mod
#   answers it into two artefact families; this asserts over them offline.
#
#     spike/compare__<ns>__<slug>__<seed>.json   headless vs live, per column
#     spike/bench__<ns>__<slug>.json             throughput, worlds, heap
#
#   The compare artefact carries BOTH sides' raw values as well as the mod's
#   own mismatch list. This script recomputes the verdict from the raw values
#   and cross-checks it against the recorded list — a command that scores its
#   own homework is exactly the failure mode the phase gate exists to prevent.
#
# Context:
#   Dump the artefacts first (the dimension must be loaded for compare, which
#   needs the live oracle; spike-sample and spike-bench need no world):
#     docker exec -i mc rcon-cli "customdim load <slug>"
#     docker exec -i mc rcon-cli "customdim spike-compare <ns>:<slug> <seed> 20 4000"
#     docker exec -i mc rcon-cli "customdim spike-bench <ns>:<slug> 1000"
#
# Usage:
#   ./scripts/check-spike-parity.py --spike <consumer>/data/config/custom-dimensions/spike
#   ./scripts/check-spike-parity.py --spike <dir> --min-seeds-per-second 500
#   ./scripts/check-spike-parity.py --spike <dir> --expect-dimensions 3 --expect-seeds 3
#
# Gotchas:
#   - A compare file whose every column reports the same biome proves nothing;
#     the script fails a run whose columns never vary, because a constant
#     answer is what a sampler that never read the noise field also produces.
#   - liveNoiseConfig is false when the requested seed is not the world's own.
#     At least one compare per dimension must have it true, or the comparison
#     never exercised NoiseConfig construction against the live one.
#   - Heap deltas are noisy on a live JVM and are reported, never gated on.
#     The leak gate is reachability instead: a rig built inside the loop must
#     be collectable afterwards, and the two registries must not grow.
# =============================================================================

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

FACT_KEYS = (
    "biome",
    "biomeAbsent",
    "surfaceHeight",
    "heightAbsent",
    "climate",
    "climateAbsent",
)


class Report:
    def __init__(self):
        self.failures = []
        self.passes = []

    def check(self, name, ok, detail=""):
        (self.passes if ok else self.failures).append((name, detail))
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {name}{(' — ' + detail) if detail else ''}")
        return ok

    def done(self):
        print()
        print(f"{len(self.passes)} passed, {len(self.failures)} failed")
        for name, detail in self.failures:
            print(f"  FAILED: {name} — {detail}")
        return 1 if self.failures else 0


def column_diff(headless, live):
    """Which facts differ between the two sides of one column, if any."""
    return [k for k in FACT_KEYS if headless.get(k) != live.get(k)]


def check_compare(path, report):
    doc = json.loads(path.read_text())
    if doc.get("schemaVersion") != 2:
        report.check(
            f"{path.name}: schemaVersion",
            False,
            f"expected 2, got {doc.get('schemaVersion')!r} — refusing to read it",
        )
        return None

    dim = doc["dimension"]
    seed = doc["seed"]
    tag = f"{dim}@{seed}"
    samples = doc.get("samples") or []

    report.check(f"{tag}: headless build succeeded", doc["headlessBuild"]["ok"],
                 str(doc["headlessBuild"].get("error")))
    report.check(f"{tag}: no world created", doc["worldsBefore"] == doc["worldsAfter"],
                 f"{doc['worldsBefore']} -> {doc['worldsAfter']}")
    report.check(f"{tag}: has columns", len(samples) > 0, f"{len(samples)} columns")

    # Recompute the verdict from raw values rather than trusting the recorded
    # mismatch list.
    recomputed = []
    for s in samples:
        diff = column_diff(s["headless"], s["live"])
        if diff:
            recomputed.append((s["headless"]["x"], s["headless"]["z"], diff))
    report.check(
        f"{tag}: headless == live at zero tolerance",
        not recomputed,
        "; ".join(f"({x}, {z}) differ in {d}" for x, z, d in recomputed[:6]),
    )
    report.check(
        f"{tag}: recorded mismatchCount agrees with recomputation",
        doc["mismatchCount"] == len(recomputed),
        f"recorded {doc['mismatchCount']}, recomputed {len(recomputed)}",
    )

    # A comparison whose answers never vary is satisfiable by a sampler that
    # reads nothing at all.
    biomes = {s["headless"]["biome"] for s in samples}
    heights = {s["headless"]["surfaceHeight"] for s in samples}
    report.check(
        f"{tag}: columns actually vary",
        len(biomes) > 1 or len(heights) > 1,
        f"{len(biomes)} distinct biomes, {len(heights)} distinct heights",
    )

    # Every column must have carried real facts, not a row of absences.
    absent = [s for s in samples if s["headless"]["biome"] is None
              and s["headless"]["surfaceHeight"] is None]
    report.check(
        f"{tag}: no all-absent column",
        not absent,
        f"{len(absent)} columns carried neither a biome nor a height",
    )

    # The climate-only rig is the screening path: same generator, a router
    # stripped to its six climate chains. It must answer identically on the
    # facts it claims to answer, and must report height absent rather than
    # inventing one from a zeroed terrain router.
    climate_diffs = []
    height_leaks = []
    for s in samples:
        c = s.get("climateOnly")
        if c is None:
            continue
        if c["biome"] != s["headless"]["biome"] or c["climate"] != s["headless"]["climate"]:
            climate_diffs.append((c["x"], c["z"]))
        if c["surfaceHeight"] is not None or not c["heightAbsent"]:
            height_leaks.append((c["x"], c["z"]))
    if any(s.get("climateOnly") for s in samples):
        report.check(
            f"{tag}: climate-only rig == full router on biome and climate",
            not climate_diffs,
            f"{len(climate_diffs)} differing columns: {climate_diffs[:6]}",
        )
        report.check(
            f"{tag}: climate-only rig reports height absent, not a number",
            not height_leaks,
            f"{len(height_leaks)} columns answered a height they cannot know",
        )
        report.check(
            f"{tag}: recorded climateOnlyMismatchCount agrees with recomputation",
            doc.get("climateOnlyMismatchCount") == len(climate_diffs),
            f"recorded {doc.get('climateOnlyMismatchCount')}, "
            f"recomputed {len(climate_diffs)}",
        )

    return {
        "dimension": dim,
        "seed": seed,
        "columns": len(samples),
        "liveNoiseConfig": doc.get("liveNoiseConfig", False),
        "mismatches": len(recomputed),
    }


def check_bench(path, report, min_per_second):
    doc = json.loads(path.read_text())
    if doc.get("schemaVersion") != 6:
        report.check(f"{path.name}: schemaVersion", False,
                     f"expected 6, got {doc.get('schemaVersion')!r}")
        return None
    dim = doc["dimension"]
    seeds = doc["seeds"]

    # The gate's floor is on the screening path — one spawn-column biome
    # check. The full-router number is reported alongside it because a
    # dimension that needs terrain facts pays that instead, and the gap
    # between the two is the whole argument for the split.
    report.check(
        f"{dim}: screening throughput >= {min_per_second}/s/core",
        doc["climateOnlySeedsPerSecond"] >= min_per_second,
        f"{doc['climateOnlySeedsPerSecond']:.1f} seeds/s climate-only, "
        f"{doc['seedsPerSecond']:.1f} seeds/s full router, over {seeds} seeds",
    )
    report.check(
        f"{dim}: no world created over {seeds} seeds",
        doc["worldsBefore"] == doc["worldsAfter"],
        f"{doc['worldsBefore']} -> {doc['worldsAfter']}",
    )
    report.check(
        f"{dim}: every seed answered",
        doc["answeredBiome"] == seeds,
        f"{doc['answeredBiome']}/{seeds}",
    )
    # One biome across a thousand seeds means the seed never reached the
    # sampler — the throughput number would look perfect either way.
    report.check(
        f"{dim}: seed reaches the sampler",
        doc["distinctBiomes"] > 1,
        f"{doc['distinctBiomes']} distinct spawn biomes across {seeds} seeds",
    )
    # "No per-seed leak" is a reachability question, not a heap-size one. A
    # live server allocates for its own reasons and System.gc() is a hint, so
    # the heap delta is reported but never gated on; what IS gated is whether
    # a rig built inside the loop became unreachable afterwards, and whether
    # the two registries a headless build could grow stayed the same size.
    report.check(
        f"{dim}: a rig built in the loop is collectable afterwards",
        doc["canaryCollected"] is True,
        f"cleared after {doc['canaryCollectRounds']} allocation rounds"
        if doc["canaryCollected"] else
        "a NoiseConfig created during the run survived 64 allocation rounds — "
        "something global is holding per-seed state",
    )
    report.check(
        f"{dim}: DIMENSION registry unchanged over {seeds} seeds",
        doc["dimensionRegistryBefore"] == doc["dimensionRegistryAfter"],
        f"{doc['dimensionRegistryBefore']} -> {doc['dimensionRegistryAfter']}",
    )
    report.check(
        f"{dim}: DIMENSION_TYPE registry unchanged over {seeds} seeds",
        doc["dimensionTypeRegistryBefore"] == doc["dimensionTypeRegistryAfter"],
        f"{doc['dimensionTypeRegistryBefore']} -> "
        f"{doc['dimensionTypeRegistryAfter']}",
    )
    first = doc["heapMidBytes"] - doc["heapBeforeBytes"]
    second = doc["heapAfterBytes"] - doc["heapMidBytes"]
    delta = doc["heapAfterBytes"] - doc["heapBeforeBytes"]
    print(f"         heap {delta:+d} bytes over the run "
          f"({first:+d} then {second:+d}) — context, not a gate")
    return {
        "dimension": dim,
        "seeds": seeds,
        "perSecond": doc["seedsPerSecond"],
        "climatePerSecond": doc["climateOnlySeedsPerSecond"],
        "noiseConfigShare": doc["noiseConfigNanos"] / max(1, doc["elapsedNanos"]),
        "baseBuildMs": doc["baseBuildNanos"] / 1e6,
        "emptyRouterMs": doc["emptyRouterNanosPerSeed"] / 1e6,
        "climateNoiseCount": doc["climateNoiseCount"],
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--spike", required=True, type=Path,
                    help="the spike/ artefact directory")
    ap.add_argument("--min-seeds-per-second", type=float, default=500.0)
    ap.add_argument("--expect-dimensions", type=int, default=0,
                    help="fail unless this many distinct dimensions were compared")
    ap.add_argument("--expect-seeds", type=int, default=0,
                    help="fail unless each compared dimension covers this many seeds")
    ap.add_argument("--expect-columns", type=int, default=0,
                    help="fail unless each comparison covers this many columns")
    args = ap.parse_args()

    if not args.spike.is_dir():
        print(f"No spike directory at {args.spike}", file=sys.stderr)
        return 2

    report = Report()
    compares = sorted(args.spike.glob("compare__*.json"))
    benches = sorted(args.spike.glob("bench__*.json"))

    print(f"Comparisons ({len(compares)} files)")
    results = [r for r in (check_compare(p, report) for p in compares) if r]

    print(f"\nBenchmarks ({len(benches)} files)")
    bench_rows = []
    for p in benches:
        row = check_bench(p, report, args.min_seeds_per_second)
        if row:
            bench_rows.append(row)
    for row in bench_rows:
        print(f"    {row['dimension']}: climate-only "
              f"{row['climatePerSecond']:.1f} seeds/s, full router "
              f"{row['perSecond']:.1f} seeds/s "
              f"({row['noiseConfigShare'] * 100:.1f}% in NoiseConfig.create); "
              f"{row['climateNoiseCount']} noises in the climate chains, "
              f"empty-router floor {row['emptyRouterMs']:.2f}ms/seed, "
              f"one-off generator build {row['baseBuildMs']:.1f}ms")

    print("\nCoverage")
    by_dim = defaultdict(list)
    for r in results:
        by_dim[r["dimension"]].append(r)

    if args.expect_dimensions:
        report.check(
            f"compared {args.expect_dimensions} dimensions",
            len(by_dim) >= args.expect_dimensions,
            f"{len(by_dim)}: {sorted(by_dim)}",
        )
    for dim, rows in sorted(by_dim.items()):
        if args.expect_seeds:
            report.check(f"{dim}: {args.expect_seeds} seeds",
                         len(rows) >= args.expect_seeds,
                         f"{len(rows)} seeds: {[r['seed'] for r in rows]}")
        if args.expect_columns:
            short = [r for r in rows if r["columns"] < args.expect_columns]
            report.check(f"{dim}: {args.expect_columns} columns per seed",
                         not short,
                         f"short: {[(r['seed'], r['columns']) for r in short]}")
        # Without one live-NoiseConfig comparison the whole dimension was only
        # ever checked against a NoiseConfig built the same way as its own.
        report.check(
            f"{dim}: at least one comparison against the live NoiseConfig",
            any(r["liveNoiseConfig"] for r in rows),
            f"seeds with liveNoiseConfig: "
            f"{[r['seed'] for r in rows if r['liveNoiseConfig']]}",
        )

    total_columns = sum(r["columns"] for r in results)
    print(f"\n{total_columns} columns compared across {len(results)} "
          f"(dimension, seed) pairs")
    return report.done()


if __name__ == "__main__":
    sys.exit(main())
