#!/usr/bin/env python3
"""map_renderer.py — Pure-Python top-down map renderer from region files.

Zero external dependencies. Reads .mca region files, extracts the top block
at each column via the WORLD_SURFACE heightmap + section block palettes,
and renders a 1px-per-block PNG using a built-in colour palette.

Handles overworld, nether (roof cut at y=127), end, and void dimensions.
~50-200ms per 144×144 block render depending on chunk complexity.
"""

import gzip
import struct
import zlib
from pathlib import Path


# ---------------------------------------------------------------------------
# Minimal NBT parser — just enough to extract heightmaps and sections.
# Matches the MC 1.21.1 NBT format (big-endian, gzip/zlib compressed).
# ---------------------------------------------------------------------------
TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class NBTReader:
    __slots__ = ("data", "pos")

    def __init__(self, data):
        self.data = data
        self.pos = 0

    def read(self, n):
        r = self.data[self.pos:self.pos + n]
        self.pos += n
        return r

    def byte(self):
        v = self.data[self.pos]
        self.pos += 1
        return v

    def short(self):
        v = struct.unpack_from(">h", self.data, self.pos)[0]
        self.pos += 2
        return v

    def ushort(self):
        v = struct.unpack_from(">H", self.data, self.pos)[0]
        self.pos += 2
        return v

    def int(self):
        v = struct.unpack_from(">i", self.data, self.pos)[0]
        self.pos += 4
        return v

    def long(self):
        v = struct.unpack_from(">q", self.data, self.pos)[0]
        self.pos += 8
        return v

    def float_(self):
        v = struct.unpack_from(">f", self.data, self.pos)[0]
        self.pos += 4
        return v

    def double(self):
        v = struct.unpack_from(">d", self.data, self.pos)[0]
        self.pos += 8
        return v

    def string(self):
        length = self.ushort()
        s = self.data[self.pos:self.pos + length].decode("utf-8", "replace")
        self.pos += length
        return s

    def skip_payload(self, tag_type):
        if tag_type == TAG_BYTE:
            self.pos += 1
        elif tag_type == TAG_SHORT:
            self.pos += 2
        elif tag_type == TAG_INT:
            self.pos += 4
        elif tag_type == TAG_LONG:
            self.pos += 8
        elif tag_type == TAG_FLOAT:
            self.pos += 4
        elif tag_type == TAG_DOUBLE:
            self.pos += 8
        elif tag_type == TAG_BYTE_ARRAY:
            self.pos += self.int()
        elif tag_type == TAG_STRING:
            self.pos += self.ushort()
        elif tag_type == TAG_LIST:
            el_type = self.byte()
            count = self.int()
            for _ in range(count):
                self.skip_payload(el_type)
        elif tag_type == TAG_COMPOUND:
            while True:
                t = self.byte()
                if t == TAG_END:
                    break
                self.pos += self.ushort()  # skip name
                self.skip_payload(t)
        elif tag_type == TAG_INT_ARRAY:
            self.pos += self.int() * 4
        elif tag_type == TAG_LONG_ARRAY:
            self.pos += self.int() * 8

    def read_compound(self, wanted_keys=None):
        result = {}
        while True:
            tag_type = self.byte()
            if tag_type == TAG_END:
                break
            name = self.string()
            if wanted_keys is not None and name not in wanted_keys:
                self.skip_payload(tag_type)
                continue
            result[name] = self._read_value(tag_type, name)
        return result

    def _read_value(self, tag_type, name=""):
        if tag_type == TAG_BYTE:
            return self.byte()
        if tag_type == TAG_SHORT:
            return self.short()
        if tag_type == TAG_INT:
            return self.int()
        if tag_type == TAG_LONG:
            return self.long()
        if tag_type == TAG_FLOAT:
            return self.float_()
        if tag_type == TAG_DOUBLE:
            return self.double()
        if tag_type == TAG_BYTE_ARRAY:
            n = self.int()
            return self.read(n)
        if tag_type == TAG_STRING:
            return self.string()
        if tag_type == TAG_LIST:
            el_type = self.byte()
            count = self.int()
            return [self._read_value(el_type) for _ in range(count)]
        if tag_type == TAG_COMPOUND:
            return self.read_compound()
        if tag_type == TAG_INT_ARRAY:
            n = self.int()
            return [struct.unpack_from(">i", self.data, self.pos + i * 4)[0]
                    for i in range(self._advance(n * 4) // 4)]
        if tag_type == TAG_LONG_ARRAY:
            n = self.int()
            return [struct.unpack_from(">q", self.data, self.pos + i * 8)[0]
                    for i in range(self._advance(n * 8) // 8)]
        return None

    def _advance(self, n):
        self.pos += n
        return n


# ---------------------------------------------------------------------------
# Region file reader
# ---------------------------------------------------------------------------
def _decompress_chunk(region_data, chunk_x, chunk_z):
    """Extract and decompress a chunk's raw NBT bytes from a region file."""
    idx = (chunk_x & 31) + (chunk_z & 31) * 32
    offset = struct.unpack(">I", b"\x00" + region_data[idx * 4:idx * 4 + 3])[0]
    if offset == 0:
        return None
    byte_offset = offset * 4096
    if byte_offset + 5 >= len(region_data):
        return None
    length = struct.unpack(">I", region_data[byte_offset:byte_offset + 4])[0]
    compression = region_data[byte_offset + 4]
    chunk_data = region_data[byte_offset + 5:byte_offset + 4 + length]
    try:
        if compression == 2:
            return zlib.decompress(chunk_data)
        if compression == 1:
            return gzip.decompress(chunk_data)
    except (zlib.error, OSError):
        pass
    return None


def _extract_heightmap(raw):
    """Extract WORLD_SURFACE heightmap directly from raw NBT bytes.
    Returns 256 height values or None."""
    marker = b"WORLD_SURFACE"
    idx = raw.find(marker)
    if idx < 0:
        return None
    # Verify it's a TAG_LONG_ARRAY (tag_type=12 before the name)
    name_start = idx - 2
    if name_start < 1 or raw[name_start - 1] != TAG_LONG_ARRAY:
        return None
    arr_start = idx + len(marker)
    if arr_start + 4 > len(raw):
        return None
    arr_count = struct.unpack(">i", raw[arr_start:arr_start + 4])[0]
    if arr_count < 37 or arr_start + 4 + arr_count * 8 > len(raw):
        return None
    longs = struct.unpack_from(f">{arr_count}q", raw, arr_start + 4)
    return unpack_heightmap(list(longs))


def _extract_ypos(raw):
    """Extract yPos (minimum section Y) from raw NBT bytes."""
    marker = b"yPos"
    idx = raw.find(marker)
    if idx < 0:
        return -4  # default for 1.18+
    name_start = idx - 2
    if name_start < 1:
        return -4
    tag = raw[name_start - 1]
    val_start = idx + len(marker)
    if tag == TAG_INT and val_start + 4 <= len(raw):
        return struct.unpack(">i", raw[val_start:val_start + 4])[0]
    return -4


def _parse_sections(raw):
    """Parse sections from raw NBT bytes using the full NBT parser.
    Returns a list of section dicts with Y and block_states, or []."""
    try:
        reader = NBTReader(raw)
        reader.byte()   # root tag type
        reader.string()  # root name
        # Walk the root compound, skip everything except 'sections'
        while reader.pos < len(raw):
            tag_type = reader.byte()
            if tag_type == TAG_END:
                break
            name = reader.string()
            if name == "sections" and tag_type == TAG_LIST:
                el_type = reader.byte()
                count = reader.int()
                if el_type != TAG_COMPOUND or count <= 0:
                    return []
                return [reader.read_compound() for _ in range(count)]
            reader.skip_payload(tag_type)
    except (IndexError, struct.error):
        pass
    return []


def read_chunk(region_data, chunk_x, chunk_z):
    """Read one chunk from a region file. Returns a dict with heightmap,
    yPos, and sections, or None."""
    raw = _decompress_chunk(region_data, chunk_x, chunk_z)
    if raw is None:
        return None
    heights = _extract_heightmap(raw)
    if heights is None:
        return None
    y_pos = _extract_ypos(raw)
    sections = _parse_sections(raw)
    return {"heights": heights, "yPos": y_pos, "sections": sections}


def unpack_heightmap(long_array, bits=9):
    """Unpack a packed long array of heightmap values. MC 1.18+ uses 9 bits
    per entry, 7 entries per long, 256 entries total (16×16 columns)."""
    values = []
    entries_per_long = 64 // bits
    mask = (1 << bits) - 1
    for packed in long_array:
        if packed < 0:
            packed += (1 << 64)
        for j in range(entries_per_long):
            values.append(packed & mask)
            packed >>= bits
            if len(values) >= 256:
                break
        if len(values) >= 256:
            break
    return values


def get_top_block(sections, x, y, z):
    """Get the block ID at (x, y, z) from the chunk's sections array.
    x, z are 0-15 within the chunk. Returns the block name or 'air'."""
    if not sections:
        return "minecraft:air"
    section_y = y >> 4
    for section in sections:
        if not isinstance(section, dict):
            continue
        sy = section.get("Y")
        if sy is None:
            sy = section.get("y")
        if sy is None or sy != section_y:
            continue
        block_states = section.get("block_states")
        if not block_states or not isinstance(block_states, dict):
            continue
        palette = block_states.get("palette", [])
        if not palette:
            continue
        if len(palette) == 1:
            name = palette[0]
            if isinstance(name, dict):
                name = name.get("Name", "minecraft:air")
            return name
        data = block_states.get("data")
        if not data:
            return palette[0].get("Name", "minecraft:air") if isinstance(palette[0], dict) else str(palette[0])
        bits = max(4, (len(palette) - 1).bit_length())
        entries_per_long = 64 // bits
        mask = (1 << bits) - 1
        local_y = y & 15
        idx = local_y * 256 + (z & 15) * 16 + (x & 15)
        long_idx = idx // entries_per_long
        bit_idx = (idx % entries_per_long) * bits
        if long_idx >= len(data):
            return "minecraft:air"
        packed = data[long_idx]
        if packed < 0:
            packed += (1 << 64)
        palette_idx = (packed >> bit_idx) & mask
        if palette_idx >= len(palette):
            return "minecraft:air"
        entry = palette[palette_idx]
        if isinstance(entry, dict):
            return entry.get("Name", "minecraft:air")
        return str(entry)
    return "minecraft:air"


# ---------------------------------------------------------------------------
# Block colour palette — covers the most common blocks.
# Colours approximate the top-down appearance in vanilla.
# ---------------------------------------------------------------------------
BLOCK_COLOURS = {
    "minecraft:grass_block": (124, 189, 107),
    "minecraft:short_grass": (124, 189, 107),
    "minecraft:tall_grass": (124, 189, 107),
    "minecraft:dirt": (134, 96, 67),
    "minecraft:coarse_dirt": (119, 85, 59),
    "minecraft:stone": (125, 125, 125),
    "minecraft:deepslate": (80, 80, 85),
    "minecraft:water": (63, 118, 228),
    "minecraft:sand": (219, 207, 163),
    "minecraft:red_sand": (190, 102, 33),
    "minecraft:gravel": (131, 127, 126),
    "minecraft:oak_log": (109, 85, 50),
    "minecraft:oak_leaves": (59, 122, 26),
    "minecraft:birch_log": (216, 210, 193),
    "minecraft:birch_leaves": (80, 132, 56),
    "minecraft:spruce_log": (58, 37, 16),
    "minecraft:spruce_leaves": (60, 98, 60),
    "minecraft:dark_oak_log": (60, 46, 26),
    "minecraft:dark_oak_leaves": (45, 90, 15),
    "minecraft:cherry_log": (53, 25, 32),
    "minecraft:cherry_leaves": (233, 177, 197),
    "minecraft:jungle_log": (85, 67, 25),
    "minecraft:jungle_leaves": (47, 118, 15),
    "minecraft:acacia_log": (103, 96, 86),
    "minecraft:acacia_leaves": (75, 128, 15),
    "minecraft:mangrove_log": (84, 67, 29),
    "minecraft:mangrove_leaves": (59, 122, 26),
    "minecraft:snow_block": (249, 254, 254),
    "minecraft:snow": (249, 254, 254),
    "minecraft:ice": (145, 183, 253),
    "minecraft:packed_ice": (141, 180, 250),
    "minecraft:blue_ice": (116, 167, 253),
    "minecraft:clay": (160, 166, 179),
    "minecraft:terracotta": (152, 94, 67),
    "minecraft:sandstone": (216, 203, 155),
    "minecraft:red_sandstone": (186, 99, 29),
    "minecraft:cobblestone": (127, 127, 127),
    "minecraft:mossy_cobblestone": (110, 127, 97),
    "minecraft:obsidian": (15, 10, 24),
    "minecraft:netherrack": (97, 38, 38),
    "minecraft:soul_sand": (81, 62, 50),
    "minecraft:soul_soil": (75, 57, 46),
    "minecraft:basalt": (72, 72, 78),
    "minecraft:smooth_basalt": (72, 72, 78),
    "minecraft:blackstone": (42, 36, 41),
    "minecraft:crimson_nylium": (130, 31, 31),
    "minecraft:warped_nylium": (22, 126, 134),
    "minecraft:crimson_stem": (92, 24, 29),
    "minecraft:warped_stem": (22, 126, 134),
    "minecraft:nether_wart_block": (114, 2, 2),
    "minecraft:warped_wart_block": (22, 126, 134),
    "minecraft:shroomlight": (240, 146, 70),
    "minecraft:glowstone": (171, 131, 68),
    "minecraft:lava": (207, 92, 15),
    "minecraft:magma_block": (142, 63, 31),
    "minecraft:end_stone": (219, 222, 158),
    "minecraft:end_stone_bricks": (219, 222, 158),
    "minecraft:chorus_plant": (92, 55, 92),
    "minecraft:chorus_flower": (136, 97, 136),
    "minecraft:purpur_block": (169, 125, 169),
    "minecraft:bedrock": (85, 85, 85),
    "minecraft:mycelium": (111, 99, 107),
    "minecraft:podzol": (91, 63, 24),
    "minecraft:moss_block": (89, 109, 45),
    "minecraft:mud": (60, 58, 60),
    "minecraft:muddy_mangrove_roots": (68, 60, 45),
    "minecraft:calcite": (223, 224, 220),
    "minecraft:dripstone_block": (134, 107, 92),
    "minecraft:tuff": (108, 109, 102),
    "minecraft:amethyst_block": (133, 97, 191),
    "minecraft:prismarine": (99, 171, 158),
    "minecraft:dark_prismarine": (51, 91, 75),
    "minecraft:sea_lantern": (172, 199, 190),
    "minecraft:sculk": (12, 29, 36),
    "minecraft:flowering_azalea_leaves": (102, 142, 62),
    "minecraft:azalea_leaves": (83, 124, 47),
    "minecraft:rooted_dirt": (144, 106, 77),
    "minecraft:copper_block": (192, 107, 79),
    "minecraft:exposed_copper": (161, 120, 90),
    "minecraft:weathered_copper": (108, 153, 110),
    "minecraft:oxidized_copper": (82, 162, 132),
    "minecraft:bamboo_block": (155, 171, 63),
    "minecraft:cherry_planks": (226, 178, 172),
    "minecraft:pale_moss_block": (177, 183, 167),
    "minecraft:pale_oak_leaves": (170, 179, 161),
}

DEFAULT_COLOUR = (110, 110, 110)
AIR_COLOUR = (0, 0, 0, 0)  # transparent
VOID_COLOUR = (20, 20, 30)


def block_colour(block_name):
    if block_name in BLOCK_COLOURS:
        return BLOCK_COLOURS[block_name]
    if "leaves" in block_name:
        return (59, 122, 26)
    if "log" in block_name or "stem" in block_name or "wood" in block_name:
        return (109, 85, 50)
    if "planks" in block_name:
        return (162, 130, 78)
    if "stone" in block_name:
        return (125, 125, 125)
    if "ore" in block_name:
        return (130, 130, 130)
    if "sand" in block_name:
        return (219, 207, 163)
    if "dirt" in block_name or "mud" in block_name:
        return (134, 96, 67)
    if "grass" in block_name or "fern" in block_name:
        return (124, 189, 107)
    if "flower" in block_name or "rose" in block_name or "tulip" in block_name:
        return (150, 130, 60)
    if "snow" in block_name or "ice" in block_name or "powder" in block_name:
        return (230, 240, 250)
    if "water" in block_name:
        return (63, 118, 228)
    if "lava" in block_name:
        return (207, 92, 15)
    if "nether" in block_name or "crimson" in block_name:
        return (120, 40, 40)
    if "warped" in block_name:
        return (22, 126, 134)
    if "end" in block_name and "stone" in block_name:
        return (219, 222, 158)
    if "air" in block_name or "void" in block_name:
        return None  # transparent
    return DEFAULT_COLOUR


# ---------------------------------------------------------------------------
# PNG writer (minimal, no dependencies)
# ---------------------------------------------------------------------------
def write_png(pixels, width, height, path):
    """Write an RGBA pixel array to a PNG file."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter: none
        for x in range(width):
            r, g, b, a = pixels[y * width + x]
            raw.extend([r, g, b, a])

    def chunk(tag, body):
        return (struct.pack(">I", len(body)) + tag + body
                + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)  # 8-bit RGBA
    Path(path).write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 6))
        + chunk(b"IEND", b""))


# ---------------------------------------------------------------------------
# Main renderer
# ---------------------------------------------------------------------------
def _height_colour(surface_y, family="overworld"):
    """Map a surface height to a colour. Uses a terrain-style palette:
    deep water → beaches → grassland → hills → mountains → peaks."""
    if family == "nether":
        t = max(0.0, min(1.0, (surface_y - 30) / 100))
        r = int(60 + 140 * t)
        g = int(20 + 40 * t)
        b = int(20 + 20 * t)
        return (r, g, b)
    if family == "end":
        if surface_y <= 0:
            return (10, 8, 20)  # void
        t = max(0.0, min(1.0, (surface_y - 40) / 80))
        return (int(180 + 40 * t), int(180 + 40 * t), int(140 + 20 * t))
    # Overworld palette
    if surface_y <= 62:
        depth = max(0.0, min(1.0, (62 - surface_y) / 40))
        return (int(50 - 20 * depth), int(80 + 40 * (1 - depth)), int(180 + 50 * (1 - depth)))
    if surface_y <= 70:
        return (194, 178, 128)  # beach/coast
    if surface_y <= 90:
        t = (surface_y - 70) / 20
        return (int(90 + 30 * t), int(150 + 30 * (1 - t)), int(60 + 20 * t))
    if surface_y <= 130:
        t = (surface_y - 90) / 40
        return (int(120 - 20 * t), int(140 - 40 * t), int(80 - 30 * t))
    if surface_y <= 200:
        t = (surface_y - 130) / 70
        return (int(100 + 60 * t), int(100 + 60 * t), int(50 + 80 * t))
    return (230, 230, 230)  # snow peaks


_TRANSPARENT_BLOCKS = frozenset({
    "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
    "minecraft:bedrock", "minecraft:barrier",
})


def _find_surface_block(sections, lx, lz, start_y, min_y):
    """Scan down from start_y to find the first visible (non-air, non-bedrock)
    block. Used for nether (scan below roof) and end (scan below void gaps)."""
    for y in range(start_y, min_y - 1, -1):
        block = get_top_block(sections, lx, y, lz)
        if block not in _TRANSPARENT_BLOCKS:
            return y, block
    return min_y, "minecraft:air"


def _render_chunk_columns(chunk, nether_roof=False, family="overworld"):
    """Extract 16×16 (colour, height) pairs from a parsed chunk.
    Returns a 256-entry list indexed by z*16+x, or None on failure.
    Uses block colours when sections are available; falls back to
    height-based palette otherwise."""
    if not chunk:
        return None
    heights = chunk.get("heights")
    if not heights:
        return None
    y_offset = chunk.get("yPos", -4) * 16
    sections = chunk.get("sections", [])
    has_sections = bool(sections)

    columns = []
    for idx in range(256):
        lx = idx & 15
        lz = idx >> 4
        surface_y = heights[idx] - 1 + y_offset if idx < len(heights) else 64

        if nether_roof and has_sections:
            # Nether: scan down from y=127 to find terrain below the roof
            surface_y, block = _find_surface_block(sections, lx, lz, 127, y_offset)
        elif has_sections:
            block = get_top_block(sections, lx, surface_y, lz)
            # If heightmap points at air/bedrock, scan down
            if block in _TRANSPARENT_BLOCKS and surface_y > y_offset:
                surface_y, block = _find_surface_block(
                    sections, lx, lz, surface_y, y_offset)
        else:
            block = None

        if surface_y <= y_offset:
            columns.append(((20, 20, 30), surface_y))
            continue

        if block and block not in _TRANSPARENT_BLOCKS:
            colour = block_colour(block)
            if colour is None:
                colour = _height_colour(surface_y, family)
        else:
            colour = _height_colour(surface_y, family)

        shade = max(0.7, min(1.3, 0.85 + (surface_y - 64) / 250))
        r = min(255, int(colour[0] * shade))
        g = min(255, int(colour[1] * shade))
        b = min(255, int(colour[2] * shade))
        columns.append(((r, g, b), surface_y))
    return columns


def render_area(world_dir, dimension_path, x0, z0, width, height,
                output_path, family="overworld"):
    """Render a top-down map of [x0..x0+width) × [z0..z0+height) blocks."""
    world = Path(world_dir)
    if dimension_path:
        region_dir = world / dimension_path / "region"
    else:
        region_dir = world / "region"

    if not region_dir.exists():
        pixels = [(20, 20, 30, 255)] * (width * height)
        write_png(pixels, width, height, output_path)
        return False

    nether_roof = family == "nether"

    # Load region files once
    regions = {}
    def get_region(rx, rz):
        key = (rx, rz)
        if key not in regions:
            path = region_dir / f"r.{rx}.{rz}.mca"
            regions[key] = path.read_bytes() if path.exists() else None
        return regions[key]

    # Parse each chunk once
    chunk_cache = {}
    def get_chunk_columns(cx, cz):
        key = (cx, cz)
        if key not in chunk_cache:
            rx, rz = cx >> 5, cz >> 5
            region_data = get_region(rx, rz)
            if region_data is None:
                chunk_cache[key] = None
            else:
                try:
                    raw = read_chunk(region_data, cx, cz)
                    chunk_cache[key] = _render_chunk_columns(raw, nether_roof, family)
                except Exception:
                    chunk_cache[key] = None
        return chunk_cache[key]

    void_pixel = (20, 20, 30, 255)
    pixels = []
    for bz in range(z0, z0 + height):
        for bx in range(x0, x0 + width):
            cx, cz = bx >> 4, bz >> 4
            columns = get_chunk_columns(cx, cz)
            if columns is None:
                pixels.append(void_pixel)
                continue
            lx, lz = bx & 15, bz & 15
            idx = lz * 16 + lx
            col, _h = columns[idx]
            pixels.append((col[0], col[1], col[2], 255))

    write_png(pixels, width, height, output_path)
    return True


def render_candidate(world_dir, ns, cand_name, seed, output_path,
                     family="overworld", size=256):
    """Render a candidate dimension's spawn area. The dimension's region
    data is at world_dir/dimensions/<ns>/<cand_name>/region/."""
    dim_path = f"dimensions/{ns}/{cand_name}"
    half = size // 2
    return render_area(
        world_dir, dim_path, -half, -half, size, size,
        output_path, family=family)


if __name__ == "__main__":
    import sys
    import time

    if len(sys.argv) < 3:
        print("Usage: map_renderer.py <world_dir> <output.png> [dimension_path] [family] [size]")
        print("  dimension_path: '' for overworld, 'DIM-1' for nether, 'dimensions/ns/name' for custom")
        sys.exit(1)

    world_dir = sys.argv[1]
    output = sys.argv[2]
    dim_path = sys.argv[3] if len(sys.argv) > 3 else ""
    family = sys.argv[4] if len(sys.argv) > 4 else "overworld"
    size = int(sys.argv[5]) if len(sys.argv) > 5 else 256

    t0 = time.time()
    half = size // 2
    ok = render_area(world_dir, dim_path, -half, -half, size, size, output, family)
    elapsed = (time.time() - t0) * 1000
    print(f"{'Rendered' if ok else 'No region data'}: {output} ({size}×{size}, {family}) in {elapsed:.0f}ms")
