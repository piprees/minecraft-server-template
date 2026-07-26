#!/usr/bin/env python3
"""Tests for config/custom-dimensions/structure-type-defaults.json (task A2).

The file is hand-authored design intent, so these tests are a schema +
coverage gate: every world type the roller knows about must have an entry,
every name must resolve, and every curve must be well-formed. A typo here is
otherwise a silent fallback at boot.
"""

import json
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
DEFAULTS = REPO / "config/custom-dimensions/structure-type-defaults.json"
GROUPS_REGISTRY = REPO / "config/custom-dimensions/structure-groups.json"

sys.path.insert(0, str(REPO / "scripts/seed"))
import dimension_profiles as dp  # noqa: E402

VALID_PROFILES = {"natural", "dense", "sparse", "cluster", "none"}

# Every type that can reach the noise path. Base worlds are excluded by
# design (see the file's own _comment) — the mod never rebuilds their
# placement calculator.
EXPECTED_TYPES = (
    dp.OVERWORLD_FAMILY
    | dp.NETHER_FAMILY
    | dp.END_FAMILY
    | {"void", "superflat", "single_biome", "paradise_lost:paradise_lost"}
)


class TestTypeDefaults(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = json.loads(DEFAULTS.read_text())
        cls.curves = {k: v for k, v in cls.doc["curves"].items()
                      if not k.startswith("_")}
        cls.group_defaults = {k: v for k, v in cls.doc["groupDefaults"].items()
                              if not k.startswith("_")}
        cls.types = {k: v for k, v in cls.doc["types"].items()
                     if not k.startswith("_")}
        cls.groups = set(json.loads(GROUPS_REGISTRY.read_text())["groups"])

    # --- curves ----------------------------------------------------------

    def test_curves_are_ten_points_in_range(self):
        for name, curve in self.curves.items():
            self.assertEqual(len(curve), 10, "curve %s" % name)
            for v in curve:
                self.assertIsInstance(v, (int, float), name)
                self.assertGreaterEqual(v, 0.0, name)
                self.assertLessEqual(v, 3.0, name)

    def test_named_curves_present(self):
        self.assertEqual(set(self.curves), {"inner", "outer", "even", "mid"})

    def test_curve_shapes_match_their_names(self):
        """A mislabelled curve is the kind of typo tests exist for."""
        inner, outer, even, mid = (self.curves[k] for k in
                                   ("inner", "outer", "even", "mid"))
        # inner front-loaded, outer back-loaded
        self.assertGreater(sum(inner[:5]), sum(inner[5:]))
        self.assertGreater(sum(outer[5:]), sum(outer[:5]))
        self.assertEqual(len(set(even)), 1)
        # mid peaks away from both ends
        self.assertEqual(max(mid), max(mid[2:7]))
        self.assertLess(mid[0], max(mid))
        self.assertLess(mid[-1], max(mid))

    # --- group defaults --------------------------------------------------

    def test_every_group_has_defaults(self):
        self.assertEqual(set(self.group_defaults), self.groups)

    def test_group_defaults_resolve(self):
        for group, cfg in self.group_defaults.items():
            self.assertIn(cfg["profile"], VALID_PROFILES, group)
            self.assertIn(cfg["radial"], self.curves, group)
            self.assertIsInstance(cfg["exclusion"], int, group)
            self.assertGreaterEqual(cfg["exclusion"], 1, group)
            self.assertLessEqual(cfg["exclusion"], 64, group)

    def test_exclusion_ordering_matches_intent(self):
        """Bigger, rarer content keeps further apart than scenery."""
        ex = {g: c["exclusion"] for g, c in self.group_defaults.items()}
        self.assertLess(ex["deco"], ex["settlements"])
        self.assertLess(ex["settlements"], ex["dungeons"])
        self.assertLess(ex["dungeons"], ex["landmarks"])
        self.assertLess(ex["landmarks"], ex["endgame"])

    # --- rarity shares ---------------------------------------------------

    def test_rarity_shares_cover_every_tier_and_decrease(self):
        shares = {k: v for k, v in self.doc["rarityShares"].items()
                  if not k.startswith("_")}
        self.assertEqual(set(shares), {"common", "uncommon", "rare", "endgame"})
        self.assertGreater(shares["common"], shares["uncommon"])
        self.assertGreater(shares["uncommon"], shares["rare"])
        self.assertGreater(shares["rare"], shares["endgame"])
        for tier, v in shares.items():
            self.assertGreater(v, 0.0, tier)

    # --- types -----------------------------------------------------------

    def test_every_world_type_has_an_entry(self):
        missing = sorted(EXPECTED_TYPES - set(self.types))
        self.assertEqual(missing, [], "world types with no defaults")

    def test_no_unknown_types(self):
        extra = sorted(set(self.types) - EXPECTED_TYPES)
        self.assertEqual(extra, [], "defaults for types the roller rejects")

    def test_type_entries_reference_valid_names(self):
        for type_name, cfg in self.types.items():
            for group in cfg["groups"]:
                self.assertIn(group, self.groups,
                              "%s enables unknown group %s" % (type_name, group))
            for group, profile in cfg["profiles"].items():
                self.assertIn(group, self.groups, type_name)
                self.assertIn(profile, VALID_PROFILES, type_name)
                self.assertIn(group, cfg["groups"],
                              "%s overrides profile for disabled group %s"
                              % (type_name, group))
            for group, curve in cfg["radial"].items():
                self.assertIn(group, self.groups, type_name)
                self.assertIn(curve, self.curves, type_name)
                self.assertIn(group, cfg["groups"],
                              "%s sets radial for disabled group %s"
                              % (type_name, group))

    def test_spike_type_table(self):
        """The type -> groups table exactly as the spike specifies it."""
        self.assertEqual(
            set(self.types["nether"]["groups"]),
            {"deco", "dungeons", "landmarks", "settlements", "endgame"})
        self.assertEqual(self.types["nether"]["profiles"]["dungeons"], "natural")
        self.assertEqual(
            set(self.types["end"]["groups"]),
            {"deco", "dungeons", "landmarks", "maritime", "endgame"})
        self.assertEqual(self.types["end"]["profiles"]["endgame"], "natural")
        self.assertEqual(
            set(self.types["cave"]["groups"]), {"deco", "dungeons", "loot"})
        self.assertEqual(self.types["cave"]["profiles"]["dungeons"], "natural")
        self.assertEqual(self.types["cave"]["profiles"]["loot"], "dense")
        self.assertEqual(self.types["cave"]["radial"]["dungeons"], "outer")
        self.assertEqual(
            set(self.types["sky_islands"]["groups"]),
            {"deco", "landmarks", "settlements", "loot"})
        self.assertEqual(
            set(self.types["paradise_lost:paradise_lost"]["groups"]),
            {"deco", "landmarks", "settlements"})
        self.assertEqual(self.types["amplified"]["profiles"]["landmarks"], "natural")
        self.assertEqual(self.types["amplified"]["radial"]["endgame"], "outer")
        # nether_islands mirrors nether
        self.assertEqual(self.types["nether_islands"]["groups"],
                         self.types["nether"]["groups"])
        self.assertEqual(self.types["nether_islands"]["profiles"],
                         self.types["nether"]["profiles"])
        # multi_biome / overworld: everything, settlements in, dungeons out
        for t in ("multi_biome", "overworld"):
            self.assertEqual(set(self.types[t]["groups"]), self.groups, t)
            self.assertEqual(self.types[t]["radial"]["settlements"], "inner", t)
            self.assertEqual(self.types[t]["radial"]["dungeons"], "outer", t)
        # void and superflat generate nothing
        for t in ("void", "superflat"):
            self.assertEqual(self.types[t]["groups"], [], t)

    # --- difficulty shifts -----------------------------------------------

    def test_difficulty_shifts(self):
        shifts = self.doc["difficultyShifts"]
        hostile, peaceful = shifts["hostile"], shifts["peaceful"]
        self.assertEqual(hostile["minMobMultiplier"], 2.0)
        self.assertEqual(hostile["radial"], {"dungeons": "even", "endgame": "mid"})
        self.assertEqual(peaceful["maxMobMultiplier"], 0.5)
        self.assertEqual(peaceful["profiles"],
                         {"dungeons": "none", "endgame": "none"})
        # ...and every name in them resolves
        for group, curve in hostile["radial"].items():
            self.assertIn(group, self.groups)
            self.assertIn(curve, self.curves)
        for group, profile in peaceful["profiles"].items():
            self.assertIn(group, self.groups)
            self.assertIn(profile, VALID_PROFILES)

    def test_shift_bands_do_not_overlap(self):
        shifts = self.doc["difficultyShifts"]
        self.assertLess(shifts["peaceful"]["maxMobMultiplier"],
                        shifts["hostile"]["minMobMultiplier"])

    # --- coverage against the shipped dimension set ----------------------

    def test_every_shipped_dimension_type_is_covered(self):
        """No shipped dimension may fall through to no defaults at all."""
        uncovered = {}
        dims = REPO / "config/custom-dimensions/dimensions"
        for path in sorted(dims.glob("*.json")):
            cfg = json.loads(path.read_text())
            world_type = cfg.get("type")
            if world_type is None:
                continue  # base-world override, never noise-placed
            if world_type not in self.types:
                uncovered[path.name] = world_type
        self.assertEqual(uncovered, {},
                         "shipped dimensions with an uncovered type")


if __name__ == "__main__":
    unittest.main()
