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

Gotchas:  A structure start is recorded in the chunk that owns it, which is
          not necessarily where its pieces render. Underground structures
          legitimately sit under a seabed — water above them is expected, so
          filter on step/name before calling one misplaced.
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


def water_depth(chunk):
    """Water over the sea floor at the chunk centre; None when unknown."""
    surf = heightmap(chunk, "WORLD_SURFACE_WG") or heightmap(chunk, "WORLD_SURFACE")
    floor = heightmap(chunk, "OCEAN_FLOOR_WG") or heightmap(chunk, "OCEAN_FLOOR")
    if not surf or not floor:
        return None
    i = 8 * 16 + 8
    return surf[i] - floor[i]


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

    found = collections.defaultdict(lambda: {"total": 0, "wet": 0, "deep": 0, "sample": []})
    seen = 0
    for mca in files:
        for chunk in chunks(mca):
            seen += 1
            root = chunk.get("") if "" in chunk else chunk
            starts = root.get("structures", {}).get("starts", {})
            if not starts:
                continue
            depth = water_depth(root)
            cx, cz = int(root.get("xPos", 0)), int(root.get("zPos", 0))
            for sid, st in starts.items():
                if not hasattr(st, "get") or str(st.get("id", "INVALID")) == "INVALID":
                    continue
                e = found[sid]
                e["total"] += 1
                if depth is not None and depth >= a.min_depth:
                    e["wet"] += 1
                    e["deep"] = max(e["deep"], depth)
                    if len(e["sample"]) < 3:
                        e["sample"].append(f"{cx*16},{cz*16}({depth}m)")

    print(f"regions {len(files)}  chunks {seen}\n")
    rows = sorted(found.items(), key=lambda kv: (-kv[1]["deep"], -kv[1]["wet"]))
    if a.water_only:
        rows = [r for r in rows if r[1]["wet"]]
    print(f"{'structure':<48}{'total':>6}{'wet':>5}{'max':>6}  samples")
    for sid, e in rows:
        print(f"  {sid:<46}{e['total']:>6}{e['wet']:>5}{e['deep']:>5}m  {', '.join(e['sample'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
