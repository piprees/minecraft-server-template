# Fork-Dimension Config GUI (seed viewer)

Idea: when forking a dimension from the seed viewer, replace the bare
name+description dialog with a proper config form — select boxes and
multi-selects for everything that makes a dimension a dimension — so a
fork can diverge from its parent without hand-editing JSON afterwards.

## Current fork flow (baseline)

- Lightbox → `Fork dimension <kbd>F</kbd>` (`.action-btn.create-dim`) opens
  `#create-dim-dialog`: **name + description only**.
- POST `/create-dimension {parent_dim, seed, name, description}` →
  `viewer-server.py:_handle_create_dimension` clones the parent's config
  file, stamps `seed` + `parentDimension`, copies the parent's candidate
  store entry.
- Any real divergence (biomes, structures, mood, portal, borders) means
  opening the JSON by hand (`/edit-config`) and re-rolling.

## Proposed form

One dialog (or a dedicated `/fork?dim=X&seed=Y` page if the dialog gets
cramped), pre-populated from the parent config, organised in sections:

| Section | Controls | Options source |
| --- | --- | --- |
| Identity | name (text, `^[a-z][a-z0-9_]*$`), description (textarea) | — |
| World | type (select), noiseSettings (select), structureDensity (select), scale (select 1/4/8/12/16), border radius (select: 256 pocket / 512 / 1024 / 2048 / 4096 / 8192 / custom number) | types + noise presets from `dimension_profiles.py` + `noise_configs.json` |
| Mood & scoring | mood (select, blurb shown inline), water (select: default/sea/high/none), spawnFilter (multi-select, subset of chosen biomes) | `MOOD_BLURBS` / `MOOD_WEIGHTS` keys |
| Biomes | searchable multi-select, grouped by namespace/family, ~1800 entries — needs a filter box, not a bare `<select>` | `biome_params.json` |
| Structures — wants | rows of [structure (searchable select)] × [distance (select: near_spawn / spread / near_border / custom min–max block inputs)] | `STRUCTS` keys (~230), `BANDS` |
| Structures — shuns | multi-select of structures + optional minDistance | `STRUCTS` keys |
| Difficulty | mobMultiplier (select 0–3 in 0.5 steps), hostileSpawning (checkbox), playerLuck (select) | — |
| Portal | frameBlock, igniterItem (searchable selects over a curated block/item list; free-text fallback), color (colour input), particleType (select), sounds (three selects over vanilla sound events) | curated lists shipped as JSON |

## Server side

- New GET `/fork-schema` endpoint: serves the option lists as one JSON blob
  (built once at startup from `dimension_profiles.py` + `biome_params.json`
  + `noise_configs.json`). Keeps the HTML template dependency-free — the
  form is built by JS from the schema, not baked into the template.
- `/create-dimension` grows an optional `config` object: when present it is
  validated (structure names via `resolve_struct`, biomes against
  biome_params, bands via `want_range`) and deep-merged over the cloned
  parent config before writing. Absent → today's behaviour.
- After creation, auto-trigger `/reroll` for the new dim (the form's whole
  point is diverging, so the parent's candidates rarely apply — today the
  fork only copies the picked seed's entry).

## UI notes

- Keep the zero-dependency constraint: no framework, plain `<dialog>` +
  inline JS. Searchable multi-select = text filter box over a scrollable
  checkbox list (same pattern as the family filter, no library needed).
- Pre-populate every control from the parent config so a fork with one
  tweak is one click, not a re-entry of 30 fields.
- Show the derived consequences live: playable radius (borders.player),
  band block-ranges for the chosen radius ("near_spawn = 0–77 blocks"),
  peaceful-drop warnings when hostileSpawning=false strips a hostile want.
- Validation errors inline per field, mirroring server-side checks.

## Why bother

- The catalyst-maw redesign loop (edit JSON → re-roll → look → edit again)
  is exactly what this collapses into one screen.
- Pocket dimensions (512×512) make forking cheap to test — a form makes
  the iteration loop seconds instead of minutes.
- The schema endpoint doubles as documentation: it IS the list of valid
  moods/bands/structures/biomes, always in sync with the code.
