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
        # Tag data for exact #minecraft:village resolution
        tags_dir = Path(tmp) / ".structure_tags" / "minecraft"
        tags_dir.mkdir(parents=True, exist_ok=True)
        (tags_dir / "village.json").write_text(json.dumps({
            "values": ["minecraft:village_plains"]
        }))
        import structure_tags
        structure_tags.clear_cache()
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
        # Structure distances anchor at each member's OWN chosen spawn
        # (different spawnFilters -> different spawns), so b's banked
        # distance must equal a fresh measurement from b's banked spawn —
        # not a's number for the same seed.
        from structure_placement import nearest_structure
        for seed, rows, _ok in by_name["b"][1]:
            d = dict(rows)
            expected = nearest_structure(
                seed, 34, 8, 10387312,
                origin_x=int(d.get("spawn_x", 0)),
                origin_z=int(d.get("spawn_z", 0)),
                search_radius=50)
            self.assertEqual(expected[0] if expected else -1,
                             d["structure_village_dist"])

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


class FakeSiteSampler:
    """A programmable biome map for select_spawn_site tests: biome_fn(x, z)
    -> biome id; climate is constant so spline heights are flat unless a
    test overrides height via the evaluator being irrelevant (flat model
    => flatness ties, decided by dryness/openness/origin)."""

    def __init__(self, biome_fn):
        self._fn = biome_fn

    def biome_and_climate(self, x, z):
        return self._fn(x, z), {"temperature": 0.0, "humidity": 0.0,
                                "continentalness": 0.2, "erosion": 0.0,
                                "depth": 0.0, "weirdness": 0.0}


class SelectSpawnSiteTests(unittest.TestCase):
    """Phase 7 spawn-site selection: hard filters, quality scoring,
    determinism, and the never-reject fallback."""

    def _profile(self, **over):
        p = {"namesake": ["minecraft:plains"], "player_border": 8192,
             "clear_spawn_radius": 48, "forced_structures": [],
             "family": "overworld"}
        p.update(over)
        return p

    def test_water_namesake_point_loses_to_dry_site(self):
        # Namesake at origin is a river (water) — a dry namesake site
        # further out must win despite the origin tie-break.
        def biome(x, z):
            if (x, z) == (0, 0):
                return "minecraft:river"
            if (x, z) == (128, 128):
                return "minecraft:plains"
            return "minecraft:forest"
        b, d, x, z = fast_roller.select_spawn_site(
            FakeSiteSampler(biome), self._profile(
                namesake=["minecraft:plains", "minecraft:river"]))
        self.assertEqual(("minecraft:plains", 128, 128), (b, x, z))
        self.assertEqual(int((128 * 128 * 2) ** 0.5), d)

    def test_border_filter_excludes_out_of_bounds_sites(self):
        def biome(x, z):
            return ("minecraft:plains" if abs(x) >= 512 or abs(z) >= 512
                    else "minecraft:forest")
        b, d, x, z = fast_roller.select_spawn_site(
            FakeSiteSampler(biome), self._profile(player_border=256))
        # Every namesake point is outside border-margin: fallback fires
        # with the nearest bare namesake point.
        self.assertEqual("minecraft:plains", b)
        self.assertEqual(512, max(abs(x), abs(z)))

    def test_forced_placement_clearance(self):
        def biome(x, z):
            return ("minecraft:plains" if (x, z) in ((64, 0), (256, 0))
                    else "minecraft:forest")
        prof = self._profile(clear_spawn_radius=128,
                             forced_structures=[{"structure": "f", "x": 64, "z": 0}])
        b, d, x, z = fast_roller.select_spawn_site(FakeSiteSampler(biome), prof)
        self.assertEqual((256, 0), (x, z), "site inside clearance skipped")

    def test_open_site_beats_lonely_site_nearer_origin(self):
        # (64,0) is a lone plains cell in forest; (320,320) sits in a
        # plains block — openness must out-score the origin distance.
        def biome(x, z):
            if (x, z) == (64, 0):
                return "minecraft:plains"
            if 256 <= x <= 384 and 256 <= z <= 384:
                return "minecraft:plains"
            return "minecraft:forest"
        b, d, x, z = fast_roller.select_spawn_site(
            FakeSiteSampler(biome), self._profile())
        self.assertEqual((320, 320), (x, z))

    def test_deterministic(self):
        def biome(x, z):
            return ("minecraft:plains" if (x + z) % 128 == 0
                    else "minecraft:forest")
        prof = self._profile()
        first = fast_roller.select_spawn_site(FakeSiteSampler(biome), prof)
        for _ in range(3):
            self.assertEqual(first, fast_roller.select_spawn_site(
                FakeSiteSampler(biome), prof))

    def test_anchor_spawn_weighting_prefers_portal_foundation(self):
        # Two intended sites: S1 (64,0) — dry .75, open .5 — and S2
        # (320,0) — dry 1.0, open 0. Normal weighting: S1 .75 beats S2
        # .70; anchor weighting (dryness-dominant — the site is a portal
        # foundation): S2 .80 beats S1 .775. The extra rivers keep S1's
        # plains neighbours (sites themselves) capped below both.
        plains = {(64, 0), (320, 0), (0, 0), (128, 0), (64, -64), (64, 64)}
        rivers = {(0, -64), (0, 64), (192, -64), (192, 0),
                  (64, -128), (64, 128)}

        def biome(x, z):
            if (x, z) in plains:
                return "minecraft:plains"
            if (x, z) in rivers:
                return "minecraft:river"
            return "minecraft:forest"
        normal = fast_roller.select_spawn_site(
            FakeSiteSampler(biome), self._profile())
        anchor = fast_roller.select_spawn_site(
            FakeSiteSampler(biome), self._profile(anchor_spawn=True))
        self.assertNotEqual(normal[2:], anchor[2:],
                            "anchor weighting must change the choice on this map")
        self.assertEqual((320, 0), anchor[2:])

    def test_no_namesake_anywhere_returns_none(self):
        b, d, x, z = fast_roller.select_spawn_site(
            FakeSiteSampler(lambda x, z: "minecraft:forest"), self._profile())
        self.assertIsNone(b)
        self.assertEqual(-1, d)


if __name__ == "__main__":
    unittest.main()
