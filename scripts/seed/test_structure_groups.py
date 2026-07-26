#!/usr/bin/env python3
"""Tests for scripts/gen-structure-groups.py (spike task A1).

Run via the repo gate: scripts/test-scripts.sh --quick, which does
`python3 -B -m unittest discover -s scripts/seed -p 'test_*.py'`.
pytest collects these too.
"""

import csv
import importlib.util
import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
GEN = REPO / "scripts/gen-structure-groups.py"
REGISTRY = REPO / "config/custom-dimensions/structure-groups.json"
THEMES = REPO / "mods/custom-dimensions/src/main/resources/structure_themes.json"


def _load_gen():
    spec = importlib.util.spec_from_file_location("gen_structure_groups", GEN)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


gen = _load_gen()


class TestRarityDerivation(unittest.TestCase):
    """Spacing-only thresholds. MIRRORED in the Java + roller sides."""

    def test_boundaries(self):
        # <= 24 common, 25-45 uncommon, 46-80 rare, > 80 endgame
        for spacing, expected in [
            (0, "common"), (1, "common"), (24, "common"),
            (25, "uncommon"), (34, "uncommon"), (45, "uncommon"),
            (46, "rare"), (61, "rare"), (80, "rare"),
            (81, "endgame"), (600, "endgame"),
        ]:
            self.assertEqual(gen.rarity_for(spacing), expected,
                             "spacing %d" % spacing)

    def test_none_spacing(self):
        self.assertIsNone(gen.rarity_for(None))

    def test_spike_named_cases(self):
        """The three cases the spike's A1 verify section names explicitly."""
        self.assertEqual(gen.rarity_for(31), "uncommon")   # mes:phantom_citadel
        self.assertEqual(gen.rarity_for(600), "endgame")   # nova shrine_tower
        self.assertEqual(gen.rarity_for(24), "common")     # minecraft:shipwrecks


class TestGroupDerivation(unittest.TestCase):
    def test_theme_maps_to_group(self):
        for theme, expected in [
            ("deco", "deco"), ("ruins", "deco"), ("settlement", "settlements"),
            ("dungeon", "dungeons"), ("landmark", "landmarks"),
            ("maritime", "maritime"), ("loot", "loot"),
        ]:
            self.assertEqual(gen.group_for(theme, "common", "x:y", ""), expected)

    def test_unknown_theme_returns_none(self):
        self.assertIsNone(gen.group_for("nonsense", "common", "x:y", ""))

    def test_endgame_needs_keyword_theme_and_rarity(self):
        # All three conditions must hold.
        self.assertEqual(
            gen.group_for("dungeon", "rare", "mod:mega_fortress", ""), "endgame")
        # ...wrong rarity: a keyword-matching set the mod tuned to be frequent
        # is not endgame content (friendsandfoes:citadel, spacing 16).
        self.assertEqual(
            gen.group_for("dungeon", "common", "mod:citadel", ""), "dungeons")
        self.assertEqual(
            gen.group_for("dungeon", "uncommon", "mod:citadel", ""), "dungeons")
        # ...wrong theme: a decorative set called "arena" stays deco.
        self.assertEqual(
            gen.group_for("deco", "endgame", "mod:small_arena", ""), "deco")
        # ...no keyword: a rare dungeon is still just a dungeon.
        self.assertEqual(
            gen.group_for("dungeon", "endgame", "mod:deep_hole", ""), "dungeons")

    def test_keyword_matches_structure_names_too(self):
        """dungeons_arise:major_structures earns endgame via its contents."""
        self.assertEqual(
            gen.group_for("dungeon", "rare", "mod:major_structures",
                          "mod:coliseum(w=1); mod:shop(w=1)"), "endgame")

    def test_decorative_variants_never_become_endgame(self):
        """The spacing artefact that motivated the keyword rule.

        mss:tree_7 has spacing 186 purely because the mod splits one feature
        across eight sets. Spacing alone would have made it endgame.
        """
        registry = json.loads(REGISTRY.read_text())["sets"]
        for set_id in ("mss:tree_7", "mvs:duck", "mss:small_pond",
                       "mns:bridge_1", "mvs:mushroom_statue"):
            self.assertIn(set_id, registry)
            self.assertNotEqual(registry[set_id]["group"], "endgame", set_id)


