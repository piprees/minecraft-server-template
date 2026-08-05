#!/usr/bin/env python3
"""Spike task F2 — census distribution scoring.

Covers census_scoring.py's maths and its integration into
score-dimensions.py's score_candidate(), including the load-bearing
regression: a dimension with NO noise groups must score exactly as it did
before noise placement existed.
"""
import importlib.util
import json
import math
import tempfile
import unittest
from pathlib import Path

import census_scoring
import noise_placement
from dimension_profiles import (
    build_profile, noise_fingerprint, set_noise_defaults_dir,
)

SCORE_PATH = Path(__file__).with_name("score-dimensions.py")
SPEC = importlib.util.spec_from_file_location("score_dimensions", SCORE_PATH)
score_dimensions = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(score_dimensions)

CONFIG_DIR = Path(__file__).resolve().parents[2] / "config" / "custom-dimensions"

# The shipped curves — relative density per radial decile, spawn -> border.
INNER = [2.8, 2.3, 1.9, 1.6, 1.35, 1.15, 0.95, 0.8, 0.65, 0.55]
OUTER = [0.3, 0.35, 0.45, 0.55, 0.65, 0.8, 0.95, 1.1, 1.3, 1.55]
EVEN = [1.0] * 10


def hist_matching(curve, scale=20.0):
    """A histogram whose per-annulus DENSITY follows `curve` exactly.

    Equal-width radial bins have areas proportional to 2i+1, so a layout that
    realises a curve puts count_i = curve_i * (2i+1) * k structures in bin i.
    """
    profile = census_scoring.desired_profile(curve, len(curve))
    areas = census_scoring.annulus_areas(len(curve))
    return [int(round(profile[i] * areas[i] * scale)) for i in range(len(curve))]


def positions_for_hist(hist, radius_chunks, origin=(0, 0)):
    """Chunk positions whose radial_hist around origin equals `hist` exactly.

    Places each bin's count at the bin's midpoint radius along +x; the
    midpoint stays inside its bin after rounding because the bin width
    (radius/bins chunks) exceeds one chunk for every radius the tests use.
    """
    bins = len(hist)
    positions = []
    for b, n in enumerate(hist):
        dist = int(round((b + 0.5) * radius_chunks / bins))
        positions.extend([(origin[0] + dist, origin[1])] * n)
    return positions


class DistributionMatchTests(unittest.TestCase):
    def test_layout_following_the_curve_scores_one(self):
        for curve in (INNER, OUTER, EVEN):
            with self.subTest(curve=curve):
                match = census_scoring.distribution_match(hist_matching(curve), curve)
                self.assertGreater(match, 0.99, f"{curve} -> {match}")

    def test_border_heavy_layout_against_an_inner_curve_is_poor(self):
        # Everything in the outer three bins, but the curve wants the middle.
        hist = [0, 0, 0, 0, 0, 0, 0, 40, 60, 80]
        self.assertLess(census_scoring.distribution_match(hist, INNER), 0.4)

    def test_inner_layout_against_an_outer_curve_is_poor(self):
        hist = [30, 25, 20, 0, 0, 0, 0, 0, 0, 0]
        self.assertLess(census_scoring.distribution_match(hist, OUTER), 0.4)

    def test_empty_histogram_scores_zero(self):
        self.assertEqual(census_scoring.distribution_match([0] * 10, INNER), 0.0)
        self.assertEqual(census_scoring.distribution_match([], INNER), 0.0)

    def test_raw_counts_are_area_normalised(self):
        # A UNIFORM layout puts more structures in the bigger outer bins.
        # Compared to a flat curve that must read as a perfect match, not as
        # a border bias — this is the bug area normalisation exists to stop.
        uniform = census_scoring.annulus_areas(10)
        hist = [int(a * 10) for a in uniform]
        self.assertGreater(census_scoring.distribution_match(hist, EVEN), 0.999)

    def test_missing_curve_is_flat(self):
        self.assertEqual(census_scoring.desired_profile(None, 10), [1.0] * 10)
        self.assertEqual(census_scoring.desired_profile([], 4), [1.0] * 4)

    def test_desired_profile_samples_bin_centres(self):
        # 10 bins over a 10-point curve: bin 0's centre is 5% of the radius,
        # which sits between curve points 0 and 1.
        got = census_scoring.desired_profile(INNER, 10)
        # Derived from the curve rather than written out, so retuning the
        # shipped numbers cannot break a test about interpolation.
        self.assertAlmostEqual(got[0], INNER[0] + (INNER[1] - INNER[0]) * (0.05 * 9))
        self.assertEqual(len(got), 10)


