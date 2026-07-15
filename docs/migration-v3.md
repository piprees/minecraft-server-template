# Migrating to v3 (worldgen customisation)

v3 is a **major** release: it changes worldgen defaults and extends the
dimension schema. Nothing in v3 changes `.env` keys, the overlay directory
contract, or compose structure — the breaking part is **what new chunks look
like**.

## The one decision that matters: existing world vs new world

Terrain and structure changes only affect chunks that have **not yet
generated**. On an existing world:

- Already-explored terrain keeps its old shape forever.
- New chunks at the edge of explored terrain generate with the v3 tune —
  you WILL see seams (cliffs, biome shears) at the border. Tectonic's
  chunk blending only covers its own v2→v3 upgrade, not config changes.
- Structure `frequency` reductions never move existing placements (safe);
  the villages **spacing** change re-rolls that placement grid for future
  chunks, which can look inconsistent near the border.

**The v3 defaults are intended for new worlds and new custom dimensions.**
For a live world you have two honest options:

1. **Accept seams** in unexplored regions and upgrade in place.
2. **Pin the old behaviour**: override `overlay/config/tectonic.json` with
   the factory values (`vertical_scale 1.125, erosion_scale 0.25,
   ridge_scale 0.25, continents_scale 0.13, flat_terrain_skew 0.1, max_y
   320, ultrasmooth false, temperature/vegetation scale 0.25`) and replace
   `overlay/config/datapacks/structures/` with an empty pack. Custom
   dimensions that gained `noiseSettings`/`structureDensity` in
   `multiverse_config.json` only apply those to *their* ungenerated chunks.

Never run `./ops reset-seed` as part of an upgrade without the full human
ritual — it deletes the world.

## What changed

| Area | v2 | v3 |
| --- | --- | --- |
| Terrain | Tectonic factory defaults | `config/tectonic.json` ships the wide/realistic tune (erosion 0.12, ridge 0.18, continents 0.1, vertical 1.0 + boost 0.3, max_y 448, ultrasmooth) |
| World height | 320 | 448 (`max_y`, overworld dimension type becomes 512 tall) |
| Structures | every mod's defaults | `structures` override datapack: sparse & natural overworld (villages MORE common, mega-dungeons rarer); `dense`/`sparse` presets in `config/datapack-presets/` |
| Dimension schema | `name/type/dimensionId/seed/biome/hostileSpawning` | + optional `noiseSettings` (e.g. `adventure:wide`/`adventure:compressed`) and `structureDensity` (`dense/normal/sparse/none`); peaceful dims auto-drop dungeon-theme structure sets |
| Platform datapacks | `config/datapacks/` never reached the world | synced to `data/world/datapacks/` on every full deploy / `./dev up` (overlay packs of the same name win) |
| Seed rolling | one hard-coded score at roll time | measure/score split: long-format `seed-measurements.csv`, profiles at report time, dimension roll mode |

## Consumer checklist

1. Bump `STACK_VERSION` to `v3` in `.env`, run `./dev update`.
2. Decide existing-world stance (above) BEFORE the first full deploy.
3. If you overrode `config/multiverse_config.json` via overlay, merge the
   new optional fields at your leisure — absent fields keep old behaviour.
4. Structure preset: default is the sparse-&-natural tune. To swap:
   `cp -r .stack/current/stack/config/datapack-presets/dense/structures overlay/config/datapacks/structures`
5. Seed workflows: `./dev seed-roll` now measures (no score column);
   `./dev seed-report --profile overworld-natural` (or `classic`) scores.
   An existing `seed-results.csv` is not readable by the new reporter —
   archive it and re-roll.

## For the platform's own cutover (new world)

Seed selection happens BEFORE the release is adopted: world seed via
`./dev seed-roll --profile overworld-natural`, per-dimension seeds via
`./dev seed-roll --dimension <name> --profile <dim profile>` for every
dimension whose terrain/structure profile changed (see
`docs/dimension-profiles-v3.md`), then a human picks winners from the
reports and writes them into `.env` / `config/multiverse_config.json`.

## Removing default mods under v3

- **Structure mods**: safe. The `structures` override datapack carries an
  ownership manifest, and every deploy/`./dev up` strips overrides owned by
  mods in your `overlay/mods-remove.txt` before the server boots. Their
  structure sets simply revert to nothing (mod absent).
- **Tectonic or Terralith**: NOT supported with v3. The jar-baked
  `adventure:wide`/`adventure:compressed` presets reference their noise
  definitions, so removing either breaks the boot. If you must run without
  them, also remove every `noiseSettings` pin from your multiverse config
  overlay — full self-containment is tracked in
  `mods/.ideas/optional-mods-hardening.md`.
