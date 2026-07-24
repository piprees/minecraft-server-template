# Seed-Roller Scoring Internals

Ground truth: `/Users/pip/Projects/minecraft-server-template/scripts/seed/dimension_profiles.py`. The mod itself ignores all of this at runtime — it only matters for `./dev seed-roll-all`, which is how a hand-written config gets turned into an actual playable world with a chosen seed. Understanding this shapes how you should fill in `seedRoll` and `structures` so the roller can actually find and reward a good seed.

## Mood weights

Every candidate seed is scored on 4 components — namesake (does the spawn biome match the theme), variety (biome diversity), terrain (relief/grain/water targets), structures (wants/shuns) — weighted per mood:

| Mood | namesake | variety | terrain | structures | Use for |
| --- | --- | --- | --- | --- | --- |
| `hard` | 15 | 15 | 25 | 45 | Hostile structures close, brutal terrain. Going here must be worth it. |
| `adventurous` | 15 | 15 | 20 | 50 | Structure-led exploration at sane distances. |
| `dramatic` | 20 | 15 | 40 | 25 | Terrain is the star — high relief, craggy grain. |
| `scenic` | 30 | 20 | 35 | 15 | Stunning and iconic at spawn, low threat. |
| `pastoral` | 25 | 20 | 30 | 25 | Rolling, liveable, buildable. Settlements near, dungeons far. |
| `serene` | 30 | 20 | 30 | 20 | Relaxing but not boring — soft terrain, gentle structures, no hostile pressure. |
| `desolate` | 30 | 10 | 40 | 20 | Empty and evocative — sparse everything, wide horizons. |
| `standard` | 20 | 20 | 30 | 30 | Balanced, vanilla-plus feel. |

Void dimensions (`type: "void"`) override this entirely: `{"namesake": 30, "variety": 55, "terrain": 15, "structures": 0}` — no terrain exists, so biome variety carries the score. Very dangerous worlds (`mobMultiplier >= 2.0`) additionally shift +10 into structures at the expense of namesake/variety — "must be worth it" made concrete.

## Placement bands (for `seedRoll.wants` band-name form)

Fractions of the **playable radius** (`borders.player`, or `8192 / portal.scale` if unset):

| Band | Range (fraction of radius) |
| --- | --- |
| `near_spawn` | 0.00 – 0.30 |
| `spread` | 0.15 – 0.65 |
| `near_border` | 0.45 – 1.00 |

`structureDensity` shifts these bands when using the band-name form (does NOT affect explicit `{min,max}` block ranges in `structures.wants` — those are always literal):

- `dense`: `near_border` → `spread`, `spread` → `near_spawn` (everything pulled inward — makes sense in a smaller, denser world).
- `sparse`: `near_spawn` → `spread`, `spread` → `near_border` (pushed outward).

**Don't pick a `near_border` want on a tiny pocket dimension** — a 1024-radius world has no "far out" in absolute terms; the band is always relative to the actual playable radius, so it still works, but check the resulting block range makes sense (e.g. `near_border` on a 512-radius world is only ~230-512 blocks out — fine for a small structure, silly for something meant to feel remote).

## Mood auto-derivation (when `seedRoll.mood` is omitted)

Set `mood` explicitly — but know what happens if you don't, so you're not surprised:

```
mobMultiplier <= 0.0        -> serene
mobMultiplier <= 0.9        -> scenic
mobMultiplier <= 1.2        -> standard
mobMultiplier <= 1.7        -> adventurous
mobMultiplier > 1.7         -> hard

nether family, no mobMultiplier: portal.scale >= 12 -> hard, >= 8 -> adventurous, else standard

hostileSpawning == false    -> always serene (overrides the above)
structureDensity == "dense" and mood in (standard, adventurous) -> bumped to adventurous
```

## `clearSpawnRadius` per mood (default, overridable via `structures.clearSpawnRadius`)

Structures inside this radius of spawn penalise the roll — the idea being hard/adventurous dims want structures in your face, serene/pastoral dims want breathing room to build:

| Mood | Default clear-spawn radius (blocks) |
| --- | --- |
| `hard` | 0 |
| `adventurous` | 0 |
| `dramatic` | 48 |
| `scenic` | 64 |
| `pastoral` | 80 |
| `serene` | 80 |
| `desolate` | 48 |
| `standard` | 48 |

