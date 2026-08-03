#!/usr/bin/env python3
"""Tests for census/terrain checkpointing and top-N census pruning.

`ensure_censuses` used to hold every result in memory and write the candidate
stores only after the last task finished. On a cold 648,971-candidate bank
that is a ~58-hour all-or-nothing job: a Ctrl+C at 24% discarded fourteen
hours of work and left `candidates/` not merely stale but absent. The census
is a pure function of the seed and the placement config and is skipped once
banked, so the work was always resumable — nothing was writing it down.

These pin the checkpointing, the resume, and the exactness of the pruning
that decides which censuses are worth computing at all.
"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import candidates
import dimension_profiles
import noise_placement

SCORE_PATH = Path(__file__).with_name("score-dimensions.py")
SPEC = importlib.util.spec_from_file_location("score_dimensions", SCORE_PATH)
score_dimensions = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(score_dimensions)


def type_defaults():
    return {
        "groupDefaults": {
            "deco": {"profile": "natural", "radial": "even", "exclusion": 3},
        },
        "types": {"single_biome": {"groups": ["deco"]}},
        "curves": {"even": [1.0] * 10},
        "difficultyShifts": {
            "peaceful": {"maxMobMultiplier": 0.0, "profiles": {}},
            "hostile": {"minMobMultiplier": 99.0, "radial": {}},
        },
    }


def dim_entry(name):
    return {
        "name": name,
        "dimensionId": f"adventure:{name}",
        "type": "single_biome",
        "biomes": ["minecraft:plains"],
        "borders": {"player": 256},
        "seedRoll": {"mood": "serene"},
    }


class CensusPersistenceTests(unittest.TestCase):
    """Results must reach disk while the job is still running."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.seedtest = self.root / ".seedtest"
        self.seedtest.mkdir()
        self.cfg = self.root / "config"
        (self.cfg / "dimensions").mkdir(parents=True)
        (self.cfg / "structure-type-defaults.json").write_text(
            json.dumps(type_defaults()))
        candidates.set_bank_root(str(self.seedtest))
        noise_placement._TYPE_DEFAULTS.clear()
        # noise_fingerprint answers None until the type defaults are loaded,
        # and ensure_censuses skips every dimension whose fingerprint is None.
        dimension_profiles.set_noise_defaults_dir(str(self.cfg))
        self.addCleanup(dimension_profiles.set_noise_defaults_dir, None)
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(candidates.set_bank_root, None)

    def build(self, n_candidates, census_top=0):
        entry = dim_entry("the_testing_grounds")
        config = {"dimensions": [entry], "worlds": []}
        from dimension_profiles import build_profile
        profiles = {"the_testing_grounds": build_profile(entry, config, {})}
        data = {"the_testing_grounds": {}}
        for i in range(n_candidates):
            data["the_testing_grounds"][str(1000 + i)] = {
                "errors": "0",
                "spawn_biome": "minecraft:plains",
                "spawn_x": "0", "spawn_z": "0",
                "height_r0c0": "64", "height_r1c1": str(64 + i % 20),
                "height_r2c2": "70", "water_r1c1": "0",
            }
        args = SimpleNamespace(
            config=str(self.cfg), seedtest=str(self.seedtest),
            census_workers=1, census_top=census_top)
        return args, config, profiles, data

    def store_path(self):
        return candidates.candidates_dir(self.cfg) / "the_testing_grounds.json"

    def test_results_are_flushed_before_the_job_ends(self):
        args, config, profiles, data = self.build(12)
        original = score_dimensions.CENSUS_FLUSH_EVERY
        score_dimensions.CENSUS_FLUSH_EVERY = 3
        self.addCleanup(setattr, score_dimensions, "CENSUS_FLUSH_EVERY", original)

        writes = []
        real_save = candidates.save_store

        def spy(path, store):
            writes.append(len(store["candidates"]))
            return real_save(path, store)

        candidates.save_store = spy
        self.addCleanup(setattr, candidates, "save_store", real_save)
        score_dimensions.ensure_censuses(args, config, profiles, data, quiet=True)

        self.assertGreater(len(writes), 1,
                           "the store must be written during the run, not once at the end")
        self.assertLess(writes[0], 12,
                        "the first write should be a partial checkpoint")
        self.assertEqual(writes[-1], 12)

    def test_an_interrupted_run_keeps_what_it_computed(self):
        """Ctrl+C is documented as finalising, not discarding."""
        args, config, profiles, data = self.build(10)
        score_dimensions.CENSUS_FLUSH_EVERY = 10 ** 9  # only the finally can save
        self.addCleanup(setattr, score_dimensions, "CENSUS_FLUSH_EVERY", 2000)

        real_task = score_dimensions._census_task
        calls = {"n": 0}

        def exploding(task):
            calls["n"] += 1
            if calls["n"] > 4:
                raise KeyboardInterrupt
            return real_task(task)

        score_dimensions._census_task = exploding
        self.addCleanup(setattr, score_dimensions, "_census_task", real_task)
        with self.assertRaises(KeyboardInterrupt):
            score_dimensions.ensure_censuses(args, config, profiles, data,
                                             quiet=True)

        store = candidates.load_store(self.store_path())
        banked = [c for c in store["candidates"].values() if "noiseCensus" in c]
        self.assertEqual(len(banked), 4,
                         "every completed census must survive the interrupt")

    def test_a_second_run_resumes_instead_of_recomputing(self):
        args, config, profiles, data = self.build(6)
        score_dimensions.ensure_censuses(args, config, profiles, data, quiet=True)

        args2, config2, profiles2, data2 = self.build(6)
        seen = []
        real_task = score_dimensions._census_task
        score_dimensions._census_task = lambda t: (seen.append(t), real_task(t))[1]
        self.addCleanup(setattr, score_dimensions, "_census_task", real_task)
        score_dimensions.ensure_censuses(args2, config2, profiles2, data2,
                                         quiet=True)
        self.assertEqual(seen, [],
                         "banked censuses must not be recomputed")
        for rows in data2["the_testing_grounds"].values():
            self.assertIn("_census", rows,
                          "a resumed run must still attach the cached census")

    def test_cached_censuses_match_freshly_computed_ones(self):
        args, config, profiles, data = self.build(4)
        score_dimensions.ensure_censuses(args, config, profiles, data, quiet=True)
        fresh = {s: r["_census"] for s, r in data["the_testing_grounds"].items()}

        args2, config2, profiles2, data2 = self.build(4)
        score_dimensions.ensure_censuses(args2, config2, profiles2, data2,
                                         quiet=True)
        cached = {s: r["_census"] for s, r in data2["the_testing_grounds"].items()}
        self.assertEqual(fresh, cached)


