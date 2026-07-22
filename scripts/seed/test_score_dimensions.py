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