class CountSatisfactionTests(unittest.TestCase):
    def test_floor_scales_with_radius_and_has_a_minimum(self):
        self.assertEqual(census_scoring.count_floor(64), 4)
        self.assertEqual(census_scoring.count_floor(512), 32)
        self.assertEqual(census_scoring.count_floor(16), census_scoring.COUNT_FLOOR_MIN)

    def test_clearing_the_floor_is_full_credit_and_caps(self):
        self.assertEqual(census_scoring.count_satisfaction(50, 64), 1.0)
        self.assertEqual(census_scoring.count_satisfaction(4, 64), 1.0)

    def test_below_the_floor_is_partial(self):
        self.assertAlmostEqual(census_scoring.count_satisfaction(1, 64), 0.25)
        self.assertEqual(census_scoring.count_satisfaction(0, 64), 0.0)

    def test_count_term_is_capped_at_its_weight(self):
        # The spike's "count bonus capped at 0.3": an over-full group cannot
        # buy more than COUNT_WEIGHT of the group score.
        positions = positions_for_hist(hist_matching(EVEN), 64)
        rich = {"count": 5000, "hist": hist_matching(EVEN), "radial": EVEN}
        exact = {"count": census_scoring.count_floor(64),
                 "hist": hist_matching(EVEN), "radial": EVEN}
        self.assertAlmostEqual(
            census_scoring.group_score(rich, 64, positions=positions,
                                       origin=(0, 0)),
            census_scoring.group_score(exact, 64, positions=positions,
                                       origin=(0, 0)), places=6)

    def test_populated_group_without_positions_is_a_loud_error(self):
        # The stored hist is display-only; scoring a populated group without
        # the exact sidecar positions must fail rather than fall back.
        entry = {"count": 5, "hist": hist_matching(EVEN), "radial": EVEN}
        with self.assertRaises(ValueError):
            census_scoring.group_score(entry, 64)

    def test_empty_group_is_a_mild_penalty_not_zero(self):
        entry = {"count": 0, "hist": [0] * 10, "radial": INNER}
        self.assertAlmostEqual(census_scoring.group_score(entry, 64),
                               census_scoring.EMPTY_GROUP_SCORE)


