---
title: Scoring Model
description: Mood weight tables, placement bands, density shifts, terrain targets, and how a seedRoll block maps to a 0-100 score — the maths behind score-dimensions.py and fast_roller.py.
tags: [scoring, mood, weights, bands, terrain, structures, namesake, variety]
---

# Scoring Model

Every measured candidate gets a 0–100 score from four weighted components. The maths lives in `dimension_profiles.py` (weights, bands, targets — the profile) and `score-dimensions.py`/`fast_roller.py` (`score_candidate`/`want_score`/`shun_score` — the arithmetic). This file is the reference for both; read it before tuning a `seedRoll` block or arguing with a low score.

## Mood weights

`MOOD_WEIGHTS` in `dimension_profiles.py` gives each mood a `{namesake, variety, terrain, structures}` weight set (normalised to 100 at use):

| Mood | namesake | variety | terrain | structures | Philosophy (`MOOD_BLURBS`) |
| --- | --- | --- | --- | --- | --- |
| `hard` | 15 | 15 | 25 | 45 | Hostile structures close enough to matter, brutal terrain, places to hide and fight. Going here must be worth it. |
| `adventurous` | 15 | 15 | 20 | 50 | Structure-led exploration: plenty to find at sane distances, terrain interesting but traversable. |
| `dramatic` | 20 | 15 | 40 | 25 | Terrain is the star — high relief, craggy grain. Structures are seasoning. |
| `scenic` | 30 | 20 | 35 | 15 | Stunning and iconic at spawn; gentle exploration, low threat. |
| `pastoral` | 25 | 20 | 30 | 25 | Rolling, liveable, buildable. Settlements near, dungeons far. |
| `serene` | 30 | 20 | 30 | 20 | Relaxing but not boring: soft terrain, gentle structures, no hostile pressure. |
| `desolate` | 30 | 10 | 40 | 20 | Empty and evocative — the namesake mood carries it; sparse everything, wide horizons. |
| `standard` | 20 | 20 | 30 | 30 | A believable, balanced world — variety, fair structure spread, vanilla-plus feel. |

If `seedRoll.mood` is omitted, `build_profile()` derives one: mob difficulty multiplier → `mood_from_difficulty()` (`<=0.0` serene, `<=0.9` scenic, `<=1.2` standard, `<=1.7` adventurous, else hard); if there's no difficulty figure and the family is `nether`, `nether_difficulty(scale)` applies the smaller-world-is-harder rule (`scale>=12` hard, `scale>=8` adventurous, else standard); `hostileSpawning: false` always forces `serene` regardless of the above; `structureDensity: dense` nudges `standard`/`adventurous` up to `adventurous`.

**Two overrides replace the mood table entirely, not just its weights:**
- **Void dimensions**: `{namesake: 30, variety: 55, terrain: 15, structures: 0}` — there's no terrain to score, so variety (what's actually findable in the fog) carries the world.
- **Mob difficulty ≥ 2.0**: `structures += 10`, `namesake -= 5` (floor 5), `variety -= 5` (floor 5) — a genuinely dangerous world must be worth it in loot/structure terms, not just survivable.

## Placement bands

`BANDS` gives each legacy band-name shorthand a fraction range of the **playable radius** (`borders.player` when set, else `8192 / portal.scale`):

| Band | Fraction range | Meaning |
| --- | --- | --- |
| `near_spawn` | 0.00 – 0.30 | Close enough to matter immediately |
| `spread` | 0.15 – 0.65 | Broad mid-distance exploration target |
| `near_border` | 0.45 – 1.00 | Far-flung, a destination in itself |

`structureDensity` shifts bands via `DENSITY_SHIFT`: `dense` pulls everything closer (`near_border`→`spread`, `spread`→`near_spawn`); `sparse` pushes everything further (`near_spawn`→`spread`, `spread`→`near_border`). This shift applies ONLY to band-name shorthand in `seedRoll.wants` — an explicit `{"min": N, "max": M}` range in `structures.wants` is absolute and is never density-shifted.

