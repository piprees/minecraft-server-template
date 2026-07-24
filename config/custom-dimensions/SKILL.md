---
name: custom-dimension-authoring
description: Author or edit a custom-dimensions Minecraft dimension config (config/custom-dimensions/dimensions/<slug>.json for platform defaults, overlay/config/custom-dimensions/dimensions/<slug>.json for consumer overrides) from a short theme prompt. Use whenever asked to create a new dimension, reskin/rebalance an existing one, tune its difficulty or portal, or troubleshoot why a dimension config won't boot or won't roll seeds. Covers the full JSON schema, the mood/difficulty scoring philosophy, structure wants/shuns, portal setup, and the seed-rolling validation workflow. Always consult this before hand-writing or hand-editing a dimension JSON file — the schema has silent-failure traps that are easy to get wrong from general Minecraft/JSON knowledge alone.
---

# Custom Dimension Authoring

You are writing a config file for the **custom-dimensions** Fabric mod (platform repo: `minecraft-server-template`, consumer repos like `elfydd`). Given a short prompt describing a dimension's theme and difficulty, your job is to produce a single JSON file that is valid, boots without errors, and can have a seed rolled and scored for it.

## Ground truth — read the source, don't guess

This document is a distilled reference. When something isn't covered here, or you're unsure, go to the source rather than guessing (unknown/invalid fields fail silently or crash Gson — see [Traps](#traps-read-this-before-you-write-json)):

| Question | Look here |
| --- | --- |
| Full field-by-field schema | `references/schema-reference.md` in this folder |
| Exact mood weights, band math, difficulty derivation | `references/scoring-internals.md` in this folder |
| Every valid structure short name (130+) | `references/structure-names.md` in this folder |
| The mod's own docs (authoritative) | `/Users/pip/Projects/minecraft-server-template/mods/custom-dimensions/README.md` |
| Mod dev conventions, verification loop, architecture traps | `/Users/pip/Projects/minecraft-server-template/mods/AGENTS.md` |
| Platform-wide dimension lifecycle traps (world-wipe rules etc.) | `/Users/pip/Projects/minecraft-server-template/AGENTS.md` (§ "Dimension lifecycle traps") |
| Seed-rolling pipeline internals | `/Users/pip/Projects/minecraft-server-template/scripts/seed/README.md` |
| The scoring source of truth (mood weights, structure ids, bands) | `/Users/pip/Projects/minecraft-server-template/scripts/seed/dimension_profiles.py` |
| 84 real, shipped examples to pattern-match against | `config/custom-dimensions/dimensions/*.json` (platform defaults) or consumer `overlay/config/custom-dimensions/dimensions/*.json` |

**Before writing anything, read 2-3 existing dimensions with a similar size/mood** to the one you're building — `the_gauntlet.json` (small, brutal), `the_wuthering_wisteria.json` (small, peaceful), `overworld.json` (large, standard) are good anchors. Real shipped files are more reliable than any description of the schema, including this one.

## Where the file goes

```
overlay/config/custom-dimensions/dimensions/<slug>.json
```

- The **filename is the dimension id** — never set `dimensionId` in the JSON (it's a legacy field; omit it).
- `<slug>` must be lowercase alphanumeric with `_`/`-`/`/`. This repo's convention is `the_<name>` (83 of 84 existing dims follow it) — match it unless told otherwise.
- **Namespace matters and is easy to get wrong**: if `<slug>` matches one of the 84 filenames already shipped by the platform (`config/custom-dimensions/dimensions/` in the template repo — same names as this folder, since elfydd overrides every platform default), the dimension keeps the platform namespace `adventure:<slug>`. If `<slug>` is genuinely new, it gets namespaced `elfydd:<slug>` (from `BRAND_SLUG` in `.env`). Check which case you're in with:
  ```bash
  ls /Users/pip/Projects/minecraft-server-template/config/custom-dimensions/dimensions/ | grep -i <slug>
  ```
  If it's a brand-new dimension, tell the user its id will be `elfydd:<slug>`, not `adventure:<slug>`.
- A file with no top-level `"overrides"` key is a **full replacement/definition**. A file with `"overrides": {...}` **deep-merges** over the platform default (only sensible when overriding an existing platform dimension). An empty `{}` **disables** that dimension entirely. Every existing elfydd dimension file is a full replacement (no `overrides` wrapper) — write new ones the same way unless you're deliberately doing a small tweak on top of a platform default.

## The workflow

1. **Read the prompt and pick the shape.** Decide: `type`, target `mood`, and playable size (`borders.player`). See [Size ↔ difficulty](#size--difficulty-the-philosophy) below — this is the load-bearing decision that drives almost everything else.
2. **Check for name/namespace collisions** (above) and pick `<slug>`.
3. **Pick biomes.** For `multi_biome` (the most common type — used by ~most of the 84 existing dims), list 8-20 real biome ids that fit the theme, mixing vanilla with installed datapack mods (`terralith:`, `incendium:`, `nullscape:`, `natures_spirit:`, `paradise_lost:`). Grep existing dimension files for biome ids as a sanity check that they're real/spelled correctly — a typo'd or unregistered biome id is silently filtered out (and if the whole list ends up empty, it silently falls back to `minecraft:plains`, wrecking the theme). Pull the biome list from a similarly-themed existing dimension and adapt it if you're unsure what's installed.
4. **Set `borders`, `difficulty`, `structureDensity`** per the size↔difficulty table.
5. **Set `structures.wants`/`structures.shuns`** (or `seedRoll.wants`/`seedRoll.shuns` — see [Traps](#traps-read-this-before-you-write-json), the two blocks use *different* value formats and mixing them up crashes the mod). Pick structure short names from `references/structure-names.md` that fit the theme and mood.
6. **Set `portal`** — frame block/tag that fits the theme, an igniter item, a colour or particle, sounds if you want a bespoke feel. Check existing files for `igniterItem` reuse (`grep -h igniterItem *.json`) before picking one, for variety, unless deliberately sharing.
7. **Set `seedRoll`** — `mood`, `spawnFilter` (3-8 biomes a good spawn must land in), and a one-line `description` echoed from the theme prompt. This block is what the roller actually scores against; get it right even though the mod ignores it at runtime.
8. **Set `spawn`** — usually `[0, 64, 0]`; the roller overwrites this once a winning seed is picked.
9. **Validate** — see [Validation](#validation-do-not-skip-this). A config that "looks right" can still crash Gson or silently drop your entire biome list.
10. **Roll a seed** if the user wants a working world (not just a config draft) — see [Seed rolling](#seed-rolling-making-it-a-real-world).

## Size ↔ difficulty: the philosophy

This is documented in the seed roller's source, not the mod itself (`scripts/seed/dimension_profiles.py`, top-of-file philosophy comment) — it's a design convention for this server, not something the mod enforces:

> Hard dims (dense + hostile + small playable radius) must be WORTH IT: hostile structures close, brutal terrain, places to hide/explore/fight. Easy/peaceful dims are relaxing but not boring: scenery, variety, gentle structures. Nether rule: the smaller the playable world (higher portal scale), the harder it should be; larger worlds are easier and more varied.

In practice, across the 84 shipped dimensions, small worlds are almost never "medium" — they're either brutal or fully peaceful, never middling. Use this table as a starting point and adjust to the theme:

| `borders.player` | Feel | `difficulty.mobMultiplier` | `structureDensity` | typical `seedRoll.mood` |
| --- | --- | --- | --- | --- |
| 256–1024 (pocket) | Peaceful retreat OR brutal gauntlet — pick one, never middling | `0.0` (peaceful) or `2.5–3.5` (brutal) | `none`/`sparse` (peaceful) or `dense` (brutal) | `serene` or `hard` |
| 2048 | Small, usually hard | `1.5–2.5` | `dense` or `normal` | `hard` / `adventurous` |
| 4096 | Medium | `1.0–1.5` | `normal` | `adventurous` / `standard` / `dramatic` |
| 8192 (full/default) | Large, varied, easier per-area | `0.0–1.3` | `normal`/`sparse` | `standard` / `scenic` / `pastoral` / `serene` / `desolate` |

For **nether-family** dimensions specifically, the roller derives mood straight from `portal.scale` if no explicit `seedRoll.mood` is set: `scale >= 12` → hard, `scale >= 8` → adventurous, else standard. Smaller playable nether (`scale` up) = harder, matching the general rule.

If `seedRoll.mood` is omitted entirely, the roller derives it from `difficulty.mobMultiplier`: `<=0.0` serene, `<=0.9` scenic, `<=1.2` standard, `<=1.7` adventurous, `>1.7` hard — and a `hostileSpawning: false` peaceful flag always forces `serene` regardless. **Set `mood` explicitly anyway** — it drives what the roller scores (namesake/variety/terrain/structures weighting) and an explicit description is always better than inference. See `references/scoring-internals.md` for the exact weight table per mood.

`playerLuck` (loot roll bonus) climbs with difficulty and danger: brutal pocket dims often carry `2.0-3.0` alongside high `mobMultiplier` — the "worth it" principle again. Peaceful dims also often carry elevated luck (`2.0-3.0`) as a reward for a dimension with nothing to fight.

## Minimal worked example

A complete, valid, medium-difficulty `multi_biome` dimension:

```json
{
  "type": "multi_biome",
  "description": "A frozen crystal cave dimension, medium difficulty.",
  "biomes": [
    "minecraft:ice_spikes",
    "minecraft:frozen_peaks",
    "minecraft:snowy_slopes",
    "minecraft:grove",
    "minecraft:dripstone_caves",
    "minecraft:deep_dark",
    "terralith:glacial_chasm",
    "terralith:skylands_winter"
  ],
  "borders": { "player": 4096, "generation": 4096 },
  "difficulty": { "mobMultiplier": 1.3 },
  "structureDensity": "normal",
  "portal": {
    "frameBlock": "minecraft:packed_ice",
    "igniterItem": "minecraft:ender_eye",
    "color": "88CCFF",
    "lightLevel": 11,
    "scale": 1.0,
    "cooldown": 40,
    "sounds": {
      "ignite": "block.portal.trigger",
      "enter": "block.portal.travel",
      "exit": "block.portal.travel"
    }
  },
  "structures": {
    "wants": { "igloo": { "min": 256, "max": 1200 }, "trial_chambers": { "min": 1800, "max": 4096 } },
    "shuns": { "village": {} }
  },
  "seedRoll": {
    "mood": "adventurous",
    "spawnFilter": ["minecraft:ice_spikes", "minecraft:frozen_peaks", "terralith:glacial_chasm"],
    "description": "A frozen crystal cave dimension, medium difficulty."
  },
  "spawn": [0, 64, 0]
}
```

This uses only the fields that appear in the vast majority (30/84+) of shipped dimensions. Advanced fields (`exitShrines`, `exits`, `environment`, `anchor`, `singleUse`, `aura`, `biomePatches`, `settingsOverrides`, `checkerboardScale`/`layers`/`flatBiome`, `structures.force`/`spacing`/`mode`) are all real and documented in `references/schema-reference.md`, but appear in **0-1 of the 84 existing dimensions** — only reach for them if the theme specifically calls for a mechanic they provide (e.g. a one-shot portal that seals behind you needs `singleUse`; a death-triggers-a-different-exit needs `exits`). Don't add them speculatively.

## Traps (read this before you write JSON)

1. **`structures.wants` and `seedRoll.wants` use DIFFERENT value formats — mixing them up crashes the mod (Gson parse failure), not a soft warning.**
   - `structures.wants`: `{"structure_short_name": {"min": N, "max": M}}` — absolute **block distances**.
   - `seedRoll.wants`: `{"structure_short_name": "near_spawn"|"spread"|"near_border"}` — **band names**, fractions of the playable radius (see `references/scoring-internals.md` for the exact fractions).
   - Putting a band-name string in `structures.wants`, or a `{min,max}` object in `seedRoll.wants`, is invalid and will crash config loading for that dimension.
2. **`structures.shuns` must be MAP form**, `{"structure_short_name": {}}` or `{"structure_short_name": {"minDistance": N}}` — a bare list `["village"]` there crashes Gson the same way. (`seedRoll.shuns` DOES accept a bare list — that's the one place list-form shuns are valid.)
3. **All worldgen fields are creation-time-only and effectively permanent**: `type`, `noiseSettings`, `biomes`, `biomePatches`, `settingsOverrides`, `checkerboardScale`, `layers`, `flatBiome`, `seed`, `environment.minY/height/logicalHeight/coordinateScale`. Once a dimension's world exists on disk, editing these fields in the config has **no effect** until a full `data/world` wipe. Get the worldgen shape right before the first `./dev up` that creates it, or plan for a wipe.
4. **Portal/runtime fields ARE re-read every boot**, no wipe needed: `portal`, `exitPortal`, `exits`, `exitShrines`, `anchor`, `singleUse`, `aura`, `difficulty`, `borders.player`, `structureDensity`, `structures.spacing`/`mode`/`force`. Safe to iterate on freely.
5. **Unknown/invalid block or item ids fail silently, not loudly.** A typo'd `frameBlock` never crashes — the portal just never ignites, with no error in the logs. Double-check block/item ids are real (check Minecraft/mod wikis, or grep an existing config for the same id).
6. **`color` is a bare 6-digit hex, no `#` prefix**: `"88CCFF"`, not `"#88CCFF"`.
7. **`lightLevel` is 0-15** (most shipped dims use `11`). **`cooldown` is in ticks**, 20 ticks = 1 second (default `40` = 2s).
8. **A tag-only `frameBlock`** (e.g. `"#minecraft:logs"`) **needs an explicit `framePlaceBlock`** for mod-built frames (arrival/exit portals) or it silently falls back to obsidian.
9. **Peaceful dims (`hostileSpawning: false`) auto-drop dungeon-theme structure sets** — don't bother listing hostile structures (`ancient_city`, `trial_chambers`, `fortress`, `mansion`, etc. — full list in `references/structure-names.md`) in `wants` for a peaceful dimension; they're a no-op there.
10. **Endgame/boss structures should generally stay away from spawn** unless the dimension is explicitly `hard`/`adventurous`-mood or `dense` — the roller penalises endgame structures near spawn otherwise. Override with `structures.endgame: {"allow": true, "safeRadius": N}` if you deliberately want an endgame structure close.

## Validation (do not skip this)

There is no standalone schema validator — validation happens at server boot and via the seed roller. Do both before calling a dimension config done:

```bash
# 1. Syntax + basic semantic validation: boot the local server
./dev up
docker logs mc 2>&1 | grep -iE 'custom-dimensions|WARN|ERROR' | tail -40

# 2. Confirm the dimension actually exists and is reachable
docker exec -i mc rcon-cli "execute in adventure:<slug> run seed"   # or elfydd:<slug> — see namespace note above
```

Loud failures (dimension skipped, logged clearly): invalid JSON syntax, `structures.wants`/`shuns` format violations (trap #1/#2 above).
Silent/soft failures (no crash, but wrong): unknown block/item ids, empty biome list after filtering (falls back to plains), tag-only `frameBlock` without `framePlaceBlock` (falls back to obsidian), unrecognised portal `shape` (every ignition attempt silently rejected).

## Seed rolling (making it a real world)

A dimension config alone doesn't have a "real" seed until it's rolled and scored — `seed` in a hand-written config is just a placeholder unless deliberately chosen. The pipeline is pure Python (fast after first Docker warmup):

```bash
./dev up                            # stage the new config into data/config/ first
./dev seed-roll-all --dims <slug>   # roll + render candidates for just this dimension
./dev seed-status                   # check candidate counts, winner, freshness
./dev seed-viewer                   # interactive HTML picker (optional — winners auto-pick otherwise)
```

Winners are written back into `config/custom-dimensions/candidates/<slug>.json` and the dimension config's own `"seed"` field. Requirements for a dimension to be **rollable** at all (from `dimension_profiles.rollable()`):
- Not `"seedRoll": {"skip": true}`.
- `type: "superflat"` is **never** rollable.
- `type: "void"` is only rollable if it has a `biomes` list (a void with no biomes has nothing to measure).
- Everything else (including `checkerboard`, whose grid is seed-independent but whose terrain/structures still vary) is rollable.

If `./dev seed-roll-all --dims <slug>` produces **zero candidates**, the most common cause is `seedRoll.spawnFilter` listing a biome that doesn't exist in the biome parameter table for that family — every candidate gets rejected at the spawn check. Cross-check the biome ids in `spawnFilter` against the `biomes` list (they should mostly overlap) and against biomes used in a real shipped dimension of the same family.

Two dimensions sharing byte-identical worldgen config (same `type`, `noiseSettings`, biome list, `structureDensity`, etc. — see `references/scoring-internals.md` for the exact fingerprint fields) share seed measurements automatically ("seed-group rolling") — you don't need to do anything for this, but be aware that changing *any* worldgen field on a dimension that's part of such a group re-keys it and its old measurements will show as drifted.
