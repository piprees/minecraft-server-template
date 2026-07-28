#!/usr/bin/env python3
"""Seed-group rolling tests for fast_roller: MemoSampler exactness and
group processing parity.

The invariant under test: within a generation-fingerprint group, sharing
one memoised sampler across members changes NOTHING about any member's
measured rows — sampling is deterministic, so group rows must be
bit-identical to a solo run of the same (member, seed).
"""
import inspect
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import candidates
import fast_roller
from biome_sampler import load_noise_configs
from dimension_profiles import build_profile

SCRIPT_DIR = Path(__file__).resolve().parent
BIOME_PARAMS = SCRIPT_DIR / "biome_params.json"
SKIP_REASON = "biome_params.json not present (CI or first checkout)"

TEST_SEED = 987654321


def make_dim(name, **over):
    dim = {"name": name, "type": "multi_biome",
           "dimensionId": f"adventure:{name}",
           "biomes": ["minecraft:plains", "minecraft:desert",
                      "minecraft:snowy_plains"],
           "seedRoll": {"spawnFilter": ["minecraft:plains"]}}
    dim.update(over)
    return dim


def profile_for(dim):
    cfg = {"namespace": "adventure", "dimensions": [dim],
           "portals": [], "worlds": []}
    return build_profile(dim, cfg)


@unittest.skipUnless(BIOME_PARAMS.exists(), SKIP_REASON)
class MemoSamplerParityTests(unittest.TestCase):
    def test_tier2_rows_identical_through_memo(self):
        noise_configs = load_noise_configs()
        profile = profile_for(make_dim("a"))
        plain = fast_roller._build_sampler(
            TEST_SEED, profile, str(BIOME_PARAMS), noise_configs)
        memo = fast_roller.MemoSampler(fast_roller._build_sampler(
            TEST_SEED, profile, str(BIOME_PARAMS), noise_configs))
        self.assertEqual(fast_roller.tier2_measure(TEST_SEED, profile, plain),
                         fast_roller.tier2_measure(TEST_SEED, profile, memo))

    def test_shared_memo_across_members_matches_solo(self):
        """ONE memo sampler serves both members in sequence (the group
        path) — each member's rows must equal its solo measurement."""
        pa = profile_for(make_dim("a"))
        pb = profile_for(make_dim(
            "b", seedRoll={"spawnFilter": ["minecraft:desert"], "mood": "hard"}))
        noise_configs = load_noise_configs()
        shared = fast_roller.MemoSampler(fast_roller._build_sampler(
            TEST_SEED, pa, str(BIOME_PARAMS), noise_configs))
        rows_a_shared, _ = fast_roller.tier2_measure(TEST_SEED, pa, shared)
        rows_b_shared, _ = fast_roller.tier2_measure(TEST_SEED, pb, shared)
        solo_a = fast_roller._build_sampler(
            TEST_SEED, pa, str(BIOME_PARAMS), noise_configs)
        solo_b = fast_roller._build_sampler(
            TEST_SEED, pb, str(BIOME_PARAMS), noise_configs)
        self.assertEqual(rows_a_shared,
                         fast_roller.tier2_measure(TEST_SEED, pa, solo_a)[0])
        self.assertEqual(rows_b_shared,
                         fast_roller.tier2_measure(TEST_SEED, pb, solo_b)[0])


