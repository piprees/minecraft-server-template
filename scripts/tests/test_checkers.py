#!/usr/bin/env python3
"""Tests for the offline verification checkers in scripts/.

These are the checks that replaced parsing RCON output. They read artefacts
the mod already writes and must fail LOUDLY on broken input — a checker that
silently passes is worse than no checker, because it launders a broken world
into a green run.

Fixtures are built in-test rather than committed: a negative fixture is only
meaningful if the specific breakage is visible next to the assertion, and a
committed one drifts out of step with the writer it imitates.
"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name):
    path = SCRIPTS / name
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


drift = load("check-dimension-drift.py")
portal = load("check-portal-integrity.py")
noise = load("check-noise-regression.py")


def write(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(body))


class DimensionDriftTests(unittest.TestCase):
    """Worldgen is creation-time-only; this catches a world older than config."""

    DIM = {
        "type": "multi_biome",
        "biomes": ["minecraft:jungle", "minecraft:bamboo_jungle"],
        "noiseSettings": "adventure:compressed",
        "seed": 1234,
    }

    def build(self, tmp, config_overrides=None, fingerprint_overrides=None):
        root = Path(tmp)
        config_dir = root / "custom-dimensions"
        dim = dict(self.DIM)
        dim.update(config_overrides or {})
        write(config_dir / "dimensions" / "the_test.json", dim)
        stamped = {
            "type": "multi_biome",
            "biomes": "minecraft:jungle,minecraft:bamboo_jungle",
            "noiseSettings": "adventure:compressed",
            "seed": "1234",
            "checkerboardScale": "null",
            "layers": "null",
            "flatBiome": "null",
            "settingsOverrides": "null",
            "biomeParameters": "null",
            "biomePatches": "null",
        }
        stamped.update(fingerprint_overrides or {})
        fp = root / "custom-dimensions-fingerprints.json"
        write(fp, {"the_test": stamped})
        return fp, config_dir

    def run_check(self, tmp, **kw):
        fp, config_dir = self.build(tmp, **kw)
        fingerprints = json.loads(fp.read_text())
        configs = drift.load_configs(config_dir)
        return drift.compare(fingerprints, configs)

    def test_matching_world_is_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            drifted, uncreated, orphans, clean = self.run_check(tmp)
            self.assertEqual(drifted, [])
            self.assertEqual(orphans, [])
            self.assertEqual([s for s, _ in clean], ["the_test"])

    def test_biome_list_change_is_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            drifted, _, _, _ = self.run_check(
                tmp, config_overrides={"biomes": ["minecraft:desert"]})
            self.assertEqual(len(drifted), 1)
            self.assertIn("biomes", [f for f, _, _ in drifted[0][1]])

    def test_type_change_is_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            drifted, _, _, _ = self.run_check(tmp, config_overrides={"type": "cave"})
            self.assertEqual(len(drifted), 1)

    def test_seed_change_alone_is_not_drift(self):
        """The seed roller re-pins winners constantly — routine, not structural."""
        with tempfile.TemporaryDirectory() as tmp:
            drifted, _, _, clean = self.run_check(tmp, config_overrides={"seed": 99})
            self.assertEqual(drifted, [])
            self.assertEqual(len(clean), 1)

    def test_missing_config_is_an_orphan(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            config_dir = root / "custom-dimensions"
            write(config_dir / "dimensions" / "other.json", self.DIM)
            fingerprints = {"gone_dimension": {"type": "multi_biome"}}
            _, _, orphans, _ = drift.compare(
                fingerprints, drift.load_configs(config_dir))
            self.assertEqual(orphans, ["gone_dimension"])

    def test_uncreated_dimension_is_not_a_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            config_dir = root / "custom-dimensions"
            write(config_dir / "dimensions" / "never_visited.json", self.DIM)
            drifted, uncreated, orphans, _ = drift.compare({}, drift.load_configs(config_dir))
            self.assertEqual(drifted, [])
            self.assertEqual(orphans, [])
            self.assertEqual(uncreated, ["never_visited"])

    def test_bespoke_fingerprint_formats_round_trip(self):
        """The mod stores these as its own strings, NOT as JSON.

        Emitting `{"seaLevel":90}` where the mod wrote `seaLevel=90` marks
        every dimension carrying that field as permanently drifted — which is
        exactly what the first version of this checker did.
        """
        cases = [
            ({"settingsOverrides": {"seaLevel": 90}}, "settingsOverrides", "seaLevel=90"),
            ({"settingsOverrides": {"defaultFluid": "minecraft:lava"}},
             "settingsOverrides", "defaultFluid=minecraft:lava"),
            ({"layers": [{"height": 1, "block": "minecraft:bedrock"},
                         {"height": 2, "block": "minecraft:dirt"}]},
             "layers", "1*minecraft:bedrock,2*minecraft:dirt"),
            ({"biomePatches": [{"biome": "minecraft:jungle", "x": 10, "z": -20,
                                "radius": 64, "replace": "minecraft:desert"}]},
             "biomePatches", "minecraft:jungle@10,-20,64>minecraft:desert"),
        ]
        for config_extra, field, expected in cases:
            with self.subTest(field=field, expected=expected):
                self.assertEqual(drift.config_value(config_extra, field), expected)

    def test_overlay_disables_and_overrides(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "custom-dimensions"
            write(root / "dimensions" / "a.json", self.DIM)
            write(root / "dimensions" / "b.json", self.DIM)
            write(root / "overlay" / "dimensions" / "a.json", {})
            write(root / "overlay" / "dimensions" / "b.json",
                  {"overrides": {"type": "cave"}})
            configs = drift.load_configs(root)
            self.assertNotIn("a", configs)
            self.assertEqual(configs["b"]["type"], "cave")


class PortalIntegrityTests(unittest.TestCase):
    ZONE = {
        "recordType": "source-zone-v1",
        "sourceWorld": "minecraft:overworld",
        "targetWorld": "adventure:the_boneyard",
        "axis": "X",
        "definition": {
            "id": "the_boneyard",
            "frameBlock": "minecraft:bone_block",
            "targetDimension": "adventure:the_boneyard",
        },
    }

    def check(self, records, known=None):
        report = portal.Report(quiet=True)
        portal.check_records(records, known or {"minecraft:overworld",
                                                "adventure:the_boneyard"}, report)
        return report

    def test_valid_zone_passes(self):
        report = self.check([dict(self.ZONE)])
        self.assertEqual(report.failures, [])
        self.assertGreater(report.passed, 0)

    def test_tag_frameblock_fails(self):
        """A '#tag' here crash-loops any jar older than FrameMatcher."""
        zone = json.loads(json.dumps(self.ZONE))
        zone["definition"]["frameBlock"] = "#minecraft:logs"
        report = self.check([zone])
        self.assertTrue(any("frameBlock" in name for name, _ in report.failures),
                        report.failures)

    def test_missing_frameblock_fails(self):
        zone = json.loads(json.dumps(self.ZONE))
        del zone["definition"]["frameBlock"]
        self.assertTrue(self.check([zone]).failures)

    def test_unknown_exit_mode_fails(self):
        zone = json.loads(json.dumps(self.ZONE))
        zone["definition"]["exitMode"] = "teleport_home"
        report = self.check([zone])
        self.assertTrue(any("exitMode" in name for name, _ in report.failures))

    def test_known_exit_modes_pass(self):
        for mode in ("origin", "bed", "worldSpawn"):
            with self.subTest(mode=mode):
                zone = json.loads(json.dumps(self.ZONE))
                zone["definition"]["exitMode"] = mode
                self.assertEqual(self.check([zone]).failures, [])

    def test_unknown_target_dimension_warns_but_does_not_fail(self):
        zone = json.loads(json.dumps(self.ZONE))
        zone["targetWorld"] = "adventure:deleted_dimension"
        report = self.check([zone])
        self.assertEqual(report.failures, [])
        self.assertTrue(report.warnings)

    def test_legacy_return_target_without_recordtype_is_still_checked(self):
        legacy = {"portalWorld": "adventure:the_boneyard", "x": 501, "y": 24,
                  "z": 500, "targetWorld": "minecraft:overworld",
                  "sourceX": 4000, "sourceY": 150, "sourceZ": 4000}
        self.assertEqual(self.check([legacy]).failures, [])
        broken = dict(legacy, portalWorld="the_boneyard")   # no namespace
        self.assertTrue(self.check([broken]).failures)

    def test_armed_countdown_without_singleuse_fails(self):
        zone = json.loads(json.dumps(self.ZONE))
        zone["singleUseTicksLeft"] = 200
        self.assertTrue(self.check([zone]).failures)
        zone["definition"]["singleUse"] = True
        self.assertEqual(self.check([zone]).failures, [])

    def test_aura_site_budget_must_be_sane(self):
        aura = {"recordType": "aura-site-v1", "world": "adventure:the_boneyard",
                "interior": [{"x": 1, "y": 2, "z": 3}], "budgetSpent": 300}
        self.assertEqual(self.check([aura]).failures, [])
        self.assertTrue(self.check([dict(aura, budgetSpent=-1)]).failures)
        self.assertTrue(self.check([dict(aura, interior=[])]).failures)


class CensusStackStampTests(unittest.TestCase):
    """An artefact from another stack must fail loudly, never read best-effort."""

    CENSUS = {"stackVersion": "4.2.0", "kind": "structure-census",
              "dimension": "adventure:the_test", "groups": {}, "forced": {}}

    def dump(self, tmp, body):
        path = Path(tmp) / "adventure__the_test.json"
        write(path, body)
        return Path(tmp)

    def running(self, version):
        """Pin the stack this checker believes it is."""
        original = noise.stack_version.stack_version
        noise.stack_version.stack_version = lambda: version
        self.addCleanup(setattr, noise.stack_version, "stack_version", original)

    def test_same_stack_loads(self):
        self.running("4.2.0")
        with tempfile.TemporaryDirectory() as tmp:
            census_dir = self.dump(tmp, self.CENSUS)
            self.assertIsNotNone(noise.load_census(census_dir, "the_test"))

    def test_other_stack_raises(self):
        self.running("4.2.0")
        with tempfile.TemporaryDirectory() as tmp:
            census_dir = self.dump(tmp, dict(self.CENSUS, stackVersion="4.1.0"))
            with self.assertRaises(noise.SchemaMismatch) as ctx:
                noise.load_census(census_dir, "the_test")
            self.assertIn("4.1.0", str(ctx.exception))
            self.assertIn("4.2.0", str(ctx.exception))

    def test_dev_stack_does_not_compare(self):
        """A linked checkout has no release identity on either side."""
        self.running("dev")
        with tempfile.TemporaryDirectory() as tmp:
            census_dir = self.dump(tmp, dict(self.CENSUS, stackVersion="dev"))
            self.assertIsNotNone(noise.load_census(census_dir, "the_test"))

    def test_dev_artefact_against_a_release_does_not_compare(self):
        self.running("4.2.0")
        with tempfile.TemporaryDirectory() as tmp:
            census_dir = self.dump(tmp, dict(self.CENSUS, stackVersion="dev"))
            self.assertIsNotNone(noise.load_census(census_dir, "the_test"))

    def test_unstamped_artefact_still_loads(self):
        self.running("4.2.0")
        with tempfile.TemporaryDirectory() as tmp:
            body = dict(self.CENSUS)
            del body["stackVersion"]
            census_dir = self.dump(tmp, body)
            self.assertIsNotNone(noise.load_census(census_dir, "the_test"))

    def test_missing_census_is_none_not_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(noise.load_census(Path(tmp), "never_dumped"))


if __name__ == "__main__":
    unittest.main()
