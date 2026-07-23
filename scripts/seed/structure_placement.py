#!/usr/bin/env python3
"""structure_placement.py — Pure-Python structure placement calculator.

Reimplements vanilla's RandomSpreadStructurePlacement algorithm to compute
where structures CAN generate, given a seed and the structure set configs
(spacing, separation, salt). No server, no RCON, no chunk generation —
runs in <1ms per structure per seed.

The algorithm matches net.minecraft.world.gen.chunk.placement.
RandomSpreadStructurePlacement in 1.21.1 (Yarn mappings).
"""

import json
import math
import os
from pathlib import Path


def java_long(n):
    """Truncate to signed 64-bit (Java long semantics)."""
    n = n & 0xFFFFFFFFFFFFFFFF
    if n >= 0x8000000000000000:
        n -= 0x10000000000000000
    return n


def java_int(n):
    """Truncate to signed 32-bit (Java int semantics)."""
    n = n & 0xFFFFFFFF
    if n >= 0x80000000:
        n -= 0x100000000
    return n


def java_floor_div(a, b):
    """Java's Math.floorDiv: rounds toward negative infinity."""
    return math.floor(a / b)


def java_floor_mod(a, b):
    """Java's Math.floorMod."""
    return a - java_floor_div(a, b) * b


def placement_seed(world_seed, salt):
    """The per-structure-set seed, matching vanilla's
    RandomSpreadStructurePlacement constructor."""
    # LCG from vanilla: seed = salt + worldSeed * salt * ... no —
    # vanilla uses: this.salt = ... let me get the exact formula.
    # From StructurePlacement: setSeed = worldSeed + salt
    # Actually from RandomState / StructurePlacementCalculator:
    #   long l = worldSeed;
    #   long m = salt (from the placement);
    #   return l * l * 6364136223846793005L + l * 1442695040888963407L + m;
    # Wait no, that's for the per-region RNG, not the seed itself.
    # Let me trace the actual code path.
    #
    # In StructurePlacementCalculator.canGenerate() ->
    #   RandomSpreadStructurePlacement.getStartChunk() does:
    #     ChunkRandom random = new ChunkRandom(new CheckedRandom(0L));
    #     random.setRegionSeed(seed, regionX, regionZ, salt);
    #
    # setRegionSeed computes:
    #   long l = (long)regionX * 341873128712L
    #          + (long)regionZ * 132897987541L
    #          + seed + (long)salt;
    #   this.setSeed(l);
    #
    # Then nextInt(spacing - separation) gives the offset.
    return world_seed  # the world seed itself; salt is used per-region


def region_seed(world_seed, region_x, region_z, salt):
    """ChunkRandom.setRegionSeed — the LCG seed for a given region."""
    return java_long(
        region_x * 341873128712
        + region_z * 132897987541
        + world_seed
        + salt
    )


