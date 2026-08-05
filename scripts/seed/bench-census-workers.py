#!/usr/bin/env python3
"""bench-census-workers.py - Measure noise-census throughput against pool size.

Context: score-dimensions.default_workers() returns max(2, cpu_count * 2 // 3)
and treats every logical core as equal. On an asymmetric CPU (Apple silicon: a
few performance cores plus many efficiency ones) that is an assumption, not a
measurement — the efficiency cores finish the same chunk several times slower,
and past some pool size the extra workers cost more in contention than they
return. This is how that gets numbers instead of opinions.

Template-only: platform development, never shipped in the stack bundle.

Usage:
  scripts/seed/bench-census-workers.py --config <custom-dimensions dir> \\
      [--overlay <overlay custom-dimensions dir>] [--dim overworld] \\
      [--count N] [--workers 1,4,6,8,12,16,18] [--baseline-count N]

Tasks are handed out at CENSUS_CHUNKSIZE exactly as _run_census does — a
smaller chunk size would measure a scheduler this code does not use.

The task count SCALES with the pool size (workers * chunksize *
--chunks-per-worker) so every worker gets exactly K whole chunks. A fixed
list instead measures chunk quantisation: at 1152 tasks the list is 18 chunks,
so 18 workers take one each and finish together while 16 workers take 16 and
then run a 2-chunk tail with 14 idle — which reads as "16 is slower than 18"
and is a property of the arithmetic, not the machine. A real census is
~10,000 chunks, where that rounding is noise.

Because each row does a different amount of work, compare the PER-CANDIDATE
columns, not wall seconds.

The single-process baseline runs over a `--baseline-count` prefix rather than
the full list — it is only used as a per-candidate rate, and running it at full
length would double the benchmark's wall time for no extra resolution.
"""
import argparse
import importlib.util
import multiprocessing
import os
import random
import sys
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import dimension_profiles  # noqa: E402
import noise_placement  # noqa: E402


def _load_score_dimensions():
    """Import the hyphenated sibling for its census constants.

    Read rather than duplicated: CENSUS_CHUNKSIZE and default_workers() are
    the things under test, and a copy here would keep reporting the old
    numbers after someone changed them.
    """
    path = SCRIPT_DIR / "score-dimensions.py"
    spec = importlib.util.spec_from_file_location("score_dimensions", path)
    if spec is None or spec.loader is None:
        raise SystemExit("Cannot load %s" % path)
    module = importlib.util.module_from_spec(spec)
    sys.modules["score_dimensions"] = module
    spec.loader.exec_module(module)
    return module


def build_tasks(config_dir, overlay_dir, dim_name, count, seed):
    config = dimension_profiles.monolith_from_dir(config_dir, overlay_dir)
    sources = {d["name"]: d for d in config.get("dimensions", [])}
    sources.update({w["name"]: w for w in config.get("worlds", [])})
    src = sources.get(dim_name)
    if src is None:
        raise SystemExit(
            "No dimension or world named %r in %s (have: %s)"
            % (dim_name, config_dir, ", ".join(sorted(sources))))
    type_defaults = noise_placement.load_type_defaults(Path(config_dir))
    if not type_defaults:
        raise SystemExit("No structure-type defaults under %s" % config_dir)
    if noise_placement.resolve_groups(src, type_defaults) == {}:
        raise SystemExit(
            "%r places no noise groups — nothing to census." % dim_name)
    radius_chunks = noise_placement.census_radius_chunks(src)
    rng = random.Random(seed)
    tasks = [
        (dim_name, str(rng.getrandbits(63) - (1 << 62)), src, type_defaults,
         radius_chunks, 0, 0)
        for _ in range(count)
    ]
    return tasks, radius_chunks


def run(tasks, workers, chunksize, census_task):
    started = time.time()
    if workers <= 1:
        for task in tasks:
            census_task(task)
    else:
        with multiprocessing.Pool(workers) as pool:
            for _ in pool.imap_unordered(census_task, tasks,
                                         chunksize=chunksize):
                pass
    return time.time() - started


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True,
                    help="custom-dimensions config dir (bundle or platform)")
    ap.add_argument("--overlay", default=None,
                    help="consumer overlay/config/custom-dimensions")
    ap.add_argument("--dim", default="overworld")
    ap.add_argument("--chunks-per-worker", type=int, default=4,
                    help="whole chunks each worker gets (default 4)")
    ap.add_argument("--count", type=int, default=0,
                    help="fixed tasks per pool size; overrides the scaled "
                         "count and reintroduces chunk quantisation")
    ap.add_argument("--baseline-count", type=int, default=192,
                    help="tasks for the single-process rate (default 192)")
    ap.add_argument("--workers", default="1,4,6,8,12,16,18",
                    help="comma-separated pool sizes to time")
    ap.add_argument("--seed", type=int, default=20260803,
                    help="task-list seed; same value gives the same tasks")
    args = ap.parse_args()

    score_dimensions = _load_score_dimensions()
    chunksize = score_dimensions.CENSUS_CHUNKSIZE
    census_task = noise_placement.census_task

    sizes = [int(w) for w in args.workers.split(",") if w.strip()]
    if not sizes:
        raise SystemExit("--workers listed no pool sizes")
    def count_for(workers):
        if args.count:
            return args.count
        return workers * chunksize * args.chunks_per_worker

    tasks, radius_chunks = build_tasks(
        args.config, args.overlay, args.dim,
        max(count_for(w) for w in sizes), args.seed)

    # Every row flushed as it lands: the whole run is tens of minutes, and a
    # block-buffered pipe shows nothing at all until it finishes.
    print("dimension      %s (radius %d chunks)" % (args.dim, radius_chunks))
    print("machine        %d logical cores, default_workers() = %d"
          % (multiprocessing.cpu_count(), score_dimensions.default_workers()))
    if args.count:
        print("tasks          %d per pool size (FIXED — quantisation applies)"
              % args.count)
    else:
        print("tasks          workers x %d chunks x %d, so every worker gets "
              "%d whole chunks" % (args.chunks_per_worker, chunksize,
                                   args.chunks_per_worker))
    print(flush=True)

    baseline = None
    if 1 in sizes:
        n = min(args.baseline_count, len(tasks))
        elapsed = run(tasks[:n], 1, chunksize, census_task)
        baseline = elapsed / n
        print("baseline       1 worker, %d tasks: %.1fs (%.4f s/candidate)\n"
              % (n, elapsed, baseline), flush=True)
        sizes = [w for w in sizes if w != 1]

    # Rows do different amounts of work by design, so the comparable columns
    # are per-candidate; wall seconds are roughly constant and mean nothing
    # across rows.
    print("%8s %8s %9s %14s %14s %9s %11s"
          % ("workers", "tasks", "wall s", "s/candidate", "core-s/cand",
             "speedup", "efficiency"), flush=True)
    for workers in sizes:
        count = count_for(workers)
        elapsed = run(tasks[:count], workers, chunksize, census_task)
        per = elapsed / count
        core_per = per * workers
        speedup = (baseline / per) if baseline else float("nan")
        efficiency = speedup / workers if baseline else float("nan")
        print("%8d %8d %9.1f %14.4f %14.4f %9.2f %10.0f%%"
              % (workers, count, elapsed, per, core_per, speedup,
                 efficiency * 100), flush=True)


if __name__ == "__main__":
    # macOS spawns rather than forks, so the child re-imports this module and
    # would re-run the benchmark inside every worker without the guard.
    os.environ.setdefault("PYTHONHASHSEED", "0")
    main()