class ExactWantShunTests(unittest.TestCase):
    """Exact scoring from positions (precision plan §3.4)."""

    def test_want_in_band_scores_full_at_the_target(self):
        # Three positions of village at chunks (1,0), (2,0), (3,0) — all
        # within 500 blocks of spawn at origin.
        positions = [(1, 0, "minecraft:village_plains"),
                     (2, 0, "minecraft:village_plains"),
                     (3, 0, "minecraft:village_plains")]
        got = census_scoring.census_want_score(
            "minecraft:village_plains", (0, 500), positions,
            spawn_cx=0, spawn_cz=0, in_pool=True)
        self.assertEqual(got, 1.0)

    def test_want_present_but_wrong_ring_earns_partial(self):
        # Position at chunk (50,0) = 800 blocks; band is 0-300.
        positions = [(50, 0, "minecraft:village_plains")]
        got = census_scoring.census_want_score(
            "minecraft:village_plains", (0, 300), positions,
            spawn_cx=0, spawn_cz=0, in_pool=True)
        self.assertEqual(got, census_scoring.WANT_WRONG_RING_SCORE)

    def test_want_not_in_pool_scores_zero(self):
        got = census_scoring.census_want_score(
            "minecraft:igloo", (0, 500), [],
            spawn_cx=0, spawn_cz=0, in_pool=False)
        self.assertEqual(got, 0.0)

    def test_want_missing_from_positions_scores_wrong_ring(self):
        positions = [(1, 0, "minecraft:village_plains")]
        got = census_scoring.census_want_score(
            "minecraft:igloo", (0, 500), positions,
            spawn_cx=0, spawn_cz=0, in_pool=True)
        self.assertEqual(got, census_scoring.WANT_WRONG_RING_SCORE)

    def test_want_empty_positions_not_in_pool(self):
        self.assertEqual(
            census_scoring.census_want_score(
                "anything", (0, 500), [],
                spawn_cx=0, spawn_cz=0, in_pool=False), 0.0)

    def test_shun_present_inside_threshold_costs_the_point(self):
        # Chunk (3, 0) = 48 blocks from origin; threshold 200.
        positions = [(3, 0, "minecraft:ancient_city")]
        got = census_scoring.census_shun_score(
            "minecraft:ancient_city", 200, positions,
            spawn_cx=0, spawn_cz=0, in_pool=True)
        self.assertEqual(got, 0.0)

    def test_shun_absent_earns_the_point(self):
        self.assertEqual(
            census_scoring.census_shun_score(
                "minecraft:ancient_city", 200, [],
                spawn_cx=0, spawn_cz=0, in_pool=True), 1.0)

    def test_shun_beyond_threshold_earns_the_point(self):
        # Chunk (50, 0) = 800 blocks; threshold 200.
        positions = [(50, 0, "minecraft:ancient_city")]
        got = census_scoring.census_shun_score(
            "minecraft:ancient_city", 200, positions,
            spawn_cx=0, spawn_cz=0, in_pool=True)
        self.assertEqual(got, 1.0)

    def test_shun_not_in_positions_earns_the_point(self):
        positions = [(1, 0, "minecraft:village_plains")]
        got = census_scoring.census_shun_score(
            "minecraft:igloo", 200, positions,
            spawn_cx=0, spawn_cz=0, in_pool=True)
        self.assertEqual(got, 1.0)

    def test_band_mass_display_only_still_works(self):
        """band_mass exists for the viewer histogram. It must not feed scoring
        but it must not crash either."""
        entry = {"count": 10, "hist": [10] + [0] * 9, "radial": EVEN}
        self.assertAlmostEqual(
            census_scoring.band_mass(entry, 0.0, 51.2, 64), 5.0)

    def test_pools_load_from_the_warmup_artefact(self):
        from structure_placement import NOISE_MANAGED_PLACEMENT_TYPES
        POOLS = {"the_test": {"settlements": {"minecraft:village_plains": 8,
                                              "explorify:farmstead": 2}}}
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(census_scoring.load_structure_pools(tmp), {})
            (Path(tmp) / "structure_pools.json").write_text(json.dumps(
                {"stackVersion": "dev",
                 "placementTypes": sorted(NOISE_MANAGED_PLACEMENT_TYPES),
                 "dimensions": POOLS}))
            loaded = census_scoring.load_structure_pools(tmp)
            self.assertEqual(loaded["the_test"]["settlements"]
                             ["minecraft:village_plains"], 8)

    def test_unreadable_pool_data_is_not_fatal(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "structure_pools.json").write_text("{ not json")
            self.assertEqual(census_scoring.load_structure_pools(tmp), {})


class BlendTests(unittest.TestCase):
    def test_either_side_alone_passes_through(self):
        self.assertEqual(census_scoring.blend(None, 0.4), 0.4)
        self.assertEqual(census_scoring.blend(0.9, None), 0.9)
        self.assertIsNone(census_scoring.blend(None, None))

    def test_census_outweighs_the_battery(self):
        self.assertAlmostEqual(census_scoring.blend(1.0, 0.0),
                               census_scoring.CENSUS_WEIGHT)
        self.assertAlmostEqual(
            census_scoring.blend(1.0, 1.0), 1.0, places=9)


