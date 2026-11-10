#!/usr/bin/env python3
"""Tests for stage-dimensions.py: a consumer's custom-dimensions overlay, resolved for the 26.3 mod.

Load-bearing: the 26.3 mod reads dimensions/ and bridges/ only and refuses a
file without "schemaVersion": 2 at start, so a wrongly staged overlay either
stops the server or silently drops the consumer's change. The platform side
is the shipped definitions; the overlay side is built per test.
"""
import importlib.util
import io
import json
import contextlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
REPO = SCRIPTS.parent
PLATFORM = REPO / "config/custom-dimensions"
spec = importlib.util.spec_from_file_location("stage_dimensions", SCRIPTS / "stage-dimensions.py")
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)

# Elfydd's overlay as it is: 1.21.1 seed-viewer thumbnails and their images.
SEED_VIEWER = {
    "dimensions/the_gauntlet_thumb.json": '{"inputHash": "afc9", "seed": -7027121575339057397}',
    "dimensions/the_gauntlet_high.png": "png",
    "dimensions/the_gauntlet_low.png": "png",
}
V1_DEFINITION = json.dumps({"description": "An old one", "seed": 1, "noiseSettings": "adventure:wide",
                            "portal": {"frame": "minecraft:stone", "scale": 8}})


class StageCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.overlay = self.root / "overlay"
        self.out = self.root / "data/custom-dimensions"
        shutil.copytree(PLATFORM / "dimensions", self.out / "dimensions")
        shutil.copytree(PLATFORM / "bridges", self.out / "bridges")

    def put(self, files):
        for rel, text in files.items():
            path = self.overlay / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text if isinstance(text, str) else json.dumps(text))

    def run_stage(self, check=False):
        lines = []
        code = stage.Stager(PLATFORM, self.overlay, self.out, check=check, log=lines.append).run()
        return code, lines

    def staged(self, rel):
        return stage.read_json(self.out / rel)

    def platform(self, rel):
        return stage.read_json(PLATFORM / rel)


