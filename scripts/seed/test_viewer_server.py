#!/usr/bin/env python3
"""Server-side validation tests for the fork/create/edit config form
(viewer-server.py). Pure-function coverage: _validate_fork_config,
_deep_merge, and the fork schema shape — no HTTP server involved."""
import importlib.util
import unittest
from pathlib import Path

VIEWER_PATH = Path(__file__).with_name("viewer-server.py")
SPEC = importlib.util.spec_from_file_location("viewer_server", VIEWER_PATH)
viewer_server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(viewer_server)

validate = viewer_server._validate_fork_config
deep_merge = viewer_server._deep_merge
schema = viewer_server._build_fork_schema("unused")


class ForkSchemaTests(unittest.TestCase):
    def test_schema_shape(self):
        self.assertEqual(schema["version"], 1)
        self.assertIn("overworld", schema["types"])
        self.assertIn("superflat", schema["types"])
        self.assertIn("adventure:wide", schema["noise_settings"])
        self.assertTrue(schema["moods"])
        self.assertTrue(schema["structures"])
        self.assertIn("near_spawn", schema["band_ranges"])
        # biome groups exist when biome_params.json is present
        if schema["biomes"]:
            self.assertIn("minecraft", schema["biomes"])


class ValidateForkConfigTests(unittest.TestCase):
    def test_happy_path(self):
        clean, errors = validate({
            "type": "cave", "noiseSettings": "adventure:compressed",
            "structureDensity": "dense", "borderRadius": 1024,
            "mood": sorted(schema["moods"])[0], "water": "none",
            "wants": {"village": "spread",
                      "monument": {"min": 100, "max": 900}},
            "shuns": {"mansion": {"minDistance": 500}},
            "mobMultiplier": 1.5, "hostileSpawning": False,
            "frameBlock": "minecraft:obsidian", "color": "#8844ff",
            "scale": 8,
        }, "unused")
        self.assertEqual(errors, {})
        self.assertEqual(clean["type"], "cave")
        self.assertEqual(clean["borders"], {"player": 1024, "generation": 1024})
        self.assertEqual(clean["structures"]["wants"]["monument"],
                         {"min": 100, "max": 900})
        # band-name wants go to seedRoll.wants — structures.wants is the
        # mod's Map<String, StructureWant>, which Gson-crashes on strings
        self.assertEqual(clean["seedRoll"]["wants"]["village"], "spread")
        self.assertNotIn("village", clean["structures"]["wants"])
        self.assertEqual(clean["structures"]["shuns"]["mansion"],
                         {"minDistance": 500})
        self.assertEqual(clean["difficulty"]["mobMultiplier"], 1.5)
        self.assertIs(clean["difficulty"]["hostileSpawning"], False)
        self.assertEqual(clean["portal"]["color"], "8844FF")
        self.assertEqual(clean["portal"]["scale"], 8.0)

    def test_each_rejection_class(self):
        cases = [
            ({"type": "hexagon"}, "type"),
            ({"noiseSettings": "minecraft:bogus"}, "noiseSettings"),
            ({"structureDensity": "extreme"}, "structureDensity"),
            ({"borderRadius": 12}, "borderRadius"),
            ({"biomes": ["not:a_biome_xyz"]}, "biomes"),
            ({"mood": "melancholy_nonsense"}, "mood"),
            ({"water": "damp"}, "water"),
            ({"wants": {"not_a_structure_xyz": "spread"}}, "wants"),
            ({"wants": {"village": {"min": 500, "max": 100}}}, "wants"),
            ({"wants": {"village": "wrong_band"}}, "wants"),
            ({"shuns": ["village"]}, "shuns"),   # list form crashes the mod
            ({"shuns": {"not_a_structure_xyz": {}}}, "shuns"),
            ({"mobMultiplier": 99}, "mobMultiplier"),
            ({"frameBlock": "no_namespace"}, "frameBlock"),
            ({"color": "red"}, "color"),
            ({"scale": 3}, "scale"),
        ]
        for raw, field in cases:
            _, errors = validate(raw, "unused")
            self.assertIn(field, errors, f"expected rejection for {raw}")

    def test_spawn_filter_must_subset_biomes(self):
        biome = None
        for ids in schema["biomes"].values():
            if ids:
                biome = ids[0]
                break
        if biome is None:
            self.skipTest("no biome_params.json")
        clean, errors = validate(
            {"biomes": [biome], "spawnFilter": ["minecraft:the_void_xyz"]},
            "unused")
        self.assertIn("spawnFilter", errors)
        clean, errors = validate({"biomes": [biome], "spawnFilter": [biome]},
                                 "unused")
        self.assertEqual(errors, {})
        self.assertEqual(clean["seedRoll"]["spawnFilter"], [biome])

    def test_empty_and_unknown_fields_pass_through_silently(self):
        clean, errors = validate({}, "unused")
        self.assertEqual((clean, errors), ({}, {}))
        clean, errors = validate({"unknownKey": 42}, "unused")
        self.assertEqual(errors, {})
        self.assertNotIn("unknownKey", clean)

    def test_non_dict_config_rejected(self):
        _, errors = validate(["not", "a", "dict"], "unused")
        self.assertIn("config", errors)


class DeepMergeTests(unittest.TestCase):
    def test_nested_merge_and_override(self):
        base = {"a": 1, "borders": {"player": 512, "generation": 512},
                "portal": {"frameBlock": "x"}}
        over = {"borders": {"player": 1024}, "portal": {"color": "FF0000"}}
        out = deep_merge(base, over)
        self.assertEqual(out["borders"], {"player": 1024, "generation": 512})
        self.assertEqual(out["portal"], {"frameBlock": "x", "color": "FF0000"})
        self.assertEqual(out["a"], 1)
        # base is not mutated
        self.assertEqual(base["borders"]["player"], 512)


if __name__ == "__main__":
    unittest.main()