class ScoreCandidateIntegrationTests(unittest.TestCase):
    """score_candidate() with and without a census attached."""

    def profile(self, **over):
        dim = {"name": "t", "type": "multi_biome",
               "dimensionId": "adventure:t",
               "biomes": ["minecraft:plains", "minecraft:forest"],
               "borders": {"player": 1024},
               "seedRoll": {"spawnFilter": ["minecraft:plains"],
                            "wants": {"village": "near_spawn"},
                            "shuns": ["ancient_city"]}}
        dim.update(over)
        return build_profile(dim, {"namespace": "adventure", "portals": []})

    def base_rows(self):
        rows = {"spawn_biome": "minecraft:plains", "errors": "0",
                "biome_minecraft:plains_dist": "0",
                "biome_minecraft:forest_dist": "200",
                "structure_village_dist": "150",
                "structure_ancient_city_dist": "-1"}
        for r in range(3):
            for c in range(3):
                rows[f"height_r{r}c{c}"] = str(70 + r * 8 + c)
                rows[f"water_r{r}c{c}"] = "0"
        return rows

    def census(self, settlements_hist, dungeons_hist=None):
        groups = {"settlements": {"count": sum(settlements_hist),
                                  "hist": settlements_hist, "radial": INNER,
                                  "profile": "natural", "exclusion": 6}}
        if dungeons_hist is not None:
            groups["dungeons"] = {"count": sum(dungeons_hist),
                                  "hist": dungeons_hist, "radial": OUTER,
                                  "profile": "sparse", "exclusion": 9}
        return {"radiusChunks": 64, "groups": groups}

    def _attach_positions(self, rows):
        """Exact sidecar positions matching each group's summary hist —
        scoring bins positions at score time, never the stored hist."""
        census = rows.get("_census")
        if not census:
            return rows
        radius = census.get("radiusChunks") or 0
        rows["_census_positions"] = {
            g: positions_for_hist(e.get("hist") or [], radius)
            for g, e in (census.get("groups") or {}).items()}
        return rows

    def test_scores_stay_inside_zero_to_one_hundred(self):
        profile = self.profile()
        for census in (None, self.census(hist_matching(INNER, 2.0)),
                       self.census([0] * 10)):
            rows = dict(self.base_rows())
            if census:
                rows["_census"] = census
                self._attach_positions(rows)
            total, parts = score_dimensions.score_candidate(profile, rows)
            self.assertGreaterEqual(total, 0.0)
            self.assertLessEqual(total, 100.0)
            for key, value in parts.items():
                self.assertGreaterEqual(value, -0.001, key)

    def test_a_good_layout_beats_an_inverted_one(self):
        profile = self.profile()
        good = dict(self.base_rows())
        good["_census"] = self.census(hist_matching(INNER, 2.0))
        self._attach_positions(good)
        bad = dict(self.base_rows())
        bad["_census"] = self.census([0] * 7 + [20, 30, 40])
        self._attach_positions(bad)
        good_total, _ = score_dimensions.score_candidate(profile, good)
        bad_total, _ = score_dimensions.score_candidate(profile, bad)
        self.assertGreater(good_total, bad_total)

    def test_no_census_keeps_the_pre_noise_grid_scoring_exactly(self):
        """The regression that matters: suppressed dims must not move."""
        profile = self.profile()
        rows = self.base_rows()
        total, parts = score_dimensions.score_candidate(profile, dict(rows))
        # Reproduce the old structures maths by hand.
        expected = (score_dimensions.want_score(150.0, 0.0, 0.30 * 1024, 1024,
                                                profile["locate_cap"])
                    + score_dimensions.shun_score(-1.0, 1024, 1024)) / 2
        expected = max(0.0, expected - 1 * 0.003)  # one found structure
        self.assertAlmostEqual(parts["structures"], round(expected, 3), places=2)
        self.assertGreater(total, 0.0)

    def test_rejected_candidates_short_circuit_regardless_of_census(self):
        profile = self.profile()
        rows = {"rejected": "1", "_census": self.census(hist_matching(INNER))}
        total, parts = score_dimensions.score_candidate(profile, rows)
        self.assertEqual(total, 0.0)
        self.assertEqual(parts["structures"], 0.0)


