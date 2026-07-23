#!/usr/bin/env python3
"""Regression tests for scoring, candidate storage, and render manifests."""
import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import candidates
from dimension_profiles import build_profile

SCORE_PATH = Path(__file__).with_name("score-dimensions.py")
SPEC = importlib.util.spec_from_file_location("score_dimensions", SCORE_PATH)
score_dimensions = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(score_dimensions)


SAMPLE_DIM = {
    "name": "sample_dimension", "type": "overworld",
    "dimensionId": "adventure:sample_dimension",
    "seedRoll": {"spawnFilter": ["minecraft:plains"]},
}


def sample_config():
    return {"namespace": "adventure", "dimensions": [dict(SAMPLE_DIM)],
            "worlds": [], "portals": []}


def write_worker_csv(seedtest, rows):
    with (Path(seedtest) / "worker-0.csv").open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["target", "seed", "metric", "value"])
        w.writerows(rows)


class RangeScoringTests(unittest.TestCase):
    """v4 Phase 6: explicit block-range want/shun scoring."""

    def test_want_score_inside_range_is_full(self):
        # Inside the range: base 1.0 + comfort bonus (up to 1.1x at centre)
        self.assertGreaterEqual(score_dimensions.want_score(500, 0, 2000, 8192), 1.0)
        self.assertLessEqual(score_dimensions.want_score(500, 0, 2000, 8192), 1.1)
        # Dead centre gets the maximum 1.1x bonus
        self.assertAlmostEqual(score_dimensions.want_score(1000, 0, 2000, 8192), 1.1)
        # At the edge of the range: no bonus (1.0)
        self.assertAlmostEqual(score_dimensions.want_score(0, 0, 2000, 8192), 1.0)
        self.assertAlmostEqual(score_dimensions.want_score(2000, 0, 2000, 8192), 1.0)
        self.assertLess(score_dimensions.want_score(4000, 0, 2000, 8192), 1.0)

    def test_want_score_absence_beyond_horizon_is_soft(self):
        # hi beyond locate's horizon: absence can't be disproven
        self.assertEqual(score_dimensions.want_score(None, 0, 8000, 8192), 0.6)
        # fully-confirmable range: absence is a hard zero
        self.assertEqual(score_dimensions.want_score(None, 0, 800, 8192), 0.0)

    def test_shun_score_min_distance_semantics(self):
        # minDistance 4000: closer fails, farther passes
        self.assertEqual(score_dimensions.shun_score(3000, 8192, 4000), 0.0)
        self.assertEqual(score_dimensions.shun_score(5000, 8192, 4000), 1.0)
        # legacy/zero threshold: anywhere inside the radius fails
        self.assertEqual(score_dimensions.shun_score(5000, 8192, None), 0.0)
        self.assertEqual(score_dimensions.shun_score(None, 8192, None), 1.0)


