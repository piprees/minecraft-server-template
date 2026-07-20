#!/usr/bin/env python3
"""Tests for map_renderer.py — pure-Python top-down map renderer.

Uses a fixture region file (test_fixtures/overworld_region/r.0.0.mca)
containing 9 real chunks from an overworld world. Tests verify:
- Region file parsing (chunk decompression, heightmap extraction)
- Height palette colour mapping (water, land, mountains)
- Full render pipeline (valid PNG output with correct dimensions)
- Edge cases (missing chunks, missing region files, void dimensions)
"""

import os
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from map_renderer import (
    _decompress_chunk, _extract_heightmap, _extract_ypos,
    _height_colour, _render_chunk_columns, block_colour,
    read_chunk, render_area, render_candidate, unpack_heightmap,
    write_png,
)

FIXTURE_DIR = Path(__file__).resolve().parent / "test_fixtures"
OVERWORLD_REGION = FIXTURE_DIR / "overworld_world" / "region" / "r.0.0.mca"
OVERWORLD_WORLD = FIXTURE_DIR / "overworld_world"


class TestUnpackHeightmap(unittest.TestCase):
    def test_round_trip(self):
        """Pack 256 heights into 37 longs, unpack, verify."""
        heights = [64 + (i % 128) for i in range(256)]
        longs = []
        for i in range(0, 256, 7):
            packed = 0
            for j in range(7):
                if i + j < 256:
                    packed |= (heights[i + j] & 0x1FF) << (j * 9)
            longs.append(packed)
        result = unpack_heightmap(longs)
        self.assertEqual(result[:256], heights)

    def test_zero_heights(self):
        longs = [0] * 37
        result = unpack_heightmap(longs)
        self.assertEqual(len(result), 256)
        self.assertTrue(all(h == 0 for h in result))


class TestHeightColour(unittest.TestCase):
    def test_water_is_blue(self):
        r, g, b = _height_colour(40, "overworld")
        self.assertGreater(b, r)
        self.assertGreater(b, g)

    def test_grassland_is_green(self):
        r, g, b = _height_colour(80, "overworld")
        self.assertGreater(g, r)
        self.assertGreater(g, b)

    def test_mountain_is_grey(self):
        r, g, b = _height_colour(180, "overworld")
        self.assertGreater(r, 100)

    def test_nether_is_red_toned(self):
        r, g, b = _height_colour(80, "nether")
        self.assertGreater(r, g)
        self.assertGreater(r, b)

    def test_end_void_is_dark(self):
        r, g, b = _height_colour(0, "end")
        self.assertLess(r, 30)
        self.assertLess(g, 30)


class TestBlockColour(unittest.TestCase):
    def test_known_blocks(self):
        self.assertIsNotNone(block_colour("minecraft:grass_block"))
        self.assertIsNotNone(block_colour("minecraft:stone"))
        self.assertIsNotNone(block_colour("minecraft:water"))
        self.assertIsNotNone(block_colour("minecraft:netherrack"))

    def test_air_is_none(self):
        self.assertIsNone(block_colour("minecraft:air"))

    def test_unknown_with_keyword(self):
        c = block_colour("modname:fancy_leaves")
        self.assertIsNotNone(c)
        self.assertEqual(c, (59, 122, 26))  # generic leaves colour


class TestWritePng(unittest.TestCase):
    def test_valid_png(self):
        pixels = [(255, 0, 0, 255)] * (16 * 16)
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            write_png(pixels, 16, 16, f.name)
            data = Path(f.name).read_bytes()
            os.unlink(f.name)
        self.assertTrue(data.startswith(b"\x89PNG\r\n\x1a\n"))
        # IHDR chunk
        ihdr_len = struct.unpack(">I", data[8:12])[0]
        self.assertEqual(data[12:16], b"IHDR")
        w, h = struct.unpack(">II", data[16:24])
        self.assertEqual(w, 16)
        self.assertEqual(h, 16)

    def test_pixel_content(self):
        pixels = [(128, 64, 32, 255)] * 4
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            write_png(pixels, 2, 2, f.name)
            data = Path(f.name).read_bytes()
            os.unlink(f.name)
        self.assertGreater(len(data), 50)


