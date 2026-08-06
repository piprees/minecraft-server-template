#!/usr/bin/env python3
"""Regression pin: build_from_spec resolves the jar-walk extraction root
from the biome table's own directory when the caller passes None.

Every roller entry point (fast_roller, score-dimensions, rederive,
viewer-server, biome_renderer) reaches build_from_spec without an explicit
extracted_data_root, while the parity gate passes one — a rootless sampler
misses the extracted noise parameter overrides and the legacy climate
replacement, so it describes a different world from the one the gate
certifies. The default resolution closes that gap; this module makes it
impossible to reopen quietly.
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))


def _write_table(tmp):
    """A one-entry nether-family table so the sampler builds standalone."""
    entry = {"biome": "minecraft:plains", "family": "nether",
             "temperature": [-2, 2], "humidity": [-2, 2],
             "continentalness": [-2, 2], "erosion": [-2, 2],
             "depth": [-2, 2], "weirdness": [-2, 2], "offset": 0.0}
    path = Path(tmp) / "biome_params.json"
    path.write_text(json.dumps([entry]))
    return str(path)


_SPEC = {"noise_family": "nether", "dim_type": "nether", "biomes": [],
         "parameters": {}, "patches": [], "checkerboard_scale": None,
         "suppressed_biomes": [], "noise_settings": None}


class TestDefaultExtractionRoot(unittest.TestCase):
    def test_resolves_sibling_dir_or_none(self):
        from biome_sampler import default_extraction_root
        with tempfile.TemporaryDirectory() as tmp:
            params = _write_table(tmp)
            self.assertIsNone(default_extraction_root(params))
            (Path(tmp) / ".noise_settings").mkdir()
            root = default_extraction_root(params)
            self.assertIsNotNone(root)
            self.assertTrue(root.endswith(".noise_settings"))
            self.assertTrue(Path(root).is_dir())

    def test_build_from_spec_defaults_to_sibling_root(self):
        """An extracted noise-parameter override beside the table must be
        picked up with no root argument — observable as the patched
        first_octave/amplitudes on the family's shifted-noise sampler."""
        from biome_sampler import build_from_spec
        with tempfile.TemporaryDirectory() as tmp:
            params = _write_table(tmp)
            noise_dir = Path(tmp) / ".noise_settings/minecraft/worldgen/noise"
            noise_dir.mkdir(parents=True)
            (noise_dir / "temperature.json").write_text(
                json.dumps({"firstOctave": -7, "amplitudes": [9.0]}))

            defaulted = build_from_spec(1234, dict(_SPEC), params)
            explicit = build_from_spec(
                1234, dict(_SPEC), params,
                extracted_data_root=str(Path(tmp) / ".noise_settings"))

            for sampler in (defaulted, explicit):
                octave_sampler = sampler._climate_params["temperature"][0]
                self.assertEqual(-7, octave_sampler.first.first_octave)
                self.assertEqual([9.0], octave_sampler.first.amplitudes)

    def test_no_sibling_root_keeps_family_params(self):
        """Without a sibling extraction the family config stands — the
        fallback is None, not an invented path."""
        from biome_sampler import build_from_spec, load_noise_configs
        family_octave = load_noise_configs()["nether"]["temperature"]["first_octave"]
        with tempfile.TemporaryDirectory() as tmp:
            params = _write_table(tmp)
            sampler = build_from_spec(1234, dict(_SPEC), params)
            octave_sampler = sampler._climate_params["temperature"][0]
            self.assertEqual(family_octave, octave_sampler.first.first_octave)


if __name__ == "__main__":
    unittest.main()
