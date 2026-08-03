#!/usr/bin/env python3
"""test_variety_scoring.py — the score must be able to tell a mixture from a
monoculture.

WHY THIS FILE EXISTS

the_wuthering_wisteria banked 1099 candidates. Nineteen scored exactly 92.50,
the viewer's top ten were ten of those nineteen, and every tile read "same as
winner" because all four components were identical across the plateau. The
dimension bands five biomes on weirdness and the real area shares across those
ten ran 11%-49% wisteria and 15%-49% meadow — genuinely different worlds,
scored identically.

Three properties of the model, each fixed here:

  1. `window_score` was FLAT-TOPPED: exactly 1.0 anywhere inside the band, so
     terrain stopped ordering candidates the moment most of them were
     acceptable — which for a serene 512-block pocket is nearly all of them.
     It now peaks at the centre and eases to 1.0 - WINDOW_COMFORT at the edges.
  2. `variety` measured only the DISTANCE TO THE NEAREST INSTANCE of each
     listed biome, on a 256-block locate grid, so it could not see proportions
     at all. It now reads per-biome AREA SHARES from the terrain survey, which
     resolves a biome at all 81 of its sample points anyway.
  3. The one term that would have noticed a monoculture was gated on
     `non_namesake_total > 1` and never fired for a dimension with five listed
     biomes and four spawn targets.

The proximity model is kept verbatim as the fallback: it is the whole component
for any candidate with no terrain survey yet, so a partially-surveyed bank
still ranks and no re-roll is needed to adopt the change.

RUN:  python3 -B -m unittest discover -s scripts/seed -p 'test_*.py'
"""
import importlib.util
import unittest
from pathlib import Path

from dimension_profiles import build_profile

SCRIPT_DIR = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "score_dimensions", str(SCRIPT_DIR / "score-dimensions.py"))
sd = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sd)

BIOMES = [
    "natures_spirit:wisteria_forest",
    "terralith:sakura_grove",
    "minecraft:cherry_grove",
    "terralith:sakura_valley",
    "minecraft:meadow",
]


def probe_dim(spawn_filter=None):
    dim = {
        "name": "tier3_probe", "dimensionId": "adventure:tier3_probe",
        "type": "multi_biome",
        "biome": ",".join(BIOMES),
        "borders": {"player": 512, "generation": 2048},
        "difficulty": {"mobMultiplier": 0.0, "hostileSpawning": False},
        "structureDensity": "none",
        "structures": {"mode": "allow", "list": []},
        "seedRoll": {"mood": "serene"},
    }
    if spawn_filter:
        dim["seedRoll"]["spawnFilter"] = list(spawn_filter)
    return dim


def probe_profile(spawn_filter=None):
    dim = probe_dim(spawn_filter)
    config = {"namespace": "adventure", "dimensions": [dim],
              "portals": [], "worlds": []}
    return build_profile(dim, config)


def flat_terrain_rows(height=74):
    rows = {}
    for r in range(3):
        for c in range(3):
            rows[f"height_r{r}c{c}"] = str(height + (r * 3 + c) * 4)
            rows[f"water_r{r}c{c}"] = "0"
    return rows


def candidate_rows(distances, shares=None, spawn=BIOMES[0]):
    rows = {"errors": "0", "spawn_biome": spawn, "spawn_x": "0",
            "spawn_z": "0", "spawn_filter_dist": "0"}
    rows.update(flat_terrain_rows())
    for biome, dist in distances.items():
        rows[f"biome_{biome}_dist"] = str(dist)
    if shares is not None:
        rows["_terrain"] = {"relief": 32.5, "grain": 2.9, "water": 0.0,
                            "land": 1.0, "reliefSpan": 512, "shares": shares}
    return rows


class TestWindowScoreHasAGradient(unittest.TestCase):
    """A flat-topped window carries no information inside the band."""

    def test_the_centre_beats_the_edges(self):
        lo, hi = 21.0, 88.0
        centre = sd.window_score((lo + hi) / 2, lo, hi)
        edge = sd.window_score(hi, lo, hi)
        self.assertAlmostEqual(centre, 1.0)
        self.assertAlmostEqual(edge, 1.0 - sd.WINDOW_COMFORT)
        self.assertGreater(centre, edge)

    def test_inside_still_beats_outside(self):
        """The gradient must not make an in-range value score worse than an
        out-of-range one — that would invert the meaning of the band."""
        lo, hi = 21.0, 88.0
        self.assertGreater(sd.window_score(hi, lo, hi),
                           sd.window_score(hi + 1, lo, hi))
        self.assertGreater(sd.window_score(lo, lo, hi),
                           sd.window_score(lo - 1, lo, hi))

    def test_it_never_exceeds_one(self):
        lo, hi = 0.0, 0.45
        for i in range(46):
            self.assertLessEqual(sd.window_score(i / 100.0, lo, hi), 1.0)


