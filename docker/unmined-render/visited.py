#!/usr/bin/env python3
# visited.py — has a player ever been in this dimension?
#
# Context: Chunky pre-generates the overworld, nether, end and paradise_lost,
# so region files exist for worlds nobody has entered. InhabitedTime counts
# ticks a player spent in a chunk, which separates the two. Region files are
# read straight off disk — never RCON, which would wake an autopaused server.
#
# Usage:
#   visited.py <region_dir> [--since EPOCH]     # exit 0 = visited
#
# Exit codes: 0 visited, 1 not visited, 2 undetermined (nothing readable —
# callers must fail open and show the dimension rather than hide it).
#
# Gotchas:
#   - --since skips regions not written since a previous scan; a full scan of a
#     pre-generated world is minutes, an incremental one is nothing.
#   - Chunks compressed with LZ4 (compression type 4) can't be read here and
#     count as unreadable, not as uninhabited.
import struct
import sys
import zlib
from pathlib import Path

TAG_LONG = 4
NAME = b"InhabitedTime"
NAMED_LONG = struct.pack(">BH", TAG_LONG, len(NAME)) + NAME


def chunk_payloads(path):
    """Yield decompressed chunk NBT from a .mca, and count what wouldn't read."""
    data = path.read_bytes()
    unreadable = 0
    if len(data) < 8192:
        return
    for i in range(1024):
        loc = struct.unpack_from(">I", data, i * 4)[0]
        offset, sectors = loc >> 8, loc & 0xFF
        if not offset or not sectors:
            continue
        start = offset * 4096
        if start + 5 > len(data):
            continue
        length = struct.unpack_from(">I", data, start)[0]
        compression = data[start + 4]
        raw = data[start + 5:start + 4 + length]
        try:
            if compression == 1:
                yield zlib.decompress(raw, 47)
            elif compression == 2:
                yield zlib.decompress(raw)
            elif compression == 3:
                yield raw
            else:
                unreadable += 1
        except zlib.error:
            unreadable += 1
    if unreadable:
        yield None


def inhabited(nbt):
    """True if a TAG_Long named InhabitedTime in this chunk is above zero."""
    i = nbt.find(NAMED_LONG)
    while i != -1:
        value_at = i + len(NAMED_LONG)
        if value_at + 8 <= len(nbt):
            if struct.unpack_from(">q", nbt, value_at)[0] > 0:
                return True
        i = nbt.find(NAMED_LONG, i + 1)
    return False


def main(argv):
    if not argv:
        print("usage: visited.py <region_dir> [--since EPOCH]", file=sys.stderr)
        return 2
    region_dir = Path(argv[0])
    since = 0.0
    if "--since" in argv:
        try:
            since = float(argv[argv.index("--since") + 1])
        except (IndexError, ValueError):
            return 2

    regions = [p for p in region_dir.glob("*.mca") if p.stat().st_mtime > since]
    if not regions:
        return 1
    # Newest first: a dimension someone plays in hits a match almost at once,
    # so only a genuinely empty one pays for the whole directory.
    regions.sort(key=lambda p: p.stat().st_mtime, reverse=True)

    read_anything = False
    for region in regions:
        try:
            for nbt in chunk_payloads(region):
                if nbt is None:
                    continue
                read_anything = True
                if inhabited(nbt):
                    return 0
        except OSError:
            continue
    return 1 if read_anything else 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
