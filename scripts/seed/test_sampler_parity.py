#!/usr/bin/env python3
"""test_sampler_parity.py — every consumer of BiomeSampler must build the SAME
world the roller measured.

WHY THIS FILE EXISTS

A dimension's biome layout is decided by five inputs: the noise family, the
ordered biome list, the per-biome `parameters` hypercubes (Tier 3), the biome
patches, and the checkerboard grid scale. Several places in the pipeline need a
sampler, and each one used to assemble those inputs by hand:

    fast_roller._build_sampler        measures the candidate (the SCORE)
    biome_renderer.render_biome_map   draws the thumbnail and the hi-res map
    viewer-server._survey_dim         builds biome_survey (the DETAIL PANEL)
    terrain_survey.survey_task        measures water/relief (a SCORED input)

The pipeline has been bitten by this twice. `resolve_noise_family` and
TestNoiseFamilyResolution in test_biome_pipeline.py exist because the renderer
resolved the noise FAMILY differently from the roller and drew paradise_lost
dimensions as overworlds (2026-07-28). The lesson was written down for one
input; when per-biome parameters were added later, three of the four call sites
were never taught about them (2026-08-01, TROUBLESHOOTING.md#t20).

The failure is silent and total. A listed biome with no climate parameters in
the table is "foreign" (TROUBLESHOOTING.md#t19): it receives every unclaimed
climate region round-robin, so when it is the ONLY foreign biome it receives
all of them and the dimension renders as one biome from edge to edge. The score
said the world was a five-biome mixture — correctly — while the picture beside
it was a single flat colour and the detail panel reported four of the five as
"not found". Nothing errored and nothing warned.

So the inputs are now derived ONCE by biome_sampler.sampler_spec() and the
sampler is built ONLY by build_from_spec(). These tests hold that line.

RUN:  python3 -B -m unittest discover -s scripts/seed -p 'test_*.py'
"""
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT_DIR = Path(__file__).resolve().parent
BIOME_PARAMS = SCRIPT_DIR / "biome_params.json"
HAS_PARAMS = BIOME_PARAMS.exists()
SKIP_NO_PARAMS = "biome_params.json not present (CI or first checkout)"

# A Tier-3 dimension in the shape monolith_from_dir() produces: an ordered
# `biome` CSV plus a `biomeParameters` map. Modelled on the shipped
# the_wuthering_wisteria — biomes banded on weirdness alone, one of which
# (natures_spirit:wisteria_forest) has NO climate parameters in the biome table
# and is therefore the dimension's only "foreign" biome when the parameters are
# dropped. That is what makes it the sharpest possible probe: with the
# parameters it is one band of several; without them it takes the entire
# round-robin pool and the world becomes a monoculture.
WISTERIA_BIOMES = [
    "natures_spirit:wisteria_forest",
    "terralith:sakura_grove",
    "minecraft:cherry_grove",
    "terralith:sakura_valley",
    "minecraft:meadow",
]

WISTERIA_PARAMS = {
    "natures_spirit:wisteria_forest": {"weirdness": [-2.0, -0.06]},
    "terralith:sakura_grove": {"weirdness": [-0.06, 0.02]},
    "minecraft:cherry_grove": {"weirdness": [0.02, 0.1]},
    "terralith:sakura_valley": {"weirdness": [0.1, 0.18]},
    "minecraft:meadow": {"weirdness": [0.18, 2.0]},
}

#: A seed from a real bank. Any seed exhibits the bug; a real one keeps the
#: numbers checkable against the viewer.
TIER3_SEED = 9223077296128276798


def wisteria_dim(name="tier3_probe"):
    """The dimension entry as load_config() hands it to every consumer."""
    return {
        "name": name,
        "dimensionId": f"adventure:{name}",
        "type": "multi_biome",
        "seed": TIER3_SEED,
        "biome": ",".join(WISTERIA_BIOMES),
        "biomeParameters": dict(WISTERIA_PARAMS),
        "borders": {"player": 512, "generation": 2048},
        "difficulty": {"mobMultiplier": 0.0, "hostileSpawning": False},
        "structureDensity": "none",
        "structures": {"mode": "allow", "list": []},
        "seedRoll": {"mood": "serene"},
    }


