#!/usr/bin/env python3
"""Java <-> Python biome parity gate (precision-plan.md section 6.2).

The sampler mirrors vanilla's multinoise algorithm faithfully, but the
only proof is comparison against the live server. This test diffs
biome-at-coordinate dumps from /customdim sample-biome-grid (the Java
getBiome call chain at quart y=16, block y=64) against the Python
build_from_spec sampler, POINT FOR POINT, ZERO TOLERANCE.

Ground truth: CSV files in scripts/seed/testdata/biome_grid/, each a
dump from a running server via sample-biome-grid at radius=768 step=64.
Regenerate with:

    ./scripts/seed/refresh-biome-fixtures.sh

The seed for each dimension comes from the corresponding census fixture
in scripts/seed/testdata/census/ (both sets are dumped from the same
server session). If the census fixture is absent, the dimension is
skipped.

With no biome grid fixtures present, every comparison test skips (so the
suite stays green on a machine with no server). Self-consistency tests
always run.
"""

import json
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts/seed"))
sys.path.insert(0, str(REPO / "scripts"))

import stack_version  # noqa: E402

STACK_VERSION = stack_version.stack_version()

BIOME_GRID_DIR = Path(__file__).resolve().parent / "testdata" / "biome_grid"
CENSUS_DIR = Path(__file__).resolve().parent / "testdata" / "census"
CONFIG_DIR = REPO / "config/custom-dimensions"
BIOME_PARAMS = Path(__file__).resolve().parent / "biome_params.json"


def _parse_biome_grid_csv(path):
    """Parse a biome grid CSV fixture.

    Returns (stack_version_str, list_of_(x, z, biome_id)).
    Comment lines (# ...) are skipped; the stackVersion is extracted
    from the header comment.
    """
    version = None
    points = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#"):
            if "stackVersion=" in line:
                for part in line[2:].split():
                    if part.startswith("stackVersion="):
                        version = part.split("=", 1)[1]
                        break
            continue
        parts = line.split(",", 2)
        if len(parts) == 3:
            points.append((int(parts[0]), int(parts[1]), parts[2]))
    return version, points


def _census_seed(dim_slug):
    """Read the world seed from the census fixture for this dimension.

    The census and biome-grid fixtures are dumped from the same server
    session, so the seed matches. Returns None if absent.
    """
    # Census filename: namespace__slug.json (e.g. adventure__the_overgrowth.json)
    for f in CENSUS_DIR.glob("*__%s.json" % dim_slug):
        try:
            doc = json.loads(f.read_text())
            return doc.get("seed")
        except (json.JSONDecodeError, OSError):
            continue
    return None


def biome_grid_files():
    if not BIOME_GRID_DIR.is_dir():
        return []
    return sorted(BIOME_GRID_DIR.glob("*.csv"))


