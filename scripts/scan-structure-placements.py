#!/usr/bin/env python3
"""Scan a world's region files for generated structures and their water depth.

Purpose:  Answer "which structures actually generated, and is one standing in
          the sea" from the world itself. Convention tags (c:*) resolve at
          runtime, so a jar scan cannot tell you what is ocean-capable, and a
          chunk's biome is 3D so the slice you sample is not the slice the
          game checked. Terrain is unambiguous: heightmaps record the ocean
          floor and the water surface separately.

Context:  Reads <world>/region/*.mca. No server, no RCON, no /locate (banned,
          AGENTS.md). Complements config/custom-dimensions/extractors/
          registries.json, which records what CAN generate; this records what
          DID.

Usage:    ./scripts/scan-structure-placements.py <world-dir> [--water-only]
                                                 [--max-regions N] [--min-depth N]

Gotchas:  Depth and biome are sampled at the start's bounding-box centre,
          clamped into the chunk that owns the start — a start whose pieces
          run into the next chunk is measured at the clamp, not at the piece.
          Underground structures legitimately sit under a seabed — water above
          them is expected, so filter on step/name before calling one
          misplaced. The `ocean` column counts placements whose biome is an
          ocean; `wet` minus `ocean` is a land biome with submerged terrain.
"""
import argparse
import collections
import gzip
import io
import struct
import sys
import zlib
from pathlib import Path

try:
    import nbtlib
except ImportError:
    sys.exit("nbtlib missing — pip3 install -r requirements-dev.txt")

SECTOR = 4096


def parse_chunk(raw):
    scheme, body = raw[0], raw[1:]
    data = zlib.decompress(body) if scheme == 2 else gzip.decompress(body) if scheme == 1 else body
    return nbtlib.File.parse(io.BytesIO(data), byteorder="big")


def heightmap(chunk, name, min_y=-64):
    """Decode a packed heightmap to 256 world Y values.

    Bit width tracks world height: a 512-tall world (Tectonic max_y 448)
    packs 10 bits per entry, not vanilla's 9. Derived from array length.
    """
    hm = chunk.get("Heightmaps", {}).get(name)
    if hm is None:
        return None
    n = len(hm)
    bits = next((b for b in (9, 10, 11, 12) if n == -(-256 // (64 // b))), None)
    if bits is None:
        return None
    per, mask, out = 64 // bits, (1 << bits) - 1, []
    for word in hm:
        w = int(word) & ((1 << 64) - 1)
        for k in range(per):
            if len(out) == 256:
                break
            out.append(((w >> (bits * k)) & mask) + min_y)
    return out if len(out) == 256 else None


def column(chunk, lx, lz):
    """(water depth, ground height) at a local column; (None, None) when unknown."""
    surf = heightmap(chunk, "WORLD_SURFACE_WG") or heightmap(chunk, "WORLD_SURFACE")
    floor = heightmap(chunk, "OCEAN_FLOOR_WG") or heightmap(chunk, "OCEAN_FLOOR")
    if not surf or not floor:
        return None, None
    i = lz * 16 + lx
    return surf[i] - floor[i], floor[i]


def biome_at(chunk, lx, y, lz):
    """Biome id at a local column and world Y; None when the section is absent.

    Biome palettes are 4x4x4 per section, so the index quantises by 2 bits.
    """
    for section in chunk.get("sections", []):
        if (int(section.get("Y", 0)) << 4) != (y & ~15):
            continue
        biomes = section.get("biomes")
        if biomes is None:
            return None
        palette = [str(p) for p in biomes.get("palette", [])]
        if len(palette) <= 1:
            return palette[0] if palette else None
        data = biomes.get("data")
        if data is None:
            return palette[0]
        bits = max(1, (len(palette) - 1).bit_length())
        per = 64 // bits
        idx = ((y & 15) >> 2) * 16 + ((lz & 15) >> 2) * 4 + ((lx & 15) >> 2)
        word = int(data[idx // per]) & ((1 << 64) - 1)
        return palette[(word >> (bits * (idx % per))) & ((1 << bits) - 1)]
    return None


def start_column(start, cx, cz):
    """Local (x, z) of a structure start's bounding-box centre, clamped to its chunk."""
    bb = start.get("BB")
    if bb is None or len(bb) != 6:
        return 8, 8
    mx, mz = (int(bb[0]) + int(bb[3])) // 2, (int(bb[2]) + int(bb[5])) // 2
    return min(15, max(0, mx - cx * 16)), min(15, max(0, mz - cz * 16))


def chunks(mca):
    blob = mca.read_bytes()
    if len(blob) < 2 * SECTOR:
        return
    for n in range(1024):
        header = struct.unpack_from(">I", blob, n * 4)[0]
        off, count = header >> 8, blob[n * 4 + 3]
        if not off or not count:
            continue
        p = off * SECTOR
        if p + 5 > len(blob):
            continue
        length = struct.unpack_from(">i", blob, p)[0]
        try:
            yield parse_chunk(blob[p + 4:p + 4 + length])
        except Exception:
            continue


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("world")
    ap.add_argument("--water-only", action="store_true")
    ap.add_argument("--max-regions", type=int, default=0)
    ap.add_argument("--min-depth", type=int, default=4, help="water depth counting as 'in water'")
    a = ap.parse_args()

    rdir = Path(a.world) / "region"
    if not rdir.is_dir():
        sys.exit(f"no region dir at {rdir}")
    files = sorted(rdir.glob("*.mca"))
    if a.max_regions:
        files = files[:a.max_regions]

    found = collections.defaultdict(
        lambda: {"total": 0, "wet": 0, "ocean": 0, "deep": 0, "sample": []})
    seen = wet_chunks = 0
    for mca in files:
        for chunk in chunks(mca):
            seen += 1
            root = chunk.get("") if "" in chunk else chunk
            cx, cz = int(root.get("xPos", 0)), int(root.get("zPos", 0))
            if (column(root, 8, 8)[0] or 0) >= a.min_depth:
                wet_chunks += 1
            starts = root.get("structures", {}).get("starts", {})
            if not starts:
                continue
            for sid, st in starts.items():
                if not hasattr(st, "get") or str(st.get("id", "INVALID")) == "INVALID":
                    continue
                e = found[sid]
                e["total"] += 1
                lx, lz = start_column(st, cx, cz)
                depth, ground = column(root, lx, lz)
                if depth is None or depth < a.min_depth:
                    continue
                biome = biome_at(root, lx, ground, lz) or "?"
                e["wet"] += 1
                e["deep"] = max(e["deep"], depth)
                if "ocean" in biome:
                    e["ocean"] += 1
                if len(e["sample"]) < 3:
                    e["sample"].append(
                        f"{cx*16+lx},{cz*16+lz}({depth}m {biome.split(':')[-1]})")

    print(f"regions {len(files)}  chunks {seen}  chunks in water {wet_chunks}\n")
    rows = sorted(found.items(), key=lambda kv: (-kv[1]["deep"], -kv[1]["wet"]))
    if a.water_only:
        rows = [r for r in rows if r[1]["wet"]]
    print(f"{'structure':<46}{'total':>6}{'wet':>5}{'ocean':>6}{'max':>6}  samples")
    for sid, e in rows:
        print(f"  {sid:<44}{e['total']:>6}{e['wet']:>5}{e['ocean']:>6}{e['deep']:>5}m  "
              f"{', '.join(e['sample'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
