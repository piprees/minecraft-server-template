"""Tests for docker/modpack-builder/merge-manifest.py, the consumer client-pack patch."""
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location(
    "merge_manifest", ROOT / "docker/modpack-builder/merge-manifest.py")
merge_manifest = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(merge_manifest)


def base():
    return {"_clientMods": {
        "required": ["fabric-api:A1", "untitled-duck-mod:1iG0fXae"],
        "optional": ["sodium:QV48eyCs", "iris:bAo1Qhte"],
    }}


class MergeTests(unittest.TestCase):
    def test_remove_by_bare_slug_drops_the_pinned_entry(self):
        out = merge_manifest.merge(base(), {"remove": ["untitled-duck-mod", "sodium"]})
        self.assertEqual(out["_clientMods"]["required"], ["fabric-api:A1"])
        self.assertEqual(out["_clientMods"]["optional"], ["iris:bAo1Qhte"])

    def test_remove_by_pinned_entry_matches_by_slug(self):
        out = merge_manifest.merge(base(), {"remove": ["sodium:OTHER"]})
        self.assertEqual(out["_clientMods"]["optional"], ["iris:bAo1Qhte"])

    def test_add_of_a_listed_slug_replaces_its_version(self):
        out = merge_manifest.merge(base(), {"add": {"optional": ["sodium:NEW"]}})
        self.assertEqual(out["_clientMods"]["optional"], ["iris:bAo1Qhte", "sodium:NEW"])

    def test_add_to_the_other_list_moves_the_slug(self):
        out = merge_manifest.merge(base(), {"add": {"required": ["iris:bAo1Qhte"]}})
        self.assertIn("iris:bAo1Qhte", out["_clientMods"]["required"])
        self.assertNotIn("iris:bAo1Qhte", out["_clientMods"]["optional"])

    def test_add_of_a_new_slug_appends(self):
        out = merge_manifest.merge(base(), {"add": {"required": ["cit-resewn"]}})
        self.assertEqual(out["_clientMods"]["required"][-1], "cit-resewn")
        self.assertEqual(len(out["_clientMods"]["required"]), 3)

    def test_empty_patch_changes_nothing(self):
        self.assertEqual(merge_manifest.merge(base(), {}), base())


if __name__ == "__main__":
    unittest.main()
