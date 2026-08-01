#!/usr/bin/env python3
"""Tests for the v4 per-file config loading in dimension_profiles.py."""
import json
import tempfile
import unittest
from pathlib import Path

import dimension_profiles as dp
from dimension_profiles import (
    build_profile,
    family_of,
    generation_fingerprint,
    generation_payload,
    load_config,
    load_dimension_configs,
    monolith_from_dir,
    rollable,
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

    def test_load_config_reads_the_config_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_tree(tmp)
            cfg = load_config(tmp)
            self.assertEqual(len(cfg["dimensions"]), 1)
            self.assertEqual(cfg["namespace"], "adventure")
            self.assertEqual({w["name"] for w in cfg["worlds"]},
                             {"overworld", "the_nether"})


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


class RollableTests(unittest.TestCase):
    """Tier 2: seedRoll.skip opt-out + checkerboard rollability."""

    def test_seed_roll_skip_excludes_any_type(self):
        self.assertFalse(rollable({"type": "overworld", "seedRoll": {"skip": True}}))
        self.assertFalse(rollable({"type": "checkerboard", "biomes": ["minecraft:plains"],
                                   "seedRoll": {"skip": True}}))
        # skip absent or falsy leaves the normal rules in force
        self.assertTrue(rollable({"type": "overworld"}))
        self.assertTrue(rollable({"type": "overworld", "seedRoll": {"skip": False}}))
        self.assertTrue(rollable({"type": "overworld", "seedRoll": {"mood": "serene"}}))

    def test_superflat_never_rolls_even_with_custom_layers(self):
        self.assertFalse(rollable({"type": "superflat"}))
        self.assertFalse(rollable({"type": "superflat", "flatBiome": "minecraft:desert",
                                   "layers": [{"block": "minecraft:sand", "height": 3}]}))

    def test_checkerboard_rolls_and_rides_overworld_family(self):
        self.assertTrue(rollable({"type": "checkerboard",
                                  "biomes": ["minecraft:plains", "minecraft:desert"]}))
        self.assertEqual(family_of("checkerboard"), "overworld")

    def test_checkerboard_scale_flows_into_profile_via_monolith(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_tree(tmp, {
                "dimensions/the_patchwork.json": {
                    "type": "checkerboard", "checkerboardScale": 4,
                    "biomes": ["minecraft:plains", "minecraft:desert"]},
            })
            cfg = monolith_from_dir(tmp)
        dim = cfg["dimensions"][0]
        self.assertEqual(dim["checkerboardScale"], 4)
        profile = build_profile(dim, cfg)
        self.assertEqual(profile["checkerboard_scale"], 4)
        self.assertEqual(profile["family"], "overworld")
        # unset scale stays None (sampler defaults to vanilla's 2)
        plain = {"name": "d", "type": "checkerboard", "dimensionId": "adventure:d",
                 "biome": "minecraft:plains"}
        cfg2 = {"namespace": "adventure", "dimensions": [plain], "portals": [], "worlds": []}
        self.assertIsNone(build_profile(plain, cfg2)["checkerboard_scale"])


class Tier3ProfileTests(unittest.TestCase):
    """Tier 3 parity: biome parameters, settingsOverrides, spacing overrides."""

    def profile_for(self, dim):
        cfg = {"namespace": "adventure", "dimensions": [dim], "portals": [], "worlds": []}
        return build_profile(dim, cfg)

    def test_biomes_object_entries_split_ids_and_parameters(self):
        dim = {"name": "d", "type": "multi_biome", "dimensionId": "adventure:d",
               "biomes": ["minecraft:plains",
                          {"id": "minecraft:cherry_grove",
                           "parameters": {"temperature": [-0.5, 0.2]}},
                          "minecraft:desert"]}
        profile = self.profile_for(dim)
        self.assertEqual(profile["create_args"]["biome"],
                         "minecraft:plains,minecraft:cherry_grove,minecraft:desert")
        self.assertEqual(profile["biome_parameters"],
                         {"minecraft:cherry_grove": {"temperature": [-0.5, 0.2]}})

    def test_settings_and_spacing_overrides_reach_profile(self):
        dim = {"name": "d", "type": "overworld", "dimensionId": "adventure:d",
               "settingsOverrides": {"seaLevel": 100, "defaultFluid": "minecraft:lava"},
               "structures": {"spacing": {"minecraft:villages": {"spacing": 8, "separation": 4}}}}
        profile = self.profile_for(dim)
        self.assertEqual(profile["settings_overrides"]["seaLevel"], 100)
        self.assertEqual(profile["spacing_overrides"]["minecraft:villages"],
                         {"spacing": 8, "separation": 4})
        # absent -> empty dicts, never None
        plain = self.profile_for({"name": "p", "type": "overworld",
                                  "dimensionId": "adventure:p"})
        self.assertEqual(plain["settings_overrides"], {})
        self.assertEqual(plain["spacing_overrides"], {})
        self.assertEqual(plain["biome_parameters"], {})

    def test_player_border_carries_raw_unscaled_value(self):
        """The derived shrine spacing (mod: DimensionStructures
        .derivedShrineSpacing; roller: fast_roller tier1) uses the RAW
        borders.player, never the scaled playable radius. The expected
        spacing table is duplicated in DimensionStructuresTest.java —
        change both together."""
        explicit = self.profile_for({"name": "d", "type": "overworld",
                                     "dimensionId": "adventure:d",
                                     "borders": {"player": 512}})
        self.assertEqual(explicit["player_border"], 512)
        # no border + portal scale: radius is scaled but player_border is raw
        scaled = self.profile_for({"name": "d", "type": "overworld",
                                   "dimensionId": "adventure:d",
                                   "portal": {"frameBlock": "b", "scale": 8.0}})
        self.assertEqual(scaled["player_border"], 8192)
        self.assertEqual(scaled["radius"], 1024.0)
        # the derived formula both sides pin: clamp(border // 32, 12, 48)
        for border, spacing in ((256, 12), (384, 12), (512, 16),
                                (1024, 32), (1536, 48), (8192, 48)):
            derived = max(12, min(48, border // 32))
            self.assertEqual(derived, spacing)
            self.assertEqual(derived // 2, spacing // 2)

    def test_exits_block_is_runtime_only(self):
        """The 'exits' block (exit conditions) must not affect scoring —
        profiles with and without it are identical (same principle as the
        portal block)."""
        base = {"name": "d", "type": "overworld", "dimensionId": "adventure:d",
                "seedRoll": {"mood": "serene"}}
        with_exits = dict(base)
        with_exits["exits"] = {"void": {"target": "bed"},
                               "death:lava": {"action": "respawnAt",
                                              "target": {"dimension": "adventure:x"}}}
        self.assertEqual(self.profile_for(base), self.profile_for(with_exits))

    def test_monolith_carries_tier3_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_tree(tmp, {
                "dimensions/the_lavasea.json": {
                    "type": "multi_biome",
                    "biomes": ["minecraft:plains",
                               {"id": "minecraft:desert",
                                "parameters": {"continentalness": 0.3}}],
                    "settingsOverrides": {"seaLevel": 40},
                },
            })
            cfg = monolith_from_dir(tmp)
        dim = cfg["dimensions"][0]
        self.assertEqual(dim["biome"], "minecraft:plains,minecraft:desert")
        self.assertEqual(dim["biomeParameters"],
                         {"minecraft:desert": {"continentalness": 0.3}})
        self.assertEqual(dim["settingsOverrides"], {"seaLevel": 40})
        profile = build_profile(dim, cfg)
        self.assertEqual(profile["biome_parameters"],
                         {"minecraft:desert": {"continentalness": 0.3}})
        self.assertEqual(profile["settings_overrides"], {"seaLevel": 40})


class GenerationFingerprintTests(unittest.TestCase):
    """Seed-group rolling: two dims share a seed's measurements iff their
    generation-affecting config is byte-identical. Scoring/runtime fields
    must NOT move the fingerprint; generation fields MUST."""

    BASE = {
        "name": "a", "type": "overworld", "dimensionId": "adventure:a",
        "biomes": ["minecraft:plains", "minecraft:forest"],
        "structureDensity": "sparse", "noiseSettings": "adventure:wide",
    }

    def fp(self, **changes):
        dim = {**self.BASE, **changes}
        return generation_fingerprint(dim)

    def test_scoring_and_runtime_fields_do_not_change_it(self):
        base = self.fp()
        self.assertEqual(base, self.fp(name="b", dimensionId="adventure:b", seed=42))
        self.assertEqual(base, self.fp(seedRoll={"mood": "hard",
                                                 "spawnFilter": ["minecraft:forest"],
                                                 "wants": {"village": "spread"}}))
        self.assertEqual(base, self.fp(portal={"frameBlock": "minecraft:clay",
                                               "scale": 8.0}))
        # Tier 2 portal customisations are runtime-only too: shapes,
        # orientation, placement blocks, per-part materials, pedestals.
        self.assertEqual(base, self.fp(portal={
            "frameBlock": "#minecraft:logs", "framePlaceBlock": "minecraft:oak_log",
            "orientation": "horizontal", "shape": "end_exit",
            "centreBlock": "minecraft:dragon_egg",
            "frameMaterials": {"top": "#minecraft:planks", "sides": "#minecraft:logs",
                               "bottom": "minecraft:stone"}}))
        # Portal auras are runtime-only environmental spread.
        self.assertEqual(base, self.fp(portal={
            "frameBlock": "minecraft:clay",
            "aura": {"radius": 12, "budget": -1, "sides": "both",
                     "palette": ["minecraft:netherrack"], "fireChance": 0.08,
                     "conversions": {"minecraft:obsidian": "minecraft:crying_obsidian"}}}))
        self.assertEqual(base, self.fp(difficulty={"mobMultiplier": 3.0}))
        self.assertEqual(base, self.fp(borders={"player": 512}))
        self.assertEqual(base, self.fp(description="x", color="FF0000",
                                       exits={"void": {"target": "bed"}}))
        # structures wants/shuns/clearSpawnRadius are scoring-only...
        self.assertEqual(base, self.fp(structures={"wants": {"village": "spread"},
                                                   "shuns": ["monument"],
                                                   "clearSpawnRadius": 64}))

    def test_generation_fields_change_it(self):
        base = self.fp()
        self.assertNotEqual(base, self.fp(type="multi_biome"))
        self.assertNotEqual(base, self.fp(noiseSettings="adventure:compressed"))
        self.assertNotEqual(base, self.fp(structureDensity="dense"))
        self.assertNotEqual(base, self.fp(biomes=["minecraft:plains"]))
        # order matters: one biome's difference re-deals the whole layout
        self.assertNotEqual(base, self.fp(biomes=["minecraft:forest", "minecraft:plains"]))
        self.assertNotEqual(base, self.fp(biomes=[
            "minecraft:plains",
            {"id": "minecraft:forest", "parameters": {"temperature": [0, 1]}}]))
        self.assertNotEqual(base, self.fp(difficulty={"hostileSpawning": False}))
        self.assertNotEqual(base, self.fp(environment={"minY": -128, "height": 512}))
        self.assertNotEqual(base, self.fp(borders={"generation": 2048}))
        self.assertNotEqual(base, self.fp(settingsOverrides={"seaLevel": 100}))
        self.assertNotEqual(base, self.fp(biomePatches=[
            {"biome": "minecraft:plains", "x": 0, "z": 0, "radius": 64}]))
        self.assertNotEqual(base, self.fp(exitShrines={"enabled": True}))
        # shrine dims derive their grid from borders.player — differing
        # player borders must split the group (but ONLY for shrine dims:
        # asserted the other way in the runtime test above)
        self.assertNotEqual(
            self.fp(exitShrines={"enabled": True}, borders={"player": 512}),
            self.fp(exitShrines={"enabled": True}, borders={"player": 4096}))
        # an explicit shrine spacing override neutralises the border derive
        self.assertEqual(
            self.fp(exitShrines={"enabled": True}, borders={"player": 512},
                    structures={"spacing": {"adventure:exit_shrines":
                                            {"spacing": 20, "separation": 10}}}),
            self.fp(exitShrines={"enabled": True}, borders={"player": 4096},
                    structures={"spacing": {"adventure:exit_shrines":
                                            {"spacing": 20, "separation": 10}}}))
        # ...but structures.spacing rescales placements: generation-affecting
        self.assertNotEqual(base, self.fp(structures={
            "spacing": {"minecraft:villages": {"spacing": 8, "separation": 4}}}))
        # structures.mode / structures.force (fixed placements, 2026-07-24)
        # are generation-affecting — and conditional, so dims without them
        # keep pre-existing fingerprints byte-stable.
        self.assertNotEqual(base, self.fp(structures={"mode": "none"}))
        self.assertNotEqual(base, self.fp(structures={
            "mode": "reject", "list": ["minecraft:villages"]}))
        self.assertNotEqual(
            self.fp(structures={"mode": "allow", "list": ["minecraft:villages"]}),
            self.fp(structures={"mode": "allow", "list": ["minecraft:mineshafts"]}))
        self.assertNotEqual(base, self.fp(structures={
            "force": [{"structure": "minecraft:ancient_city", "x": 100, "z": -200}]}))
        self.assertNotEqual(
            self.fp(structures={"force": [{"structure": "minecraft:ancient_city",
                                           "x": 100, "z": -200}]}),
            self.fp(structures={"force": [{"structure": "minecraft:ancient_city",
                                           "x": 100, "z": -300}]}))
        # malformed force entries are ignored, not fingerprinted
        self.assertEqual(base, self.fp(structures={
            "force": [{"structure": None, "x": 1, "z": 2}, {"x": 3, "z": 4}]}))

    def test_structure_parity_helpers(self):
        # Mirrors DimensionStructures: forced placements are constants,
        # mode filters organic sets, the exit-shrines opt-in is exempt.
        from structure_placement import forced_distance, mode_drops
        profile = {
            "forced_structures": [
                {"structure": "minecraft:ancient_city", "x": 300, "z": 400},
                {"structure": "minecraft:ancient_city", "x": 30, "z": 40}],
            "structures_mode": "allow",
            "structures_list": ["minecraft:villages"],
            "exit_shrines": True,
        }
        self.assertEqual(50, forced_distance("minecraft:ancient_city", profile))
        self.assertEqual(50, forced_distance("#minecraft:ancient_city", profile))
        self.assertIsNone(forced_distance("minecraft:villages", profile))
        self.assertFalse(mode_drops("minecraft:villages", profile))
        self.assertTrue(mode_drops("minecraft:mineshafts", profile))
        self.assertFalse(mode_drops("adventure:exit_shrines", profile))  # opt-in exempt
        profile["structures_mode"] = "reject"
        self.assertTrue(mode_drops("minecraft:villages", profile))
        self.assertFalse(mode_drops("minecraft:mineshafts", profile))
        profile["structures_mode"] = "none"
        self.assertTrue(mode_drops("minecraft:mineshafts", profile))
        profile["structures_mode"] = None
        self.assertFalse(mode_drops("minecraft:mineshafts", profile))

    def test_runtime_environment_keys_do_not_change_it(self):
        base = self.fp(environment={"minY": -64})
        self.assertEqual(base, self.fp(environment={"minY": -64, "ambientLight": 0.5,
                                                    "hasSkyLight": False}))

    def test_a_raw_entry_with_no_type_has_no_payload(self):
        # Base worlds get theirs stamped by monolith synthesis; a raw dict
        # that has not been through it has nothing to resolve groups against.
        self.assertIsNone(generation_fingerprint({"name": "overworld", "seed": 1}))
        self.assertIsNone(generation_payload({"name": "the_nether", "scale": 8.0}))

    def test_v4_dict_and_monolith_entry_agree(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_tree(tmp, {
                "dimensions/the_test.json": {
                    "type": "multi_biome", "seed": 7,
                    "biomes": ["minecraft:plains",
                               {"id": "minecraft:desert",
                                "parameters": {"continentalness": 0.3}}],
                    "structureDensity": "dense",
                    "environment": {"minY": -32, "height": 288, "ambientLight": 0.1},
                    "borders": {"player": 1024, "generation": 2048},
                    "seedRoll": {"mood": "hard"},
                    "portal": {"frameBlock": "minecraft:clay", "scale": 4.0},
                },
            })
            cfg = monolith_from_dir(tmp)
        synthesised = cfg["dimensions"][0]
        raw = {
            "name": "the_test", "type": "multi_biome", "seed": 7,
            "biomes": ["minecraft:plains",
                       {"id": "minecraft:desert",
                        "parameters": {"continentalness": 0.3}}],
            "structureDensity": "dense",
            "environment": {"minY": -32, "height": 288, "ambientLight": 0.1},
            "borders": {"player": 1024, "generation": 2048},
        }
        self.assertEqual(generation_fingerprint(raw),
                         generation_fingerprint(synthesised))


class BaseWorldParityTests(unittest.TestCase):
    """Base worlds are managed exactly like custom dimensions.

    The mod's side is
    MultiverseConfig.getBaseWorld (exact dimension id, never a namespace) and
    DimensionConfig.getType's family fallback — change both together.
    """

    @classmethod
    def setUpClass(cls):
        import os
        repo = os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))))
        cls.config_dir = os.path.join(repo, "config", "custom-dimensions")
        dp.set_noise_defaults_dir(cls.config_dir)

    def test_base_world_gets_a_payload_of_its_own(self):
        dim = {"name": "the_end", "dimensionId": "minecraft:the_end",
               "type": "end", "borders": {"player": 4096, "generation": 4096},
               "difficulty": {"mobMultiplier": 1.5}}
        payload = generation_payload(dim)
        self.assertIsNotNone(payload)
        self.assertEqual(payload["baseWorld"], "minecraft:the_end")
        self.assertIn("noisePlacement", payload)
        self.assertEqual(payload["noisePlacement"]["radiusChunks"], 256)
        self.assertIsNotNone(dp.noise_fingerprint(dim))

    def test_every_base_world_carries_its_family_after_synthesis(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_tree(tmp, {f"dimensions/{slug}.json": {"seed": 1}
                             for slug in dp.BASE_WORLD_TYPES})
            cfg = monolith_from_dir(tmp)
        worlds = {w["name"]: w for w in cfg["worlds"]}
        for slug, expected in dp.BASE_WORLD_TYPES.items():
            self.assertEqual(worlds[slug]["type"], expected, slug)
            self.assertIsNotNone(generation_fingerprint(worlds[slug]), slug)

    def test_a_base_world_never_shares_a_group_with_a_custom_dimension(self):
        """Vanilla builds a base world from the level's world preset; the mod
        templates a custom dimension off overworldOpts, and the roller
        measures the two through different plumbing. Agreeing on every other
        field does not make them clones."""
        world = {"name": "overworld", "dimensionId": "minecraft:overworld",
                 "type": "overworld", "borders": {"player": 8192, "generation": 8192}}
        custom = {"name": "the_twin", "dimensionId": "adventure:the_twin",
                  "type": "overworld", "borders": {"player": 8192, "generation": 8192}}
        self.assertNotEqual(generation_fingerprint(world),
                            generation_fingerprint(custom))

    def test_no_shipped_dimension_collides_with_a_base_world(self):
        """The discriminator has to hold against the real config set, not a
        hand-built pair."""
        cfg = load_config(self.config_dir)
        by_fp = {}
        for entry in cfg["dimensions"] + cfg["worlds"]:
            probe = dict(entry)
            probe.setdefault("type", "overworld")   # force every one to fingerprint
            fp = generation_fingerprint(probe)
            if fp is None:
                continue
            by_fp.setdefault(fp, []).append(entry["name"])
        base = set(dp.BASE_WORLD_IDS)
        for fp, names in by_fp.items():
            if base & set(names):
                self.assertEqual(1, len(names),
                                 f"base world grouped with {names} under {fp}")

    def test_is_world_is_keyed_on_identity_not_on_the_type_field(self):
        """Keying `is_world` on the ABSENCE of a type sends a base world down
        the custom-dimension path: paradise_lost resolves family 'overworld'
        and the scale comes from portal.scale."""
        cfg = {"namespace": "adventure", "dimensions": [], "portals": [], "worlds": []}
        for name, dim_id, family in (
                ("overworld", "minecraft:overworld", "overworld"),
                ("the_nether", "minecraft:the_nether", "nether"),
                ("the_end", "minecraft:the_end", "end"),
                ("paradise_lost", "paradise_lost:paradise_lost", "paradise_lost")):
            typed = {"name": name, "dimensionId": dim_id, "scale": 4.0,
                     "type": dp.BASE_WORLD_TYPES[name]}
            profile = build_profile(typed, cfg)
            self.assertTrue(profile["is_world"], name)
            self.assertEqual(profile["family"], family, name)
            self.assertEqual(profile["scale"], 4.0, name)

    def test_monolith_carries_a_base_worlds_structure_block(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_tree(tmp, {
                "dimensions/the_end.json": {
                    "seed": 5, "borders": {"player": 4096},
                    "structureDensity": "sparse",
                    "structures": {"noise": {"endgame": "natural"},
                                   "force": [{"structure": "minecraft:end_city",
                                              "x": 300, "z": 300}]},
                },
                "dimensions/overworld.json": {"seed": 9},
            })
            cfg = monolith_from_dir(tmp)
        end = next(w for w in cfg["worlds"] if w["name"] == "the_end")
        self.assertEqual(end["type"], "end")
        self.assertEqual(end["structureDensity"], "sparse")
        self.assertEqual(end["structures"]["noise"], {"endgame": "natural"})
        # An explicit type in the file still wins over the family default.
        with tempfile.TemporaryDirectory() as tmp:
            write_tree(tmp, {"dimensions/the_end.json": {"seed": 5, "type": "nether"}})
            cfg = monolith_from_dir(tmp)
        self.assertEqual(cfg["worlds"][0]["type"], "nether")


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

    def test_borders_player_sets_playable_radius(self):
        cfg = {"namespace": "adventure", "dimensions": [], "portals": [], "worlds": []}
        pocket = {"name": "p", "type": "multi_biome", "dimensionId": "adventure:p",
                  "borders": {"player": 256, "generation": 2048},
                  "structures": {"wants": {"farmstead": {"min": 512, "max": 2048}}}}
        profile = build_profile(pocket, cfg)
        self.assertEqual(profile["radius"], 256.0)
        # bands are relative to the real playable radius, not 8192/scale
        self.assertEqual(profile["grid_pitch"], 64)
        # wants beyond the border stretch the locate cap to reach them
        self.assertEqual(profile["locate_cap"], 3048)
        # fallback: no borders block -> 8192/scale heuristic unchanged
        plain = {"name": "d", "type": "overworld", "dimensionId": "adventure:d"}
        self.assertEqual(build_profile(plain, cfg)["radius"], 8192.0)
        # zero means borderless: heuristic applies
        borderless = {"name": "b", "type": "overworld", "dimensionId": "adventure:b",
                      "borders": {"player": 0}}
        self.assertEqual(build_profile(borderless, cfg)["radius"], 8192.0)

    def test_borders_carried_through_monolith_synthesis(self):
        import json
        import pathlib
        import tempfile
        with tempfile.TemporaryDirectory() as td:
            dims = pathlib.Path(td) / "dimensions"
            dims.mkdir()
            (dims / "pocket.json").write_text(json.dumps(
                {"type": "multi_biome", "borders": {"player": 256},
                 "structures": {"wants": {"farmstead": {"min": 512, "max": 2048}},
                                "shuns": ["village"]}}))
            (dims / "the_end.json").write_text(json.dumps(
                {"seed": 5, "borders": {"player": 4096}}))
            cfg = monolith_from_dir(td)
        dim = next(d for d in cfg["dimensions"] if d["name"] == "pocket")
        self.assertEqual(dim["borders"], {"player": 256})
        profile = build_profile(dim, cfg)
        self.assertEqual(profile["radius"], 256.0)
        # the v4 "structures" block survives synthesis: explicit wants/shuns,
        # not the DEFAULT_WANTS fallback
        self.assertEqual({(n, kind) for n, _sid, _spec, kind in profile["battery"]},
                         {("farmstead", "want"), ("village", "shun")})
        world = next(w for w in cfg["worlds"] if w["name"] == "the_end")
        self.assertEqual(build_profile(world, cfg)["radius"], 4096.0)


class PlacesNothingTests(unittest.TestCase):
    """A dimension that cannot place an organic structure must not be scored
    on structures it has deliberately switched off.

    Three shipped dimensions inherited the family DEFAULT_WANTS battery
    despite structureDensity "none" / an empty allow-list, then lost the full
    structures weight on every candidate for absent Villages and Mineshafts.
    That caps the dimension below 100 and makes structures a constant, so it
    discriminates between candidates not at all (2026-07-28).
    """

    def _profile(self, **over):
        dim = {"name": "t", "type": "multi_biome", "dimensionId": "adventure:t",
               "biomes": ["minecraft:plains"], "seedRoll": {"mood": "serene"}}
        dim.update(over)
        cfg = {"namespace": "adventure", "dimensions": [dim], "portals": [], "worlds": []}
        return build_profile(dim, cfg)

    def test_density_none_empties_the_battery(self):
        p = self._profile(structureDensity="none")
        self.assertEqual(p["battery"], [])

    def test_allow_with_empty_list_empties_the_battery(self):
        p = self._profile(structures={"mode": "allow", "list": []})
        self.assertEqual(p["battery"], [])

    def test_mode_none_empties_the_battery(self):
        p = self._profile(structures={"mode": "none"})
        self.assertEqual(p["battery"], [])

    def test_weight_is_redistributed_not_dropped(self):
        p = self._profile(structureDensity="none")
        w = p["weights"]
        self.assertEqual(w["structures"], 0)
        # Leaving the hole would silently lower the dimension's ceiling by
        # whatever structures was worth.
        self.assertAlmostEqual(sum(w.values()), 100.0, places=6)

    def test_forced_placements_do_not_keep_it_scored(self):
        """Forced structures sit at fixed coordinates, so they are identical
        for every seed and carry no information for ranking candidates."""
        p = self._profile(structureDensity="none",
                          structures={"force": [{"structure": "minecraft:village_plains",
                                                 "x": 10, "z": 10}]})
        self.assertEqual(p["battery"], [])
        self.assertEqual(p["weights"]["structures"], 0)
        self.assertEqual(len(p["forced_structures"]), 1)

    def test_a_normal_dimension_is_untouched(self):
        p = self._profile(structures={"mode": "allow", "list": ["minecraft:villages"]})
        self.assertTrue(p["battery"])
        self.assertGreater(p["weights"]["structures"], 0)



class JsoncCommentTests(unittest.TestCase):
    """The readers-first JSONC contract: whole-line // comments only, shared
    verbatim with DimensionConfigLoader.stripJsonComments (Java) and
    check-dimension-drift.py."""

    def test_whole_line_only(self):
        self.assertEqual('{"url": "https://x/y"}',
                         dp.strip_json_comments('{"url": "https://x/y"}'))
        kept = '{"a": 1 // NOT stripped: whole-line only\n}'
        self.assertEqual(kept, dp.strip_json_comments(kept))
        self.assertEqual('\n{"a": 1}', dp.strip_json_comments('// x\n{"a": 1}'))

    def test_commented_dimension_rolls_identically(self):
        plain = {"type": "multi_biome", "seed": 42,
                 "biomes": ["minecraft:plains", "minecraft:forest"],
                 "borders": {"player": 1024}}
        commented = ('{\n'
                     '  // worldgen block, creation-time-only\n'
                     '  "type": "multi_biome",\n'
                     '  "seed": 42,\n'
                     '    // the biome list re-deals the whole layout\n'
                     '  "biomes": ["minecraft:plains", "minecraft:forest"],\n'
                     '  "borders": {"player": 1024}\n'
                     '}\n')
        with tempfile.TemporaryDirectory() as tmp:
            dims = Path(tmp) / "dimensions"
            dims.mkdir()
            (dims / "plain.json").write_text(json.dumps(plain))
            (dims / "commented.json").write_text(commented)
            configs = dp.load_dimension_configs(tmp, set_noise_defaults=False)
        self.assertEqual(configs["plain"], configs["commented"])
        a = dict(configs["plain"], name="x")
        b = dict(configs["commented"], name="x")
        self.assertEqual(dp.generation_fingerprint(a), dp.generation_fingerprint(b),
                         "commented copy must roll in the same seed group")

if __name__ == "__main__":
    unittest.main()


class TestNoisePlacementFingerprint(unittest.TestCase):
    """Spike task F3 — the fingerprint corollary for noise placement.

    Noise placement is generation-affecting and makes two previously
    scoring-only fields into worldgen inputs: `borders.player` (it sets both
    the scanned radius and the frequency scale) and
    `difficulty.mobMultiplier` (it drives the peaceful/hostile shifts). The
    key must therefore be present for dims that get noise and ABSENT for
    those that don't, or the wrong half of the candidate stores goes DRIFTED.
    """

    @classmethod
    def setUpClass(cls):
        import os
        repo = os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))))
        cls.config_dir = os.path.join(repo, "config", "custom-dimensions")
        dp.set_noise_defaults_dir(cls.config_dir)
        if dp._NOISE_DEFAULTS is None:
            raise unittest.SkipTest("structure-type-defaults.json not found")

    def test_noise_dims_carry_the_key(self):
        payload = dp.generation_payload({"type": "multi_biome",
                                         "borders": {"player": 1024}})
        self.assertIn("noisePlacement", payload)
        self.assertEqual(payload["noisePlacement"]["radiusChunks"], 64)
        self.assertEqual(len(payload["noisePlacement"]["groups"]), 7)

    def test_suppressed_dims_stay_byte_stable(self):
        """The whole point of making the key conditional."""
        for dim in ({"type": "multi_biome", "structureDensity": "none"},
                    {"type": "void", "biomes": ["minecraft:the_end"]},
                    {"type": "superflat"},
                    {"type": "multi_biome", "structures": {"noise": False}},
                    {"type": "multi_biome", "structures": {"mode": "none"}}):
            payload = dp.generation_payload(dim)
            self.assertNotIn("noisePlacement", payload, dim)

    def test_base_worlds_are_unaffected(self):
        self.assertIsNone(dp.generation_payload({"seed": 1}))

    def test_player_border_is_now_generation_affecting(self):
        """It was scoring-only before noise (bar exit-shrine dims)."""
        a = dp.generation_fingerprint({"type": "multi_biome",
                                       "borders": {"player": 1024}})
        b = dp.generation_fingerprint({"type": "multi_biome",
                                       "borders": {"player": 2048}})
        self.assertNotEqual(a, b)

    def test_mob_multiplier_is_now_generation_affecting(self):
        """Peaceful suppresses whole groups; hostile changes radial curves."""
        normal = dp.generation_fingerprint({"type": "multi_biome"})
        peaceful = dp.generation_fingerprint(
            {"type": "multi_biome", "difficulty": {"mobMultiplier": 0.0}})
        hostile = dp.generation_fingerprint(
            {"type": "multi_biome", "difficulty": {"mobMultiplier": 2.5}})
        self.assertNotEqual(normal, peaceful)
        self.assertNotEqual(normal, hostile)
        self.assertNotEqual(peaceful, hostile)

    def test_per_group_profile_changes_the_fingerprint(self):
        a = dp.generation_fingerprint({"type": "multi_biome"})
        b = dp.generation_fingerprint({
            "type": "multi_biome",
            "structures": {"noise": {"dungeons": "cluster"}}})
        self.assertNotEqual(a, b)

    def test_radial_curve_changes_the_fingerprint(self):
        a = dp.generation_fingerprint({"type": "multi_biome"})
        b = dp.generation_fingerprint({
            "type": "multi_biome",
            "structures": {"radial": {"dungeons": [1.0] * 10}}})
        self.assertNotEqual(a, b)

    def test_pool_filters_change_the_fingerprint(self):
        """Same positions, different structures on them — not clones."""
        base = dp.generation_fingerprint({"type": "multi_biome"})
        for block in ({"rarity": {"minecraft:trial_chambers": "common"}},
                      {"exclude": ["minecraft:villages"]},
                      {"include": ["mes:phantom_citadel"]}):
            self.assertNotEqual(
                base,
                dp.generation_fingerprint({"type": "multi_biome",
                                           "structures": block}), block)

    def test_exclusive_force_changes_the_fingerprint(self):
        """An exclusive force removes a structure from the noise pool."""
        excl = dp.generation_fingerprint({
            "type": "multi_biome",
            "structures": {"force": [{"structure": "a:b", "x": 0, "z": 0}]}})
        shared = dp.generation_fingerprint({
            "type": "multi_biome",
            "structures": {"force": [{"structure": "a:b", "x": 0, "z": 0,
                                      "exclusive": False}]}})
        self.assertNotEqual(excl, shared)

    def test_identical_noise_config_shares_a_fingerprint(self):
        """Seed-group rolling still works — this is what it is for."""
        a = dp.generation_fingerprint({"type": "multi_biome",
                                       "borders": {"player": 1024},
                                       "biomes": ["minecraft:plains"]})
        b = dp.generation_fingerprint({"type": "multi_biome",
                                       "borders": {"player": 1024},
                                       "biomes": ["minecraft:plains"],
                                       "description": "different text",
                                       "portal": {"scale": 8.0}})
        self.assertEqual(a, b)


