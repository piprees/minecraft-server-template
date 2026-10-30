#!/usr/bin/env python3
"""Tests for carrying dep-review's hold edits across the weekly mod re-pin.

A wrong merge either drops a hold the reviewer set (the broken version comes
back next week) or resurrects one it released (the fix never lands).
"""
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from dep_review.carry_holds import merge_holds  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "dep_review" / "carry_holds.py"
MANIFEST = REPO / "modpack" / "adventure.mrpack.json"


class MergeHoldsTests(unittest.TestCase):
    def test_branch_addition_is_carried(self):
        merged, carried = merge_holds({"a": "x"}, {"a": "x", "b": "broke"}, {"a": "x"})
        self.assertEqual(merged, {"a": "x", "b": "broke"})
        self.assertEqual(carried, ["b"])

    def test_branch_removal_is_carried(self):
        merged, carried = merge_holds({"a": "x"}, {}, {"a": "x"})
        self.assertEqual(merged, {})
        self.assertEqual(carried, ["a"])

    def test_branch_reason_change_is_carried(self):
        merged, _ = merge_holds({"a": "x"}, {"a": "y"}, {"a": "x"})
        self.assertEqual(merged, {"a": "y"})

    def test_hold_added_on_main_survives(self):
        merged, carried = merge_holds({"a": "x", "m": "main"}, {"a": "x"}, {"a": "x"})
        self.assertEqual(merged, {"a": "x", "m": "main"})
        self.assertEqual(carried, [])

    def test_hold_removed_on_main_stays_removed(self):
        merged, carried = merge_holds({}, {"a": "x"}, {"a": "x"})
        self.assertEqual(merged, {})
        self.assertEqual(carried, [])

    def test_both_sides_change_branch_wins(self):
        merged, _ = merge_holds({"a": "main"}, {"a": "branch"}, {"a": "x"})
        self.assertEqual(merged, {"a": "branch"})


class ScriptTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.manifest = self.tmp / "manifest.json"
        shutil.copy(MANIFEST, self.manifest)
        self.original = self.manifest.read_text()
        data = json.loads(self.original)
        self.base = self.tmp / "base.json"
        self.base.write_text(self.original)
        data["_holds"]["sodium"] = "0.7 breaks iris (dep-review: PR #1)"
        self.branch = self.tmp / "branch.json"
        self.branch.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")

    def run_script(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--manifest", str(self.manifest),
             "--branch", str(self.branch), "--base", str(self.base)],
            capture_output=True, text=True, check=True,
        )

    def test_only_holds_change_in_the_real_manifest(self):
        result = self.run_script()
        self.assertIn("sodium: held", result.stdout)
        self.assertEqual(self.manifest.read_text(), self.branch.read_text())

    def test_nothing_to_carry_leaves_file_untouched(self):
        self.branch.write_text(self.original)
        result = self.run_script()
        self.assertEqual(result.stdout, "")
        self.assertEqual(self.manifest.read_text(), self.original)


if __name__ == "__main__":
    unittest.main()
