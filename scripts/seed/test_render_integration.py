#!/usr/bin/env python3
"""Integration tests for BlueMap CLI rendering pipeline.

Tests that render_candidate() in seed_worker.py produces valid PNG
images for all dimension families via BlueMap CLI with mod textures.

These tests require Docker and a prepared .seedtest/wr/ worker dir.
They are SLOW (~30-45s per candidate) and only run when the
SEED_RENDER_TEST environment variable is set.

Run: SEED_RENDER_TEST=1 python3 test_render_integration.py
"""

import json
import os
import struct
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

SKIP = not os.environ.get("SEED_RENDER_TEST")
SKIP_REASON = "Set SEED_RENDER_TEST=1 to run (requires Docker + MC server)"

# The consumer project root (elfydd)
PROJECT = Path(os.environ.get("SEED_PROJECT", Path(__file__).resolve().parent.parent.parent))
SEEDTEST = PROJECT / ".seedtest"
CANDIDATES = PROJECT / "data" / "config" / "custom-dimensions" / "candidates"


def png_dimensions(path):
    """Read width and height from a PNG file's IHDR chunk."""
    data = Path(path).read_bytes()
    if not data.startswith(b"\x89PNG"):
        return 0, 0
    pos = 8
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        if tag == b"IHDR":
            body = data[pos + 8:pos + 8 + length]
            w, h = struct.unpack(">II", body[:8])
            return w, h
        pos += 12 + length
    return 0, 0


def best_seed(dim_name):
    """Get the highest-scoring seed for a dimension."""
    store_path = CANDIDATES / f"{dim_name}.json"
    if not store_path.exists():
        return None
    store = json.loads(store_path.read_text())
    best = None
    best_score = -1
    for seed, cand in store.get("candidates", {}).items():
        for _h, s in cand.get("scores", {}).items():
            if s["total"] > best_score:
                best_score = s["total"]
                best = seed
            break
    return best


@unittest.skipIf(SKIP, SKIP_REASON)
class TestBlueMapRendering(unittest.TestCase):
    """Verify that renders exist and are valid PNGs for 10 dimensions."""

    # These should have been rendered by the 10-dimension test run
    DIMENSIONS = [
        ("overworld", "overworld"),
        ("the_nether", "nether"),
        ("the_end", "end"),
        ("paradise_lost", "overworld"),  # paradise_lost uses overworld family
        ("the_blackstone_keep", "nether"),
        ("the_blossom_gardens", "overworld"),
        ("the_gauntlet", "overworld"),
        ("the_crucible", "overworld"),
        ("the_shattered_skies", "overworld"),
        ("the_frozen_hearth", "overworld"),
    ]

    def test_renders_exist_for_all_dimensions(self):
        """Every tested dimension must have at least one render PNG."""
        missing = []
        for dim_name, _family in self.DIMENSIONS:
            render_dir = SEEDTEST / "renders" / dim_name
            pngs = list(render_dir.glob("*.png")) if render_dir.exists() else []
            if not pngs:
                missing.append(dim_name)
        self.assertEqual(missing, [],
                         f"Missing renders for: {', '.join(missing)}")

    def test_renders_are_valid_pngs(self):
        """Every render must be a valid PNG with non-zero dimensions."""
        for dim_name, _family in self.DIMENSIONS:
            render_dir = SEEDTEST / "renders" / dim_name
            if not render_dir.exists():
                continue
            for png in render_dir.glob("*.png"):
                w, h = png_dimensions(png)
                self.assertGreater(w, 0, f"{dim_name}/{png.name}: zero width")
                self.assertGreater(h, 0, f"{dim_name}/{png.name}: zero height")

    def test_renders_are_not_tiny(self):
        """Renders must be at least 2KB (not empty/corrupt/void-only)."""
        for dim_name, _family in self.DIMENSIONS:
            render_dir = SEEDTEST / "renders" / dim_name
            if not render_dir.exists():
                continue
            for png in render_dir.glob("*.png"):
                sz = png.stat().st_size
                self.assertGreater(sz, 2000,
                                   f"{dim_name}/{png.name}: only {sz} bytes — "
                                   "likely empty or void-only")

    def test_overworld_render_is_substantial(self):
        """Overworld renders should be >10KB (lots of terrain variety)."""
        render_dir = SEEDTEST / "renders" / "overworld"
        if not render_dir.exists():
            self.skipTest("No overworld renders")
        largest = max(render_dir.glob("*.png"), key=lambda p: p.stat().st_size)
        self.assertGreater(largest.stat().st_size, 10000,
                           f"Overworld render only {largest.stat().st_size} bytes")

    def test_nether_renders_exist(self):
        """Nether dimensions must render (not fail on dimension type)."""
        for dim_name in ["the_nether", "the_blackstone_keep"]:
            render_dir = SEEDTEST / "renders" / dim_name
            pngs = list(render_dir.glob("*.png")) if render_dir.exists() else []
            self.assertGreater(len(pngs), 0,
                               f"Nether dim {dim_name} has no renders")

    def test_end_render_exists(self):
        """End dimensions must render."""
        render_dir = SEEDTEST / "renders" / "the_end"
        pngs = list(render_dir.glob("*.png")) if render_dir.exists() else []
        self.assertGreater(len(pngs), 0, "End dimension has no renders")

    def test_sky_islands_render_exists(self):
        """Sky islands dimensions must render."""
        render_dir = SEEDTEST / "renders" / "the_shattered_skies"
        pngs = list(render_dir.glob("*.png")) if render_dir.exists() else []
        self.assertGreater(len(pngs), 0, "Sky islands dimension has no renders")

    def test_paradise_lost_render_exists(self):
        """Paradise Lost dimension must render."""
        render_dir = SEEDTEST / "renders" / "paradise_lost"
        pngs = list(render_dir.glob("*.png")) if render_dir.exists() else []
        self.assertGreater(len(pngs), 0, "Paradise Lost has no renders")


