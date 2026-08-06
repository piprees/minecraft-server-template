#!/usr/bin/env python3
"""Regression pin: _structure_section renders band bounds that arrive as
JSON strings.

A structures.wants entry with quoted numbers ({"min": "100", "max": "2000"})
reaches the battery as string bounds; every use is numeric (band compare,
{:.0f} render), so without coercion the detail panel dies on a TypeError
mid-render. Coercion happens once at the top of the want row — a
non-numeric bound still fails loudly there.
"""

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

SCORE_PATH = SCRIPT_DIR / "score-dimensions.py"
_SPEC = importlib.util.spec_from_file_location("score_dimensions_bands", SCORE_PATH)
score = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(score)


def _candidate():
    return {
        "structure_all": {},
        "metrics": {
            "spawn_x": 0, "spawn_z": 0,
            "_census": {
                "radiusChunks": 256,
                "groups": {"settlements": {
                    "count": 3,
                    "byStructure": {"minecraft:village_plains":
                                    {"count": 3, "nearest": 500}},
                }},
            },
            "_census_positions": {
                "settlements": [[10, 10, "minecraft:village_plains"],
                                [40, 40, "minecraft:village_plains"]],
            },
        },
    }


def _profile(spec):
    return {
        "battery": [("village_plains", "minecraft:village_plains", spec, "want")],
        "forced_structures": [],
        "_battery_groups": ({"minecraft:village_plains": "mvs:villages"},
                            {"mvs:villages": "settlements"}),
        "radius": 4096.0,
        "clear_spawn_radius": 0,
    }


class TestBandBoundCoercion(unittest.TestCase):
    def test_string_band_bounds_render(self):
        html_out = score._structure_section(_candidate(), _profile(("100", "2000")))
        self.assertIn("Village Plains", html_out)
        self.assertIn("mrow", html_out)

    def test_numeric_band_bounds_render_identically(self):
        as_str = score._structure_section(_candidate(), _profile(("100", "2000")))
        as_num = score._structure_section(_candidate(), _profile((100, 2000)))
        self.assertEqual(as_num, as_str)

    def test_non_numeric_bound_fails_loudly(self):
        with self.assertRaises(ValueError):
            score._structure_section(_candidate(), _profile(("near", "far")))

    def test_grid_path_string_bounds_render(self):
        """The grid/forced want path coerces too (its own raw unpack)."""
        c = _candidate()
        c["metrics"]["structure_village_plains_dist"] = 800.0
        profile = _profile(("100", "2000"))
        profile["_battery_groups"] = None
        html_out = score._structure_section(c, profile)
        self.assertIn("Village Plains", html_out)

    def test_range_label_string_bounds(self):
        label = score.range_label({"radius": 4096.0}, ("100", "2000"))
        self.assertEqual("100–2000 blocks", label)


if __name__ == "__main__":
    unittest.main()