@unittest.skipUnless(BIOME_PARAMS.exists(), SKIP_REASON)
class ProcessGroupTests(unittest.TestCase):
    def _run(self, members, seeds, tmp, pool, count):
        ss_dir = (Path(tmp) / "sets" / "data" / "minecraft"
                  / "worldgen" / "structure_set")
        ss_dir.mkdir(parents=True, exist_ok=True)
        (ss_dir / "villages.json").write_text(json.dumps({
            "placement": {"type": "minecraft:random_spread", "spacing": 34,
                          "separation": 8, "salt": 10387312},
            "structures": [{"structure": "minecraft:village_plains",
                            "weight": 1}],
        }))
        it = iter(seeds)
        with mock.patch.object(fast_roller, "random_seed", lambda: next(it)):
            task = (members, pool, count, str(Path(tmp) / "sets"),
                    str(BIOME_PARAMS), load_noise_configs(), set())
            return fast_roller._process_group(task)

    def test_group_rows_match_solo_rows_for_shared_seeds(self):
        seeds = [11, 22, 33, 44]
        pa = profile_for(make_dim(
            "a", seedRoll={"spawnFilter": ["minecraft:plains"],
                           "wants": {"village": "near_spawn"}}))
        pb = profile_for(make_dim(
            "b", seedRoll={"spawnFilter": ["minecraft:desert"],
                           "wants": {"village": "spread"}}))
        # count = pool: every seed survives in both runs, so the group and
        # solo runs measure the identical seed set.
        with tempfile.TemporaryDirectory() as tmp:
            grouped = self._run([("a", pa), ("b", pb)], seeds, tmp,
                                pool=4, count=4)
        with tempfile.TemporaryDirectory() as tmp:
            solo = self._run([("a", pa)], seeds, tmp, pool=4, count=4)

        by_name = {r[0]: r for r in grouped}
        rows_group_a = {seed: rows for seed, rows, _ok in by_name["a"][1]}
        rows_solo_a = {seed: rows for seed, rows, _ok in solo[0][1]}
        self.assertEqual(rows_group_a, rows_solo_a)
        # Every member banks rows for EVERY group seed (richness).
        self.assertEqual(len(by_name["b"][1]), 4)
        # b's structure rows use b's OWN battery window semantics — the
        # village distance itself is seed-determined, identical for both.
        for seed, rows, _ok in by_name["b"][1]:
            self.assertIn(("structure_village_dist",
                           dict(rows_group_a[seed])["structure_village_dist"]),
                          rows)

    def test_singleton_group_draws_one_pool(self):
        seeds = [11, 22, 33, 44, 55]
        pa = profile_for(make_dim("a"))
        with tempfile.TemporaryDirectory() as tmp:
            result = self._run([("a", pa)], seeds, tmp, pool=3, count=2)
        name, results, _acc, _rej, pool, surv, *_ = result[0]
        self.assertEqual((name, pool, surv), ("a", 3, 2))
        self.assertEqual(len(results), 2)


class BankRootOrderingTests(unittest.TestCase):
    """The bank root must be set before ANYTHING reads or writes the bank.

    fast_roller.main() used to set it only just before persisting, which
    left load_seen_seeds() resolving through the legacy in-config path: an
    empty `seen` set on every run, so seeds already banked as rejected were
    drawn and re-measured forever. A consumer running the same mismatch
    across a hand-patched bundle banked 6911 candidates into
    .stack/<version>/stack/config/custom-dimensions/candidates and the
    viewer reported "no candidates" (2026-07-28).
    """

    def test_main_sets_bank_root_before_reading_seen_seeds(self):
        # Code only: the comments explaining the ordering name both calls.
        src = "\n".join(ln for ln in inspect.getsource(fast_roller.main).splitlines()
                        if not ln.lstrip().startswith("#"))
        set_at = src.find("set_bank_root(")
        read_at = src.find("load_seen_seeds(args")
        self.assertNotEqual(set_at, -1, "fast_roller.main must set the bank root")
        self.assertNotEqual(read_at, -1, "fast_roller.main must load seen seeds")
        self.assertLess(set_at, read_at,
                        "set_bank_root must come before load_seen_seeds")

    def test_missing_root_warns_instead_of_failing_silently(self):
        candidates.set_bank_root(None)
        candidates._WARNED_NO_ROOT = False
        with tempfile.TemporaryDirectory() as tmp:
            import io
            import contextlib
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                got = candidates.candidates_dir(Path(tmp))
            self.assertEqual(got, Path(tmp) / "candidates")
            self.assertIn("bank root not set", err.getvalue())


if __name__ == "__main__":
    unittest.main()