class FormsTest(StageCase):
    def test_no_overlay_changes_nothing(self):
        before = {p.name: p.read_bytes() for p in (self.out / "dimensions").iterdir()}
        code, lines = self.run_stage()
        self.assertEqual(code, 0)
        self.assertEqual({p.name: p.read_bytes() for p in (self.out / "dimensions").iterdir()}, before)
        self.assertEqual(lines, ["  custom-dimensions overlay: 0 staged, 0 warning(s)"])

    def test_overrides_merge_over_the_platform_definition(self):
        self.put({"dimensions/overworld.json": {"overrides": {
            "seed": 42, "spawn": [10, 70, -10], "borders": {"player": 4096}, "description": None}}})
        code, lines = self.run_stage()
        self.assertEqual(code, 0)
        got, base = self.staged("dimensions/overworld.json"), self.platform("dimensions/overworld.json")
        self.assertEqual((got["seed"], got["spawn"]), (42, [10, 70, -10]))
        self.assertEqual(got["borders"], dict(base["borders"], player=4096), "objects merge key by key")
        self.assertNotIn("description", got, "null removes a key")
        self.assertEqual(got["schemaVersion"], 2)
        self.assertEqual({k: v for k, v in got.items() if k not in ("seed", "spawn", "borders")},
                         {k: v for k, v in base.items() if k not in ("seed", "spawn", "borders", "description")})
        self.assertIn("  merged   dimensions/overworld.json (borders, description, seed, spawn)", lines)

    def test_a_v2_file_replaces_or_adds(self):
        mine = dict(self.platform("dimensions/the_aviary.json"), description="Ours")
        self.put({"dimensions/the_aviary.json": mine,
                  "dimensions/the_new_realm.json": dict(mine, description="New")})
        _, lines = self.run_stage()
        self.assertEqual(self.staged("dimensions/the_aviary.json")["description"], "Ours")
        self.assertEqual(self.staged("dimensions/the_new_realm.json")["description"], "New")
        self.assertIn("  replaced dimensions/the_aviary.json", lines)
        self.assertIn("  added    dimensions/the_new_realm.json", lines)

    def test_empty_object_removes_a_lazy_dimension_but_not_a_base_world(self):
        lazy = next(p.stem for p in sorted((PLATFORM / "dimensions").glob("*.json"))
                    if p.stem not in stage.BASE_WORLDS and p.stem != "settings")
        self.put({f"dimensions/{lazy}.json": "{}", "dimensions/the_end.json": "{}", "dimensions/settings.json": "{}"})
        _, lines = self.run_stage()
        self.assertFalse((self.out / f"dimensions/{lazy}.json").exists())
        self.assertTrue((self.out / "dimensions/the_end.json").exists())
        self.assertTrue((self.out / "dimensions/settings.json").exists())
        self.assertEqual(sum("cannot remove" in line for line in lines), 2)

    def test_settings_take_overrides(self):
        self.put({"dimensions/settings.json": {"overrides": {"idleUnloadMinutes": 15}}})
        self.run_stage()
        got = self.staged("dimensions/settings.json")
        self.assertEqual(got["idleUnloadMinutes"], 15)
        self.assertEqual(got["defaults"], self.platform("dimensions/settings.json")["defaults"])

    def test_comment_lines_are_read_and_dropped(self):
        self.put({"dimensions/overworld.json": '// our seed\n{"overrides": {"seed": 7}}'})
        self.run_stage()
        self.assertNotIn("//", (self.out / "dimensions/overworld.json").read_text())
        self.assertEqual(self.staged("dimensions/overworld.json")["seed"], 7)

    def test_extra_bridges_sit_beside_the_platform_set(self):
        name = sorted((PLATFORM / "bridges").glob("*.json"))[0].name
        self.put({"bridges/my-structures.json": {"mod": "my-structures"}, f"bridges/{name}": {"mod": "x"}})
        _, lines = self.run_stage()
        self.assertEqual(self.staged("bridges/my-structures.json"), {"mod": "my-structures"})
        self.assertEqual(self.staged(f"bridges/{name}"), {"mod": "x"})
        self.assertEqual(len(list((self.out / "bridges").glob("*.json"))),
                         len(list((PLATFORM / "bridges").glob("*.json"))) + 1)
        self.assertIn(f"  replaced bridges/{name}", lines)


class RefusalsTest(StageCase):
    def test_a_v1_definition_is_not_staged_and_is_named(self):
        before = (self.out / "dimensions/the_end.json").read_bytes()
        self.put({"dimensions/the_end.json": V1_DEFINITION, "dimensions/the_old_realm.json": V1_DEFINITION})
        code, lines = self.run_stage()
        self.assertEqual(code, 0)
        self.assertEqual((self.out / "dimensions/the_end.json").read_bytes(), before)
        self.assertFalse((self.out / "dimensions/the_old_realm.json").exists())
        warns = [line for line in lines if line.startswith("  WARN")]
        self.assertEqual(len(warns), 2)
        self.assertTrue(all("schema v1" in w and "overrides" in w for w in warns), warns)

    def test_seed_viewer_files_are_ignored_quietly(self):
        self.put(SEED_VIEWER)
        before = sorted(p.name for p in (self.out / "dimensions").iterdir())
        _, lines = self.run_stage()
        self.assertEqual(sorted(p.name for p in (self.out / "dimensions").iterdir()), before)
        self.assertIn("  ignored  3 seed-viewer or documentation file(s) (nothing in 26.3 reads them)", lines)
        self.assertFalse(any(line.startswith("  WARN") for line in lines))

    def test_paths_the_mod_does_not_read_are_named(self):
        self.put({"settings.json": "{}", "overlay/dimensions/x.json": "{}"})
        _, lines = self.run_stage()
        self.assertEqual(sum("reads dimensions/ and bridges/ only" in line for line in lines), 2)

    def test_unreadable_json_and_orphan_overrides_are_warnings(self):
        self.put({"dimensions/overworld.json": "{not json", "dimensions/the_nowhere.json": {"overrides": {"seed": 1}},
                  "bridges/bad.json": "["})
        code, lines = self.run_stage()
        self.assertEqual(code, 0)
        self.assertEqual(sum(line.startswith("  WARN") for line in lines), 3)
        self.assertFalse((self.out / "dimensions/the_nowhere.json").exists())
        self.assertFalse((self.out / "bridges/bad.json").exists())