## Endgame/boss structure safety

`ENDGAME_STRUCTURES` (see `references/structure-names.md` for the full set) — boss-tier structures whose presence near spawn means the adventure is over before it starts. Default `endgame_safe_radius` is `max(256, 0.15 * playable_radius)` unless:

- `structures.endgame.allow` is explicitly set, or
- `mood` is `hard` or `adventurous` (these are "endgame-safe" moods — brutal dims are allowed a boss structure close by, matching "worth it"), or
- `structureDensity` is `dense`.

## Terrain targets by `noiseSettings`

Relief (max−min height across a sample grid), grain (mean |Δheight| between adjacent points), water (fraction of the grid that's wet):

| `noiseSettings` | relief target | grain target | water target |
| --- | --- | --- | --- |
| `adventure:compressed` | 40-160 | 6-26 | 0.0-0.30 |
| `adventure:wide` | 10-60 | 0-6 | 0.05-0.45 |
| (unset / other) | 18-90 | 2-14 | 0.0-0.45 |

`hard`/`dramatic` moods widen relief/grain targets further (relief ×1.25-1.4, grain floor raised); `serene`/`pastoral` narrow relief (×0.7-0.8). `seedRoll.water` (`"sea"`/`"high"`/`"none"`) overrides the water target directly regardless of `noiseSettings`.

## Hostile / dungeon-theme structures

Auto-dropped from a peaceful dimension's structure generation (and pointless to `want` there):

```
ancient_city, trial_chambers, fortress, bastion, sanctum, coliseum, mansion,
monument, pillager_outpost, bandit_towers, bandit_village, illager_fort,
keep_kayra, infested_temple
```

## Endgame / boss-tier structures (near-spawn-penalised set)

```
ancient_city, trial_chambers, mansion, monument, coliseum, keep_kayra,
infested_temple, bandit_towers, bandit_village, illager_fort,
heavenly_rider, heavenly_conqueror, heavenly_challenger, typhon,
shiraz_palace, plague_asylum, mechanical_nest, kisegi_sanctuary,
thornborn_towers, undead_pirate_ship, illager_corsair, illager_galley,
ceryneian_hind, scorched_mines, foundry, sanctum, forbidden_castle,
nether_reactor, mns_nether_tower, nether_temple, phantom_citadel,
enderkeep, end_gate_fortress, mega_ship_crashed, mega_ship_deepslate,
ice_dungeon_l, sand_dungeon_l, ancient_crypt, ancient_temple,
relic_temple, wizard_tower, ocean_fortress, sky_arena, sky_castle_ruin,
sky_castle_tower, creeping_crypt, undead_crypt, illager_hideout,
shrine_tower, trident_trial, lone_citadel, stray_fort, illager_manor,
antiquus_crypta, iceologer_citadel
```

## Seed-group rolling (why some dimensions share measurements instantly)

Many dimensions in this repo are "same world, different curated taste" — identical generation, differing only in wants/shuns/spawn filters/portal/difficulty. The roller measures each seed **once per generation fingerprint** and shares the rows across every dimension with a byte-identical fingerprint, so adding a new dimension that reuses an existing worldgen shape can score near-instantly.

The fingerprint (`generation_fingerprint()`) is computed from: `type`, `noiseSettings`, the **full ordered** biome list + per-biome parameters (reordering or changing even one biome re-deals the whole layout — no partial credit), `structureDensity`, the peaceful flag (`hostileSpawning: false`), worldgen `environment` fields (`minY`/`height`/`logicalHeight`/`coordinateScale`), `borders.generation`, `checkerboardScale`/`layers`/`flatBiome`, `settingsOverrides`, `biomePatches`, whether `exitShrines` is enabled (and its derived spacing, when not explicitly overridden), and `structures.mode`/`list`/`force`/`spacing`. Everything else — `seedRoll`, `portal`, difficulty multipliers, `description`, colours — is scoring/runtime-only and shares freely without affecting the fingerprint.

**Practical implication**: if you want a new dimension to roll instantly against an existing dimension's already-measured seed pool, copy its `type`/`noiseSettings`/`biomes` (in the exact same order) /`structureDensity`/environment fields exactly, and only vary `seedRoll`, `portal`, `difficulty`, `structures.wants`/`shuns`. If you change even one biome, it becomes its own fingerprint group and needs a full independent roll.
