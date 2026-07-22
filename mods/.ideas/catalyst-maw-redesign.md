# The Catalyst Maw — Dimension Redesign Brief

**Theme**: "What the fuck is going on here" — sculk-infected end islands, oppressive, minging, chaotic. A dimension that makes players uncomfortable.

**Current problem**: Type `end` with deep_dark/lush_caves variety targets that are cave biomes — they can't appear on the surface so variety always scores badly. The dimension is end-noised which limits biome expression.

## Proposed changes

### 1. Type change
From `end` to one of:
- `multi_biome` with overworld noise — most flexible, all biomes surface-placeable
- Custom noise preset (`adventure:compressed` or similar) — dramatic vertical terrain
- Consider `nether_islands` or `paradise_lost:paradise_lost` for sky-island variants

### 2. Biome list — oppressive/sculky/end themed
Pick from these surface-compatible biomes that fit "minging and oppressive":

**End-flavoured:**
- `minecraft:end_highlands` — yellow-grey end terrain
- `minecraft:end_midlands` — muted end
- `nullscape:shadowlands` — dark end
- `nullscape:void_barrens` — barren end

**Sculky/dark overworld:**
- `minecraft:deep_dark` (only works with overworld noise as surface biome)
- `minecraft:dark_forest` — oppressive canopy
- `minecraft:pale_garden` — eerie pale trees
- `terralith:moonlight_grove` — dark, blue-tinted
- `terralith:moonlight_valley` — same family
- `minecraft:swamp` — murky
- `minecraft:mangrove_swamp` — tangled roots

**Volcanic/hostile:**
- `terralith:volcanic_peaks` — dark rock, lava
- `terralith:volcanic_crater` — crater terrain
- `terralith:caldera` — collapsed volcano
- `minecraft:basalt_deltas` — grey hostile terrain

**Modded dark:**
- `terralith:cave/infested_caves` — spider-infested feel
- `incendium:withered_forest` — dead trees (if nether biomes allowed)

### 3. Structures
Fit the "minging" theme:
- `minecraft:end_cities` — end structures in a corrupted landscape
- `friendsandfoes:citadel` — looming fortress
- Ancient cities (these ARE surface-placeable)
- Sculk dungeons
- Any dark/ruined structures from the mod pack

### 4. Noise settings
Consider `adventure:compressed` for dramatic vertical terrain that feels claustrophobic, or a custom noise with high weirdness for chaotic shapes.

### 5. Scale / border
Current: scale 1.0. Could increase to 2-4x for a more compressed, intense feel with a smaller playable area.

## Implementation
This is a mod config change in:
- Platform: `.stack/current/stack/data/config/custom-dimensions/dimensions/the_catalyst_maw.json`
- Consumer overlay: `overlay/config/custom-dimensions/dimensions/the_catalyst_maw.json`

After changing type/biomes, re-roll to get candidates with the new configuration.