class ShippedConfigTests(unittest.TestCase):
    """The maths against real dimension configs, not synthetic ones."""

    @classmethod
    def setUpClass(cls):
        cls.type_defaults = noise_placement.load_type_defaults(CONFIG_DIR)
        if not cls.type_defaults:
            raise unittest.SkipTest("structure-type-defaults.json not present")
        # noise_fingerprint() resolves groups through module state that the
        # config loaders normally set; without this it silently answers None
        # for every dimension and the fingerprint assertions pass vacuously.
        set_noise_defaults_dir(CONFIG_DIR)

    def load(self, slug):
        return json.loads((CONFIG_DIR / "dimensions" / f"{slug}.json").read_text())

    def test_a_real_pocket_dimension_summarises_and_scores(self):
        cfg = self.load("the_overgrowth")
        summary = noise_placement.census_summary(
            12345, "the_overgrowth", cfg, self.type_defaults)
        self.assertGreater(len(summary["groups"]), 0)
        for group, entry in summary["groups"].items():
            self.assertEqual(sum(entry["hist"]), entry["count"], group)
        # The banked summary is slim; the scorer re-attaches the curves.
        self.assertNotIn("radial", next(iter(summary["groups"].values())))
        enriched = score_dimensions._with_group_settings(
            summary, noise_placement.resolve_groups(cfg, self.type_defaults))
        settlements = enriched["groups"].get("settlements")
        self.assertIsNotNone(settlements)
        self.assertIsInstance(settlements["radial"], list)
        # Scoring bins exact positions at score time; reconstruct positions
        # with each group's own summary shape (the summary hist here is
        # freshly computed from the same NoiseFieldIndex positions).
        radius = enriched.get("radiusChunks") or 0
        positions = {g: positions_for_hist(e.get("hist") or [], radius)
                     for g, e in enriched["groups"].items()}
        part = census_scoring.distribution_component(
            enriched, positions_by_group=positions, origin=(0, 0))
        self.assertIsNotNone(part)
        self.assertGreater(part, 0.0)
        self.assertLessEqual(part, 1.0)

    def test_a_suppressed_dimension_has_no_census_component(self):
        cfg = self.load("the_dustbowl")  # structureDensity: none + force
        summary = noise_placement.census_summary(
            12345, "the_dustbowl", cfg, self.type_defaults)
        self.assertEqual(summary["groups"], {})
        self.assertIsNone(census_scoring.distribution_component(summary))
        self.assertIsNone(noise_fingerprint(cfg))

    def test_census_summary_matches_the_full_census(self):
        cfg = self.load("the_luminous_caverns")
        full = noise_placement.noise_census(
            777, "the_luminous_caverns", cfg, self.type_defaults)
        summary = noise_placement.census_summary(
            777, "the_luminous_caverns", cfg, self.type_defaults)
        self.assertEqual(set(full), set(summary["groups"]))
        radius = summary["radiusChunks"]
        for group, positions in full.items():
            entry = summary["groups"][group]
            self.assertEqual(entry["count"], len(positions), group)
            hist = [0] * 10
            for cx, cz in positions:
                b = min(9, int(math.hypot(cx, cz) / radius * 10))
                hist[b] += 1
            self.assertEqual(entry["hist"], hist, group)

    def test_noise_fingerprint_tracks_placement_inputs_only(self):
        cfg = self.load("the_overgrowth")
        base = noise_fingerprint(cfg)
        self.assertIsNotNone(base)
        # A biome edit re-keys the GENERATION fingerprint but moves no
        # position, so the census cache must survive it.
        biome_edit = json.loads(json.dumps(cfg))
        biome_edit["biomes"] = list(biome_edit.get("biomes") or []) + ["minecraft:plains"]
        self.assertEqual(noise_fingerprint(biome_edit), base)
        # The player border sets the radius AND the frequency scale.
        border_edit = json.loads(json.dumps(cfg))
        border_edit.setdefault("borders", {})["player"] = 2048
        self.assertNotEqual(noise_fingerprint(border_edit), base)


