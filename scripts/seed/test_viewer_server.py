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


class SampleEvenlyTests(unittest.TestCase):
    """The census is nearest-first, so a truncation is a lie about the world.

    `[:50]` of a 9000-position group is 50 sites in a knot around spawn. Drawn
    on the map that is a picture of the truncation, and it would flatly
    contradict the radial histogram in the same panel. An even stride keeps the
    radial shape, which is the one thing the map is asked about.
    """

    def test_short_lists_are_returned_whole(self):
        self.assertEqual(viewer_server._sample_evenly([1, 2, 3], 10), [1, 2, 3])
        self.assertEqual(viewer_server._sample_evenly([], 10), [])

    def test_first_and_last_are_always_kept(self):
        items = list(range(1000))
        out = viewer_server._sample_evenly(items, 50)
        self.assertEqual(len(out), 50)
        self.assertEqual(out[0], 0)
        self.assertEqual(out[-1], 999)

    def test_the_stride_is_even_across_the_whole_list(self):
        # The stride spans index 0 to index n-1 inclusive, so for 100 items and
        # 11 picks it is 99/10 = 9.9 — NOT 10. Rounding that walk gives
        # 0, 9.9, 19.8, ... 99, which is why the middle entries drift one below
        # the round numbers. Getting this wrong by using n/limit would leave the
        # last pick at index 90 and never sample the outermost ring at all.
        out = viewer_server._sample_evenly(list(range(100)), 11)
        self.assertEqual(out, [0, 10, 20, 30, 40, 50, 59, 69, 79, 89, 99])

    def test_the_sample_spans_the_radius_rather_than_hugging_spawn(self):
        # Positions ordered nearest-first, as noise_census returns them.
        radii = list(range(0, 9000, 1))
        out = viewer_server._sample_evenly(radii, 50)
        # A nearest-first truncation would top out at 49; the stride must reach
        # the far edge of the dimension.
        self.assertGreater(max(out), 8900)
        self.assertLess(min(out), 100)

    def test_degenerate_limits(self):
        self.assertEqual(viewer_server._sample_evenly([1, 2, 3], 1), [1])
        self.assertEqual(viewer_server._sample_evenly([1, 2, 3], 0), [])