def wisteria_config(dim):
    """A minimal load_config()-shaped wrapper around one dimension."""
    return {"namespace": "adventure", "dimensions": [dim],
            "portals": [], "worlds": []}


def biome_mix(sampler, radius=512, step=32):
    """{biome: share of the sampled play area} — what a reader of the render is
    actually judging."""
    counts = {}
    for x in range(-radius, radius + 1, step):
        for z in range(-radius, radius + 1, step):
            b = sampler.biome_at(x, z)
            counts[b] = counts.get(b, 0) + 1
    total = sum(counts.values()) or 1
    return {b: n / total for b, n in counts.items()}


class RecordingSampler:
    """A BiomeSampler stand-in that records how it was constructed.

    The bug under test is not in the sampler — it is in what its callers
    forget to hand it. Recording the constructor arguments IS the assertion.
    """

    calls = []

    def __init__(self, seed, biome_params_path, noise_config=None,
                 biome_filter=None, family=None, param_overrides=None):
        RecordingSampler.calls.append({
            "seed": seed, "biome_filter": biome_filter, "family": family,
            "param_overrides": param_overrides,
        })
        self.seed = seed
        self._entries = [(b, (), 0.0) for b in (biome_filter or [])]

    @classmethod
    def reset(cls):
        cls.calls = []

    def biome_at(self, x, z):
        return self._entries[0][0] if self._entries else "unknown"

    def biome_and_climate(self, x, z):
        return self.biome_at(x, z), self.sample_climate(x, z)

    def sample_climate(self, x, z):
        return {"temperature": 0.0, "humidity": 0.0, "continentalness": 0.0,
                "erosion": 0.0, "depth": 0.0, "weirdness": 0.0}

    def locate_biome(self, biome_id, radius=6400, step=64,
                     origin_x=0, origin_z=0):
        return (0, 0, 0)

    def spawn_filter(self, namesake_biomes, radius=768, step=64):
        b = self.biome_at(0, 0)
        return (b, 0, 0, 0) if b in set(namesake_biomes) else (None, -1, 0, 0)


# ---------------------------------------------------------------------------
# 1. The mixing algorithm itself — pinning it is what makes the caller tests
#    below unambiguous rather than merely red.
# ---------------------------------------------------------------------------
class TestMixingSemantics(unittest.TestCase):
    """build_mixed_entries: what `parameters` is FOR."""

    @classmethod
    def setUpClass(cls):
        from biome_source_mixing import build_mixed_entries
        cls.build = staticmethod(build_mixed_entries)
        cls.table = json.loads(BIOME_PARAMS.read_text()) if HAS_PARAMS else []

    @unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
    def test_with_parameters_the_dimension_is_exactly_its_biome_list(self):
        entries = self.build(self.table, WISTERIA_BIOMES,
                             family_filter="overworld",
                             param_overrides=WISTERIA_PARAMS)
        self.assertEqual([e["biome"] for e in entries], WISTERIA_BIOMES,
                         "one explicit hypercube per listed biome, in config order")

    @unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
    def test_without_parameters_the_foreign_biome_takes_the_whole_pool(self):
        """The T19 failure mode, stated as an assertion."""
        entries = self.build(self.table, WISTERIA_BIOMES,
                             family_filter="overworld", param_overrides=None)
        counts = {}
        for e in entries:
            counts[e["biome"]] = counts.get(e["biome"], 0) + 1
        foreign = "natures_spirit:wisteria_forest"
        self.assertGreater(
            counts.get(foreign, 0), 1000,
            "without parameters the foreign biome should hold the entire pool")
        self.assertGreater(
            counts[foreign] / sum(counts.values()), 0.9,
            "and therefore over 90% of the dimension's climate space")

    def test_an_invalid_axis_falls_back_rather_than_crashing(self):
        """Mirrors the Java: warn and ignore, never throw. A malformed
        parameters block must not take a dimension out of the roll."""
        bad = {"minecraft:meadow": {"weirdness": [0.5, -0.5]}}  # min > max
        entries = self.build([], ["minecraft:meadow"], param_overrides=bad)
        self.assertEqual(entries, [],
                         "invalid parameters are dropped, not raised")