class UnsatisfiableWantSweepTests(unittest.TestCase):
    """A want the placement rules forbid outright is a CONSTANT deduction.

    PlacesNothingTests above covers the loud case — a dimension that can
    place nothing at all. This is the quiet one it cannot see: a dimension
    that places plenty, asking for a structure in a ring its group cannot
    reach. Every candidate loses the same points for it, so it contributes
    nothing to the ranking while capping the ceiling.

    The maths under test is exact, not a heuristic. Since 2026-07-29 the radial
    curve scales the exclusion radius rather than gating eligibility, so the
    only curve-driven way to make a band unreachable is a weight of exactly 0.0
    across the whole of it — deliberate, and reported as
    CURVE-SUPPRESSED-BAND. A band the curve merely thins is satisfiable and
    reported as THIN-BAND, which is a question rather than a fault.

    Before that change the bar was `radialWeight > profile.threshold`, and the
    shipped tapers crossed it on their way down: 19 wants across 12 dimensions
    were impossible by accident. Those cases now pass, which is the point.
    """

    #: The shipped shapes, so a curve edit that breaks an assumption here
    #: fails loudly rather than silently changing what is reachable. Relative
    #: DENSITY per radial decile, spawn -> border.
    OUTER = [0.3, 0.35, 0.45, 0.55, 0.65, 0.8, 0.95, 1.1, 1.3, 1.55]
    INNER = [2.8, 2.3, 1.9, 1.6, 1.35, 1.15, 0.95, 0.8, 0.65, 0.55]
    #: A deliberate hard edge, the one thing a 0.0 still means.
    OUTER_HALF_ONLY = [0.0] * 5 + [1.0] * 5

    TYPE_DEFAULTS = {
        "curves": {"outer": OUTER, "inner": INNER, "even": [1.0] * 10},
        "groupDefaults": {
            "dungeons": {"profile": "sparse", "radial": "outer", "exclusion": 8},
            "settlements": {"profile": "natural", "radial": "inner", "exclusion": 6},
        },
        "difficultyShifts": {
            "peaceful": {"maxMobMultiplier": 0.5, "profiles": {}},
            "hostile": {"minMobMultiplier": 2.0, "radial": {}},
        },
        "types": {"multi_biome": {"groups": ["dungeons", "settlements"],
                                  "profiles": {}, "radial": {}}},
    }

    @classmethod
    def setUpClass(cls):
        import sweep_structure_wants
        cls.sweep = sweep_structure_wants

    def _dim(self, radius, wants, **over):
        dim = {"name": "t", "type": "multi_biome", "dimensionId": "adventure:t",
               "biomes": ["minecraft:plains"],
               "borders": {"player": radius, "generation": radius},
               "structures": {"wants": wants},
               "seedRoll": {"mood": "standard"}}
        dim.update(over)
        return dim

    def _run(self, dim, struct_to_set, set_to_group, struct_sets=None):
        cfg = {"namespace": "adventure", "dimensions": [dim],
               "portals": [], "worlds": []}
        profile = build_profile(dim, cfg)
        return self.sweep.sweep_dimension(
            "t", dim, profile, struct_sets or {}, {}, struct_to_set,
            set_to_group, self.TYPE_DEFAULTS)

    # --- the exact reachability maths -----------------------------------
    def test_max_curve_weight_is_the_peak_not_the_mean(self):
        # The band spans the whole curve, so the peak is the curve's peak.
        self.assertAlmostEqual(
            self.sweep.max_curve_weight(self.OUTER, 0, 1000, 1000), max(self.OUTER))

    def test_max_curve_weight_over_a_thinned_inner_band(self):
        # radial_weight puts control point i at fraction i/9, so the inner
        # 30% of an `outer` curve tops out between points 2 and 3.
        peak = self.sweep.max_curve_weight(self.OUTER, 0, 300, 1000)
        self.assertLess(peak, 0.6)
        # Positive, so the band is thin rather than dead — the distinction the
        # 2026-07-29 placement change created.
        self.assertGreater(peak, 0.0)

    def test_mean_curve_weight_is_the_bands_relative_density(self):
        # `even` is 1.0 everywhere, so any band averages exactly its own density.
        self.assertAlmostEqual(
            self.sweep.mean_curve_weight([1.0] * 10, 0, 1000, 1000), 1.0)
        # `inner` is front-loaded, so the inner third beats the outer third.
        near = self.sweep.mean_curve_weight(self.INNER, 0, 333, 1000)
        far = self.sweep.mean_curve_weight(self.INNER, 667, 1000, 1000)
        self.assertGreater(near, 2.0 * far)

    def test_max_curve_weight_with_no_curve_is_flat_one(self):
        self.assertEqual(self.sweep.max_curve_weight(None, 0, 300, 1000), 1.0)

    # --- what the sweep reports -----------------------------------------
    def test_near_spawn_dungeon_want_is_thin_not_impossible(self):
        """`outer` opens at 0.3x, so a near-spawn dungeon want is rare rather
        than forbidden. This case used to be CURVE-BELOW-THRESHOLD."""
        found = self._run(
            self._dim(8192, {"trial_chambers": "near_spawn"}),
            {"minecraft:trial_chambers": "minecraft:trial_chambers"},
            {"minecraft:trial_chambers": "dungeons"})
        self.assertEqual([f["code"] for f in found], ["THIN-BAND"])

    def test_near_border_dungeon_want_is_not_flagged(self):
        found = self._run(
            self._dim(8192, {"trial_chambers": "near_border"}),
            {"minecraft:trial_chambers": "minecraft:trial_chambers"},
            {"minecraft:trial_chambers": "dungeons"})
        self.assertEqual(found, [])

    def test_near_border_settlement_want_is_no_longer_flagged(self):
        """The headline fix. `inner` used to end in three hard zeros, so this
        want was structurally impossible; it now tapers to 0.55x and a village
        at the border is simply less common than one at spawn."""
        found = self._run(
            self._dim(1024, {"village": "near_border"}),
            {"minecraft:village_plains": "minecraft:villages"},
            {"minecraft:villages": "settlements"})
        self.assertEqual(found, [])

    def test_a_zero_band_is_still_reported(self):
        """A curve an author has deliberately zeroed still makes a want
        unsatisfiable, and the sweep must still say so."""
        dim = self._dim(1024, {"village": "near_spawn"})
        dim["structures"]["radial"] = {"settlements": self.OUTER_HALF_ONLY}
        found = self._run(dim,
                          {"minecraft:village_plains": "minecraft:villages"},
                          {"minecraft:villages": "settlements"})
        self.assertEqual([f["code"] for f in found], ["CURVE-SUPPRESSED-BAND"])

    def test_band_starting_beyond_the_border_is_flagged(self):
        found = self._run(
            self._dim(256, {"village": {"min": 256, "max": 2048}}),
            {"minecraft:village_plains": "minecraft:villages"},
            {"minecraft:villages": "settlements"})
        self.assertEqual([f["code"] for f in found], ["BAND-OUTSIDE-BORDER"])

    def test_a_suppressed_group_is_flagged(self):
        found = self._run(
            self._dim(1024, {"trial_chambers": "near_border"},
                      structureDensity="normal"),
            {"minecraft:trial_chambers": "minecraft:trial_chambers"},
            {"minecraft:trial_chambers": "endgame"})  # not in the type's groups
        self.assertEqual([f["code"] for f in found], ["GROUP-SUPPRESSED"])

    def test_a_want_with_no_set_and_no_group_is_flagged(self):
        found = self._run(self._dim(1024, {"trial_chambers": "near_border"}),
                          {}, {})
        self.assertEqual([f["code"] for f in found], ["SET-NOT-EXTRACTED"])

    def test_a_forced_want_is_never_flagged(self):
        """structures.force is the strongest statement of intent in the
        schema; the author put it exactly where they wanted it."""
        dim = self._dim(
            256, {"village": {"min": 256, "max": 2048}},
            structures={"wants": {"village": {"min": 256, "max": 2048}},
                        "force": [{"structure": "#minecraft:village",
                                   "x": 100, "z": 100}]})
        found = self._run(dim, {"minecraft:village_plains": "minecraft:villages"},
                          {"minecraft:villages": "settlements"})
        # The band check runs first and is about the CONFIG, not the seed, so
        # it still fires; nothing group-related does.
        self.assertNotIn("CURVE-SUPPRESSED-BAND", [f["code"] for f in found])
        self.assertNotIn("THIN-BAND", [f["code"] for f in found])

    def test_shuns_are_not_swept(self):
        """A shun that can never be violated is a free point, not a bug."""
        dim = self._dim(8192, {})
        dim["structures"] = {"wants": {}, "shuns": {"trial_chambers": {}}}
        found = self._run(dim,
                          {"minecraft:trial_chambers": "minecraft:trial_chambers"},
                          {"minecraft:trial_chambers": "dungeons"})
        self.assertEqual(found, [])