class StructureGroupLookupTests(unittest.TestCase):
    """Battery entry -> owning noise group."""

    SET_JSON = {
        "placement": {"type": "minecraft:random_spread", "spacing": 40,
                      "separation": 12, "salt": 1},
        "structures": [{"structure": "dungeons_arise:coliseum", "weight": 1}],
    }

    def build(self, tmp, nested_first):
        seedtest = Path(tmp) / "seedtest"
        real = (seedtest / ".structure_sets" / "data" / "dungeons_arise"
                / "worldgen" / "structure_set")
        nested = (seedtest / ".structure_sets" / "data" / "structures" / "data"
                  / "dungeons_arise" / "worldgen" / "structure_set")
        for d in (real, nested):
            d.mkdir(parents=True, exist_ok=True)
            (d / "major_structures.json").write_text(json.dumps(self.SET_JSON))
        config_dir = Path(tmp) / "config"
        (config_dir / "dimensions").mkdir(parents=True, exist_ok=True)
        (config_dir / "structure-groups.json").write_text(json.dumps({
            "sets": {"dungeons_arise:major_structures":
                     {"group": "endgame", "rarity": "endgame"}}}))
        if nested_first:
            # Make the bogus copy the one a naive first-wins map would take.
            (real / "major_structures.json").touch()
        score_dimensions._STRUCT_LOOKUP_CACHE.clear()
        noise_placement._TYPE_DEFAULTS.clear()
        return score_dimensions.structure_group_lookup(seedtest, config_dir)

    def test_nested_datapack_copy_never_shadows_the_real_set_id(self):
        for nested_first in (False, True):
            with self.subTest(nested_first=nested_first), \
                    tempfile.TemporaryDirectory() as tmp:
                lookup = self.build(tmp, nested_first)
                self.assertEqual(
                    score_dimensions.battery_group_for(
                        "dungeons_arise:coliseum", lookup), "endgame")

    def test_unclassified_structure_falls_back_to_grid(self):
        with tempfile.TemporaryDirectory() as tmp:
            lookup = self.build(tmp, False)
            self.assertIsNone(score_dimensions.battery_group_for(
                "minecraft:not_a_real_structure", lookup))

    def test_shipped_battery_entries_are_almost_all_classified(self):
        """A silent regression here turns census scoring back into grid
        scoring for whole mods, with no error anywhere."""
        seedtest = Path(__file__).resolve().parents[2] / ".seedtest"
        if not (seedtest / ".structure_sets").is_dir():
            self.skipTest("no warmup extraction available")
        score_dimensions._STRUCT_LOOKUP_CACHE.clear()
        lookup = score_dimensions.structure_group_lookup(seedtest, CONFIG_DIR)
        struct_to_set, set_to_group = lookup
        bogus = [s for s in struct_to_set.values() if s not in set_to_group]
        self.assertEqual(bogus, [], "unclassified set ids leaked into the map")


class CensusCacheTests(unittest.TestCase):
    def test_census_is_not_persisted_into_measurements(self):
        """`_census` is a derived view; measurements stay raw."""
        import candidates
        with tempfile.TemporaryDirectory() as tmp:
            store = candidates.empty_store()
            rows = {"errors": "0", "_census": {"groups": {}},
                    "_enriched_structure_count": "4"}
            filtered = {k: v for k, v in rows.items() if not k.startswith("_")}
            candidates.merge_rows(store, "5", filtered)
            path = Path(tmp) / "x.json"
            candidates.save_store(path, store)
            got = candidates.load_store(path)
            self.assertEqual(got["candidates"]["5"]["measurements"], {"errors": "0"})


if __name__ == "__main__":
    unittest.main()