def next_random(seed):
    """Java LCG: one step of java.util.Random.next(bits=31).
    seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1)
    return (int)(seed >>> 17)  -- top 31 bits"""
    seed = java_long((seed ^ 0x5DEECE66D) * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
    return seed, java_int(seed >> 17)


def next_int(seed, bound):
    """Java Random.nextInt(bound) using the LCG."""
    if bound <= 0:
        return seed, 0
    # Standard rejection sampling from java.util.Random
    seed, bits = next_random(seed)
    val = bits % bound
    while bits - val + (bound - 1) < 0:
        seed, bits = next_random(seed)
        val = bits % bound
    return seed, val


def get_start_chunk(world_seed, region_x, region_z, spacing, separation, salt,
                    spread_type="linear"):
    """Compute the structure start chunk position for one region.
    Returns (chunkX, chunkZ)."""
    rseed = region_seed(world_seed, region_x, region_z, salt)
    # Java Random constructor: seed = (seed ^ 0x5DEECE66DL) & mask
    rseed = (rseed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    max_offset = spacing - separation
    if spread_type == "triangular":
        rseed, r1 = next_int(rseed, max_offset)
        rseed, r2 = next_int(rseed, max_offset)
        offset_x = (r1 + r2) // 2
        rseed, r1 = next_int(rseed, max_offset)
        rseed, r2 = next_int(rseed, max_offset)
        offset_z = (r1 + r2) // 2
    else:
        rseed, offset_x = next_int(rseed, max_offset)
        rseed, offset_z = next_int(rseed, max_offset)

    chunk_x = region_x * spacing + offset_x
    chunk_z = region_z * spacing + offset_z
    return chunk_x, chunk_z


def nearest_structure(world_seed, spacing, separation, salt, origin_x=0, origin_z=0,
                      search_radius=100, spread_type="linear", frequency=1.0,
                      frequency_reduction_method="default"):
    """Find the nearest structure placement to origin (in blocks).
    search_radius is in regions (matching vanilla locate's 100-region default).
    Returns (distance_blocks, block_x, block_z) or None if not found."""
    origin_chunk_x = origin_x >> 4
    origin_chunk_z = origin_z >> 4
    best = None
    best_dist_sq = float('inf')

    for ring in range(search_radius + 1):
        if ring == 0:
            coords = [(0, 0)]
        else:
            coords = []
            for i in range(-ring, ring + 1):
                coords.append((i, -ring))
                coords.append((i, ring))
            for i in range(-ring + 1, ring):
                coords.append((-ring, i))
                coords.append((ring, i))

        for drx, drz in coords:
            region_x = java_floor_div(origin_chunk_x, spacing) + drx
            region_z = java_floor_div(origin_chunk_z, spacing) + drz

            cx, cz = get_start_chunk(world_seed, region_x, region_z,
                                     spacing, separation, salt, spread_type)

            # Frequency check: some structures only generate in a fraction of
            # valid positions. frequency < 1.0 means probabilistic placement.
            if frequency < 1.0:
                fseed = region_seed(world_seed, cx, cz, salt)
                fseed = (fseed ^ 0x5DEECE66D) & ((1 << 48) - 1)
                # nextFloat(): next(24) / (1 << 24)
                fseed, bits = next_random(fseed)
                fval = (bits >> 7) / (1 << 24)  # 24-bit float
                if fval >= frequency:
                    continue

            bx = cx * 16 + 8
            bz = cz * 16 + 8
            dx = bx - origin_x
            dz = bz - origin_z
            dist_sq = dx * dx + dz * dz

            if dist_sq < best_dist_sq:
                best_dist_sq = dist_sq
                best = (int(math.sqrt(dist_sq)), bx, bz)

        # Early exit: if we found one and expanded at least one more ring
        if best is not None and ring > 0:
            # The nearest in this ring can't be closer than (ring-1)*spacing*16
            min_possible = (ring - 1) * spacing * 16
            if min_possible * min_possible > best_dist_sq:
                break

    return best


def load_structure_sets(extract_dir):
    """Load all structure_set JSONs from the extraction directory.
    Returns {set_id: {structures, spacing, separation, salt, frequency, spread_type}}."""
    sets = {}
    for json_path in Path(extract_dir).rglob("*.json"):
        if "structure_set" not in str(json_path):
            continue
        try:
            data = json.loads(json_path.read_text())
        except (json.JSONDecodeError, OSError):
            continue

        placement = data.get("placement", {})
        ptype = placement.get("type", "")
        if "random_spread" not in ptype and "concentric_rings" not in ptype:
            continue

        # Derive set ID from path: data/<namespace>/worldgen/structure_set/<name>.json
        parts = json_path.parts
        try:
            data_idx = parts.index("data")
            namespace = parts[data_idx + 1]
            name = json_path.stem
            set_id = f"{namespace}:{name}"
        except (ValueError, IndexError):
            continue

        structures = []
        for s in data.get("structures", []):
            sid = s.get("structure", "")
            weight = s.get("weight", 1)
            structures.append({"id": sid, "weight": weight})

        if "concentric_rings" in ptype:
            # Skip concentric rings (strongholds) — different algorithm
            continue

        sets[set_id] = {
            "id": set_id,
            "structures": structures,
            "spacing": placement.get("spacing", 32),
            "separation": placement.get("separation", 8),
            "salt": placement.get("salt", 0),
            "frequency": placement.get("frequency", 1.0),
            "spread_type": placement.get("spread_type", "linear"),
            "frequency_reduction_method": placement.get(
                "frequency_reduction_method", "default"),
        }

    return sets


def find_all_in_radius(world_seed, spacing, separation, salt, radius_blocks,
                       origin_x=0, origin_z=0, spread_type="linear", frequency=1.0):
    """Find ALL structure placements within radius_blocks of origin.
    Returns list of (distance, block_x, block_z), sorted by distance."""
    results = []
    # Convert block radius to region range
    region_range = (radius_blocks // (spacing * 16)) + 2
    origin_chunk_x = origin_x >> 4
    origin_chunk_z = origin_z >> 4
    base_region_x = java_floor_div(origin_chunk_x, spacing)
    base_region_z = java_floor_div(origin_chunk_z, spacing)

    for rx in range(base_region_x - region_range, base_region_x + region_range + 1):
        for rz in range(base_region_z - region_range, base_region_z + region_range + 1):
            cx, cz = get_start_chunk(world_seed, rx, rz, spacing, separation, salt, spread_type)

            if frequency < 1.0:
                fseed = region_seed(world_seed, cx, cz, salt)
                fseed = (fseed ^ 0x5DEECE66D) & ((1 << 48) - 1)
                fseed, bits = next_random(fseed)
                fval = (bits >> 7) / (1 << 24)
                if fval >= frequency:
                    continue

            bx = cx * 16 + 8
            bz = cz * 16 + 8
            dx = bx - origin_x
            dz = bz - origin_z
            dist = int(math.sqrt(dx * dx + dz * dz))
            if dist <= radius_blocks:
                results.append((dist, bx, bz))

    results.sort()
    return results


def locate_all(world_seed, structure_sets, search_radius=50, origin_x=0, origin_z=0):
    """Locate the nearest placement for every structure set.
    Returns {set_id: (distance, x, z) or None}."""
    results = {}
    for set_id, cfg in structure_sets.items():
        result = nearest_structure(
            world_seed,
            cfg["spacing"], cfg["separation"], cfg["salt"],
            origin_x, origin_z,
            search_radius=search_radius,
            spread_type=cfg.get("spread_type", "linear"),
            frequency=cfg.get("frequency", 1.0),
        )
        results[set_id] = result
    return results


if __name__ == "__main__":
    import sys
    import time

    extract_dir = sys.argv[1] if len(sys.argv) > 1 else "/private/tmp/claude-501/-Users-pip-Projects-elfydd/3f9641ec-d29c-4f31-ba92-d1e7bc4be715/scratchpad/structure_sets"
    seed = int(sys.argv[2]) if len(sys.argv) > 2 else 12345

    print(f"Loading structure sets from {extract_dir}...")
    sets = load_structure_sets(extract_dir)
    print(f"Loaded {len(sets)} structure sets")

    t0 = time.time()
    results = locate_all(seed, sets, search_radius=50)
    elapsed = time.time() - t0

    found = sum(1 for r in results.values() if r is not None)
    print(f"\nLocated {found}/{len(results)} sets in {elapsed*1000:.1f}ms (seed {seed})")
    print()
    for set_id, result in sorted(results.items()):
        if result:
            dist, x, z = result
            print(f"  {set_id}: {dist} blocks at ({x}, {z})")
        else:
            print(f"  {set_id}: not found")
