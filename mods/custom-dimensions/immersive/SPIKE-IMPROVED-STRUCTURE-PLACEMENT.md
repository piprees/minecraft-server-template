# Spike — Improved Structure Placement

> **Date:** 2026-07-26 | **Status:** exploration, design decision needed

## Problem statement

The current structure placement system (vanilla `RandomSpreadStructurePlacement`) is a fixed grid: every structure set has a `spacing` (grid cell size in chunks) and `separation` (minimum distance within the cell). This produces:

1. **Predictable patterns.** Fortresses every ~256 blocks, villages every ~416 blocks. Players learn the cadence and stop exploring.
2. **No control over WHERE in the world structures appear.** The grid doesn't know about dimension borders, spawn, or "near the edge." A `near_border` want in a dimension config is a scoring preference the roller evaluates, but the world itself places structures uniformly everywhere.
3. **Scale-invariant spacing, scale-dependent scoring.** Structure sets have fixed spacing regardless of world size. The seed roller's placement bands (`near_spawn`, `spread`, `near_border`) scale with the playable radius. In a 8192-block world, `spread` = 1229–5325 blocks — but the nearest bastion is always ~200 blocks away. The scorer penalises it for being "too close" to the wanted range, producing negative scores for structures found at their natural distances.
4. **No compositional density.** You can't say "I want dungeons in the outer ring and settlements near spawn." Every structure type tiles independently and uniformly.

## Design goal

Replace the grid with a system where:

- The **seed** still controls everything (deterministic, reproducible).
- Structure placement is **non-uniform** and controllable per dimension.
- The dimension config can express **radial preferences** (zones/bands) and **group composition** (what generates together).
- The roller can **catalogue all placements in the world** and score by band occupancy rather than nearest-instance distance.
- Players **never learn the cadence** — spacing is seed-variable, not fixed.

## Proposed system: Structure Noise

### Core idea

Replace the per-set grid placement with a **continuous noise function** evaluated per chunk-region. The noise output determines:

1. **Whether** a structure attempts to place at all (density threshold).
2. **Which group** the placement belongs to (noise value → group range mapping).
3. **Which specific structure** within the group is selected (sub-range or weighted random with chunk-seed).

The noise derives from the world seed + a per-dimension salt + the existing terrain noise (continentalness, erosion, weirdness), making placement biome-aware and terrain-correlated without being biome-locked.

### Noise function

```
structure_noise(x, z) = perlin(
    seed   = world_seed ^ dimension_salt,
    x      = chunk_x * frequency,
    z      = chunk_z * frequency,
    octaves = 2,
    lacunarity = 2.0,
    persistence = 0.5
)
```

Output: continuous value in `[-1.0, 1.0]`, normalised to `[0.0, 1.0]` for range mapping.

The **frequency** parameter controls clumping:
- Low frequency (0.01): structures cluster in regions (continents of structure-rich vs structure-poor).
- High frequency (0.1): structures scatter more uniformly.
- Default: `0.03` — regional variation without extreme deserts.

### Density control

A **threshold** determines the fraction of chunks where any structure attempts to place:

| `structureDensity` | Threshold | Effective hit rate | Feel |
|---|---|---|---|
| `none` | 1.0 | 0% | No organic structures |
| `sparse` | 0.85 | ~15% of regions | Long stretches of nothing |
| `normal` | 0.70 | ~30% of regions | Balanced exploration |
| `dense` | 0.50 | ~50% of regions | Structures around every corner |
| `saturated` | 0.30 | ~70% of regions | Custom gauntlet dims |

Only chunks where `structure_noise(x, z) > threshold` are placement candidates. The remaining value space above the threshold is divided among groups.

### Radial weighting (the band system, realised in worldgen)

A **radial multiplier** modulates the noise based on distance from spawn, making bands a worldgen reality rather than a scoring preference:

```java
float distFromSpawn = sqrt(chunk_x² + chunk_z²) * 16;
float radialFraction = distFromSpawn / playableRadius;

// Band weights (0.0 = suppress, 1.0 = normal, 2.0 = boost)
float weight = group.radialCurve.sample(radialFraction);

// Apply: shift the effective noise up or down
float effective = structure_noise * weight;
```

Each group carries a **radial curve** (a piecewise-linear function over `[0.0, 1.0]`) that controls where in the world it generates:

```json
"groups": {
  "settlements": {
    "radialCurve": [[0.0, 1.5], [0.3, 1.0], [0.7, 0.3], [1.0, 0.0]],
    "comment": "Dense near spawn, fade toward border"
  },
  "dungeons": {
    "radialCurve": [[0.0, 0.0], [0.2, 0.3], [0.5, 1.0], [1.0, 1.5]],
    "comment": "Absent at spawn, dense toward border"
  }
}
```

This makes `near_spawn` / `spread` / `near_border` a **generation** property, not just a scoring wish. The roller doesn't need to hope structures land in the right band — the worldgen PUTS them there.

### Structure groups

Groups are thematic bundles that share a noise range. Existing theme data (`scripts/data/structure-dials.csv`) already classifies 377 sets into: `deco`, `dungeon`, `landmark`, `settlement`, `maritime`, `ruins`, `loot`.

Proposed dimension-config syntax:

```json
"structures": {
  "placement": "noise",
  "groups": {
    "adventure-decorative": {
      "weight": 40,
      "radialCurve": [[0.0, 1.0], [1.0, 1.0]],
      "structures": ["ruins", "explorify:guide_posts", "structory:ruin", "mvs:paths"]
    },
    "settlements-camps": {
      "weight": 20,
      "radialCurve": [[0.0, 1.5], [0.4, 1.0], [0.8, 0.2], [1.0, 0.0]],
      "structures": ["#minecraft:village", "explorify:farmsteads", "nova_structures:taverns"]
    },
    "endgame-dungeons": {
      "weight": 10,
      "radialCurve": [[0.0, 0.0], [0.3, 0.0], [0.5, 0.5], [0.8, 1.5], [1.0, 2.0]],
      "structures": ["dungeons_arise:major_structures", "epic:large_dungeons", "minecraft:trial_chambers"]
    }
  }
}
```

**Weight** determines the fraction of the above-threshold noise space each group occupies. A group with weight 40 in a world with total weight 70 occupies 40/70 ≈ 57% of the placement-eligible space.

Within a group, individual structures compete via sub-weights (matching the vanilla `(w=N)` weighting in structure sets).

### Predefined group presets

Config shorthand for common compositions, expanded at boot:

| Preset | Contents | Use for |
|---|---|---|
| `overworld-vanilla` | villages, outposts, shipwrecks, monuments, mansions, temples | Standard overworld dims |
| `overworld-ruins` | structory ruins, philip's ruins, explorify ruins, abandoned camps | Post-apocalyptic dims |
| `overworld-civilised` | villages, taverns, farmsteads, campsites, guide posts | Pastoral/settled dims |
| `nether-vanilla` | fortresses, bastions, piglin villages | Standard nether dims |
| `nether-incendium` | sanctum, forbidden castle, reactor, pipelines, ruined labs | Incendium-heavy dims |
| `end-cities` | end cities, phantom citadels, enderkeep, ender spires | End urban dims |
| `dungeons-hostile` | all dungeon-theme sets (the `HOSTILE_STRUCTURES` list) | Hard dims |
| `dungeons-archaeology` | ancient crypt, lost soul, sculk dungeon, bone dungeon | Archaeological dims |
| `maritime-wrecks` | shipwrecks, ocean ruins, ocean fortress, conduit ruins | Ocean/frozen dims |

Presets are additive — combine them:

```json
"structures": {
  "placement": "noise",
  "presets": ["nether-vanilla", "nether-incendium"],
  "groups": {
    "endgame-extra": {
      "weight": 5,
      "radialCurve": [[0.0, 0.0], [0.6, 0.5], [1.0, 2.0]],
      "structures": ["mns:mega_fortress", "mtr:nether_temple"]
    }
  }
}
```

### How the noise avoids grids

Vanilla's grid guarantees exactly one placement attempt per grid cell. The noise system instead:

1. Evaluates noise at a fixed sampling rate (every N chunks, where N derives from `structureDensity`).
2. A placement candidate exists only where `noise > threshold`.
3. The seed-derived jitter within a candidate chunk uses the same `ChunkRandom` pattern (deterministic, reproducible) but the SPACING between candidates is variable (noise-driven, not grid-driven).
4. Minimum separation is enforced by a simple "did anything place within K chunks?" exclusion zone, checked at generation time. This prevents pile-ups without creating a visible grid.

The exclusion zone per group replaces `separation`:

| Group type | Exclusion radius (chunks) |
|---|---|
| deco | 4 |
| settlement | 8 |
| landmark | 12 |
| dungeon | 10 |
| maritime | 6 |

### Biome correlation (optional, per-group)

Groups can optionally bind to biome properties via the existing climate router:

```json
"settlements-camps": {
  "biomeAffinity": ["temperature > 0.0", "humidity > -0.5"],
  "comment": "No camps in frozen wastelands"
}
```

This uses the same `continentalness`/`temperature`/`humidity`/`erosion`/`weirdness` parameters the biome sampler already computes — no extra noise evaluation needed. The affinity modulates the group's effective weight at each chunk.

## Impact on the seed roller

### Full-world structure catalogue

The roller can compute every structure placement in the world (deterministic from seed + noise parameters) and store them as:

```json
{
  "structure_all": {
    "bastion": [[278, 31], [-412, 198], [623, -340]],
    "fortress": [[-291, -47], [510, 220]],
    "village": [[120, 80], [340, -150], [890, 420], ...]
  }
}
```

This replaces `nearest_structure` (which only finds the closest grid hit) with a complete census.

### Band-occupancy scoring

Instead of "is the nearest X in the right band?", score becomes "how well does the distribution of X match the configured radial preference?":

```python
def band_score(positions, radial_curve, radius):
    """Score a structure's placement distribution against the desired curve."""
    if not positions:
        return 0.0
    
    # Bin positions by radial fraction
    bins = [0] * 10  # 10 radial bins
    for x, z in positions:
        frac = min(1.0, sqrt(x*x + z*z) / radius)
        bins[int(frac * 9.99)] += 1
    
    # Compare actual distribution to desired curve
    expected = [radial_curve.sample(i/10 + 0.05) for i in range(10)]
    expected_sum = sum(expected) or 1
    expected_norm = [e / expected_sum for e in expected]
    
    actual_sum = sum(bins) or 1
    actual_norm = [b / actual_sum for b in bins]
    
    # Correlation or cosine similarity
    return cosine_similarity(actual_norm, expected_norm)
```

This answers the right question: "Does this seed's structure layout match what this dimension WANTS?" rather than "Is the nearest bastion between 1229 and 5325 blocks?"

### Interim fix (before the mod implements noise placement)

Even without the mod-side change, the roller can already:

1. **Enumerate all grid placements within the playable radius** (the maths is pure — `nearest_structure` already reimplements it; extending to `all_structures_in_radius` is straightforward).
2. **Score by count-in-band** rather than nearest-distance.
3. **Use sqrt-scaled bands** so large-radius dims don't create physically impossible ranges.

This is a scoring-only change (~200 lines in `fast_roller.py` + `score-dimensions.py`) and can ship immediately while the mod-side noise system is developed.

## Implementation path

### Phase 1: Roller-side fix (Python, no mod changes)