class TestVarietyReadsAreaShares(unittest.TestCase):
    """The component named "variety" now measures proportion, not just
    proximity."""

    def setUp(self):
        self.profile = probe_profile()
        # Identical nearest-instance distances for both worlds below: the only
        # thing that differs is how much of the play area each biome covers,
        # which is precisely what the old model could not see.
        self.distances = {b: 256 for b in BIOMES}
        self.distances[BIOMES[0]] = 0

    def _variety(self, shares):
        return sd.score_candidate(
            self.profile, candidate_rows(self.distances, shares))[1]["variety"]

    def test_a_monoculture_scores_below_a_mixture(self):
        monoculture = {BIOMES[0]: 0.96, BIOMES[1]: 0.01, BIOMES[2]: 0.01,
                       BIOMES[3]: 0.01, BIOMES[4]: 0.01}
        mixture = {BIOMES[0]: 0.40, BIOMES[1]: 0.10, BIOMES[2]: 0.15,
                   BIOMES[3]: 0.15, BIOMES[4]: 0.20}
        self.assertGreater(self._variety(mixture), self._variety(monoculture))

    def test_the_real_top_ten_are_no_longer_tied(self):
        """Two candidates from the live bank's 19-way plateau. They had
        identical scores; their actual layouts are nothing alike."""
        a = {BIOMES[0]: 0.489, BIOMES[1]: 0.063, BIOMES[2]: 0.107,
             BIOMES[3]: 0.126, BIOMES[4]: 0.214}
        b = {BIOMES[0]: 0.111, BIOMES[1]: 0.146, BIOMES[2]: 0.131,
             BIOMES[3]: 0.165, BIOMES[4]: 0.446}
        self.assertNotAlmostEqual(self._variety(a), self._variety(b), places=3)

    def test_the_rarest_biome_ranks_two_healthy_mixtures(self):
        """Presence is a threshold, so once most candidates clear it the term
        stops ranking — the flat-top problem again. The rarest biome's share is
        the continuous version of the same question, and it is what separates
        two worlds that both contain everything."""
        generous = {BIOMES[0]: 0.30, BIOMES[1]: 0.18, BIOMES[2]: 0.18,
                    BIOMES[3]: 0.17, BIOMES[4]: 0.17}
        thin = {BIOMES[0]: 0.60, BIOMES[1]: 0.03, BIOMES[2]: 0.12,
                BIOMES[3]: 0.12, BIOMES[4]: 0.13}
        self.assertGreater(self._variety(generous), self._variety(thin))

    def test_a_biome_below_the_presence_floor_does_not_count_as_present(self):
        sliver = {b: 0.001 for b in BIOMES[1:]}
        sliver[BIOMES[0]] = 0.996
        present = {b: 0.2 for b in BIOMES}
        self.assertGreater(self._variety(present), self._variety(sliver))

    def test_a_headline_biome_is_not_punished_below_the_cap(self):
        """An author is entitled to a dominant biome — wisteria's band is
        deliberately the widest of the five. The dominance term is a cap on
        "has effectively become single-biome", not a demand for an even split.
        """
        even = {b: 0.2 for b in BIOMES}
        headline = {BIOMES[0]: 0.5, BIOMES[1]: 0.1, BIOMES[2]: 0.1,
                    BIOMES[3]: 0.1, BIOMES[4]: 0.2}
        self.assertAlmostEqual(self._variety(even), self._variety(headline),
                               places=6)

    def test_past_the_cap_it_bites(self):
        capped = {BIOMES[0]: 0.75, BIOMES[1]: 0.07, BIOMES[2]: 0.06,
                  BIOMES[3]: 0.06, BIOMES[4]: 0.06}
        swallowed = {BIOMES[0]: 0.92, BIOMES[1]: 0.02, BIOMES[2]: 0.02,
                     BIOMES[3]: 0.02, BIOMES[4]: 0.02}
        self.assertGreater(self._variety(capped), self._variety(swallowed))


