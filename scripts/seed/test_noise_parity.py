#!/usr/bin/env python3
"""Java <-> Python parity for noise placement (spike task F4).

The hardest gate in the plan: every position the mod places must be a
position the roller predicts, and vice versa, with zero tolerance.

Ground truth comes from `/customdim structure-census <dim>` on a running
server, which dumps the LIVE StructurePlacementCalculator — the same objects
chunk generation and /locate consult. Copy those files into
scripts/seed/testdata/census/ and this compares them position for position.

    docker exec -i mc rcon-cli "customdim structure-census adventure:the_overgrowth"
    docker cp mc:/data/config/custom-dimensions/census/. \\
        scripts/seed/testdata/census/

With no census files present the comparison tests skip (so the suite stays
green on a machine with no server), but the self-consistency tests below
always run — they pin the properties that would have to break for parity to
break.
"""

import json
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts/seed"))

import noise_placement as npl  # noqa: E402

CENSUS_DIR = Path(__file__).resolve().parent / "testdata" / "census"
CONFIG_DIR = REPO / "config/custom-dimensions"


sys.path.insert(0, str(REPO / "scripts"))
import stack_version  # noqa: E402

STACK_VERSION = stack_version.stack_version()


def census_files():
    if not CENSUS_DIR.is_dir():
        return []
    return sorted(CENSUS_DIR.glob("*.json"))


class TestJavaPythonParity(unittest.TestCase):
    """Diffs real census output against the mirror."""

    @classmethod
    def setUpClass(cls):
        cls.files = census_files()
        if not cls.files:
            raise unittest.SkipTest(
                "no census files in %s — run /customdim structure-census and "
                "copy them in (see this module's docstring)" % CENSUS_DIR)
        cls.defaults = npl.load_type_defaults(CONFIG_DIR)
        if cls.defaults is None:
            raise unittest.SkipTest("structure-type-defaults.json not found")

    def test_every_census_matches_the_mirror(self):
        """Rebuilds each group's field from the RESOLVED inputs the census
        records, so a failure means the placement maths diverged — not that
        the two sides read the dimension config differently. Config
        resolution is covered separately, on both sides."""
        checked = 0
        groups_checked = 0
        positions_checked = 0
        for path in self.files:
            with self.subTest(census=path.name):
                doc = json.loads(path.read_text())
                dimension = doc["dimension"]
                # A missing stamp is as stale as a wrong one — it means a jar
                # that predates stamping produced the fixture. A dev stack has
                # no release identity, so there is nothing to compare.
                stamped = doc.get("stackVersion")
                if not stack_version.is_dev(STACK_VERSION):
                    self.assertEqual(
                        stamped, STACK_VERSION,
                        "%s was dumped by stack %s but this one is %s — the "
                        "fixture is stale, not divergent. Re-dump it:\n"
                        "  ./scripts/seed/refresh-census-fixtures.sh"
                        % (path.name, stamped, STACK_VERSION))
                else:
                    self.assertIsNotNone(
                        stamped,
                        "%s carries no stackVersion — it predates stamping. "
                        "Re-dump it:\n"
                        "  ./scripts/seed/refresh-census-fixtures.sh"
                        % path.name)
                for group, block in sorted((doc.get("groups") or {}).items()):
                    profile = npl.profile_from_string(block["profile"])
                    self.assertIsNotNone(
                        profile, "%s/%s: unknown profile %r"
                        % (dimension, group, block["profile"]))
                    index = npl.NoiseFieldIndex(
                        block["noiseSeed"], profile, block["exclusion"],
                        block["radial"], block["radiusChunks"],
                        block["spawnChunkX"], block["spawnChunkZ"])

                    expected = index.positions()
                    actual = [tuple(p) for p in block["positions"]]

                    if actual != expected:
                        got_set, exp_set = set(actual), set(expected)
                        only_java = sorted(got_set - exp_set)[:5]
                        only_py = sorted(exp_set - got_set)[:5]
                        detail = ("ORDER differs but membership matches — the "
                                  "sort key has drifted"
                                  if got_set == exp_set else
                                  "only in Java:   %s\n  only in Python: %s"
                                  % (only_java, only_py))
                        self.fail(
                            "%s/%s diverged: %d Java vs %d Python positions.\n  %s"
                            % (dimension, group, len(actual), len(expected), detail))

                    self.assertEqual(index.spacing, block["spacing"],
                                     "%s/%s: spacing differs" % (dimension, group))

                    # Structure identity parity (schemaVersion >= 2 fixtures
                    # carry per-position ids via 3-element arrays).
                    schema = doc.get("schemaVersion", 1)
                    if schema >= 2 and actual and len(actual[0]) >= 3:
                        pool = block.get("structures") or {}
                        sorted_pool = sorted(pool.items())
                        ps = npl.pick_seed(block["noiseSeed"])
                        for pos in actual:
                            cx, cz, java_id = pos[0], pos[1], pos[2]
                            py_id = npl.resolve_structure(
                                sorted_pool, npl.priority(ps, cx, cz))
                            self.assertEqual(
                                py_id, java_id,
                                "%s/%s: id mismatch at (%d, %d): Java=%s Python=%s"
                                % (dimension, group, cx, cz, java_id, py_id))
                    elif schema < 2:
                        # Fixtures pre-date schemaVersion 2 — regenerate via
                        # refresh-census-fixtures.sh to enable id comparison.
                        pass

                    groups_checked += 1
                    positions_checked += len(actual)
                checked += 1
        self.assertGreater(checked, 0, "no census file could be compared")
        print("\n  F4: %d dimensions, %d groups, %d positions — exact match"
              % (checked, groups_checked, positions_checked))

    def test_config_resolution_agrees_with_the_census(self):
        """The other half: resolving a shipped config must produce the same
        profile/exclusion/radial the mod resolved. Separate from the maths so
        a failure points at one or the other, never both."""
        compared = 0
        for path in self.files:
            doc = json.loads(path.read_text())
            slug = doc["dimension"].split(":", 1)[1]
            config_path = CONFIG_DIR / "dimensions" / ("%s.json" % slug)
            if not config_path.exists():
                continue
            config = json.loads(config_path.read_text())
            resolved = npl.resolve_groups(config, self.defaults)
            actual = doc.get("groups") or {}
            with self.subTest(dimension=slug):
                # The census can hold FEWER groups than the plan resolves: the
                # mod skips a group whose pool comes out empty after biome
                # filtering (the boot log reports this as "groups=4/5"). A
                # jungle dimension resolving `maritime` and then finding no
                # ocean structures whose biomes it contains is normal, not a
                # divergence. What must never happen is the census holding a
                # group the plan did not resolve.
                unplanned = sorted(set(actual) - set(resolved))
                self.assertEqual(
                    unplanned, [],
                    "%s: census has group(s) the plan never resolved — the two "
                    "sides disagree about config" % slug)
                for group, block in actual.items():
                    settings = resolved[group]
                    self.assertEqual(settings["profile"].id, block["profile"], group)
                    self.assertEqual(settings["exclusion"], block["exclusion"], group)
                    self.assertEqual(settings["radial"], block["radial"], group)
                    compared += 1
        self.assertGreater(compared, 0, "no dimension config could be compared")