@unittest.skipUnless(OVERWORLD_REGION.exists(), "fixture region file missing")
class TestRegionParsing(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.region_data = OVERWORLD_REGION.read_bytes()

    def test_decompress_existing_chunk(self):
        raw = _decompress_chunk(self.region_data, 0, 0)
        self.assertIsNotNone(raw)
        self.assertGreater(len(raw), 1000)

    def test_decompress_missing_chunk(self):
        raw = _decompress_chunk(self.region_data, 31, 31)
        self.assertIsNone(raw)

    def test_extract_heightmap(self):
        raw = _decompress_chunk(self.region_data, 0, 0)
        heights = _extract_heightmap(raw)
        self.assertIsNotNone(heights)
        self.assertEqual(len(heights), 256)
        self.assertTrue(all(isinstance(h, int) for h in heights))
        nonzero = sum(1 for h in heights if h > 0)
        self.assertGreater(nonzero, 200)

    def test_extract_ypos(self):
        raw = _decompress_chunk(self.region_data, 0, 0)
        ypos = _extract_ypos(raw)
        self.assertEqual(ypos, -4)  # 1.18+ default

    def test_read_chunk(self):
        chunk = read_chunk(self.region_data, 0, 0)
        self.assertIsNotNone(chunk)
        self.assertIn("heights", chunk)
        self.assertIn("yPos", chunk)
        self.assertEqual(len(chunk["heights"]), 256)

    def test_read_chunk_missing(self):
        chunk = read_chunk(self.region_data, 31, 31)
        self.assertIsNone(chunk)

    def test_render_chunk_columns(self):
        chunk = read_chunk(self.region_data, 0, 0)
        columns = _render_chunk_columns(chunk)
        self.assertIsNotNone(columns)
        self.assertEqual(len(columns), 256)
        # Each column is ((r, g, b), height)
        for col, h in columns:
            self.assertEqual(len(col), 3)
            self.assertTrue(all(0 <= c <= 255 for c in col))

    def test_heights_in_realistic_range(self):
        chunk = read_chunk(self.region_data, 0, 0)
        heights = chunk["heights"]
        ypos = chunk["yPos"]
        for h in heights:
            abs_y = h - 1 + ypos * 16
            self.assertGreater(abs_y, -64)
            self.assertLess(abs_y, 320)


@unittest.skipUnless(OVERWORLD_REGION.exists(), "fixture region file missing")
class TestFullRender(unittest.TestCase):
    def test_render_small_area(self):
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            ok = render_area(
                str(OVERWORLD_WORLD),
                "",  # overworld = region/ directly
                0, 0, 48, 48, f.name, "overworld")
            self.assertTrue(ok)
            data = Path(f.name).read_bytes()
            os.unlink(f.name)
        self.assertTrue(data.startswith(b"\x89PNG"))
        # Should have real terrain, not just void
        self.assertGreater(len(data), 500)

    def test_render_has_multiple_colours(self):
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            render_area(
                str(OVERWORLD_WORLD),
                "", 0, 0, 48, 48, f.name, "overworld")
            data = Path(f.name).read_bytes()
            os.unlink(f.name)
        # Decompress and count unique colours
        pos = 8
        colours = set()
        while pos < len(data):
            length = struct.unpack(">I", data[pos:pos + 4])[0]
            tag = data[pos + 4:pos + 8]
            if tag == b"IHDR":
                body = data[pos + 8:pos + 8 + length]
                w, h = struct.unpack(">II", body[:8])
            if tag == b"IDAT":
                body = data[pos + 8:pos + 8 + length]
                raw = zlib.decompress(body)
                stride = w * 4 + 1
                for row in range(h):
                    off = row * stride + 1
                    for col in range(w):
                        colours.add(raw[off + col * 4:off + col * 4 + 4])
                break
            pos += 12 + length
        self.assertGreater(len(colours), 5,
                           "Render should have multiple terrain colours, not solid void")

    def test_render_missing_region(self):
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            ok = render_area(
                "/nonexistent/world", "", 0, 0, 16, 16,
                f.name, "overworld")
            self.assertFalse(ok)
            data = Path(f.name).read_bytes()
            os.unlink(f.name)
        # Should produce a valid void PNG
        self.assertTrue(data.startswith(b"\x89PNG"))

    def test_nether_family(self):
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            # Nether rendering on overworld data — should still produce valid output
            ok = render_area(
                str(OVERWORLD_WORLD),
                "", 0, 0, 16, 16, f.name, "nether")
            data = Path(f.name).read_bytes()
            os.unlink(f.name)
        self.assertTrue(data.startswith(b"\x89PNG"))

    def test_end_family(self):
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            ok = render_area(
                str(OVERWORLD_WORLD),
                "", 0, 0, 16, 16, f.name, "end")
            data = Path(f.name).read_bytes()
            os.unlink(f.name)
        self.assertTrue(data.startswith(b"\x89PNG"))


if __name__ == "__main__":
    unittest.main()