class NoisePositionsTests(unittest.TestCase):
    """GET /noise-census: real positions for ONE candidate.

    The panel emits `data-pos` only where a grid position is real, which on a
    modern overworld is nowhere — the winner carried 0 positional rows against
    77 rows whose only spatial hint was a band, 53 of them starting at 0. The
    positions exist in noise_placement; they are simply too big to bank per
    candidate (62k for the largest shipped dimension), so they are computed on
    demand here and cached in memory only.
    """

    def setUp(self):
        import json
        import shutil
        import tempfile
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.cfg = self.tmp / "config"
        (self.cfg / "dimensions").mkdir(parents=True)
        # A real type-defaults file is required — resolve_groups returns {}
        # without one, and a test that passed on an empty census would prove
        # nothing at all.
        src = (Path(__file__).resolve().parents[2]
               / "config" / "custom-dimensions" / "structure-type-defaults.json")
        if not src.exists():
            self.skipTest("structure-type-defaults.json not found")
        shutil.copy2(src, self.cfg / "structure-type-defaults.json")
        (self.cfg / "dimensions" / "d1.json").write_text(json.dumps({
            "name": "d1", "type": "overworld",
            "biomes": ["minecraft:plains"],
            # 1024 blocks = 64 chunks: small enough to census in milliseconds,
            # big enough that every group lands several sites.
            "borders": {"player": 1024, "generation": 1024},
        }))
        (self.cfg / "dimensions" / "suppressed.json").write_text(json.dumps({
            "name": "suppressed", "type": "overworld",
            "biomes": ["minecraft:plains"],
            "structureDensity": "none",
            "borders": {"player": 1024, "generation": 1024},
        }))
        viewer_server._CENSUS_POS_CACHE.clear()
        del viewer_server._CENSUS_POS_ORDER[:]

    def _positions(self, dim="d1", seed=12345, **kw):
        return viewer_server.noise_positions(str(self.cfg), dim, seed, **kw)

    def test_returns_block_positions_grouped(self):
        out = self._positions()
        self.assertEqual(out["dim"], "d1")
        self.assertEqual(out["radiusChunks"], 64)
        self.assertTrue(out["groups"], "an overworld resolves seven noise groups")
        for group, entry in out["groups"].items():
            self.assertGreater(entry["count"], 0, group)
            self.assertEqual(entry["shown"], len(entry["pos"]))
            for pos in entry["pos"]:
                # schemaVersion 2: [blockX, blockZ, "ns:id"] or [blockX, blockZ, None]
                # when no structure_pools.json is available.
                self.assertEqual(len(pos), 3, f"position must be [bx, bz, id]: {pos}")
                x, z = pos[0], pos[1]
                # BLOCKS, not chunks — the client's coordinate model is the one
                # data-pos already uses. x16 happens server-side so there is
                # only one place to get it wrong.
                self.assertEqual(x % 16, 0)
                self.assertEqual(z % 16, 0)
                self.assertLessEqual(abs(x), 64 * 16)
                self.assertLessEqual(abs(z), 64 * 16)

    def test_positions_match_noise_census_exactly(self):
        """The endpoint must not become a second placement implementation.

        Positions are [blockX, blockZ, assigned_id]. Without a
        structure_pools.json in the test dir the id is None for every
        position; count and block coordinates still match the census.
        """
        import noise_placement
        src = viewer_server._dim_source(str(self.cfg), "d1")
        defaults = noise_placement.load_type_defaults(self.cfg)
        census = noise_placement.noise_census(12345, "d1", src, defaults)
        out = self._positions(per_group=10_000)
        self.assertEqual(sorted(out["groups"]), sorted(census))
        for group, positions in census.items():
            self.assertEqual(out["groups"][group]["count"], len(positions))
            # Block coordinates match; the third element is the assigned
            # structure id (None here because no pools file is available).
            got_coords = [[p[0], p[1]] for p in out["groups"][group]["pos"]]
            self.assertEqual(
                got_coords,
                [[cx * 16, cz * 16] for cx, cz in positions])

    def test_per_group_caps_the_sample_not_the_count(self):
        out = self._positions(per_group=5)
        for group, entry in out["groups"].items():
            self.assertLessEqual(entry["shown"], 5, group)
            # The true total is still reported, so the UI can say what it left
            # out instead of implying the world is emptier than it is.
            self.assertGreaterEqual(entry["count"], entry["shown"], group)

    def test_a_different_seed_is_a_different_layout(self):
        a = self._positions(seed=1)
        b = self._positions(seed=2)
        self.assertNotEqual(a["groups"], b["groups"])

    def test_suppressed_dimension_says_so_rather_than_looking_empty(self):
        out = self._positions(dim="suppressed")
        self.assertTrue(out["suppressed"])
        self.assertEqual(out["groups"], {})

    def test_unknown_dimension_raises_keyerror_for_a_404(self):
        with self.assertRaises(KeyError):
            self._positions(dim="does_not_exist")

    def test_cached_per_dim_seed_and_fingerprint(self):
        first = self._positions()
        self.assertIs(self._positions(), first, "a repeat must not recompute")
        # A placement-config edit must invalidate it. structureDensity is
        # generation-affecting, so the noise fingerprint moves with it.
        import json
        p = self.cfg / "dimensions" / "d1.json"
        data = json.loads(p.read_text())
        data["structureDensity"] = "dense"
        p.write_text(json.dumps(data))
        self.assertIsNot(self._positions(), first)

    def test_cache_is_bounded(self):
        for seed in range(viewer_server._CENSUS_POS_MAX + 6):
            self._positions(seed=seed)
        self.assertLessEqual(len(viewer_server._CENSUS_POS_CACHE),
                             viewer_server._CENSUS_POS_MAX)


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


