#!/usr/bin/env python3
"""Regression tests for shortlist render manifest generation."""
import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from dimension_profiles import build_profile

SCORE_PATH = Path(__file__).with_name("score-dimensions.py")
SPEC = importlib.util.spec_from_file_location("score_dimensions", SCORE_PATH)
score_dimensions = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(score_dimensions)


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