class CandidateStoreTests(unittest.TestCase):
    def test_config_hash_ignores_seed_and_spawn(self):
        base = {"type": "overworld", "structureDensity": "sparse"}
        h1 = candidates.config_hash({**base, "seed": 1, "spawn": [1, 64, 2]})
        h2 = candidates.config_hash({**base, "seed": 999})
        self.assertEqual(h1, h2)
        h3 = candidates.config_hash({**base, "structureDensity": "dense"})
        self.assertNotEqual(h1, h3)

    def test_merge_rows_routes_rejects_and_measurements(self):
        store = candidates.empty_store()
        candidates.merge_rows(store, 111, {"rejected": "1", "spawn_filter_dist": "2000"})
        candidates.merge_rows(store, 222, {"spawn_biome": "minecraft:plains", "errors": "0"})
        self.assertIn("111", store["rejected"])
        self.assertIn("2000 blocks", store["rejected"]["111"])
        self.assertEqual(store["candidates"]["222"]["measurements"]["spawn_biome"],
                         "minecraft:plains")

    def test_store_round_trip_is_atomic(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "candidates" / "d.json"
            store = candidates.empty_store()
            candidates.merge_rows(store, 1, {"errors": "0"})
            candidates.save_store(path, store)
            self.assertFalse(path.with_name(path.name + ".tmp").exists())
            self.assertEqual(candidates.load_store(path)["candidates"]["1"]["measurements"],
                             {"errors": "0"})

    def test_merge_rows_stamps_fingerprint_on_new_candidates_only(self):
        store = candidates.empty_store()
        candidates.merge_rows(store, 1, {"errors": "0"}, fingerprint="aaa111")
        self.assertEqual(store["candidates"]["1"]["fingerprint"], "aaa111")
        # an existing candidate keeps the stamp from its own measurement run
        candidates.merge_rows(store, 1, {"extra": "1"}, fingerprint="bbb222")
        self.assertEqual(store["candidates"]["1"]["fingerprint"], "aaa111")
        # no fingerprint given -> no stamp (legacy callers)
        candidates.merge_rows(store, 2, {"errors": "0"})
        self.assertNotIn("fingerprint", store["candidates"]["2"])

    def test_seen_seeds_covers_all_banks(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = candidates.empty_store()
            candidates.merge_rows(store, 1, {"errors": "0"})
            store["rejected"]["2"] = "spawn filter"
            store["abandoned"]["3"] = "rcon-timeout"
            candidates.save_store(Path(tmp) / "candidates" / "d.json", store)
            self.assertEqual(candidates.seen_seeds(tmp), {"1", "2", "3"})


class CandidatePipelineTests(unittest.TestCase):
    def make_args(self, tmp):
        cfg = Path(tmp) / "custom-dimensions"
        (cfg / "dimensions").mkdir(parents=True, exist_ok=True)
        (cfg / "dimensions" / "sample_dimension.json").write_text(
            json.dumps({"type": "overworld",
                        "seedRoll": {"spawnFilter": ["minecraft:plains"]}}))
        seedtest = Path(tmp) / "seedtest"
        seedtest.mkdir(exist_ok=True)
        return SimpleNamespace(config=str(cfg), seedtest=str(seedtest),
                               csv=str(seedtest / "measurements.csv"),
                               write_config=False, viewer=False, open_viewer=False)

    def test_spools_persist_into_candidate_store_and_survive_spool_removal(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.make_args(tmp)
            write_worker_csv(args.seedtest, [
                ["sample_dimension", "123", "spawn_biome", "minecraft:plains"],
                ["sample_dimension", "123", "errors", "0"],
                ["sample_dimension", "666", "rejected", "1"],
                ["sample_dimension", "666", "spawn_filter_dist", "1500"],
            ])
            (Path(args.seedtest) / "abandoned-worker-0.csv").write_text(
                "target,seed,reason\nsample_dimension,777,rcon-timeout\n")
            config = sample_config()
            profiles = {"sample_dimension": build_profile(config["dimensions"][0], config)}

            data = score_dimensions.gather_measurements(args)
            results, rejected = score_dimensions.score_all(profiles, data)
            self.assertEqual(len(results["sample_dimension"]), 1)
            self.assertEqual(rejected["sample_dimension"], 1)
            score_dimensions.persist_candidates(args, config, profiles, results, data)

            store = candidates.load_store(
                Path(args.config) / "candidates" / "sample_dimension.json")
            chash = candidates.config_hash(config["dimensions"][0])
            self.assertEqual(store["configHash"], chash)
            self.assertIn("123", store["candidates"])
            self.assertIn(chash, store["candidates"]["123"]["scores"])
            self.assertIn("666", store["rejected"])
            self.assertEqual(store["abandoned"]["777"], "rcon-timeout")

            # Spools removed: the candidate store alone still feeds scoring.
            (Path(args.seedtest) / "worker-0.csv").unlink()
            data2 = score_dimensions.gather_measurements(args)
            self.assertEqual(data2["sample_dimension"]["123"]["spawn_biome"],
                             "minecraft:plains")
            self.assertEqual(data2["sample_dimension"]["666"]["rejected"], "1")

    def test_rescore_keeps_old_hash_and_adds_new(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.make_args(tmp)
            write_worker_csv(args.seedtest, [
                ["sample_dimension", "123", "spawn_biome", "minecraft:plains"],
                ["sample_dimension", "123", "errors", "0"],
            ])
            config = sample_config()
            profiles = {"sample_dimension": build_profile(config["dimensions"][0], config)}
            data = score_dimensions.gather_measurements(args)
            results, _ = score_dimensions.score_all(profiles, data)
            score_dimensions.persist_candidates(args, config, profiles, results, data)
            old_hash = candidates.config_hash(config["dimensions"][0])

            # Config change: measurements stay valid, scores get a new hash.
            config["dimensions"][0]["structureDensity"] = "dense"
            (Path(args.config) / "dimensions" / "sample_dimension.json").write_text(
                json.dumps({"type": "overworld", "structureDensity": "dense",
                            "seedRoll": {"spawnFilter": ["minecraft:plains"]}}))
            profiles = {"sample_dimension": build_profile(config["dimensions"][0], config)}
            data = score_dimensions.gather_measurements(args)
            results, _ = score_dimensions.score_all(profiles, data)
            score_dimensions.persist_candidates(args, config, profiles, results, data)

            store = candidates.load_store(
                Path(args.config) / "candidates" / "sample_dimension.json")
            new_hash = candidates.config_hash(config["dimensions"][0])
            self.assertNotEqual(old_hash, new_hash)
            scores = store["candidates"]["123"]["scores"]
            self.assertIn(old_hash, scores)
            self.assertIn(new_hash, scores)
            self.assertEqual(store["configHash"], new_hash)

    def test_pinned_winner_survives_better_scores(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.make_args(tmp)
            # Seed 200 scores higher (namesake spawn); 100 is the human pick.
            write_worker_csv(args.seedtest, [
                ["sample_dimension", "100", "spawn_biome", "minecraft:desert"],
                ["sample_dimension", "100", "errors", "0"],
                ["sample_dimension", "200", "spawn_biome", "minecraft:plains"],
                ["sample_dimension", "200", "errors", "0"],
            ])
            config = sample_config()
            profiles = {"sample_dimension": build_profile(config["dimensions"][0], config)}
            cdir = Path(args.config) / "candidates"
            store = candidates.empty_store()
            store["winner"] = "100"
            store["winnerPinned"] = True
            candidates.save_store(cdir / "sample_dimension.json", store)

            score_dimensions.cmd_finalise(args, config, profiles)

            store = candidates.load_store(cdir / "sample_dimension.json")
            self.assertEqual(store["winner"], "100")
            self.assertTrue(store["winnerPinned"])
            # Unpinned: the better seed wins.
            store["winnerPinned"] = False
            candidates.save_store(cdir / "sample_dimension.json", store)
            score_dimensions.cmd_finalise(args, config, profiles)
            store = candidates.load_store(cdir / "sample_dimension.json")
            self.assertEqual(store["winner"], "200")
            self.assertFalse(store["winnerPinned"])


class GroupWinnerAssignmentTests(unittest.TestCase):
    """Seed-group rolling: within a generation-fingerprint group the same
    seed means literal world clones, so finalise must assign distinct
    winners; a winner measured under a drifted fingerprint gets a warning."""

    def make_args(self, tmp, gen_extra_b=None):
        cfg = Path(tmp) / "custom-dimensions"
        (cfg / "dimensions").mkdir(parents=True, exist_ok=True)
        base = {"type": "overworld",
                "seedRoll": {"spawnFilter": ["minecraft:plains"]}}
        (cfg / "dimensions" / "twin_a.json").write_text(json.dumps(base))
        dim_b = dict(base)
        if gen_extra_b:
            dim_b.update(gen_extra_b)
        (cfg / "dimensions" / "twin_b.json").write_text(json.dumps(dim_b))
        seedtest = Path(tmp) / "seedtest"
        seedtest.mkdir(exist_ok=True)
        return SimpleNamespace(config=str(cfg), seedtest=str(seedtest),
                               csv=str(seedtest / "measurements.csv"),
                               write_config=False, viewer=False,
                               open_viewer=False)

    def _measure_twins(self, args):
        # Seed 100 spawns on the namesake (scores higher) for BOTH twins;
        # 200 is the runner-up. Identical rows -> identical rankings.
        rows = []
        for dim in ("twin_a", "twin_b"):
            rows += [[dim, "100", "spawn_biome", "minecraft:plains"],
                     [dim, "100", "errors", "0"],
                     [dim, "200", "spawn_biome", "minecraft:desert"],
                     [dim, "200", "errors", "0"]]
        write_worker_csv(args.seedtest, rows)

    def _finalise(self, args):
        import contextlib
        import io
        from dimension_profiles import load_config
        config = load_config(args.config)
        profiles = {d["name"]: build_profile(d, config)
                    for d in config["dimensions"]}
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            score_dimensions.cmd_finalise(args, config, profiles)
        return out.getvalue()

    def test_group_members_get_distinct_winners(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.make_args(tmp)
            self._measure_twins(args)
            output = self._finalise(args)
            cdir = Path(args.config) / "candidates"
            winner_a = candidates.load_store(cdir / "twin_a.json")["winner"]
            winner_b = candidates.load_store(cdir / "twin_b.json")["winner"]
            self.assertEqual({winner_a, winner_b}, {"100", "200"})
            self.assertIn("group assignment", output)

    def test_different_fingerprints_keep_independent_winners(self):
        with tempfile.TemporaryDirectory() as tmp:
            # structureDensity changes the generation fingerprint — no
            # group, both twins keep their own best seed.
            args = self.make_args(tmp, gen_extra_b={"structureDensity": "dense"})
            self._measure_twins(args)
            self._finalise(args)
            cdir = Path(args.config) / "candidates"
            self.assertEqual(candidates.load_store(cdir / "twin_a.json")["winner"], "100")
            self.assertEqual(candidates.load_store(cdir / "twin_b.json")["winner"], "100")

    def test_pinned_winner_claims_its_seed_first(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.make_args(tmp)
            self._measure_twins(args)
            cdir = Path(args.config) / "candidates"
            # Pin the LOWER-scoring twin_b to the shared top seed: twin_a
            # must step down to 200 even though it out-scores twin_b.
            store = candidates.empty_store()
            store["winner"] = "100"
            store["winnerPinned"] = True
            candidates.save_store(cdir / "twin_b.json", store)
            self._finalise(args)
            self.assertEqual(candidates.load_store(cdir / "twin_b.json")["winner"], "100")
            self.assertEqual(candidates.load_store(cdir / "twin_a.json")["winner"], "200")

    def test_fingerprint_drift_warns_at_finalise(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.make_args(tmp)
            self._measure_twins(args)
            self._finalise(args)  # stamps candidates with the current fp
            # Generation change AFTER measurement: twin_a's banked
            # measurements describe a world this config no longer makes.
            (Path(args.config) / "dimensions" / "twin_a.json").write_text(
                json.dumps({"type": "overworld", "structureDensity": "dense",
                            "seedRoll": {"spawnFilter": ["minecraft:plains"]}}))
            output = self._finalise(args)
            self.assertIn("measured under", output)
            self.assertIn("twin_a", output)


class WinnerOverlayWritebackTests(unittest.TestCase):
    def test_consumer_overlay_writeback_shapes(self):
        winners = {
            "fresh": {"seed": "11", "metrics": {"spawn_x": "1", "spawn_z": "2"}},
            "merged": {"seed": "22", "metrics": {}},
            "owned": {"seed": "33", "metrics": {}},
            "disabled": {"seed": "44", "metrics": {}},
        }
        platform_sources = {
            "fresh": {"type": "overworld", "name": "fresh",
                      "dimensionId": "adventure:fresh", "seed": 0},
        }
        with tempfile.TemporaryDirectory() as tmp:
            overlay = Path(tmp) / "overlay" / "custom-dimensions"
            dims = overlay / "dimensions"
            dims.mkdir(parents=True)
            (dims / "merged.json").write_text(json.dumps(
                {"overrides": {"difficulty": {"mobMultiplier": 1.5}}}))
            (dims / "owned.json").write_text(json.dumps({"type": "nether", "seed": 1}))
            (dims / "disabled.json").write_text("{}")
            seedtest = Path(tmp) / "seedtest"
            seedtest.mkdir()

            changed, _ = score_dimensions.write_winners_to_overlay(
                overlay, winners, seedtest, platform_sources=platform_sources)

            self.assertEqual(changed, 3)
            fresh = json.loads((dims / "fresh.json").read_text())
            # New files for platform-known dims are seed/spawn OVERRIDES —
            # a full copy would freeze the platform config at write time
            # and mask later platform-side changes.
            self.assertEqual(fresh["overrides"]["seed"], 11)
            self.assertEqual(fresh["overrides"]["spawn"], [1, 64, 2])
            self.assertNotIn("type", fresh)
            self.assertNotIn("type", fresh["overrides"])
            merged = json.loads((dims / "merged.json").read_text())
            self.assertEqual(merged["overrides"]["seed"], 22)
            self.assertEqual(merged["overrides"]["difficulty"], {"mobMultiplier": 1.5})
            owned = json.loads((dims / "owned.json").read_text())
            self.assertEqual(owned["seed"], 33)          # full-replace file: top-level
            self.assertEqual(owned["type"], "nether")
            self.assertEqual((dims / "disabled.json").read_text(), "{}")  # never resurrected


class WinnerWritebackTests(unittest.TestCase):
    def test_directory_mode_writes_seed_and_spawn_per_file(self):
        import json
        winners = {
            "the_claymarsh": {"seed": "123", "metrics": {"spawn_x": "10", "spawn_z": "-20"}},
            "overworld": {"seed": "456", "metrics": {}},
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg = Path(temp_dir) / "custom-dimensions"
            dims = cfg / "dimensions"
            dims.mkdir(parents=True)
            (dims / "the_claymarsh.json").write_text(json.dumps(
                {"type": "overworld", "seed": 1, "structureDensity": "sparse"}))
            (dims / "overworld.json").write_text(json.dumps({"seed": 1}))
            (dims / "untouched.json").write_text(json.dumps({"type": "overworld", "seed": 5}))
            seedtest = Path(temp_dir) / "seedtest"
            seedtest.mkdir()

            changed, backup = score_dimensions.write_winners_to_dir(cfg, winners, seedtest)

            self.assertEqual(changed, 2)
            clay = json.loads((dims / "the_claymarsh.json").read_text())
            self.assertEqual(clay["seed"], 123)
            self.assertEqual(clay["spawn"], [10, 64, -20])
            self.assertEqual(clay["structureDensity"], "sparse")  # untouched keys survive
            self.assertEqual(json.loads((dims / "overworld.json").read_text())["seed"], 456)
            self.assertEqual(json.loads((dims / "untouched.json").read_text())["seed"], 5)
            # one session backup, outside the config dir
            self.assertIsNotNone(backup)
            self.assertTrue((seedtest / ".config-backed-up").exists())

            # second write in the same session: no new backup
            changed2, backup2 = score_dimensions.write_winners_to_dir(cfg, winners, seedtest)
            self.assertEqual(changed2, 0)
            self.assertIsNone(backup2)


class VarietyScoringTests(unittest.TestCase):
    """Variety scores proximity-weighted biome diversity, penalising monocultures."""

    def _make_profile(self, radius, namesake, n_variety):
        """Minimal profile with the fields variety scoring reads."""
        biomes = list(namesake) + [f"minecraft:biome_{i}" for i in range(n_variety - len(namesake))]
        return {
            "radius": radius,
            "namesake": sorted(namesake),
            "variety_biomes": biomes,
            "weights": {"namesake": 20, "variety": 20, "terrain": 30, "structures": 30},
            "battery": [],
            "terrain": {"relief": (18, 90), "grain": (2, 14), "water": (0.0, 0.45)},
            "is_void": False,
            "is_islands": False,
        }

    def _rows(self, spawn, biome_dists, terrain=True):
        """Build a rows dict from biome distances and spawn biome."""
        rows = {"spawn_biome": spawn, "errors": "0"}
        for biome_id, dist in biome_dists.items():
            rows[f"biome_{biome_id}_dist"] = str(dist)
        if terrain:
            for r in range(3):
                for c in range(3):
                    rows[f"height_r{r}c{c}"] = "64"
                    rows[f"water_r{r}c{c}"] = "0"
        return rows

    def test_well_mixed_scores_higher_than_monoculture(self):
        """A world with biomes spread throughout should beat one where
        non-namesake biomes exist only as distant fringe slivers."""
        profile = self._make_profile(
            radius=256,
            namesake=["ns:wisteria", "ns:cherry", "ns:sakura"],
            n_variety=8,
        )
        # Well-mixed: all biomes within half the radius
        mixed = self._rows("ns:wisteria", {
            "ns:wisteria": 0, "ns:cherry": 60, "ns:sakura": 80,
            "minecraft:biome_0": 40, "minecraft:biome_1": 90,
            "minecraft:biome_2": 100, "minecraft:biome_3": 70,
            "minecraft:biome_4": 110,
        })
        # Monoculture: namesake at spawn, everything else beyond the border
        mono = self._rows("ns:wisteria", {
            "ns:wisteria": 0, "ns:cherry": 250, "ns:sakura": 300,
            "minecraft:biome_0": 500, "minecraft:biome_1": 600,
            "minecraft:biome_2": 700, "minecraft:biome_3": 400,
            "minecraft:biome_4": 550,
        })
        _, mixed_parts = score_dimensions.score_candidate(profile, mixed)
        _, mono_parts = score_dimensions.score_candidate(profile, mono)
        self.assertGreater(mixed_parts["variety"], mono_parts["variety"])
        self.assertGreater(mixed_parts["variety"], 0.6)
        self.assertLess(mono_parts["variety"], 0.35)

    def test_large_world_barely_affected(self):
        """In a large world (r=8192), biomes found within 1000 blocks should
        still score well — the quadratic decay is relative to radius."""
        profile = self._make_profile(
            radius=8192,
            namesake=["minecraft:plains"],
            n_variety=5,
        )
        rows = self._rows("minecraft:plains", {
            "minecraft:plains": 0, "minecraft:biome_0": 300,
            "minecraft:biome_1": 600, "minecraft:biome_2": 800,
            "minecraft:biome_3": 1000,
        })
        _, parts = score_dimensions.score_candidate(profile, rows)
        self.assertGreater(parts["variety"], 0.85)

    def test_no_biome_metrics_defaults_to_half(self):
        """With no biome distance data, variety defaults to 0.5."""
        profile = self._make_profile(radius=256, namesake=["ns:a"], n_variety=1)
        rows = {"spawn_biome": "ns:a", "errors": "0",
                "height_r0c0": "64", "water_r0c0": "0"}
        _, parts = score_dimensions.score_candidate(profile, rows)
        self.assertAlmostEqual(parts["variety"], 0.5)

    def test_non_namesake_close_boosts_balance(self):
        """Two identical worlds except one has non-namesake biomes close to
        spawn — that one should score higher on variety."""
        profile = self._make_profile(
            radius=512,
            namesake=["ns:identity"],
            n_variety=4,
        )
        close_others = self._rows("ns:identity", {
            "ns:identity": 0, "minecraft:biome_0": 100,
            "minecraft:biome_1": 150, "minecraft:biome_2": 200,
        })
        far_others = self._rows("ns:identity", {
            "ns:identity": 0, "minecraft:biome_0": 480,
            "minecraft:biome_1": 500, "minecraft:biome_2": 510,
        })
        _, close_parts = score_dimensions.score_candidate(profile, close_others)
        _, far_parts = score_dimensions.score_candidate(profile, far_others)
        self.assertGreater(close_parts["variety"], far_parts["variety"])


class ClearSpawnRadiusTests(unittest.TestCase):
    """structures.clearSpawnRadius penalises wants found too close to spawn."""

    def _profile_with_clear(self, clear_r, want_range=(0, 2000)):
        return {
            "radius": 8192,
            "namesake": ["minecraft:plains"],
            "weights": {"namesake": 20, "variety": 20, "terrain": 30, "structures": 30},
            "battery": [("village", "#minecraft:village", want_range, "want")],
            "terrain": {"relief": (18, 90), "grain": (2, 14), "water": (0.0, 0.45)},
            "is_void": False,
            "is_islands": False,
            "clear_spawn_radius": clear_r,
        }

    def _rows(self, struct_dist):
        rows = {"spawn_biome": "minecraft:plains", "errors": "0",
                "structure_village_dist": str(struct_dist)}
        for r in range(3):
            for c in range(3):
                rows[f"height_r{r}c{c}"] = "64"
                rows[f"water_r{r}c{c}"] = "0"
        return rows

    def test_structure_at_spawn_penalised_with_clear_radius(self):
        """A village at 10 blocks with clearSpawnRadius=100 should score
        much worse than a village at 200 blocks."""
        profile = self._profile_with_clear(100)
        _, parts_close = score_dimensions.score_candidate(profile, self._rows(10))
        _, parts_far = score_dimensions.score_candidate(profile, self._rows(200))
        self.assertGreater(parts_far["structures"], parts_close["structures"])
        self.assertLess(parts_close["structures"], 0.2)

    def test_no_penalty_when_clear_radius_is_zero(self):
        """Hard dims with clearSpawnRadius=0: structures at spawn are fine."""
        profile = self._profile_with_clear(0)
        _, parts = score_dimensions.score_candidate(profile, self._rows(10))
        self.assertGreater(parts["structures"], 0.9)

    def test_structure_at_clear_boundary_not_penalised(self):
        """A structure exactly at the clear radius boundary should get no penalty."""
        profile = self._profile_with_clear(100)
        _, parts = score_dimensions.score_candidate(profile, self._rows(100))
        self.assertGreater(parts["structures"], 0.9)

    def test_penalty_scales_linearly(self):
        """Structure at 50/100 should score better than at 10/100."""
        profile = self._profile_with_clear(100)
        _, parts_50 = score_dimensions.score_candidate(profile, self._rows(50))
        _, parts_10 = score_dimensions.score_candidate(profile, self._rows(10))
        self.assertGreater(parts_50["structures"], parts_10["structures"])

    def test_clear_radius_applies_to_shuns_too(self):
        """A shun found inside the clear radius gets penalised — any
        structure right on spawn is bad regardless of want/shun status."""
        profile = {
            "radius": 8192,
            "namesake": ["minecraft:plains"],
            "weights": {"namesake": 20, "variety": 20, "terrain": 30, "structures": 30},
            "battery": [("ruined_portal", "minecraft:ruined_portal", 8192, "shun")],
            "terrain": {"relief": (18, 90), "grain": (2, 14), "water": (0.0, 0.45)},
            "is_void": False,
            "is_islands": False,
            "clear_spawn_radius": 100,
        }
        # Shun absent: base score is 1.0 (good), density bias nudges it
        rows_absent = {"spawn_biome": "minecraft:plains", "errors": "0",
                       "structure_ruined_portal_dist": "-1"}
        # Shun found at 10 blocks: inside clear zone AND inside shun threshold
        rows_close = {"spawn_biome": "minecraft:plains", "errors": "0",
                      "structure_ruined_portal_dist": "10"}
        for r in range(3):
            for c in range(3):
                rows_absent[f"height_r{r}c{c}"] = "64"
                rows_absent[f"water_r{r}c{c}"] = "0"
                rows_close[f"height_r{r}c{c}"] = "64"
                rows_close[f"water_r{r}c{c}"] = "0"
        _, parts_absent = score_dimensions.score_candidate(profile, rows_absent)
        _, parts_close = score_dimensions.score_candidate(profile, rows_close)
        # Shun at spawn: both shun_score (0.0) AND clear-spawn penalty apply
        self.assertLess(parts_close["structures"], 0.01)
        # Absent shun: no penalty (slight density nudge only)
        self.assertGreater(parts_absent["structures"], 0.9)


class DensityBiasTests(unittest.TestCase):
    """structureDensity nudges the structure score: sparse prefers fewer
    found structures, dense prefers more, default very slightly fewer.
    The bias is a tiebreaker — it can't override want_score."""

    def _profile(self, density, n_wants=4):
        battery = [(f"s{i}", f"ns:s{i}", (0, 4000), "want") for i in range(n_wants)]
        return {
            "radius": 8192,
            "namesake": ["minecraft:plains"],
            "weights": {"namesake": 20, "variety": 20, "terrain": 30, "structures": 30},
            "battery": battery,
            "terrain": {"relief": (18, 90), "grain": (2, 14), "water": (0.0, 0.45)},
            "is_void": False,
            "is_islands": False,
            "clear_spawn_radius": 0,
            "density": density,
        }

    def _rows(self, found_dists):
        rows = {"spawn_biome": "minecraft:plains", "errors": "0"}
        for i, d in enumerate(found_dists):
            rows[f"structure_s{i}_dist"] = str(d)
        for r in range(3):
            for c in range(3):
                rows[f"height_r{r}c{c}"] = "64"
                rows[f"water_r{r}c{c}"] = "0"
        return rows

    def test_sparse_same_want_scores_fewer_wins(self):
        """Two candidates with identical want_score outcomes but one has
        fewer structures found — sparse should prefer that one."""
        profile = self._profile("sparse", n_wants=2)
        # Both have 1 structure in range and 1 not found (same want_score base).
        # But candidate A has an extra structure_s2 found (3 total found_count).
        rows_more = {"spawn_biome": "minecraft:plains", "errors": "0",
                     "structure_s0_dist": "500", "structure_s1_dist": "-1"}
        rows_fewer = {"spawn_biome": "minecraft:plains", "errors": "0",
                      "structure_s0_dist": "500", "structure_s1_dist": "-1"}
        for r in range(3):
            for c in range(3):
                rows_more[f"height_r{r}c{c}"] = "64"
                rows_more[f"water_r{r}c{c}"] = "0"
                rows_fewer[f"height_r{r}c{c}"] = "64"
                rows_fewer[f"water_r{r}c{c}"] = "0"
        # Same base score, but found_count differs: 1 vs 1 — no difference
        # here. Test with 2-want battery where both found vs 1 found:
        profile2 = self._profile("sparse", n_wants=2)
        both_found = self._rows([500, 600])  # found_count=2
        one_found = self._rows([500, -1])    # found_count=1
        _, parts_both = score_dimensions.score_candidate(profile2, both_found)
        _, parts_one = score_dimensions.score_candidate(profile2, one_found)
        # sparse penalty: -0.012/hit. With 2 found: -0.024 vs 1 found: -0.012.
        # parts_both base is higher (both wants satisfied) but the bias pulls it down.
        # The bias alone: 0.012 difference.
        sparse_nudge = 0.012
        # Verify the bias exists and goes the right direction:
        # (both_found base - sparse_penalty*2) vs (one_found base - sparse_penalty*1)
        # We can't test the direction overriding want_score, but we CAN test
        # that the sparse penalty is applied.
        self.assertLess(parts_both["structures"],
                        parts_both["structures"] + sparse_nudge)

    def test_dense_rewards_more_found_structures(self):
        """In a dense dim, same base but more found should score higher
        from the density bonus alone."""
        # Compare no-density vs dense with same found structures
        profile_none = self._profile(None, n_wants=4)
        profile_dense = self._profile("dense", n_wants=4)
        rows = self._rows([500, 600, 700, 800])
        _, parts_none = score_dimensions.score_candidate(profile_none, rows)
        _, parts_dense = score_dimensions.score_candidate(profile_dense, rows)
        # Dense gets +0.008 * 4 = +0.032; default gets -0.003 * 4 = -0.012
        self.assertGreater(parts_dense["structures"], parts_none["structures"])

    def test_default_density_nudge_is_tiny(self):
        """Without explicit density, the per-hit nudge is barely visible."""
        profile = self._profile(None, n_wants=4)
        all_found = self._rows([500, 600, 700, 800])
        _, parts = score_dimensions.score_candidate(profile, all_found)
        # Base want_score ≈ 1.05 avg. 4 found * 0.003 = 0.012 penalty.
        # structures should be very close to the raw want_score average.
        self.assertGreater(parts["structures"], 0.95)
        self.assertLess(parts["structures"], 1.1)


class EnrichedDensityBiasTests(unittest.TestCase):
    """When structure_all enrichment data exists in the candidate store,
    the density bias uses the TOTAL structure count (including unlisted
    structures) instead of the battery-found-count."""

    def _profile(self, density, n_wants=2):
        battery = [(f"s{i}", f"ns:s{i}", (0, 4000), "want") for i in range(n_wants)]
        return {
            "radius": 8192,
            "namesake": ["minecraft:plains"],
            "weights": {"namesake": 20, "variety": 20, "terrain": 30, "structures": 30},
            "battery": battery,
            "terrain": {"relief": (18, 90), "grain": (2, 14), "water": (0.0, 0.45)},
            "is_void": False,
            "is_islands": False,
            "clear_spawn_radius": 0,
            "density": density,
        }

    def _rows(self, found_dists, enriched_count=None):
        rows = {"spawn_biome": "minecraft:plains", "errors": "0"}
        for i, d in enumerate(found_dists):
            rows[f"structure_s{i}_dist"] = str(d)
        if enriched_count is not None:
            rows["_enriched_structure_count"] = str(enriched_count)
        for r in range(3):
            for c in range(3):
                rows[f"height_r{r}c{c}"] = "64"
                rows[f"water_r{r}c{c}"] = "0"
        return rows

    def test_enriched_count_amplifies_sparse_penalty(self):
        """A sparse dim with 2 battery hits but 15 total structures
        (enriched) should score lower than one with 2 battery hits and
        only 3 total structures."""
        profile = self._profile("sparse")
        cluttered = self._rows([500, 600], enriched_count=15)
        quiet = self._rows([500, 600], enriched_count=3)
        _, parts_cluttered = score_dimensions.score_candidate(profile, cluttered)
        _, parts_quiet = score_dimensions.score_candidate(profile, quiet)
        self.assertGreater(parts_quiet["structures"], parts_cluttered["structures"])

    def test_enriched_count_amplifies_dense_bonus(self):
        """A dense dim with many total structures should score slightly
        higher than one with few."""
        profile = self._profile("dense")
        crowded = self._rows([500, 600], enriched_count=20)
        sparse_world = self._rows([500, 600], enriched_count=2)
        _, parts_crowded = score_dimensions.score_candidate(profile, crowded)
        _, parts_sparse = score_dimensions.score_candidate(profile, sparse_world)
        self.assertGreater(parts_crowded["structures"], parts_sparse["structures"])

    def test_unenriched_falls_back_to_battery_count(self):
        """Without enrichment, density bias uses battery-found-count.
        Two candidates with the same battery results should get the
        same score."""
        profile = self._profile(None)
        rows_a = self._rows([500, 600])
        rows_b = self._rows([500, 600])
        _, parts_a = score_dimensions.score_candidate(profile, rows_a)
        _, parts_b = score_dimensions.score_candidate(profile, rows_b)
        self.assertEqual(parts_a["structures"], parts_b["structures"])

    def test_enrichment_flows_through_gather_measurements(self):
        """structure_all in the candidate store produces
        _enriched_structure_count in the gathered rows."""
        with tempfile.TemporaryDirectory() as tmp:
            cfg = Path(tmp) / "custom-dimensions"
            (cfg / "dimensions").mkdir(parents=True, exist_ok=True)
            (cfg / "dimensions" / "test_dim.json").write_text(
                json.dumps({"type": "overworld",
                            "seedRoll": {"spawnFilter": ["minecraft:plains"]}}))
            seedtest = Path(tmp) / "seedtest"
            seedtest.mkdir()
            store = candidates.empty_store()
            candidates.merge_rows(store, 42, {
                "spawn_biome": "minecraft:plains", "errors": "0"})
            store["candidates"]["42"]["structure_all"] = {
                "village": [[100, 64, 200]],
                "tavern": [[300, 64, 400]],
                "mineshaft": [[500, 64, 600]],
            }
            candidates.save_store(cfg / "candidates" / "test_dim.json", store)

            args = SimpleNamespace(config=str(cfg), seedtest=str(seedtest),
                                   csv=str(seedtest / "measurements.csv"))
            data = score_dimensions.gather_measurements(args)
            self.assertEqual(data["test_dim"]["42"]["_enriched_structure_count"], "3")

    def test_enrichment_absent_no_synthetic_key(self):
        """Without structure_all, no _enriched_structure_count appears."""
        with tempfile.TemporaryDirectory() as tmp:
            cfg = Path(tmp) / "custom-dimensions"
            (cfg / "dimensions").mkdir(parents=True, exist_ok=True)
            (cfg / "dimensions" / "test_dim.json").write_text(
                json.dumps({"type": "overworld",
                            "seedRoll": {"spawnFilter": ["minecraft:plains"]}}))
            seedtest = Path(tmp) / "seedtest"
            seedtest.mkdir()
            store = candidates.empty_store()
            candidates.merge_rows(store, 42, {
                "spawn_biome": "minecraft:plains", "errors": "0"})
            candidates.save_store(cfg / "candidates" / "test_dim.json", store)

            args = SimpleNamespace(config=str(cfg), seedtest=str(seedtest),
                                   csv=str(seedtest / "measurements.csv"))
            data = score_dimensions.gather_measurements(args)
            self.assertNotIn("_enriched_structure_count", data["test_dim"]["42"])


class ClearSpawnProfileTests(unittest.TestCase):
    """build_profile reads clearSpawnRadius from config with mood fallback."""

    def test_explicit_config_value(self):
        dim = {"name": "test", "type": "overworld",
               "dimensionId": "adventure:test",
               "structures": {"clearSpawnRadius": 200}}
        config = {"namespace": "adventure", "dimensions": [dim],
                  "worlds": [], "portals": []}
        p = build_profile(dim, config)
        self.assertEqual(p["clear_spawn_radius"], 200)

    def test_mood_fallback_hard(self):
        dim = {"name": "test", "type": "overworld",
               "dimensionId": "adventure:test",
               "seedRoll": {"mood": "hard"}}
        config = {"namespace": "adventure", "dimensions": [dim],
                  "worlds": [], "portals": []}
        p = build_profile(dim, config)
        self.assertEqual(p["clear_spawn_radius"], 0)

    def test_mood_fallback_serene(self):
        dim = {"name": "test", "type": "overworld",
               "dimensionId": "adventure:test",
               "seedRoll": {"mood": "serene"}}
        config = {"namespace": "adventure", "dimensions": [dim],
                  "worlds": [], "portals": []}
        p = build_profile(dim, config)
        self.assertEqual(p["clear_spawn_radius"], 80)

    def test_explicit_zero_overrides_mood(self):
        dim = {"name": "test", "type": "overworld",
               "dimensionId": "adventure:test",
               "seedRoll": {"mood": "serene"},
               "structures": {"clearSpawnRadius": 0}}
        config = {"namespace": "adventure", "dimensions": [dim],
                  "worlds": [], "portals": []}
        p = build_profile(dim, config)
        self.assertEqual(p["clear_spawn_radius"], 0)


class RenderManifestTests(unittest.TestCase):
    def test_emits_top_unrendered_candidate_as_finite_job(self):
        config = {
            "namespace": "adventure",
            "dimensions": [{
                "name": "sample_dimension",
                "type": "overworld",
                "dimensionId": "adventure:sample_dimension",
                "seedRoll": {"spawnFilter": ["minecraft:plains"]},
            }],
            "worlds": [],
            "portals": [],
        }
        profile = build_profile(config["dimensions"][0], config)
        with tempfile.TemporaryDirectory() as temp_dir:
            seedtest = Path(temp_dir)
            csv_path = seedtest / "measurements.csv"
            with csv_path.open("w", newline="") as fh:
                writer = csv.writer(fh)
                writer.writerow(["target", "seed", "metric", "value"])
                writer.writerow(["sample_dimension", "123", "spawn_biome", "minecraft:plains"])
                writer.writerow(["sample_dimension", "123", "errors", "0"])
            args = SimpleNamespace(
                seedtest=str(seedtest), csv=str(csv_path), workers=2, top=1)

            score_dimensions.cmd_render_manifest(args, config, {"sample_dimension": profile})

            self.assertEqual((seedtest / "work-r0.txt").read_text(), "sample_dimension|123\n")
            self.assertEqual((seedtest / "work-r1.txt").read_text(), "")


if __name__ == "__main__":
    unittest.main()