class TestBiomeParity(unittest.TestCase):
    """Diffs real server biome grids against the Python sampler."""

    @classmethod
    def setUpClass(cls):
        cls.files = biome_grid_files()
        if not cls.files:
            raise unittest.SkipTest(
                "no biome grid fixtures in %s — regenerate with:\n"
                "  ./scripts/seed/refresh-biome-fixtures.sh"
                % BIOME_GRID_DIR)
        if not BIOME_PARAMS.exists():
            raise unittest.SkipTest(
                "biome_params.json not present (CI or first checkout)")

    def test_every_grid_matches_the_sampler(self):
        """Rebuild each dimension's sampler from its config via
        build_from_spec and compare biome-at-coordinate against every
        point in the fixture, zero tolerance."""
        from biome_sampler import build_from_spec, sampler_spec, load_noise_configs
        from dimension_profiles import load_config

        config = load_config(str(CONFIG_DIR))
        noise_configs = load_noise_configs()

        compared = 0
        points_checked = 0

        for path in self.files:
            basename = path.stem  # e.g. adventure__the_overgrowth
            parts = basename.split("__", 1)
            if len(parts) != 2:
                continue
            _ns, slug = parts

            with self.subTest(fixture=path.name):
                # stackVersion staleness check (mirrors test_noise_parity.py).
                stamped, points = _parse_biome_grid_csv(path)
                if not stack_version.is_dev(STACK_VERSION):
                    self.assertEqual(
                        stamped, STACK_VERSION,
                        "%s was dumped by stack %s but this one is %s — the "
                        "fixture is stale, not divergent. Re-dump it:\n"
                        "  ./scripts/seed/refresh-biome-fixtures.sh"
                        % (path.name, stamped, STACK_VERSION))
                else:
                    self.assertIsNotNone(
                        stamped,
                        "%s carries no stackVersion — it predates stamping. "
                        "Re-dump it:\n"
                        "  ./scripts/seed/refresh-biome-fixtures.sh"
                        % path.name)

                self.assertGreater(
                    len(points), 0,
                    "%s contains no data points" % path.name)

                # Seed from the census fixture (same server session).
                seed = _census_seed(slug)
                if seed is None:
                    self.skipTest(
                        "no census fixture for %s — cannot determine the "
                        "world seed. Regenerate both fixture sets:\n"
                        "  ./scripts/seed/refresh-census-fixtures.sh %s\n"
                        "  ./scripts/seed/refresh-biome-fixtures.sh %s"
                        % (slug, slug, slug))

                # Find the dimension config to build the sampler spec.
                dim_entry = _find_dim_entry(config, slug)
                if dim_entry is None:
                    self.skipTest(
                        "no dimension config for %s in %s"
                        % (slug, CONFIG_DIR / "dimensions"))

                from dimension_profiles import build_profile
                profile = build_profile(dim_entry, config)
                spec = sampler_spec(profile)
                sampler = build_from_spec(
                    seed, spec, str(BIOME_PARAMS), noise_configs)

                depth_exact = getattr(sampler, "depth_exact", False)
                # When the sampler wraps a PatchedBiomeSampler, depth_exact
                # lives on the delegate.
                if hasattr(sampler, "delegate"):
                    depth_exact = getattr(sampler.delegate, "depth_exact",
                                          depth_exact)

                # Count every fixture that reaches comparison, not just
                # those that match cleanly.
                compared += 1

                mismatches = []
                for x, z, java_biome in points:
                    py_biome = sampler.biome_at(x, z)
                    if py_biome != java_biome:
                        mismatches.append((x, z, java_biome, py_biome))

                if mismatches:
                    sample = mismatches[:5]
                    lines = ["  (%d, %d): Java=%s Python=%s"
                             % (x, z, jb, pb)
                             for x, z, jb, pb in sample]
                    detail = "\n".join(lines)
                    suffix = ""
                    if not depth_exact:
                        suffix = ("\n\n  NOTE: this dimension's depth axis "
                                  "is NOT exact (depth_exact=False). "
                                  "Mismatches may stem from the depth "
                                  "climate value diverging between Java "
                                  "(router evaluation at y=64) and Python "
                                  "(fixed 0.0). The depth evaluator must "
                                  "be extended to this family before biome "
                                  "claims are exact.")
                    self.fail(
                        "%s: %d of %d points diverged.\n"
                        "First %d mismatching points:\n%s%s"
                        % (path.name, len(mismatches), len(points),
                           len(sample), detail, suffix))

                points_checked += len(points)

        self.assertGreater(compared, 0,
                           "no biome grid fixture could be compared")
        print("\n  biome parity: %d dimensions, %d points — exact match"
              % (compared, points_checked))


def _find_dim_entry(config, slug):
    """Find a dimension or world entry by slug in the loaded config."""
    for dim in config.get("dimensions", []):
        if dim.get("name") == slug:
            return dim
    for world in config.get("worlds", []):
        if world.get("name") == slug:
            return world
    return None


if __name__ == "__main__":
    unittest.main()