# ---------------------------------------------------------------------------
# 2. One spec, one constructor.
# ---------------------------------------------------------------------------
class TestSamplerSpec(unittest.TestCase):
    """sampler_spec() must carry every input that changes the layout."""

    @classmethod
    def setUpClass(cls):
        from biome_sampler import sampler_spec
        from dimension_profiles import build_profile
        cls.sampler_spec = staticmethod(sampler_spec)
        cls.build_profile = staticmethod(build_profile)

    def _spec(self, **overrides):
        dim = wisteria_dim()
        dim.update(overrides)
        return self.sampler_spec(self.build_profile(dim, wisteria_config(dim)))

    def test_it_carries_the_tier3_parameters(self):
        self.assertEqual(self._spec()["parameters"], WISTERIA_PARAMS)

    def test_it_keeps_the_biome_list_in_config_order(self):
        """Foreign-biome round-robin follows config order in the mod
        (LinkedHashSet). A set here would scramble the layout."""
        self.assertEqual(self._spec()["biomes"], WISTERIA_BIOMES)

    def test_it_carries_biome_patches(self):
        patch = [{"biome": "minecraft:plains", "x": 0, "z": 0, "radius": 64}]
        self.assertEqual(self._spec(biomePatches=patch)["patches"], patch)

    def test_it_carries_the_checkerboard_scale(self):
        spec = self._spec(type="checkerboard", checkerboardScale=4)
        self.assertEqual(spec["dim_type"], "checkerboard")
        self.assertEqual(spec["checkerboard_scale"], 4)

    def test_the_type_decides_the_noise_family_not_the_profile_family(self):
        """A custom paradise_lost:paradise_lost dimension resolves family
        "overworld" — correct for scoring, catastrophic for sampling."""
        spec = self._spec(type="paradise_lost:paradise_lost")
        self.assertEqual(spec["noise_family"], "paradise_lost")

    @unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
    def test_build_from_spec_applies_the_parameters(self):
        from biome_sampler import build_from_spec
        sampler = build_from_spec(TIER3_SEED, self._spec(), str(BIOME_PARAMS))
        self.assertEqual({e[0] for e in sampler._entries}, set(WISTERIA_BIOMES),
                         "an explicit hypercube per listed biome and nothing else")

    @unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
    def test_build_from_spec_wraps_patches(self):
        from biome_sampler import PatchedBiomeSampler, build_from_spec
        spec = self._spec(biomePatches=[
            {"biome": "minecraft:plains", "x": 0, "z": 0, "radius": 64}])
        sampler = build_from_spec(TIER3_SEED, spec, str(BIOME_PARAMS))
        self.assertIsInstance(sampler, PatchedBiomeSampler)