class CensusEndpointTests(unittest.TestCase):
    """GET /census/<dim>/<seed>: exact positions from the census sidecar.

    The sidecar is written by census_task during scoring; the endpoint loads
    it, derives per-structure summaries from the banked spawn, and reports
    staleness when the fingerprint drifts.
    """

    def setUp(self):
        import gzip
        import json
        import shutil
        import tempfile
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

        self.cfg = self.tmp / "config"
        (self.cfg / "dimensions").mkdir(parents=True)
        (self.cfg / "dimensions" / "d1.json").write_text(json.dumps({
            "name": "d1", "type": "overworld",
            "biomes": ["minecraft:plains"],
            "borders": {"player": 1024, "generation": 1024},
        }))

        self.seedtest = self.tmp / "seedtest"
        (self.seedtest / "candidates").mkdir(parents=True)
        import candidates as cmod
        cmod.set_bank_root(str(self.seedtest))

        self.seed_str = "9007199254740993"
        self.spawn_x, self.spawn_z = 64, -128

        store = {"configHash": "h", "candidates": {
            self.seed_str: {
                "measurements": {"spawn_x": self.spawn_x, "spawn_z": self.spawn_z},
                "scores": {"h": {"total": 95}},
            },
        }, "rejected": {}, "abandoned": {}, "winner": None, "winnerPinned": False}
        (self.seedtest / "candidates" / "d1.json").write_text(json.dumps(store))

        self.fp = "abc123def456"
        self.sidecar_doc = {
            "schemaVersion": 2,
            "fp": self.fp,
            "poolHash": "pool999",
            "groups": {
                "settlements": {
                    "ids": ["minecraft:village", "minecraft:pillager_outpost"],
                    "positions": [
                        [4, 8, 0],
                        [10, -5, 1],
                        [20, 20, 0],
                    ],
                },
                "loot": {
                    "ids": ["minecraft:buried_treasure"],
                    "positions": [
                        [0, 0, 0],
                    ],
                },
            },
        }
        census_dir = self.seedtest / "census-positions" / "d1"
        census_dir.mkdir(parents=True)
        raw = json.dumps(self.sidecar_doc).encode()
        dest = census_dir / ("%s.json.gz" % self.seed_str)
        with gzip.open(str(dest), "wb") as gz:
            gz.write(raw)

    def _endpoint(self, dim="d1", seed=None):
        if seed is None:
            seed = self.seed_str
        return viewer_server.census_endpoint(
            str(self.seedtest), str(self.cfg), dim, seed)

    def test_happy_path(self):
        payload, status = self._endpoint()
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["dim"], "d1")
        self.assertEqual(payload["seed"], self.seed_str)
        self.assertEqual(payload["spawnX"], self.spawn_x)
        self.assertEqual(payload["spawnZ"], self.spawn_z)
        self.assertIn("settlements", payload["groups"])
        self.assertIn("loot", payload["groups"])
        self.assertEqual(payload["groups"]["settlements"]["count"], 3)
        self.assertEqual(payload["groups"]["loot"]["count"], 1)
        self.assertEqual(payload["totalPositions"], 4)
        self.assertNotIn("stale", payload)

    def test_positions_are_block_coordinates(self):
        payload, _ = self._endpoint()
        positions = payload["groups"]["settlements"]["positions"]
        for bx, bz, sid in positions:
            self.assertEqual(bx % 16, 0, "positions must be chunk-origin blocks")
            self.assertEqual(bz % 16, 0)
            self.assertIn(sid, ["minecraft:village", "minecraft:pillager_outpost"])

    def test_seed_as_string_integrity(self):
        """A seed > 2^53 must round-trip exactly as a string."""
        payload, status = self._endpoint()
        self.assertEqual(status, 200)
        self.assertEqual(payload["seed"], self.seed_str)
        self.assertIsInstance(payload["seed"], str)

    def test_by_structure_summary(self):
        """Per-structure counts and nearest distances must match an
        independent derivation from the same fixture positions."""
        import math
        payload, _ = self._endpoint()
        bs = payload["byStructure"]
        self.assertIn("minecraft:village", bs)
        self.assertEqual(bs["minecraft:village"]["count"], 2)
        self.assertEqual(bs["minecraft:pillager_outpost"]["count"], 1)
        self.assertEqual(bs["minecraft:buried_treasure"]["count"], 1)

        for sid, entry in bs.items():
            expected_count = 0
            expected_nearest = float("inf")
            for group_name, gdata in self.sidecar_doc["groups"].items():
                ids = gdata["ids"]
                for pos in gdata["positions"]:
                    resolved_sid = ids[pos[2]]
                    if resolved_sid == sid:
                        expected_count += 1
                        bx, bz = pos[0] * 16, pos[1] * 16
                        dx = bx - self.spawn_x
                        dz = bz - self.spawn_z
                        dist = math.sqrt(float(dx) * dx + float(dz) * dz)
                        if dist < expected_nearest:
                            expected_nearest = dist
            self.assertEqual(entry["count"], expected_count,
                             "count mismatch for %s" % sid)
            self.assertAlmostEqual(entry["nearestBlocks"],
                                   round(expected_nearest, 1), places=1,
                                   msg="nearest mismatch for %s" % sid)

    def test_by_structure_carries_nearest_position_and_group(self):
        """nearestPos is the nearest instance's block coordinates — the fact
        that makes a bearing stateable without the positions payload."""
        import math
        payload, _ = self._endpoint()
        bs = payload["byStructure"]
        for sid, entry in bs.items():
            best = None
            best_dist = float("inf")
            best_group = None
            for group_name, gdata in self.sidecar_doc["groups"].items():
                ids = gdata["ids"]
                for pos in gdata["positions"]:
                    if ids[pos[2]] != sid:
                        continue
                    bx, bz = pos[0] * 16, pos[1] * 16
                    dx, dz = bx - self.spawn_x, bz - self.spawn_z
                    dist = math.sqrt(float(dx) * dx + float(dz) * dz)
                    if dist < best_dist:
                        best_dist, best, best_group = dist, [bx, bz], group_name
            self.assertEqual(entry["nearestPos"], best,
                             "nearestPos mismatch for %s" % sid)
            self.assertEqual(entry["group"], best_group,
                             "group mismatch for %s" % sid)

    def test_summary_variant_drops_positions_but_not_counts(self):
        """?summary=1 must be the same payload minus groups[].positions —
        identical counts, byStructure, totalPositions. Different counts
        between the two variants would be the two-truths bug reborn."""
        full, status_full = self._endpoint()
        payload, status = viewer_server.census_endpoint(
            str(self.seedtest), str(self.cfg), "d1", self.seed_str,
            summary=True)
        self.assertEqual((status_full, status), (200, 200))
        for group, entry in payload["groups"].items():
            self.assertNotIn("positions", entry, group)
            self.assertEqual(entry["count"],
                             full["groups"][group]["count"], group)
        self.assertEqual(payload["byStructure"], full["byStructure"])
        self.assertEqual(payload["totalPositions"], full["totalPositions"])

    def test_missing_sidecar_returns_404(self):
        payload, status = self._endpoint(dim="d1", seed="999")
        self.assertEqual(status, 404)
        self.assertIn("error", payload)

    def test_stale_fingerprint_flagged(self):
        """When the sidecar's fp differs from the dimension's current noise
        fingerprint, the response must include stale: true."""
        import json
        src = (Path(__file__).resolve().parents[2]
               / "config" / "custom-dimensions" / "structure-type-defaults.json")
        if not src.exists():
            self.skipTest("structure-type-defaults.json not found")
        import shutil
        shutil.copy2(src, self.cfg / "structure-type-defaults.json")
        import dimension_profiles
        dimension_profiles.set_noise_defaults_dir(str(self.cfg))
        payload, status = self._endpoint()
        self.assertEqual(status, 200)
        self.assertTrue(payload.get("stale"),
                        "sidecar fp 'abc123def456' should not match the "
                        "dimension's current noise fingerprint")

    def test_path_traversal_rejected(self):
        payload, status = self._endpoint(dim="d1", seed="../../../etc/passwd")
        self.assertEqual(status, 400)
        self.assertIn("error", payload)

    def test_invalid_dim_slug_rejected(self):
        payload, status = self._endpoint(dim="UPPER")
        self.assertEqual(status, 400)
        payload2, status2 = self._endpoint(dim="has spaces")
        self.assertEqual(status2, 400)

    def test_hyphenated_slug_maps_to_underscore(self):
        """Dimension slugs in URLs use hyphens; names use underscores."""
        import gzip
        import json
        (self.cfg / "dimensions" / "the_nether.json").write_text(json.dumps({
            "name": "the_nether", "type": "nether",
            "biomes": ["minecraft:nether_wastes"],
            "borders": {"player": 1024, "generation": 1024},
        }))
        store = {"configHash": "h", "candidates": {
            "12345": {"measurements": {"spawn_x": 0, "spawn_z": 0},
                      "scores": {"h": {"total": 50}}},
        }, "rejected": {}, "abandoned": {}, "winner": None, "winnerPinned": False}
        (self.seedtest / "candidates" / "the_nether.json").write_text(json.dumps(store))
        census_dir = self.seedtest / "census-positions" / "the_nether"
        census_dir.mkdir(parents=True)
        doc = {"schemaVersion": 2, "fp": "xx", "poolHash": "yy",
               "groups": {"dungeons": {"ids": ["a:b"], "positions": [[1, 2, 0]]}}}
        with gzip.open(str(census_dir / "12345.json.gz"), "wb") as gz:
            gz.write(json.dumps(doc).encode())
        payload, status = viewer_server.census_endpoint(
            str(self.seedtest), str(self.cfg), "the-nether", "12345")
        self.assertEqual(status, 200)
        self.assertEqual(payload["dim"], "the_nether")
