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


class RenderWorkerPlanTests(unittest.TestCase):
    """Rendering runs whether or not anything is rolling, and always against
    the CURRENT top ten.

    It used to be step 4 of the roll cycle, so a viewer opened on a bank
    with thousands of banked candidates produced no images at all until
    someone pressed Start — measured on a real bank at 1457 missing images
    across 74 of 81 targets (2026-07-29). The plan is recomputed from the
    stores every pass rather than queued, so a candidate demoted by a
    re-rank simply stops being in the answer.
    """

    def setUp(self):
        import tempfile, json, shutil
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.cfg = self.tmp / "config"
        (self.cfg / "dimensions").mkdir(parents=True)
        (self.cfg / "dimensions" / "d1.json").write_text(json.dumps(
            {"type": "overworld", "biomes": ["minecraft:plains"]}))
        (self.cfg / "dimensions" / "d2.json").write_text(json.dumps(
            {"type": "overworld", "biomes": ["minecraft:plains"]}))
        self.seedtest = self.tmp / "seedtest"
        (self.seedtest / "candidates").mkdir(parents=True)
        import candidates as cmod
        cmod.set_bank_root(str(self.seedtest))
        self.cmod = cmod

    def _store(self, name, seeds, chash="h"):
        import json
        store = {"configHash": chash, "candidates": {}, "rejected": {},
                 "abandoned": {}, "winner": None, "winnerPinned": False}
        for i, s in enumerate(seeds):
            store["candidates"][str(s)] = {
                "measurements": {}, "scores": {chash: {"total": 100 - i}}}
        (self.seedtest / "candidates" / f"{name}.json").write_text(json.dumps(store))

    def _worker(self):
        return viewer_server.RenderWorker(str(self.cfg), str(self.seedtest))

    def test_every_missing_image_is_planned(self):
        self._store("d1", [11, 22])
        plan = dict(self._worker()._plan())
        # two seeds x two passes (low + hi-res)
        self.assertEqual(plan.get("d1"), 4)

    def test_existing_images_are_not_replanned(self):
        self._store("d1", [11])
        d = self.seedtest / "renders" / "d1"
        d.mkdir(parents=True)
        (d / "11.png").write_bytes(b"")
        (d / "11_hires.png").write_bytes(b"")
        self.assertEqual(dict(self._worker()._plan()).get("d1"), None)

    def test_most_missing_first(self):
        self._store("d1", [11])
        self._store("d2", [21, 22, 23])
        self.assertEqual([n for n, _ in self._worker()._plan()], ["d2", "d1"])

    def test_only_the_current_top_n_is_planned(self):
        self._store("d1", list(range(100, 100 + viewer_server.RENDER_TOP + 5)))
        plan = dict(self._worker()._plan())
        self.assertEqual(plan["d1"], viewer_server.RENDER_TOP * 2)

    def test_a_stale_config_hash_leaves_nothing_to_render(self):
        """Scores keyed to an old config hash still count — _top_seeds falls
        back to the best score on record, so a config edit never blanks the
        render plan while the rescore is still catching up."""
        self._store("d1", [11], chash="old")
        self.assertEqual(dict(self._worker()._plan()).get("d1"), 2)

    def test_bump_sets_the_wake_flag(self):
        w = self._worker()
        self.assertFalse(w.wake.is_set())
        w.bump()
        self.assertTrue(w.wake.is_set())


class FinaliseSerialisationTests(unittest.TestCase):
    """One finalise at a time, and say so out loud.

    Nine call sites spawn `score-dimensions.py finalise` — the roll thread, six
    HTTP handlers, the re-roll job and startup. Two overlapping means two
    processes rewriting the same candidate stores and index.html, and the
    loser's writes vanish. Startup used to dodge this by running before the
    port was open, which is precisely what made a cold start look dead for half
    an hour, so the accident was replaced with a lock.
    """

    def setUp(self):
        self.calls = []
        self.overlaps = []
        self.live = 0
        self.live_lock = __import__("threading").Lock()
        self._real_popen = viewer_server.subprocess.Popen
        viewer_server._finalise_state.update(
            running=False, reason="", since=0.0, line="", queued=0)

    def tearDown(self):
        viewer_server.subprocess.Popen = self._real_popen

    def _fake_popen(self, lines=("one", "two"), delay=0.02):
        import io
        import time as _t
        test = self

        class FakeProc:
            def __init__(self, *a, **kw):
                test.calls.append(a[0] if a else kw.get("args"))
                with test.live_lock:
                    test.live += 1
                    if test.live > 1:
                        test.overlaps.append(test.live)
                _t.sleep(delay)
                self.stdout = io.StringIO("\n".join(lines) + "\n")

            def wait(self):
                with test.live_lock:
                    test.live -= 1
                return 0

            def kill(self):
                pass

        return FakeProc

    def test_two_callers_never_overlap(self):
        import threading
        viewer_server.subprocess.Popen = self._fake_popen()
        threads = [threading.Thread(target=viewer_server.run_finalise,
                                    args=(["--config", "x"], "caller%d" % i),
                                    kwargs={"echo": False})
                   for i in range(6)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=10)
        self.assertEqual(len(self.calls), 6, "every caller must still run")
        self.assertEqual(self.overlaps, [], "two finalises ran concurrently")

    def test_the_child_gets_the_finalise_subcommand(self):
        viewer_server.subprocess.Popen = self._fake_popen()
        viewer_server.run_finalise(["--config", "cfg", "--viewer"], "test",
                                   echo=False)
        argv = self.calls[0]
        self.assertIn("finalise", argv)
        self.assertEqual(argv[argv.index("finalise") + 1:],
                         ["--config", "cfg", "--viewer"])

    def test_status_reports_the_latest_line_then_clears(self):
        seen = {}
        viewer_server.subprocess.Popen = self._fake_popen(
            lines=("noise census: computing 4447 candidate layout(s)",
                   "  222/4447 (4%)"))
        real_set = viewer_server._set_finalise

        def spy(**kw):
            real_set(**kw)
            if kw.get("line"):
                seen["line"] = kw["line"]
                seen["running"] = viewer_server.finalise_status()["running"]
        viewer_server._set_finalise = spy
        try:
            viewer_server.run_finalise(["--config", "x"], "startup", echo=False)
        finally:
            viewer_server._set_finalise = real_set
        # Mid-run the page can see progress...
        self.assertEqual(seen["line"], "  222/4447 (4%)")
        self.assertTrue(seen["running"])
        # ...and afterwards it is not left claiming to be busy.
        after = viewer_server.finalise_status()
        self.assertFalse(after["running"])
        self.assertEqual(after["line"], "")
        self.assertEqual(after["queued"], 0)

    def test_a_failed_spawn_is_not_fatal_and_releases_the_lock(self):
        def boom(*a, **kw):
            raise OSError("no such file")
        viewer_server.subprocess.Popen = boom
        self.assertEqual(
            viewer_server.run_finalise(["--config", "x"], "broken", echo=False), 1)
        self.assertFalse(viewer_server.finalise_status()["running"])
        # The lock must be free, or every later finalise deadlocks.
        self.assertTrue(viewer_server._FINALISE_LOCK.acquire(timeout=1))
        viewer_server._FINALISE_LOCK.release()