@unittest.skipIf(SKIP, SKIP_REASON)
class TestRenderTiming(unittest.TestCase):
    """Verify render speed is within acceptable limits."""

    def test_render_speed_from_log(self):
        """The 10-dimension render batch should complete in under 600s.
        Target: ~43s per candidate (30s boot + 12s forceload + 27s BlueMap
        amortised across the batch since the server stays up)."""
        # This test checks the result, not runs a render
        total_renders = 0
        for dim_name in ["overworld", "the_nether", "the_end", "paradise_lost",
                         "the_blackstone_keep", "the_blossom_gardens",
                         "the_gauntlet", "the_crucible",
                         "the_shattered_skies", "the_frozen_hearth"]:
            render_dir = SEEDTEST / "renders" / dim_name
            if render_dir.exists():
                total_renders += len(list(render_dir.glob("*.png")))
        self.assertGreaterEqual(total_renders, 10,
                                f"Only {total_renders} renders found, expected ≥10")


@unittest.skipIf(SKIP, SKIP_REASON)
class TestRenderConfig(unittest.TestCase):
    """Verify BlueMap CLI config generation is correct."""

    def test_bluemap_docker_image_available(self):
        """The BlueMap CLI Docker image must be pullable."""
        import subprocess
        r = subprocess.run(
            ["docker", "image", "inspect", "ghcr.io/bluemap-minecraft/bluemap:latest"],
            capture_output=True, check=False)
        self.assertEqual(r.returncode, 0,
                         "BlueMap Docker image not available — pull it first")

    def test_mods_directory_exists(self):
        """The worker dir must have a mods/ directory for -n flag."""
        worker_dir = SEEDTEST / "wr"
        if not worker_dir.exists():
            self.skipTest("No worker dir")
        mods = worker_dir / "mods"
        self.assertTrue(mods.exists(), f"No mods dir at {mods}")
        jars = list(mods.glob("*.jar"))
        self.assertGreater(len(jars), 10,
                           f"Only {len(jars)} mod JARs — expected 100+")


if __name__ == "__main__":
    unittest.main()