**`structures.wants` and `seedRoll.wants` are scored identically once resolved to a `(lo, hi)` block-distance window** (`want_range()` in `dimension_profiles.py` normalises both to the same shape) — the difference is purely in how the author writes them, not how the roller judges them. See the format-mismatch trap in the main SKILL.md; it's a config-authoring crash, not a scoring difference.

### Want scoring (`want_score` in `score-dimensions.py`)

- Inside `[lo, hi]`: 1.0, plus up to +0.1 "comfort bonus" for sitting near the range's centre.
- Too close (`dist < lo`): scales from -0.5 at spawn up to 1.0 at `lo` — being found WAY too close actively costs points, not just zeroes them.
- Too far (`dist > hi`): linear falloff over one range-width past `hi`.
- Not found, and `hi` is within the ~1600-block locate horizon: 0.0 (it should have been findable and wasn't).
- Not found, but `lo >= 1600` (a genuinely far-out want): 0.8 — absence at that range is compatible with a fine world; the locate radius realistically can't confirm either way.

### Shun scoring (`shun_score`)

Binary: 0.0 if the structure exists closer than its threshold (the shun's `minDistance`, or the whole playable radius for the legacy "must not exist anywhere" bare-list form), else 1.0.

### Clear-spawn radius and density bias

`clear_spawn_radius` (from `structures.clearSpawnRadius`, else `MOOD_CLEAR_SPAWN[mood]`) further penalises ANY battery structure — want or shun — found inside that radius: the player needs breathing room at spawn regardless of what the want range says. Separately, `structureDensity` nudges the whole structures score by how many battery structures were actually found: `sparse` prefers fewer hits (small negative nudge per find), `dense` prefers more (small positive nudge), `normal`/unset applies a mild negative nudge (the density bias assumes "normal" still slightly prefers restraint). When render-pass enrichment data exists (`structure_all`, the full per-structure hit list from the viewer's structure enumeration), the bias uses the TOTAL discovered structure count instead of just the battery's found-count — richer data can dethrone an earlier winner if it reveals an unexpectedly cluttered or empty world.

## Terrain targets

`TERRAIN_TARGETS`, keyed by the `noiseSettings` preset's path suffix (`compressed`/`wide`/unset), gives `{relief, grain, water}` ranges measured from a 3×3 sample grid (spacing from `grid_pitch()`, roughly a quarter of the playable radius):

| Preset | Relief (height spread) | Grain (adjacent Δheight) | Water fraction |
| --- | --- | --- | --- |
| `compressed` | 40–160 | 6–26 | 0.0–0.30 |
| `wide` | 10–60 | 0–6 | 0.05–0.45 |
| unset (default) | 18–90 | 2–14 | 0.0–0.45 |

Mood modulates the targets further: `hard`/`dramatic` widen relief to `(lo*1.25, hi*1.4)` and grain to `(max(lo,3), hi*1.3)` — these moods want MORE violence than the noise preset alone implies. `serene`/`pastoral` narrow relief to `(lo*0.7, hi*0.8)` — gentler than the preset's own range. `seedRoll.water` (`"none"|"high"|"sea"`) overrides the water target outright regardless of preset (`none` → 0.0–0.10, `high` → 0.25–0.8, `sea` → 0.5–1.0).

Void dimensions score terrain as `1.0` only when the 3×3 grid found NO land at all (`max(0, 1 - land_fraction*2)` otherwise) — a "void" with solid ground anywhere on the grid is failing at being a void. Island-type dimensions (`sky_islands`, `nether_islands`, anything `paradise_lost`) blend a land-fraction window (want 25–80% land — real gaps AND real land) with the usual relief/grain windows, weighted 50/30/20.

## Variety scoring

Biome variety samples up to 8 evenly-spaced biomes from the dimension's full `biomes` list (or the namesake list if empty) and locates each. Distance decays quadratically inside the playable radius (`max(0.25, 1 - (d/radius)^2)`) and flatlines at a token 0.15 for anything beyond it. A "balance" term additionally checks that non-namesake biomes exist within HALF the playable radius (not just somewhere inside it) — a monoculture with a token far-flung biome no longer scores as diverse. If more than 2 biomes were probed and the raw variety total sits under 1.5, the whole component is further discounted ×0.7 — a weak spread is punished harder than a merely-average one.
