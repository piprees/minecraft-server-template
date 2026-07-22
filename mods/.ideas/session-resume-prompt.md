# Session Resume Prompt — Seed Viewer & Renderer

Hand this to the next agent to continue where we left off.

## Project

`/Users/pip/Projects/minecraft-server-template` — a Minecraft modded server template.
Consumer repo: `/Users/pip/Projects/elfydd` (uses `.stack/v3.0.1/stack/scripts/seed/` which is synced from the template).

## What was built (2026-07-21/22)

### Biome Map Renderer (`scripts/seed/biome_renderer.py`)
- Pure Python renderer producing unmined-style top-down map images from seed + biome data
- Tree canopy simulation (per-biome leaf types: oak, birch, spruce, cherry, etc.)
- MC MaterialColor palette, coral in warm oceans, structure footprints
- Per-family height clamping (nether/end/paradise_lost)
- Paradise Lost noise config was WRONG — fixed `generator/weirdness` noise and added missing `continentalness`
- Dimension scale factor adjusts zoom (nether 8x = 8x zoom)
- Renders: normal 1024px/8K blocks, hires 2048px/32K blocks

### Scoring (`scripts/seed/score-dimensions.py`)
- Structures found too close to spawn now score NEGATIVE: `score = dist/lo * 1.5 - 0.5`
  - At spawn: -0.5 (heavy penalty). At range minimum: 1.0.
- Comfort bonus for structures dead-centre of range (up to 1.1x)
- Proximity bonus for biomes found within half radius (1.05x)
- Total score capped at 100
- Multiple dimension configs updated with thematic structures/variety biomes

### Seed Viewer (`scripts/seed/viewer_template.html` + `viewer-server.py` + `score-dimensions.py`)
- **Lightbox detail modal**: click candidate image → split panel (image left, detail right)
  - Header: `dimension | score 🏆 | seed` then `**spawn biome**` then inline score parts
  - Meta badges: type, mood, noise preset, mob multiplier (colour-coded), border, scale
  - Sections: Terrain (relief/grain/water), Structures (sorted by distance, ✅/⚠️/❌ with counts from enrichment), Biomes (full survey or variety distances)
  - Bottom-pinned action buttons: Use this seed `U`, Shortlist `X`, Fork dimension `F`
- **Arrow key navigation** between candidates in lightbox
- **Show borders toggle**: scales all dimension card images relative to the largest border
- **Lightbox border ring**: red square at border/coverage ratio on hires images
- **Block size label** on every card (shows render coverage, updates when hires loads)
- **Auto-refresh**: polls for new images every 10s, upgrades to hires
- **Background rendering**: server starts first, batch renders normal then hires with CLI progress
- **Structure enumeration**: `find_all_in_radius()` on startup for top 10 candidates per dim
- **Biome survey**: grid sample within border on startup for top 10 candidates
- Both stored in candidate store JSON as `structure_all` and `biome_survey`
- CSS custom properties (15 tokens), responsive breakpoints (900px, 600px), focus indicators
- Scoring legend at page top, copy rewrites (34 changes from UX audit)
- `index.html` instead of `viewer.html`, query params instead of hash

### Dimension Config Updates (elfydd overlay)
Updated configs for: the_catalyst_maw (→nether_islands with oppressive biomes), the_end, the_burning_archipelago, the_crimson_nexus, the_endless_expanse, the_glacial_drift, the_pillared_void, the_red_monument, the_sun_kingdoms (shuns ocean monuments, low water terrain), the_lantern_pools, the_verdant_hollow, the_greenreach, the_stonemantle, the_basalt_spires, paradise_lost, the_slatemouth (added lava fluid)

## Key files

| File | Purpose |
|---|---|
| `scripts/seed/biome_renderer.py` | Map renderer — batch + single render |
| `scripts/seed/terrain_height.py` | Spline evaluator for terrain heights |
| `scripts/seed/surface_rules.py` | Surface colours, tree canopy data, leaf colours |
| `scripts/seed/noise_configs.json` | Per-family noise parameters |
| `scripts/seed/score-dimensions.py` | Scoring + viewer HTML generation |
| `scripts/seed/viewer_template.html` | Viewer CSS + JS template |
| `scripts/seed/viewer-server.py` | HTTP server + render/enrich on startup |
| `scripts/seed/structure_placement.py` | Structure position calculator |
| `scripts/seed/biome_sampler.py` | Multinoise biome sampler |
| `mods/.ideas/seed-viewer-audit-reports.md` | Three audit reports (technical, UX, copy) |
| `mods/.ideas/seed-viewer-improvement-prompt.md` | Remaining improvement backlog |

## Syncing to elfydd

After any change to seed scripts:
```bash
# NOTE: sync to the stack elfydd ACTUALLY runs — resolve the `current`
# symlink (was v3.0.1 when this doc was written; v3.0.2 as of 2026-07-22).
STACK="/Users/pip/Projects/elfydd/.stack/$(readlink /Users/pip/Projects/elfydd/.stack/current)/stack/scripts/seed"
for f in biome_renderer.py terrain_height.py surface_rules.py noise_configs.json \
         score-dimensions.py viewer_template.html viewer-server.py \
         structure_placement.py biome_sampler.py; do
  cp scripts/seed/$f "$STACK/$f"
done
```

## What's next (from audit backlog)

### Immediate
- Wire `structure_all` data into the biome renderer to draw structure positions ON the map images
- Wire `biome_survey` into scoring (currently only used for display, not scoring)
- Re-roll dimensions that got config updates: `./dev seed-roll`

### Viewer improvements remaining
- Keyboard support for dim cards (tabindex + role=button + Enter/Space)
- ARIA on lightbox (role=dialog, focus management)
- `loading="lazy"` on generated `<img>` tags
- DocumentFragment for `buildUngrouped()` DOM cloning
- Progress indicator: X/77 dimensions reviewed

### Rendering improvements
- Per-block surface texture variation (sand shimmer, rock grain)
- Shoreline brightening (water adjacent to land)
- Better water rendering (MC water colour with depth)

### Scoring
- Consider scoring on structure COUNT within border (not just nearest distance)
- Review all 77 dimensions for missing variety/structure targets
- Some biomes in spawn filters don't exist in biome_params (causes high rejection)

## Tests

```bash
python3 scripts/seed/test_biome_pipeline.py    # 31 tests
python3 scripts/seed/test_score_dimensions.py  # 13 tests
```

## Commands

```bash
./dev seed-roll                    # Roll seeds (no rendering)
./dev seed-viewer                  # Render + enrich + serve viewer
./dev seed-viewer --refresh        # Wipe renders and regenerate
./dev seed-rescore                 # Rescore without re-rolling
```