class TestVarietyFallsBackCleanly(unittest.TestCase):
    """No survey, no shares — and the bank still ranks."""

    def test_an_unsurveyed_candidate_uses_the_proximity_model(self):
        profile = probe_profile()
        near = sd.score_candidate(
            profile, candidate_rows({b: 0 for b in BIOMES}))[1]["variety"]
        far = sd.score_candidate(
            profile, candidate_rows({b: 512 for b in BIOMES}))[1]["variety"]
        self.assertGreater(near, far)

    def test_a_survey_from_a_different_config_is_ignored(self):
        """Shares naming none of the configured biomes describe another
        dimension. Scoring every configured biome as absent would be worse
        than falling back."""
        profile = probe_profile()
        rows = candidate_rows({b: 0 for b in BIOMES},
                              shares={"minecraft:desert": 1.0})
        with_junk = sd.score_candidate(profile, rows)[1]["variety"]
        without = sd.score_candidate(
            profile, candidate_rows({b: 0 for b in BIOMES}))[1]["variety"]
        self.assertAlmostEqual(with_junk, without)


class TestSpawnFilterFreesTheNamesakeComponent(unittest.TestCase):
    """`namesake` defaults to the first FOUR listed biomes, which pins it at
    1.0 for any dimension whose play area is mostly listed biomes — and makes
    four of five biomes "spawn targets" rather than variety."""

    def test_the_default_claims_four_biomes(self):
        profile = probe_profile()
        self.assertEqual(len(profile["namesake"]), 4)

    def test_an_explicit_spawn_filter_leaves_the_rest_as_variety(self):
        profile = probe_profile(spawn_filter=["minecraft:meadow"])
        self.assertEqual(profile["namesake"], ["minecraft:meadow"])
        rest = [b for b in profile["variety_biomes"]
                if b not in set(profile["namesake"])]
        self.assertEqual(len(rest), 4)

    def test_missing_the_filter_now_costs_something(self):
        profile = probe_profile(spawn_filter=["minecraft:meadow"])
        shares = {b: 0.2 for b in BIOMES}
        on_target = sd.score_candidate(
            profile, candidate_rows({b: 0 for b in BIOMES}, shares,
                                    spawn="minecraft:meadow"))[1]
        off_target = sd.score_candidate(
            profile, candidate_rows({b: 0 for b in BIOMES}, shares,
                                    spawn=BIOMES[0]))[1]
        self.assertEqual(on_target["namesake"], 1.0)
        self.assertLess(off_target["namesake"], 1.0)


class TestTerrainSurveyRecordsShares(unittest.TestCase):
    """The shares have to come from somewhere, and it has to be somewhere that
    covers EVERY candidate — a scored input cannot be enriched for the top N
    only, or the ranking compares surveyed candidates against unsurveyed ones.
    """

    def test_survey_records_a_share_per_configured_biome(self):
        import terrain_survey

        class OneBiome:
            """Half the world plains, half forest, split on x."""

            def biome_at(self, x, z):
                return "minecraft:plains" if x < 0 else "minecraft:forest"

            def sample_climate(self, x, z):
                return {"continentalness": 0.0, "erosion": 0.0}

        result = terrain_survey.survey(
            OneBiome(), 512,
            configured_biomes=["minecraft:plains", "minecraft:forest",
                               "minecraft:desert"])
        shares = result["shares"]
        self.assertEqual(set(shares), {"minecraft:plains", "minecraft:forest",
                                       "minecraft:desert"})
        self.assertEqual(shares["minecraft:desert"], 0.0,
                         "a configured biome that never appears is 0, not absent")
        self.assertAlmostEqual(shares["minecraft:plains"]
                               + shares["minecraft:forest"], 1.0, places=5)

    def test_the_fingerprint_is_keyed_on_the_stack(self):
        import sys
        from pathlib import Path
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
        import stack_version
        import terrain_survey
        self.assertTrue(terrain_survey.fingerprint("abc", 512).startswith(
            stack_version.cache_key() + ":"))
        self.assertNotEqual(terrain_survey.fingerprint("abc", 512),
                            "abc:512:9:2048")


if __name__ == "__main__":
    unittest.main()
