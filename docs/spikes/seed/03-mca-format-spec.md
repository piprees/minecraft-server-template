# .mca (Anvil) Region File Format — Technical Spec for 1.21.1

DataVersion: **3955** for MC 1.21.1.

## File Layout

| Offset | Size | Description |
|--------|------|-------------|
| 0 | 4096 bytes | Location table — 1024 entries × 4 bytes |
| 4096 | 4096 bytes | Timestamp table — 1024 entries × 4 bytes |
| 8192+ | variable | Chunk data sectors (4096-byte aligned) |

### Location Table Entry (4 bytes, big-endian)

- Bytes 0–2: sector offset from file start (unsigned 24-bit)
- Byte 3: sector count (max 255 ≈ 1 MiB)
- Entry `0x00000000` = chunk does not exist
- Minimum valid offset: 2 (sectors 0+1 are the header)

### Chunk index

```
i = (chunkX & 31) + (chunkZ & 31) * 32
```

### Chunk Data

| Offset | Size | Description |
|--------|------|-------------|
| 0–3 | 4 bytes | Length (big-endian signed int32) |
| 4 | 1 byte | Compression type (2 = zlib standard) |
| 5+ | length-1 | Compressed NBT data |
| ... | padding | Zero-padded to 4096-byte boundary |

## Chunk NBT Structure (1.21.1)

```
ROOT (TAG_Compound)
├── DataVersion: TAG_Int(3955)
├── xPos: TAG_Int          — absolute chunk X
├── yPos: TAG_Int          — lowest section Y (-4 overworld, 0 nether/end)
├── zPos: TAG_Int          — absolute chunk Z
├── Status: TAG_String     — "minecraft:full" for renderers
├── LastUpdate: TAG_Long
├── InhabitedTime: TAG_Long
├── sections: TAG_List[TAG_Compound]
├── Heightmaps: TAG_Compound
├── block_entities: TAG_List (empty)
├── block_ticks: TAG_List (empty)
├── fluid_ticks: TAG_List (empty)
├── PostProcessing: TAG_List (24 empty lists)
└── isLightOn: TAG_Byte(1)
```

### Section (24 for overworld Y:-4 to 19, 16 for nether Y:0 to 15)

```
TAG_Compound
├── Y: TAG_Byte
├── block_states: TAG_Compound
│   ├── palette: TAG_List[TAG_Compound]
│   │   └── {Name: TAG_String, Properties?: TAG_Compound}
│   └── data: TAG_Long_Array (omitted if palette size = 1)
└── biomes: TAG_Compound
    ├── palette: TAG_List[TAG_String]
    └── data: TAG_Long_Array (omitted if palette size = 1)
```

### Block States Packing

- 4096 entries (16×16×16), index = `y*256 + z*16 + x`
- Bits per entry: `max(4, ceil(log2(palette_size)))` — **minimum 4 bits**
- Entries per long: `floor(64 / bits_per_entry)`
- Longs needed: `ceil(4096 / entries_per_long)`
- Indices DO NOT cross long boundaries
- Single-palette optimisation: if palette has 1 entry, data array is omitted

### Biome Packing

- 64 entries (4×4×4), index = `y*16 + z*4 + x`
- Bits per entry: `ceil(log2(palette_size))` — **no minimum** (1 bit for 2 biomes)
- If palette size = 1: bits = 0, data omitted
- Same packing rules as block_states

### Heightmap Packing

- 256 entries (16×16), index = `z*16 + x`
- 9 bits per entry (range 0–384)
- 7 entries per long (63/64 bits used)
- 37 longs total = ceil(256/7)
- Values represent `actual_y - world_min_y + 1` (0 = no block)

Types in fully generated chunks:
- MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR, WORLD_SURFACE

## Minimum Viable Synthetic Chunk for Renderers

For unmined-cli to render something visible:

1. **Status**: `"minecraft:full"` (renderers skip proto-chunks)
2. **Sections**: all 24 (overworld), each with:
   - `block_states.palette` with at least `minecraft:air`
   - `biomes.palette` with the correct biome for that location
   - For the surface section: palette needs surface block (grass_block, sand, etc.)
3. **Heightmaps**: MOTION_BLOCKING and WORLD_SURFACE with correct values
4. **Single-palette optimisation** for most sections (air + biome = no data arrays = tiny)

### Size estimate for a minimal chunk

- Air-only section with 1 biome: ~30 bytes NBT (no data arrays)
- Surface section with 2 blocks (stone + grass_block): ~80 bytes NBT
- Full chunk (24 sections): ~800 bytes uncompressed → ~200-400 bytes zlib-compressed
- Region file (32×32 = 1024 chunks): 8192 header + ~300KB compressed data
- Compare: real chunks are 2-8KB compressed each → 2-8MB per region

## NBT Tag Types

| ID | Type | Payload |
|----|------|---------|
| 0 | TAG_End | Nothing |
| 1 | TAG_Byte | 1 byte signed |
| 2 | TAG_Short | 2 bytes BE signed |
| 3 | TAG_Int | 4 bytes BE signed |
| 4 | TAG_Long | 8 bytes BE signed |
| 7 | TAG_Byte_Array | 4-byte length + N bytes |
| 8 | TAG_String | 2-byte length + UTF-8 |
| 9 | TAG_List | 1-byte type + 4-byte length + N payloads |
| 10 | TAG_Compound | Named tags until TAG_End |
| 12 | TAG_Long_Array | 4-byte length + N×8-byte longs |

Named tag: `[type_id: 1B][name_len: 2B BE][name: UTF-8][payload]`