class CensusPruningTests(unittest.TestCase):
    """`--census-top N` must never change which candidates reach the top N."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        candidates.set_bank_root(str(self.root))
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(candidates.set_bank_root, None)
        entry = dim_entry("the_testing_grounds")
        config = {"dimensions": [entry], "worlds": []}
        from dimension_profiles import build_profile
        self.profiles = {"the_testing_grounds": build_profile(entry, config, {})}

    def rows(self, quality):
        """A candidate whose non-structure score scales with `quality`."""
        return {
            "errors": "0",
            "spawn_biome": ("minecraft:plains" if quality > 0.5 else "minecraft:desert"),
            "spawn_x": "0", "spawn_z": "0",
            "biome_minecraft:plains_dist": str(int(2000 * (1 - quality))),
            "height_r0c0": "64",
            "height_r1c1": str(int(64 + 40 * quality)),
            "height_r2c2": "70", "water_r1c1": "0",
        }

    def test_bounds_bracket_the_real_score(self):
        profile = self.profiles["the_testing_grounds"]
        for q in (0.0, 0.3, 0.7, 1.0):
            rows = self.rows(q)
            lo, _ = score_dimensions.score_candidate(
                profile, rows, score_dimensions.STRUCTURES_MIN)
            hi, _ = score_dimensions.score_candidate(
                profile, rows, score_dimensions.STRUCTURES_MAX)
            self.assertLessEqual(lo, hi)
            # Every attainable structures value must land inside the bracket.
            for s in (0.0, 0.25, 0.5, 0.9, 1.05):
                mid, _ = score_dimensions.score_candidate(profile, rows, s)
                self.assertGreaterEqual(mid, lo)
                self.assertLessEqual(mid, hi)

    def hopeless_rows(self):
        """Nothing the census could add would rescue this candidate."""
        return {"errors": "20", "spawn_biome": "unknown",
                "spawn_x": "0", "spawn_z": "0"}

    def _assert_prune_is_exact(self, data, tasks, top_n, expect_pruning):
        profile = self.profiles["the_testing_grounds"]
        rows_for = data["the_testing_grounds"]
        kept = score_dimensions._prune_census_tasks(
            self.profiles, data, list(tasks), top_n, quiet=True)
        kept_seeds = {t[1] for t in kept}
        if expect_pruning:
            self.assertLess(len(kept), len(tasks), "nothing was pruned")
        # The floor is the Nth best LOWER bound, which is a floor on the Nth
        # best real total. Anything dropped must have an UPPER bound below it.
        los = sorted((score_dimensions.score_candidate(
            profile, rows_for[t[1]], score_dimensions.STRUCTURES_MIN)[0]
            for t in tasks), reverse=True)
        floor = los[top_n - 1]
        for _name, seed, *_rest in tasks:
            if seed in kept_seeds:
                continue
            hi, _ = score_dimensions.score_candidate(
                profile, rows_for[seed], score_dimensions.STRUCTURES_MAX)
            self.assertLess(hi, floor,
                            f"{seed} was pruned but could still reach the "
                            f"top {top_n}")
        return kept

    def test_a_long_tail_is_pruned_and_the_prune_is_exact(self):
        data = {"the_testing_grounds": {}}
        tasks = []
        for i in range(10):                       # genuine contenders
            seed = str(500 + i)
            data["the_testing_grounds"][seed] = self.rows(0.6 + i / 25.0)
            tasks.append(("the_testing_grounds", seed, {}, {}, 16, 0, 0))
        for i in range(30):                       # unreachable tail
            seed = str(600 + i)
            data["the_testing_grounds"][seed] = self.hopeless_rows()
            tasks.append(("the_testing_grounds", seed, {}, {}, 16, 0, 0))
        self._assert_prune_is_exact(data, tasks, 5, expect_pruning=True)

    def test_a_tightly_clustered_bank_is_left_alone(self):
        """The band is wider than the spread, so nothing can be PROVEN out.

        Pinning this deliberately: a prune that fired here would not be
        exact, and silently dropping a contender is the one failure mode
        that matters.
        """
        data = {"the_testing_grounds": {}}
        tasks = []
        for i in range(40):
            seed = str(500 + i)
            data["the_testing_grounds"][seed] = self.rows(i / 39.0)
            tasks.append(("the_testing_grounds", seed, {}, {}, 16, 0, 0))
        kept = self._assert_prune_is_exact(data, tasks, 5, expect_pruning=False)
        self.assertEqual(len(kept), len(tasks))

    def test_the_best_candidates_are_always_kept(self):
        data = {"the_testing_grounds": {}}
        tasks = []
        for i in range(30):
            seed = str(700 + i)
            data["the_testing_grounds"][seed] = self.rows(i / 29.0)
            tasks.append(("the_testing_grounds", seed, {}, {}, 16, 0, 0))
        kept = {t[1] for t in score_dimensions._prune_census_tasks(
            self.profiles, data, list(tasks), 5, quiet=True)}
        # The five highest-quality candidates must survive any pruning.
        for i in range(25, 30):
            self.assertIn(str(700 + i), kept)

    def test_small_banks_are_never_pruned(self):
        data = {"the_testing_grounds": {}}
        tasks = []
        for i in range(4):
            seed = str(900 + i)
            data["the_testing_grounds"][seed] = self.rows(i / 3.0)
            tasks.append(("the_testing_grounds", seed, {}, {}, 16, 0, 0))
        kept = score_dimensions._prune_census_tasks(
            self.profiles, data, list(tasks), 10, quiet=True)
        self.assertEqual(len(kept), 4)


if __name__ == "__main__":
    unittest.main()