class TestMirrorSelfConsistency(unittest.TestCase):
    """Properties that must hold for parity to be possible at all.

    These run without a server, so a change that would break F4 fails here
    first rather than waiting for someone to re-dump a census.
    """

    def test_traversal_order_does_not_change_membership(self):
        """The placement rule is order-free by design.

        This is what let the Java side swap an O(r^3) ring walk for an O(r^2)
        scan without touching worldgen. If it ever stops holding, the two
        implementations can silently diverge on iteration order alone.
        """
        index = npl.NoiseFieldIndex(999, npl.NATURAL, 4, None, 48, 0, 0)
        positions = set(index.positions())

        # Recompute membership directly from the rule, chunk by chunk, in a
        # completely different order.
        scale = npl.frequency_scale(48)
        freq = npl.NATURAL.frequency * scale
        noise = npl.StructureNoise(999)
        eligible = set()
        for cx in range(-48, 49):
            for cz in range(-48, 49):
                if cx * cx + cz * cz > 48 * 48:
                    continue
                if noise.sample_chunk(cx, cz, freq) > npl.NATURAL.threshold:
                    eligible.add((cx, cz))

        rebuilt = set()
        for cx, cz in sorted(eligible, reverse=True):    # reverse order
            rank = npl.priority(999, cx, cz)
            key = npl.chunk_pos_to_long(cx, cz)
            best = True
            for ox in range(-4, 5):
                for oz in range(-4, 5):
                    if (ox, oz) == (0, 0) or ox * ox + oz * oz > 16:
                        continue
                    n = (cx + ox, cz + oz)
                    if n not in eligible:
                        continue
                    other = npl.priority(999, n[0], n[1])
                    if other > rank or (other == rank
                                        and npl.chunk_pos_to_long(*n) < key):
                        best = False
                        break
                if not best:
                    break
            if best:
                rebuilt.add((cx, cz))

        self.assertEqual(positions, rebuilt,
                         "membership depends on traversal order")

    def test_unsigned_rank_comparison(self):
        """A signed comparison would favour half the range systematically."""
        seen_high = seen_low = False
        for i in range(2000):
            r = npl.priority(1234, i, -i)
            self.assertGreaterEqual(r, 0, "priority must be unsigned")
            self.assertLess(r, 1 << 64)
            if r >= (1 << 63):
                seen_high = True
            else:
                seen_low = True
        self.assertTrue(seen_high and seen_low,
                        "priority never spanned both halves of the range")

    def test_chunk_pos_to_long_matches_vanilla(self):
        """Vanilla: (long)x & 0xFFFFFFFF | ((long)z & 0xFFFFFFFF) << 32."""
        for x, z in [(0, 0), (1, 0), (0, 1), (-1, 0), (0, -1), (-1, -1),
                     (1 << 20, -(1 << 20)), (2147483647, -2147483648)]:
            expected = (x & 0xFFFFFFFF) | ((z & 0xFFFFFFFF) << 32)
            if expected >= 1 << 63:
                expected -= 1 << 64
            self.assertEqual(npl.chunk_pos_to_long(x, z), expected, (x, z))

    def test_mix64_is_a_bijection_on_a_sample(self):
        values = {npl.mix64(i) for i in range(10000)}
        self.assertEqual(len(values), 10000, "mix64 collided")
        for v in list(values)[:100]:
            self.assertGreaterEqual(v, 0)
            self.assertLess(v, 1 << 64)

    def test_salt_of_handles_the_shipped_names(self):
        """Every shipped dimension and group name must salt distinctly."""
        names = [p.stem for p in (CONFIG_DIR / "dimensions").glob("*.json")]
        names += ["deco", "settlements", "dungeons", "landmarks",
                  "maritime", "endgame", "loot"]
        salts = {n: npl.salt_of(n) for n in names}
        self.assertEqual(len(set(salts.values())), len(salts),
                         "two names produced the same salt")


if __name__ == "__main__":
    unittest.main()