class TestEveryCallerPassesTheParameters(unittest.TestCase):
    """A sampler built without `param_overrides` is a DIFFERENT WORLD.

    Each test patches BiomeSampler with a recorder and drives one real call
    site. The assertion is always the same: the parameters the config carries
    reached the sampler.
    """

    def setUp(self):
        RecordingSampler.reset()

    @unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
    def test_roller_passes_them(self):
        import biome_sampler
        import fast_roller
        from biome_sampler import load_noise_configs
        from dimension_profiles import build_profile

        dim = wisteria_dim()
        profile = build_profile(dim, wisteria_config(dim))
        with mock.patch.object(biome_sampler, "BiomeSampler", RecordingSampler):
            fast_roller._build_sampler(TIER3_SEED, profile, str(BIOME_PARAMS),
                                       load_noise_configs())
        self.assertEqual(len(RecordingSampler.calls), 1)
        self.assertEqual(RecordingSampler.calls[0]["param_overrides"],
                         WISTERIA_PARAMS)

    @unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
    def test_renderer_passes_them(self):
        """biome_renderer.render_biome_map draws every thumbnail and every
        hi-res preview. Without the parameters it draws a monoculture beside a
        score computed from the mixture."""
        import biome_sampler
        import biome_renderer
        from biome_sampler import sampler_spec
        from dimension_profiles import build_profile

        dim = wisteria_dim()
        spec = sampler_spec(build_profile(dim, wisteria_config(dim)))
        with tempfile.TemporaryDirectory() as tmp:
            out = str(Path(tmp) / "probe.png")
            with mock.patch.object(biome_sampler, "BiomeSampler",
                                   RecordingSampler):
                biome_renderer.render_biome_map(
                    TIER3_SEED, str(BIOME_PARAMS), out,
                    size=16, blocks_per_pixel=64, sample_resolution=8,
                    sampler_spec=spec)
        self.assertEqual(RecordingSampler.calls[0]["param_overrides"],
                         WISTERIA_PARAMS)

    @unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
    def test_the_batch_render_task_carries_the_whole_spec(self):
        """Accepting the argument is half the fix; the batch task tuple has to
        carry it too, or every rendered candidate still loses it."""
        import inspect
        import biome_renderer
        src = inspect.getsource(biome_renderer.batch_render)
        self.assertIn("build_sampler_spec(profile)", src,
                      "batch_render must build the spec from the same profile "
                      "the roller scores through")
        self.assertIn("spec", inspect.getsource(biome_renderer._render_one),
                      "_render_one must unpack the spec from its task tuple")

    def test_viewer_biome_survey_passes_them(self):
        """viewer-server._survey_dim builds `biome_survey`, which the detail
        panel reads — so a survey built from the wrong world makes the panel
        contradict the score sitting beside it."""
        import importlib.util
        import biome_sampler
        import candidates as cmod

        spec = importlib.util.spec_from_file_location(
            "viewer_server", str(SCRIPT_DIR / "viewer-server.py"))
        viewer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(viewer)

        dim = wisteria_dim()
        config = wisteria_config(dim)
        # The bank root is process-global and sticky. Leaving it pointed at a
        # deleted temp directory breaks every later test in the same run, which
        # is a nastier failure to diagnose than the one under test here.
        saved_root = cmod._BANK_ROOT
        with tempfile.TemporaryDirectory() as tmp:
            cmod.set_bank_root(tmp)
            self.addCleanup(cmod.set_bank_root, saved_root)
            cdir = Path(tmp) / "candidates"
            cdir.mkdir(parents=True, exist_ok=True)
            store = cmod.empty_store()
            store["configHash"] = "deadbeef"
            store["candidates"][str(TIER3_SEED)] = {
                "measurements": {"errors": "0"},
                "scores": {"deadbeef": {"total": 90.0}},
            }
            cmod.save_store(cdir / f"{dim['name']}.json", store)
            with mock.patch.object(biome_sampler, "BiomeSampler",
                                   RecordingSampler):
                viewer._survey_dim(dim["name"], dim, config, {}, cdir,
                                   str(BIOME_PARAMS), 5)
        self.assertTrue(RecordingSampler.calls,
                        "_survey_dim built no sampler at all")
        self.assertEqual(RecordingSampler.calls[0]["param_overrides"],
                         WISTERIA_PARAMS)

    def test_terrain_survey_passes_them(self):
        """terrain_survey feeds the water figure into the TERRAIN component, so
        a survey of the wrong biome source is a wrong score, not just a wrong
        picture."""
        import biome_sampler
        import terrain_survey
        from biome_sampler import sampler_spec
        from dimension_profiles import build_profile

        dim = wisteria_dim()
        spec = sampler_spec(build_profile(dim, wisteria_config(dim)))
        task = ("tier3_probe", TIER3_SEED, {
            "biome_params": str(BIOME_PARAMS), "sampler": spec,
            "configured_biomes": list(spec["biomes"]), "radius": 512,
            "is_void": False, "has_continentalness": True})
        with mock.patch.object(biome_sampler, "BiomeSampler", RecordingSampler):
            terrain_survey.survey_task(task)
        self.assertEqual(RecordingSampler.calls[0]["param_overrides"],
                         WISTERIA_PARAMS)

    def test_the_scorer_hands_the_terrain_survey_a_spec(self):
        """The spec dict is built in score-dimensions.ensure_terrain_surveys;
        the key has to exist there or survey_task has nothing to forward."""
        import importlib.util
        import inspect
        spec = importlib.util.spec_from_file_location(
            "score_dimensions", str(SCRIPT_DIR / "score-dimensions.py"))
        sd = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(sd)
        src = inspect.getsource(sd.ensure_terrain_surveys)
        self.assertIn("sampler_spec(profile)", src)
        self.assertIn('"sampler": sampler', src)


