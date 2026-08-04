#!/usr/bin/env python3
"""warmup_structure_pools.py — dump which structures each dimension can place.

A noise position is one weighted draw from its structure group's pool, so "is
there a Village within 500 blocks" and "is there a settlement within 500 blocks"
are different questions. The banked census answers only the second: it records
per-GROUP counts and a radial histogram, never which structure landed where.
That made two whole classes of criterion meaningless — 167 shuns across 64 of 81
dimensions had never once been satisfied, because a shun failed whenever its
group was present and an enabled group is populated by definition.

The roller cannot derive the pool. Membership is decided server-side by
NoisePoolBuilder, intersecting each structure's own biome list with the
dimension's biome source — registry data extracted from ~150 mod jars, which the
pure-Python roller has no access to. So it has to be told.

WHAT THIS COSTS, AND WHY IT IS SHAPED THIS WAY

The mod records a dimension's pool as its world loads (StructurePoolRecord), so
a dump covers the dimensions loaded so far. Dimensions are REGISTERED at boot
from config but their ServerWorlds are created lazily, so this walks the rollable
list issuing `customdim load` and then dumps once.

That first sentence is why this boots WITHOUT SEED_ROLL_MODE, unlike every
measurement worker: under SEED_ROLL_MODE nothing is registered, and a
`customdim load` for an unregistered dimension is accepted, queued, and then
silently dropped by getOrCreateDimension.

Loading a world is cheap for placement (a few ms) but it creates a level
directory and generates spawn chunks, so this is a one-time warmup cost paid in
the throwaway boot directory, never in a real world.

GRACEFUL DEGRADATION IS THE POINT. A dimension missing from the dump has no
pool data: its per-structure identity is not exactly measurable and is banked
as such, never estimated. A partial dump improves precision for what it covers
and changes nothing else — this script failing outright leaves the roller
working, just less precise.

Uses docker exec rcon-cli for ALL RCON commands, like warmup_biomes.py: the
Python RCON socket enters a bad state after the boot warmup's create/destroy
cycle.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dimension_profiles as dp  # noqa: E402
from seed_worker import boot, docker, prepare_boot_dir  # noqa: E402

# How many `customdim load` calls to issue before waiting. The mod queues each
# on END_SERVER_TICK, so they do not need to be serialised — but firing all 81
# at once and then waiting once means a slow world starves the whole batch's
# budget, and a batch small enough to finish inside the poll window keeps the
# progress reporting honest.
BATCH = 8
POLL_SECONDS = 2
BATCH_TIMEOUT = 60
# Consecutive polls with no new pool before a batch is called done. A batch
# cannot always reach its target — see wait_for_batch — and waiting for one
# that never arrives is what turned this warmup into ten idle minutes.
QUIET_POLLS = 3


def rcon(container, cmd, timeout=60):
    """One RCON command via docker exec rcon-cli (fresh connection)."""
    try:
        r = subprocess.run(
            ["docker", "exec", container, "rcon-cli", cmd],
            capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return ""
    return r.stdout.strip()


def recorded_count(container):
    """How many dimensions have registered a structure pool.

    This, not `customdim list`, is the progress signal. Two reasons:

      - `customdim list` counts LOADED worlds, and the mod unloads an idle one
        after idleUnloadMinutes (5 by default). Over a run long enough to load
        78 dimensions the early ones unload again, so the loaded count is not
        monotonic and would never reach the target.
      - the pool record is an accumulator that survives unloading — verified on
        a live server: the_overgrowth stayed in the dump after its world was
        unloaded. So this number only ever goes up, and it is exactly the
        coverage the roller will get.

    RCON concatenates feedback lines with NO separator, which is why this is a
    regex rather than a whitespace split: the reply is one run-together string
    and the digits sit immediately before the phrase. The live reply is
    `dump-structure-pools: 5 dimension(s) -> ./config/.../structure_pools.json`.

    Each call rewrites structure_pools.json inside the throwaway boot
    directory. That is the command's only shape — a summary plus a path —
    and the file is overwritten by the final dump anyway.

    -> -1 when the reply carries no number: an RCON timeout returns "", and a
    server with nothing recorded yet answers with an error sentence. Both mean
    "no answer", so callers must count it as neither progress nor silence.
    """
    out = rcon(container, "customdim dump-structure-pools", timeout=60)
    match = re.search(r"(\d+) dimension", out)
    return int(match.group(1)) if match else -1


def rollable_slugs(config_dir):
    """Every dimension the roller will score, base worlds excluded.

    Base worlds load themselves at boot, so they are already in the record; a
    `customdim load overworld` would be meaningless anyway.
    """
    configs = dp.load_dimension_configs(config_dir)
    return sorted(slug for slug, cfg in configs.items()
                  if slug not in dp.BASE_WORLD_IDS and dp.rollable(cfg))


def stage_dimension_configs(workdir, config_dir):
    """Give the warmup server the dimension configs it is about to be asked
    to load.

    prepare_boot_dir is shared with the seed workers, which measure through
    the Python sampler and genuinely need nothing but a namespace — so it
    writes an EMPTY dimensions/ directory and says so. The pool dump is the
    one warmup that drives the MOD instead of the sampler, and against an
    empty directory every `customdim load <slug>` answers "No configured
    dimension named" and the whole dump comes back 0/77.

    The consumer overlay is STAGED, not pre-merged: the mod reads
    custom-dimensions/overlay/dimensions/ and merges it itself, exactly as it
    does on a real boot. Merging it here instead would be a second
    implementation of the overlay contract, free to disagree with the one
    that actually generates the world.
    """
    src = Path(config_dir)
    dst = Path(workdir) / "config" / "custom-dimensions"
    staged = 0
    dims_dst = dst / "dimensions"
    dims_dst.mkdir(parents=True, exist_ok=True)
    for f in sorted((src / "dimensions").glob("*.json")):
        shutil.copy2(f, dims_dst / f.name)
        staged += 1
    # settings.json carries the namespace and the per-dimension defaults the
    # mod falls back to; prepare_boot_dir wrote a namespace-only stub.
    if (src / "settings.json").is_file():
        shutil.copy2(src / "settings.json", dst / "settings.json")

    overlay_src = os.environ.get("SEED_OVERLAY_DIR")
    overlaid = 0
    if overlay_src and (Path(overlay_src) / "dimensions").is_dir():
        overlay_dst = dst / "overlay" / "dimensions"
        overlay_dst.mkdir(parents=True, exist_ok=True)
        for f in sorted((Path(overlay_src) / "dimensions").glob("*.json")):
            shutil.copy2(f, overlay_dst / f.name)
            overlaid += 1
    print("  Staged %d dimension config(s)%s" % (
        staged, " + %d overlay" % overlaid if overlaid else ""), flush=True)
    return staged


def wait_for_batch(container, target):
    """Wait for a batch's worlds to record their pools. -> the count reached.

    Two exits, and the second one is the point. A batch cannot always reach
    its target: a dimension whose structure groups are all suppressed
    (structureDensity "none", structures.mode "none", structures.noise false)
    loads its world through the legacy density path in DimensionStructures,
    which never calls StructurePoolRecord.record. Waiting for a target that
    dimension can never contribute to burns the full BATCH_TIMEOUT — and,
    because the target keeps climbing, so does every batch after it. So the
    batch is also done when the count simply stops moving.

    A -1 is neither: it means the reply carried no number, so it must not
    count as progress and must not count towards the quiet run either.
    """
    best = recorded_count(container)
    quiet = 0
    waited = 0
    while best < target and waited < BATCH_TIMEOUT:
        time.sleep(POLL_SECONDS)
        waited += POLL_SECONDS
        recorded = recorded_count(container)
        if recorded < 0:
            continue
        if recorded > best:
            best, quiet = recorded, 0
        else:
            quiet += 1
            if quiet >= QUIET_POLLS:
                break
    return best


def load_all(container, slugs):
    """Ask for every dimension's world, in batches, and report coverage.

    Batched rather than fired all at once: the mod queues each load on
    END_SERVER_TICK and drains one per tick, and a batch small enough to
    settle inside the poll window keeps the progress numbers honest and stops
    one slow world from swallowing the whole run's time budget.
    """
    baseline = max(0, recorded_count(container))   # the base worlds load themselves
    recorded = baseline
    for start in range(0, len(slugs), BATCH):
        batch = slugs[start:start + BATCH]
        for slug in batch:
            out = rcon(container, "customdim load %s" % slug, timeout=30)
            if "Queued" not in out and "already" not in out.lower():
                print("    %s did not queue: %s" % (slug, out[:80]), flush=True)
        requested = min(start + BATCH, len(slugs))
        recorded = wait_for_batch(container, baseline + requested)
        print("    %d/%d requested, %d recorded" % (
            requested, len(slugs), max(0, recorded - baseline)), flush=True)
    return max(0, recorded_count(container) - baseline)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workdir", required=True)
    ap.add_argument("--mvconfig", required=True)
    ap.add_argument("--seedtest", required=True)
    ap.add_argument("--config", required=True,
                    help="config/custom-dimensions, for the rollable list")
    ap.add_argument("--output", required=True)
    ap.add_argument("--memory", default=os.environ.get("ROLL_MEMORY", "10G"))
    args = ap.parse_args()

    slugs = rollable_slugs(args.config)
    if not slugs:
        print("  No rollable dimensions — nothing to dump", flush=True)
        return 0

    container = "seedrollall-warmup-pools"
    prepare_boot_dir(args.workdir, args.mvconfig, args.seedtest)
    # MUST run after prepare_boot_dir — that rmtree's custom-dimensions/.
    stage_dimension_configs(args.workdir, args.config)
    print("  Booting MC server for structure pool dump (%d dimensions)..."
          % len(slugs), flush=True)
    # seed_roll_mode=False is load-bearing: it is what registers the
    # configured dimensions, and without a registry entry `customdim load`
    # queues a load that getOrCreateDimension then drops on the floor.
    rcon_obj = boot("warmup", container, args.workdir, args.memory,
                    seed_roll_mode=False)
    if rcon_obj is None:
        print("  ERROR: server boot failed", flush=True)
        docker("rm", "-f", container, check=False)
        return 1
    rcon_obj.close()

    try:
        load_all(container, slugs)
        out = rcon(container, "customdim dump-structure-pools", timeout=60)
        if "dump-structure-pools" not in out:
            print("  dump failed: %s" % out[:160], flush=True)
            return 1
        dumped = (Path(args.workdir) / "config" / "custom-dimensions"
                  / "structure_pools.json")
        if not dumped.exists():
            print("  structure_pools.json was not written", flush=True)
            return 1
        doc = json.loads(dumped.read_text())
    finally:
        docker("rm", "-f", container, check=False)

    dimensions = doc.get("dimensions") or {}
    Path(args.output).write_text(json.dumps(doc, indent=1) + "\n")

    groups = sum(len(g) for g in dimensions.values())
    structures = sum(len(w) for g in dimensions.values() for w in g.values())
    print("  Dumped %d/%d dimension(s), %d groups, %d structure entries"
          % (len(dimensions), len(slugs) + len(dp.BASE_WORLD_IDS),
             groups, structures), flush=True)
    missing = [s for s in slugs if s not in dimensions]
    if missing:
        # Named, not silently dropped: these dimensions keep the group-level
        # reading, which is the pre-pool behaviour rather than a wrong answer.
        #
        # "recorded no pool", not "never loaded" — the usual member of this
        # list is a SUPPRESSED dimension (structureDensity "none",
        # structures.mode "none", structures.noise false). Its world loads
        # normally but takes the legacy density path in DimensionStructures,
        # which never calls StructurePoolRecord.record, so it has no pool to
        # record and group-level scoring is the correct answer for it. A
        # world that genuinely failed to load lands here too, and is worth
        # chasing — the difference shows in the container log.
        print("  %d dimension(s) recorded no pool and keep group-level "
              "scoring (expected for suppressed structures): %s"
              % (len(missing), ", ".join(missing[:8])
                 + (" ..." if len(missing) > 8 else "")), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
