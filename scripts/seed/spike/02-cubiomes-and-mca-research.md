# Cubiomes, .mca Format, and Synthetic Region Generation

## Key Findings

### Cubiomes is vanilla-only but the noise is reusable

cubiomes (C library by Cubitect) replicates MC's exact multinoise algorithm for 1.18+:
- Six `DoublePerlinNoise` generators for temperature, humidity, continentalness, erosion, depth, weirdness
- `sampleBiomeNoise()` samples all six climate parameters at a coordinate
- `climateToBiome()` maps the 6D climate vector to a biome ID via a spline-based decision tree

**The biome mapping is hardcoded to vanilla biome IDs.** No dynamic biome registry, no datapack loading, no way to add custom biome IDs. No forks support modded biomes.

### How modded biomes actually work

Terralith, Incendium, Nullscape, and Nature's Spirit are datapacks that modify:
1. **The multinoise biome source JSON** (`worldgen/world_preset/`) — they add custom biome entries mapping climate parameter ranges to custom biome resource locations (e.g., `terralith:yellowstone`)
2. **Density functions** (`worldgen/density_function/`) — alter terrain shape, xz_scale in shifted noise

The key insight: modded biome placement is defined by **JSON parameter mappings**, not code changes. The noise generation is identical to vanilla.

### Our biome_sampler.py IS the modded cubiomes

The existing `biome_sampler.py` already does exactly what a "modded cubiomes" would do:
- Same Xoroshiro128++ PRNG
- Same DoublePerlinNoiseSampler (two OctavePerlinNoiseSamplers combined)
- Same coordinate shifts from offset noise
- Same 6D climate parameter sampling
- Nearest-neighbour biome lookup against the **extracted modded parameter table** (`biome_params.json`)
- Per-family noise configs from `noise_configs.json` (including Incendium, Nullscape, Paradise Lost parameters)

**There is no reason to port cubiomes to C for this project.** The Python sampler already matches the algorithm and supports all modded biomes. Speed improvement from C would be ~100x, but the current Python renderer already does 713 renders in ~14 minutes.

### To replicate modded biome generation you need:

1. Parse the datapack's `worldgen/world_preset/` and `worldgen/biome/` JSON files → **already done** (`biome_params.json`)
2. Build a custom multinoise parameter tree from those JSON definitions → **already done** (BiomeSampler)
3. Sample the same six climate noise parameters cubiomes computes → **already done** (DoublePerlinNoiseSampler)
4. Map the noise values to biome IDs using the datapack's parameter ranges → **already done** (nearest-neighbour lookup)

## .mca Format Details

### Header (8192 bytes)

- Bytes 0–4095: Location table — 1024 entries (32×32 chunks), each 4 bytes
  - Bytes 0–2: offset in 4KiB sectors from file start (big-endian)
  - Byte 3: sector count
- Bytes 4096–8191: Timestamp table — 1024 entries, 4 bytes each (Unix timestamp)

Chunk index: `i = (chunkX & 31) + (chunkZ & 31) * 32`

### Chunk data

| Offset | Size | Description |
|--------|------|-------------|
| 0–3 | 4 bytes | Length (big-endian, excludes padding) |
| 4 | 1 byte | Compression (1=GZip, 2=Zlib, 3=Uncompressed, 4=LZ4) |
| 5+ | Length-1 | Compressed NBT data |

File size must be a multiple of 4096 bytes.

### Biome storage (1.18+)

Each section's biomes compound:
- **palette:** List of strings (biome resource locations)
- **data:** LongArray of packed indices
  - 64 biome entries per section (4×4×4 grid in a 16×16×16 section)
  - Bits per entry = ceil(log2(palette_size)), min 1 bit
  - If palette has 1 entry, `data` field is omitted
  - Indices packed into longs, not spanning long boundaries
  - Index order: XZY (x fastest, then z, then y)

### Block state storage (1.18+)

Each section's block_states compound:
- **palette:** List of compounds (block state with Name + Properties)
- **data:** LongArray of packed indices
  - 4096 entries per section (16×16×16)
  - Bits per entry = max(4, ceil(log2(palette_size)))
  - If palette has 1 entry, `data` field is omitted

### Heightmap format

6 types: MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR, OCEAN_FLOOR_WG, WORLD_SURFACE, WORLD_SURFACE_WG

37 longs, each containing 7 packed 9-bit values (256 columns = 16×16). Values range 0–384 representing blocks above world bottom (-64).

### Minimum viable chunk NBT

```
{} (root compound)
├── DataVersion: Int (e.g., 3953 for 1.21.1)
├── xPos: Int
├── zPos: Int
├── yPos: Int (-4 for standard overworld)
├── Status: String ("minecraft:full" for renderers)
├── sections: List[Compound]
│   └── [for each Y section]:
│       ├── Y: Byte
│       ├── biomes: Compound {palette, data?}
│       └── block_states: Compound {palette, data?}
├── Heightmaps: Compound
│   ├── MOTION_BLOCKING: LongArray[37]
│   └── WORLD_SURFACE: LongArray[37]
├── LastUpdate: Long (0)
└── InhabitedTime: Long (0)
```

**For renderers:** Status must be `minecraft:full` (not `minecraft:biomes`). Must have block_states with at least surface blocks. Heightmaps are needed for shadows.

## Tools for Writing .mca

| Tool | Language | .mca Write? | Biome Write? | Notes |
|------|----------|-------------|--------------|-------|
| amulet-core | Python | Yes | Yes (1.18+) | Most complete, handles packing |
| anvil-parser2 | Python | Yes | TODO | Block writing works |
| mca crate | Rust | Yes | Manual NBT | 147 MiB/s, need fastnbt for serialisation |
| Hand-roll | C/Python | Yes | Manual | Format is simple: header + zlib-compressed NBT |

### Alternative: deepslate (TypeScript, by misode)

Implements multinoise biome generation and supports datapack-defined biomes. Could theoretically be used for rendering, but doesn't write .mca files.

## Implications

1. **We already have modded cubiomes** — `biome_sampler.py` does the same job
2. **Porting to C gains speed but we're already fast enough** for biome sampling
3. **The real gap is rendering quality**, not biome accuracy
4. **Synthetic .mca files need block states** for unmined-cli to render — biome-only chunks produce blank output
5. **If we write synthetic .mca with surface blocks**, unmined-cli can render them with proper biome tints and shadows
