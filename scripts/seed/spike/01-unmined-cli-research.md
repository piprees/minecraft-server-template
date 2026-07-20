# unmined-cli Research Report

## Key Finding

**unmined-cli fundamentally requires full block state data.** There is no biome-only, heightmap-only, or seed-based rendering mode. The CLI always needs real `.mca` region files with actual block states in every chunk section. The cubiomes seed overlay exists only in the Windows GUI — not in CLI exports.

## What unmined-cli Reads

| Data | Required? | Used for |
|------|-----------|----------|
| Block states (palette + data per section) | **YES — mandatory** | Every pixel's colour comes from block→tag→colour mapping |
| Biome palette per section | Yes for full quality | Biome tints on grass/foliage/water, biome-specific styling |
| Heightmaps | Yes for shadows | Elevation gradients, shadow calculations |

Without block data, unmined produces **nothing** — it's fundamentally a block-state-to-colour mapper.

## Rendering Pipeline

1. **Block tags** (`default.blocktags.minecraft.js`, 30KB) — maps every vanilla block to semantic tags (`#ground`, `#water`, `#leaves`, `#oak`, `#artificial`, etc.)
2. **Stylesheet** (`default.stylesheet.minecraft.js`, 32KB) — maps tags to HSL colours with biome overrides, elevation gradients, and lightness curves
3. **Custom overrides** — `custom.blockstyles.txt`, `custom.biometints.txt`, `custom.blocktags.txt`, `custom.colors.txt`

~140 named colour IDs (like `map.land`, `map.water.cold`, `map.leaves.savanna`). For modded blocks, unmined averages textures from the client JAR (`--java-client-jar`).

## CLI Options

```
unmined-cli image render
  --world=<path>              Required. Game folder
  --output=<file>             Required. Output PNG
  --dimension=<id>            0=Overworld, -1=Nether, 1=End, or custom ns:id
  --area=b(x,z,w,h)          Render area in block coords
  --zoom=N                    -5 to 3 (negative = zoomed out)
  --topY=N / --bottomY=N      Y bounds (nether ceiling, cave maps)
  --shadows=true|2d|3d|3do    Shadow mode
  --textures=true             Use block textures (effective at zoom >= 1:1)
  --java-client-jar=<path>    MC JAR for texture extraction
  --trim                      Trim transparent pixels
  -c                          Continue on chunk errors
```

No `--biome` flag. No seed-based rendering. No heightmap-only mode.

## Alternative Renderers — All Need Block Data

| Renderer | Status | Block data needed? | Notes |
|----------|--------|-------------------|-------|
| unmined-cli | Active | Yes | Best for top-down maps |
| BlueMap CLI | Active (in stack) | Yes + light data | 3D isometric, needs MORE than unmined |
| Overviewer | Dead | Yes | Devs recommend BlueMap |
| Mapcrafter | Dead | Yes | Last release for MC 1.13 |
| PapyrusCS | Active | Yes | Bedrock only (LevelDB) |

**No existing renderer can produce terrain maps from biomes + heightmaps alone.** Every renderer is a block-state-to-colour mapper.

## Current Usage in seed_worker.py

```python
# Lines 1046-1130: the only reason the system forceloads + saves chunks
unmined-cli image render \
  --world=<world_path> \
  --output=<png> \
  --dimension=<ns:id> \
  --area=b(-256,-256,512,512) \
  --zoom=0 \
  --trim \
  --shadows=true \
  --textures=true \
  -c \
  --log-level=warning
```

Nether adds `--topY=127`. Render is ~1s natively. The 20s bottleneck is forceload+save, not unmined-cli itself.

## Implication for Synthetic .mca Approach

A synthetic .mca file with **only biome palettes and heightmaps** (no block states) will produce a **blank image** from unmined-cli. To use unmined-cli, synthetic .mca files must contain actual block state data — at minimum, the surface block for each column (grass_block, sand, stone, netherrack, etc.) mapped from the biome.