1. Add `all_placements_in_radius(seed, spacing, separation, salt, radius)` to `structure_placement.py`.
2. Store full placement list in candidate measurements (`structure_all`).
3. Replace `want_score(nearest_dist, lo, hi)` with `band_occupancy_score(all_positions, radial_curve, radius)`.
4. Remove the negative-score cliff for "too close" structures.
5. Rescore all existing candidates → verify ranking improves.

**Estimated: 2–3 days, no Docker, no mod build, immediate rescore.**

### Phase 2: Config schema for noise placement (JSON + mod parsing)

1. Add `"placement": "noise"` to the `structures` block schema.
2. Define group presets in a new `config/custom-dimensions/structure-groups.json`.
3. Mod parses noise-placement configs at boot (DimensionStructures gains a `NoiseStructurePlacement` path alongside the existing `RandomSpreadStructurePlacement` path).
4. Falls back to grid placement when `"placement"` is absent or `"grid"`.

**Estimated: 1 week. Config-only dims can test immediately; worldgen change is creation-time.**

### Phase 3: Mod-side noise placement (Java, Fabric mixin)

1. New `NoiseStructurePlacement` class implementing `StructurePlacement`.
2. Per-dimension `StructureSetRegistry` override (the mod already does this for `structureDensity` and the exit-shrine set raise).
3. Radial curve evaluation from the dimension's parsed config.
4. Exclusion-zone enforcement via a chunk-local placement cache.
5. Verification: `/locate` still works (it walks candidates instead of grid cells — slightly slower but bounded by the playable radius).

**Estimated: 2–3 weeks. Requires world wipe for affected dims (creation-time).**

### Phase 4: Enriched scoring (Python, uses Phase 3 data)

1. Roller's `_build_sampler` gains a noise-placement evaluator (mirrors the mod's logic).
2. Full-world structure census computed per seed in tier-1 (still instant — it's just noise evaluation + exclusion, no I/O).
3. Band-occupancy scoring replaces band-distance scoring.
4. Variety bonus: dims with noise placement can score "structure diversity" (how many different structures appeared) alongside distribution correctness.

## Open questions

1. **Backwards compatibility.** Existing worlds have grid-placed structures in their chunks. Switching to noise placement means NEW chunks get noise-placed structures while OLD chunks keep grid-placed ones. Is the visible boundary acceptable, or does this require a world wipe? (Same question as any worldgen change — creation-time-only.)

2. **Performance.** Grid placement is O(1) per chunk (one cell lookup). Noise placement is O(1) per chunk too (one noise evaluation + one exclusion check), but the exclusion check needs a small spatial cache. At 377 structure sets the cache is trivial.

3. **Interaction with `structures.force`.** Forced placements (`the_gilded_pit`'s hand-placed bastions etc.) should bypass the noise system entirely — they're constants, not generated. The noise system only controls organic placement.

4. **Interaction with `structures.mode`/`list`.** The allow/reject filter applies BEFORE noise evaluation — a rejected set never enters the noise pool. An allowed-only list restricts the pool to those sets.

5. **Should Phase 1 (roller fix) use the proposed radial curves, or simpler sqrt-scaled bands?** Radial curves are more expressive but require schema additions. Sqrt bands are a mechanical fix to the scoring formula that needs no config changes.

> **Decision needed:** Start with Phase 1 (scoring fix) immediately, or design the full noise system first and ship them together?

## References

- `scripts/data/structure-sets-extracted.csv` — 377 structure sets with spacing/separation/theme
- `scripts/data/structure-dials.csv` — per-set tuning recommendations by dim mood
- `scripts/seed/structure_placement.py` — current `nearest_structure` implementation
- `scripts/seed/fast_roller.py` — tier-1 structure screening
- `scripts/seed/score-dimensions.py` — `want_score` / `shun_score` (the broken formula)
- `scripts/seed/dimension_profiles.py` — band definitions, density shifts, mood weights
- Minecraft's `RandomSpreadStructurePlacement` — the vanilla grid system this replaces
- `mods/custom-dimensions/` — where Phase 3 would live (DimensionStructures, StructureSetOverrides)