class LifecycleTest(StageCase):
    def test_removing_an_overlay_file_restores_or_drops_what_it_staged(self):
        mine = dict(self.platform("dimensions/the_aviary.json"), description="New")
        self.put({"dimensions/overworld.json": {"overrides": {"seed": 1}},
                  "dimensions/the_new_realm.json": mine})
        self.run_stage()
        shutil.rmtree(self.overlay)
        _, lines = self.run_stage()
        self.assertEqual((self.out / "dimensions/overworld.json").read_bytes(),
                         (PLATFORM / "dimensions/overworld.json").read_bytes())
        self.assertFalse((self.out / "dimensions/the_new_realm.json").exists())
        self.assertIn("  restored dimensions/overworld.json (platform copy)", lines)
        self.assertIn("  dropped  dimensions/the_new_realm.json (no longer in the overlay)", lines)
        self.assertFalse((self.out / stage.STATE).exists())

    def test_a_removal_is_undone_when_its_overlay_file_goes(self):
        lazy = next(p.stem for p in sorted((PLATFORM / "dimensions").glob("*.json"))
                    if p.stem not in stage.BASE_WORLDS and p.stem != "settings")
        self.put({f"dimensions/{lazy}.json": "{}"})
        self.run_stage()
        (self.overlay / f"dimensions/{lazy}.json").unlink()
        self.run_stage()
        self.assertEqual((self.out / f"dimensions/{lazy}.json").read_bytes(),
                         (PLATFORM / f"dimensions/{lazy}.json").read_bytes())

    def test_a_second_run_is_idempotent(self):
        self.put({"dimensions/overworld.json": {"overrides": {"seed": 3}}})
        self.run_stage()
        snapshot = {p.relative_to(self.out): p.read_bytes() for p in self.out.rglob("*") if p.is_file()}
        self.run_stage()
        self.assertEqual({p.relative_to(self.out): p.read_bytes() for p in self.out.rglob("*") if p.is_file()},
                         snapshot)

    def test_a_file_written_by_hand_since_is_kept(self):
        self.put({"dimensions/the_new_realm.json": dict(self.platform("dimensions/the_aviary.json"))})
        self.run_stage()
        (self.out / "dimensions/the_new_realm.json").write_text('{"schemaVersion": 2, "seed": 9}')
        shutil.rmtree(self.overlay)
        self.run_stage()
        self.assertTrue((self.out / "dimensions/the_new_realm.json").exists())

    def test_check_writes_nothing(self):
        self.put({"dimensions/overworld.json": {"overrides": {"seed": 1}}})
        before = (self.out / "dimensions/overworld.json").read_bytes()
        code, lines = self.run_stage(check=True)
        self.assertEqual(code, 0)
        self.assertEqual((self.out / "dimensions/overworld.json").read_bytes(), before)
        self.assertFalse((self.out / stage.STATE).exists())
        self.assertIn("  merged   dimensions/overworld.json (seed)", lines)


class CliTest(StageCase):
    def test_cli(self):
        self.put({"dimensions/overworld.json": {"overrides": {"seed": 5}}})
        proc = subprocess.run([sys.executable, str(SCRIPTS / "stage-dimensions.py"), "--platform", str(PLATFORM),
                               "--overlay", str(self.overlay), "--out", str(self.out)],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("1 staged, 0 warning(s)", proc.stdout)
        self.assertEqual(self.staged("dimensions/overworld.json")["seed"], 5)

    def test_a_missing_platform_folder_exits_one(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(stage.main(["--platform", str(self.root / "nope"), "--overlay", str(self.overlay),
                                         "--out", str(self.out)]), 1)

    def test_a_missing_overlay_is_no_overlay(self):
        code = stage.Stager(PLATFORM, self.root / "absent", self.out, log=lambda _l: None).run()
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
