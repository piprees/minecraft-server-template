#!/usr/bin/env python3
"""Tests for the v4 per-file config loading in dimension_profiles.py."""
import json
import tempfile
import unittest
from pathlib import Path

from dimension_profiles import (
    build_profile,
    load_config,
    load_dimension_configs,
    monolith_from_dir,
)


def write_tree(root, files):
    for rel, data in files.items():
        p = Path(root) / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(data if isinstance(data, str) else json.dumps(data))


class LoadDimensionConfigsTests(unittest.TestCase):
    def test_scans_directory_and_keys_by_filename(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_tree(tmp, {
                "dimensions/the_claymarsh.json": {"type": "overworld", "seed": 42},
                "dimensions/overworld.json": {"seed": 7, "spawn": [1, 64, 2]},
                "dimensions/broken.json": "{not json",
            })
            configs = load_dimension_configs(tmp)
            self.assertEqual(sorted(configs), ["overworld", "the_claymarsh"])
            self.assertEqual(configs["the_claymarsh"]["seed"], 42)
            self.assertEqual(configs["overworld"]["spawn"], [1, 64, 2])

    def test_missing_directory_returns_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(load_dimension_configs(Path(tmp) / "nope"), {})


class MonolithFromDirTests(unittest.TestCase):
    def make_tree(self, tmp):
        write_tree(tmp, {
            "settings.json": {"namespace": "adventure", "idleUnloadMinutes": 5,
                              "frames": {"overworld": "minecraft:crying_obsidian"}},
            "dimensions/the_claymarsh.json": {
                "type": "overworld", "seed": -1,
                "biomes": ["minecraft:swamp", "natures_spirit:marsh"],
                "structureDensity": "sparse",
                "difficulty": {"hostileSpawning": False, "mobMultiplier": 1.8},
                "portal": {"frameBlock": "minecraft:clay", "igniterItem": "minecraft:amethyst_shard",
                           "color": "9B8B7A", "lightLevel": 11, "scale": 8.0, "cooldown": 40,
                           "sounds": {"ignite": "i.g", "enter": "e.n", "exit": "e.x"}},
                "seedRoll": {"mood": "serene"},
            },
            "dimensions/overworld.json": {"seed": 999, "spawn": [5, 64, 6], "scale": 1.0},
            "dimensions/the_nether.json": {"seed": 111, "scale": 8.0},
        })

    def test_synthesises_legacy_shape(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_tree(tmp)
            cfg = monolith_from_dir(tmp)
            self.assertEqual(cfg["namespace"], "adventure")
            self.assertEqual(cfg["frameOverworld"], "minecraft:crying_obsidian")
            # overworld seed becomes the top-level worldSeed (legacy carrier)
            self.assertEqual(cfg["worldSeed"], 999)
            ow = next(w for w in cfg["worlds"] if w["name"] == "overworld")
            self.assertNotIn("seed", ow)
            self.assertEqual(ow["spawn"], [5, 64, 6])
            nether = next(w for w in cfg["worlds"] if w["name"] == "the_nether")
            self.assertEqual(nether["seed"], 111)
            self.assertEqual(nether["dimensionId"], "minecraft:the_nether")

            dim = cfg["dimensions"][0]
            self.assertEqual(dim["name"], "the_claymarsh")
            self.assertEqual(dim["dimensionId"], "adventure:the_claymarsh")
            self.assertEqual(dim["biome"], "minecraft:swamp,natures_spirit:marsh")
            self.assertIs(dim["hostileSpawning"], False)

            portal = cfg["portals"][0]
            self.assertEqual(portal["id"], "the_claymarsh")
            self.assertEqual(portal["targetDimension"], "adventure:the_claymarsh")
            self.assertEqual(portal["scale"], 8.0)
            self.assertEqual(portal["igniteSound"], "i.g")
            self.assertEqual(portal["enterSound"], "e.n")

    def test_env_seed_sentinel_is_omitted(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_tree(tmp, {"dimensions/overworld.json": {"seed": "env"}})
            cfg = monolith_from_dir(tmp)
            self.assertNotIn("worldSeed", cfg)
            self.assertNotIn("seed", cfg["worlds"][0])

    def test_staged_overlay_is_resolved_like_the_mod(self):
        import os
        from unittest import mock
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.dict(os.environ, {"BRAND_SLUG": "mybrand"}):
            write_tree(tmp, {
                "settings.json": {"namespace": "adventure"},
                "dimensions/kept.json": {"type": "overworld", "seed": 1},
                "dimensions/merged.json": {"type": "overworld", "seed": 2,
                                           "structureDensity": "sparse"},
                "dimensions/replaced.json": {"type": "overworld", "seed": 3},
                "dimensions/skipped.json": {"type": "overworld", "seed": 4},
                "overlay/dimensions/merged.json": {"overrides": {"seed": 22}},
                "overlay/dimensions/replaced.json": {"type": "nether", "seed": 33},
                "overlay/dimensions/skipped.json": {},
                "overlay/dimensions/added.json": {"type": "overworld", "seed": 55},
            })
            cfg = monolith_from_dir(tmp)
            dims = {d["name"]: d for d in cfg["dimensions"]}
            self.assertEqual(dims["kept"]["seed"], 1)
            self.assertEqual(dims["merged"]["seed"], 22)
            self.assertEqual(dims["merged"]["structureDensity"], "sparse")  # merge keeps siblings
            self.assertEqual(dims["replaced"]["type"], "nether")
            self.assertNotIn("structureDensity", dims["replaced"])
            self.assertNotIn("skipped", dims)
            self.assertEqual(dims["added"]["seed"], 55)
            # consumer-added dimensions are namespaced by BRAND_SLUG
            self.assertEqual(dims["added"]["dimensionId"], "mybrand:added")

    def test_load_config_dispatches_on_path_type(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_tree(tmp)
            from_dir = load_config(tmp)
            self.assertEqual(len(from_dir["dimensions"]), 1)
            mono = Path(tmp) / "multiverse_config.json"
            mono.write_text(json.dumps({"namespace": "x", "dimensions": [], "portals": [], "worlds": []}))
            self.assertEqual(load_config(mono)["namespace"], "x")


class StructureRangeTests(unittest.TestCase):
    """v4 Phase 6: explicit {min,max}/{minDistance} ranges vs band names."""

    def profile_for(self, dim):
        cfg = {"namespace": "adventure", "dimensions": [dim], "portals": [], "worlds": []}
        return build_profile(dim, cfg)

    def test_band_string_and_equivalent_range_produce_same_battery(self):
        radius = 8192.0
        banded = self.profile_for({
            "name": "d", "type": "overworld", "dimensionId": "adventure:d",
            "seedRoll": {"wants": {"swamp_ruin": "spread"}, "shuns": ["village"]},
        })
        ranged = self.profile_for({
            "name": "d", "type": "overworld", "dimensionId": "adventure:d",
            "structures": {"wants": {"swamp_ruin": {"min": 0.15 * radius,
                                                    "max": 0.65 * radius}},
                           "shuns": {"village": {"minDistance": 0}}},
        })
        self.assertEqual(banded["battery"], ranged["battery"])

    def test_explicit_ranges_ignore_density_shift(self):
        dim = {"name": "d", "type": "overworld", "dimensionId": "adventure:d",
               "structureDensity": "sparse",
               "structures": {"wants": {"swamp_ruin": {"min": 100, "max": 900}}}}
        profile = self.profile_for(dim)
        self.assertEqual(profile["battery"][0][2], (100.0, 900.0))

    def test_shun_min_distance_carries_through(self):
        dim = {"name": "d", "type": "overworld", "dimensionId": "adventure:d",
               "structures": {"wants": {}, "shuns": {"village": {"minDistance": 4000}}}}
        profile = self.profile_for(dim)
        name, _sid, threshold, kind = profile["battery"][0]
        self.assertEqual((name, kind, threshold), ("village", "shun", 4000.0))

    def test_full_locate_ids_pass_through(self):
        dim = {"name": "d", "type": "overworld", "dimensionId": "adventure:d",
               "structures": {"wants": {"mymod:custom_keep": {"min": 0, "max": 2000}}}}
        profile = self.profile_for(dim)
        self.assertEqual(profile["battery"][0][1], "mymod:custom_keep")

    def test_endgame_override_block(self):
        dim = {"name": "d", "type": "overworld", "dimensionId": "adventure:d",
               "structures": {"endgame": {"allow": False, "safeRadius": 1228}}}
        profile = self.profile_for(dim)
        self.assertEqual(profile["endgame_safe_radius"], 1228)
        allow = {"name": "d", "type": "overworld", "dimensionId": "adventure:d",
                 "seedRoll": {"mood": "serene"},
                 "structures": {"endgame": {"allow": True}}}
        self.assertEqual(self.profile_for(allow)["endgame_safe_radius"], 0)


class BuildProfileV4Tests(unittest.TestCase):
    def test_v4_dict_matches_legacy_equivalent(self):
        legacy_cfg = {
            "namespace": "adventure",
            "dimensions": [{
                "name": "the_claymarsh", "type": "overworld",
                "dimensionId": "adventure:the_claymarsh", "seed": 1,
                "biome": "minecraft:swamp,natures_spirit:marsh",
                "structureDensity": "sparse", "hostileSpawning": False,
                "seedRoll": {"mood": "serene", "spawnFilter": ["minecraft:swamp"],
                             "wants": {"swamp_ruin": "spread"}, "shuns": ["village"]},
            }],
            "portals": [{"id": "the_claymarsh", "targetDimension": "adventure:the_claymarsh",
                         "scale": 8.0}],
            "worlds": [],
        }
        v4_dim = {
            "name": "the_claymarsh", "type": "overworld",
            "dimensionId": "adventure:the_claymarsh", "seed": 1,
            "biomes": ["minecraft:swamp", "natures_spirit:marsh"],
            "structureDensity": "sparse",
            "difficulty": {"hostileSpawning": False},
            "portal": {"frameBlock": "minecraft:clay", "scale": 8.0},
            "seedRoll": {"mood": "serene", "spawnFilter": ["minecraft:swamp"],
                         "wants": {"swamp_ruin": "spread"}, "shuns": ["village"]},
        }
        p_legacy = build_profile(legacy_cfg["dimensions"][0], legacy_cfg)
        p_v4 = build_profile(v4_dim, {"namespace": "adventure", "dimensions": [v4_dim],
                                      "portals": [], "worlds": []})
        self.assertEqual(p_legacy, p_v4)
        self.assertEqual(p_v4["scale"], 8.0)          # from portal.scale
        self.assertTrue(p_v4["peaceful"])             # from difficulty.hostileSpawning

    def test_per_file_mob_multiplier_wins_over_legacy_dict(self):
        dim = {"name": "d", "type": "overworld", "dimensionId": "adventure:d",
               "difficulty": {"mobMultiplier": 2.5}}
        cfg = {"namespace": "adventure", "dimensions": [dim], "portals": [], "worlds": []}
        profile = build_profile(dim, cfg, {"adventure:d": 1.0})
        self.assertEqual(profile["mob_difficulty"], 2.5)
        # fallback path when the per-file block has no multiplier
        dim2 = {"name": "d", "type": "overworld", "dimensionId": "adventure:d"}
        self.assertEqual(build_profile(dim2, cfg, {"adventure:d": 1.3})["mob_difficulty"], 1.3)


if __name__ == "__main__":
    unittest.main()
