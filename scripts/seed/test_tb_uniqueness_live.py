"""Live-server parity gate for tb_regions.py's uniqueness mirror.

Compares the Python uniqueness Area against a truth table dumped from a
running server via /customdim tb-probe. The truth table records the LIVE
TerraBlender getUniqueness(qx, qy, qz) result at each grid point — the
exact value the MixinParameterList computes from the seed stored in
level.dat (GeneratorOptions.getSeed()), NOT the per-dimension seed that
ServerWorld.getSeed() returns.

Fixture: scripts/seed/testdata/tb_probe/adventure__the_dustbowl.csv
Regenerate: /customdim tb-probe adventure:the_dustbowl 768 64
            then copy the artefact here.

Skips cleanly when the fixture or biome_params.json is absent (CI,
first checkout).
"""

import csv
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts/seed"))

TB_PROBE_DIR = Path(__file__).resolve().parent / "testdata" / "tb_probe"
_ELFYDD_SEEDTEST = Path.home() / "Projects" / "elfydd" / ".seedtest"
BIOME_PARAMS = _ELFYDD_SEEDTEST / "biome_params.json"

# The world seed TerraBlender reads from level.dat via
# server.getSaveProperties().getGeneratorOptions().getSeed().
# This differs from /seed output when ServerWorldSeedMixin overrides
# ServerWorld.getSeed() with a per-dimension value.
LEVEL_DAT_SEED = 2806730136320717460


def _parse_tb_probe_csv(path):
    """Parse a tb-probe CSV fixture.

    Returns (dimension_id, seed, list of (x, z, uniqueness)).
    Comment lines (# ...) carry metadata; the data rows are x,z,uniqueness,biome.
    """
    dimension = None
    seed = None
    points = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#"):
            for part in line[2:].split():
                if part.startswith("dimension="):
                    dimension = part.split("=", 1)[1]
                elif part.startswith("seed="):
                    seed = int(part.split("=", 1)[1])
            continue
        if line.startswith("x,"):
            continue
        parts = line.split(",")
        if len(parts) >= 3:
            points.append((int(parts[0]), int(parts[1]), int(parts[2])))
    return dimension, seed, points


class TestTBUniquenessLive(unittest.TestCase):
    """Diffs the Python uniqueness mirror against a live-server tb-probe."""

    @classmethod
    def setUpClass(cls):
        if not TB_PROBE_DIR.is_dir():
            raise unittest.SkipTest(
                "no tb_probe fixtures in %s" % TB_PROBE_DIR)
        cls.files = sorted(TB_PROBE_DIR.glob("*.csv"))
        if not cls.files:
            raise unittest.SkipTest(
                "no tb_probe fixtures in %s" % TB_PROBE_DIR)
        if not BIOME_PARAMS.exists():
            raise unittest.SkipTest(
                "biome_params.json not present at %s — "
                "run dump-biome-params on the live server first"
                % BIOME_PARAMS)
        # No sentinel means the plain platform copy, which `--reset` restores
        # from scripts/seed/biome_params.json — "no live dump to compare
        # against", the same condition as the file being absent. Failing here
        # instead of skipping makes the repo uncommittable after any --reset,
        # because the pre-commit gate runs this suite (T29).
        from tb_regions import load_tb_regions
        if not load_tb_regions(str(BIOME_PARAMS)):
            raise unittest.SkipTest(
                "%s carries no _tbRegions sentinel (plain platform copy) — "
                "run dump-biome-params on the live server to compare"
                % BIOME_PARAMS)

    def test_uniqueness_matches_live_server(self):
        from tb_regions import _build_uniqueness, load_tb_regions
        from dimension_profiles import load_config

        all_tables = load_tb_regions(str(BIOME_PARAMS))   # non-empty: setUpClass skipped otherwise

        config = load_config(str(REPO / "config/custom-dimensions"))
        checked = 0

        for path in self.files:
            with self.subTest(fixture=path.name):
                dimension, _csv_seed, points = _parse_tb_probe_csv(path)
                self.assertGreater(len(points), 0,
                                   "%s contains no data points" % path.name)

                # Determine region type from the fixture's dimension config
                slug = ((dimension or "").split(":", 1)[-1]
                        if dimension
                        else path.stem.split("__", 1)[-1])
                region_type = "overworld"
                for d in config.get("dimensions", []) + config.get("worlds", []):
                    if d.get("name") == slug:
                        dt = d.get("type", "")
                        if dt in ("nether", "nether_islands"):
                            region_type = "nether"
                        break

                tb_regions = all_tables.get(region_type)
                if tb_regions is None:
                    self.skipTest(
                        "%s: no _tbRegions sentinel for type %s"
                        % (path.name, region_type))

                weights = [r["weight"] for r in tb_regions["regions"]]
                indices = [r["index"] for r in tb_regions["regions"]]
                region_size = 2 if region_type == "nether" else 3

                area = _build_uniqueness(
                    LEVEL_DAT_SEED, weights, indices,
                    region_size=region_size)

                mismatches = []
                for x, z, expected in points:
                    got = area.get(x >> 2, z >> 2)
                    if got != expected:
                        mismatches.append((x, z, expected, got))

                self.assertEqual(
                    len(mismatches), 0,
                    "%s: %d of %d points diverged. First 5:\n%s"
                    % (path.name, len(mismatches), len(points),
                       "\n".join("  (%d, %d): expected=%d got=%d"
                                 % (x, z, e, g)
                                 for x, z, e, g in mismatches[:5])))

                checked += len(points)

        print("\n  TB uniqueness parity: %d fixtures, %d points — exact match"
              % (len(self.files), checked))


if __name__ == "__main__":
    unittest.main()