# ---------------------------------------------------------------------------
# 3. What the reader actually sees.
# ---------------------------------------------------------------------------
@unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
class TestTheTwoWorldsDiffer(unittest.TestCase):
    """The size of the discrepancy, in the units a human reads."""

    @classmethod
    def setUpClass(cls):
        from biome_sampler import BiomeSampler, load_noise_configs
        nc = load_noise_configs()["overworld"]
        cls.with_params = BiomeSampler(
            TIER3_SEED, str(BIOME_PARAMS), noise_config=nc,
            family="overworld", biome_filter=list(WISTERIA_BIOMES),
            param_overrides=dict(WISTERIA_PARAMS))
        cls.without = BiomeSampler(
            TIER3_SEED, str(BIOME_PARAMS), noise_config=nc,
            family="overworld", biome_filter=list(WISTERIA_BIOMES))

    def test_the_parameterised_world_is_a_mixture(self):
        mix = biome_mix(self.with_params)
        self.assertEqual(set(mix), set(WISTERIA_BIOMES),
                         "every configured biome should appear in the play area")
        self.assertLess(max(mix.values()), 0.75,
                        "no single biome should dominate a banded dimension")

    def test_the_unparameterised_world_is_a_monoculture(self):
        mix = biome_mix(self.without)
        self.assertEqual(len(mix), 1,
                         "dropping the parameters collapses the world to the "
                         "single foreign biome")
        self.assertEqual(next(iter(mix)), "natures_spirit:wisteria_forest")

    def test_the_measured_distances_belong_to_the_parameterised_world(self):
        for biome in WISTERIA_BIOMES:
            hit = self.with_params.locate_biome(biome, radius=1512, step=256)
            self.assertIsNotNone(
                hit, f"{biome} should be locatable in the parameterised world")
            self.assertLessEqual(hit[0], 1512)

    def test_the_two_worlds_disagree_inside_the_player_border(self):
        """The discrepancy sits exactly where the player is.

        Out to the locate horizon the broken world is 99% one biome, and what
        little else it contains lies BEYOND the 512-block player border. So the
        render, the survey and the score were not describing the same world in
        the only region that can be visited — and none of them said so.
        """
        wide = biome_mix(self.without, radius=1512, step=64)
        self.assertGreater(wide["natures_spirit:wisteria_forest"], 0.95)
        for biome in WISTERIA_BIOMES:
            if biome == "natures_spirit:wisteria_forest":
                continue
            hit = self.without.locate_biome(biome, radius=1512, step=256)
            self.assertTrue(
                hit is None or hit[0] > 512,
                f"{biome} should be absent from the play area in the broken "
                f"world, found at {hit}")


if __name__ == "__main__":
    unittest.main()