class TestGeneratedOutputs(unittest.TestCase):
    def setUp(self):
        self.doc = json.loads(REGISTRY.read_text())
        self.sets = self.doc["sets"]
        self.themes = json.loads(THEMES.read_text())

    def test_outputs_are_not_stale(self):
        """--check must pass, i.e. the committed files match the inputs."""
        r = subprocess.run([sys.executable, str(GEN), "--check"],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0,
                         "structure group outputs are stale:\n" + r.stderr)

    def test_sanity_count(self):
        # The union of both CSVs. The spike said 377 (the extracted census
        # alone); dials adds 2 sets the census lost to a pin drift.
        self.assertGreaterEqual(len(self.sets), 370)
        self.assertEqual(len(self.sets), len(self.themes))

    def test_every_dials_set_is_classified(self):
        """No set from the hand-curated list is orphaned."""
        missing = []
        with open(REPO / "scripts/data/structure-dials.csv", newline="") as fh:
            for row in csv.DictReader(fh):
                set_id = row["structure_set"].strip()
                if ":" not in set_id or "(" in set_id:
                    continue  # marker row
                if set_id not in self.sets:
                    missing.append(set_id)
        self.assertEqual(missing, [], "dials sets missing from the registry")

    def test_every_extracted_set_is_classified(self):
        """No set from the machine census is orphaned either."""
        missing = []
        with open(REPO / "scripts/data/structure-sets-extracted.csv", newline="") as fh:
            for row in csv.DictReader(fh):
                set_id = row["structure_set_id"].strip()
                if ":" not in set_id:
                    continue
                if set_id not in self.sets:
                    missing.append(set_id)
        self.assertEqual(missing, [], "census sets missing from the registry")

    def test_every_entry_has_a_valid_group_and_rarity(self):
        for set_id, entry in self.sets.items():
            self.assertIn(entry["group"], gen.GROUPS, set_id)
            self.assertIn(entry["rarity"], gen.RARITIES, set_id)
            self.assertIn(entry["theme"], gen.THEME_TO_GROUP, set_id)

    def test_themes_resource_round_trips(self):
        """The jar resource is valid JSON with the three runtime fields."""
        for set_id, entry in self.themes.items():
            self.assertIsInstance(entry, dict, set_id)
            self.assertEqual(set(entry), {"theme", "group", "rarity"}, set_id)
            self.assertIn(entry["group"], gen.GROUPS, set_id)
            self.assertIn(entry["rarity"], gen.RARITIES, set_id)

    def test_themes_resource_matches_registry(self):
        for set_id, entry in self.sets.items():
            self.assertEqual(
                self.themes[set_id],
                {"theme": entry["theme"], "group": entry["group"],
                 "rarity": entry["rarity"]}, set_id)

    def test_set_ids_are_parseable_identifiers(self):
        """A bad id would be a boot break on the Java side."""
        pattern = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
        for set_id in self.sets:
            self.assertRegex(set_id, pattern)

    def test_named_classifications(self):
        """The classifications the spike's regression checks depend on."""
        expect = {
            # spike A1 verify
            "mes:phantom_citadel": ("dungeons", "uncommon"),
            "nova_structures:shrine_tower": ("landmarks", "endgame"),
            "minecraft:shipwrecks": ("maritime", "common"),
            # spike G2 regression targets — none of these are in the dials CSV,
            # which is why the curated table exists.
            "minecraft:igloos": ("landmarks", "uncommon"),
            "minecraft:jungle_temples": ("landmarks", "uncommon"),
            "minecraft:desert_pyramids": ("landmarks", "uncommon"),
            "minecraft:ocean_monuments": ("maritime", "uncommon"),
            "minecraft:buried_treasures": ("loot", "common"),
            "minecraft:villages": ("settlements", "uncommon"),
            "supplementaries:galleons": ("maritime", "uncommon"),
            "paradise_lost:vault": ("dungeons", "uncommon"),
        }
        for set_id, (group, rarity) in expect.items():
            self.assertIn(set_id, self.sets, set_id)
            self.assertEqual(
                (self.sets[set_id]["group"], self.sets[set_id]["rarity"]),
                (group, rarity), set_id)

    def test_curated_overrides_win(self):
        for set_id, curated in gen.CURATED.items():
            theme = curated[0]
            self.assertIn(set_id, self.sets, set_id)
            self.assertEqual(self.sets[set_id]["theme"], theme, set_id)
            self.assertEqual(self.sets[set_id]["themeSource"], "curated", set_id)

    def test_endgame_group_membership_is_justified(self):
        """Every endgame member is rare-or-rarer and keyword-matching."""
        for set_id, entry in self.sets.items():
            if entry["group"] != "endgame":
                continue
            self.assertIn(entry["rarity"], gen.ENDGAME_ELIGIBLE_RARITIES, set_id)
            self.assertIn(entry["theme"], gen.ENDGAME_ELIGIBLE_THEMES, set_id)

    def test_all_groups_are_populated(self):
        """An empty group would silently disable a whole slice of content."""
        present = {e["group"] for e in self.sets.values()}
        self.assertEqual(present, set(gen.GROUPS))


if __name__ == "__main__":
    unittest.main()
