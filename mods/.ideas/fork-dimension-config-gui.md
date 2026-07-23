# Fork-Dimension Config GUI (seed viewer)

Idea: when forking a dimension from the seed viewer, replace the bare name+description dialog with a proper config form — select boxes and multi-selects for everything that makes a dimension a dimension — so a fork can diverge from its parent without hand-editing JSON afterwards.

## Current fork flow (baseline)

- Lightbox → `Fork dimension <kbd>F</kbd>` (`.action-btn.create-dim`) opens `#create-dim-dialog`: **name + description only**.
- POST `/create-dimension {parent_dim, seed, name, description}` → `viewer-server.py:_handle_create_dimension` clones the parent's config file, stamps `seed` + `parentDimension`, copies the parent's candidate store entry.
- Any real divergence (biomes, structures, mood, portal, borders) means opening the JSON by hand (`/edit-config`) and re-rolling.

## Proposed form

One dialog (or a dedicated `/fork?dim=X&seed=Y` page if the dialog gets cramped), pre-populated from the parent config, organised in sections:

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

- New GET `/fork-schema` endpoint: serves the option lists as one JSON blob (built once at startup from `dimension_profiles.py` + `biome_params.json`
  - `noise_configs.json`). Keeps the HTML template dependency-free — the form is built by JS from the schema, not baked into the template.
- `/create-dimension` grows an optional `config` object: when present it is validated (structure names via `resolve_struct`, biomes against biome_params, bands via `want_range`) and deep-merged over the cloned parent config before writing. Absent → today's behaviour.
- After creation, auto-trigger `/reroll` for the new dim (the form's whole point is diverging, so the parent's candidates rarely apply — today the fork only copies the picked seed's entry).

## UI notes

- Keep the zero-dependency constraint: no framework, plain `<dialog>` + inline JS. Searchable multi-select = text filter box over a scrollable checkbox list (same pattern as the family filter, no library needed).
- Pre-populate every control from the parent config so a fork with one tweak is one click, not a re-entry of 30 fields.
- Show the derived consequences live: playable radius (borders.player), band block-ranges for the chosen radius ("near_spawn = 0–77 blocks"), peaceful-drop warnings when hostileSpawning=false strips a hostile want.
- Validation errors inline per field, mirroring server-side checks.

## Why bother

- The catalyst-maw redesign loop (edit JSON → re-roll → look → edit again) is exactly what this collapses into one screen.
- Pocket dimensions (512×512) make forking cheap to test — a form makes the iteration loop seconds instead of minutes.
- The schema endpoint doubles as documentation: it IS the list of valid moods/bands/structures/biomes, always in sync with the code.

---

# Fork-Dimension Config GUI — Implementation Prompt

You're working in `/Users/pip/Projects/minecraft-server-template`. Implement the fork-dimension configuration form designed in `mods/.ideas/fork-dimension-config-gui.md` (read it first, in full) in the seed viewer, then verify it end-to-end against the local consumer at `~/Projects/elfydd`.

Read `AGENTS.md` (repo root) first. This is pure Python/HTML work in `scripts/seed/` — no mod changes, no Java.

## Current state (verified 2026-07-22)

- Fork flow today: lightbox → `Fork dimension (F)` → `#create-dim-dialog` (name + description only) → POST `/create-dimension {parent_dim, seed, name, description}` → `viewer-server.py:_handle_create_dimension` clones the parent config + stamps `seed`/`parentDimension`.
- Winner overlay files for platform-known dims are now `{"overrides": {seed, spawn}}` — full-copy overlays are banned because they freeze platform config at write time and mask later changes (this bug ate two fixes in one day). Forked dims are consumer-added, so a fork writes a FULL config file — that's correct for forks; keep the distinction straight.
- The viewer is a single generated HTML file (`viewer_template.html` = CSS + JS template, `score-dimensions.py` fills it) served by `viewer-server.py`. Zero dependencies, inline everything, dark theme. `viewer_template.html` ships in the stack bundle — if you add ANY new file, add it to the `MANIFEST` array in `scripts/build-stack-bundle.sh` or consumers crash (two files were missed this way in one release; the lint check does NOT catch Python imports or data files).

## Scope

1. **GET `/fork-schema`** on viewer-server: one JSON blob built at startup from `dimension_profiles.py` (types, `MOOD_BLURBS`/`MOOD_WEIGHTS` keys, `BANDS`, `STRUCTS` short names ~230), `noise_configs.json` (noise presets), and `biome_params.json` (~1800 biome ids grouped by namespace + family). Include the schema version so the JS can detect drift.

2. **The form** replacing the bare dialog — sections and controls exactly per the ideas doc's table (Identity / World / Mood & scoring / Biomes / Structures wants + shuns / Difficulty / Portal). Constraints:
   - Plain `<dialog>` + inline JS, no framework, no fetch of external assets (works from `file:` minus live actions).
   - Searchable multi-select = filter input over a scrollable checkbox list; must stay usable with 1800 biomes (render lazily or virtualise with plain JS).
   - Pre-populate every control from the parent config; a one-tweak fork must not require re-entering 30 fields.
   - Live derived hints: playable radius from `borders.player`, band block-ranges for the chosen radius ("near_spawn = 0–77 blocks" at r=256), and a warning chip when `hostileSpawning: false` will strip a hostile want (list them; `HOSTILE_STRUCTURES` is in dimension_profiles).
   - Keyboard + focus: the existing `:focus-visible` rules and `Escape` handling extend to the form; the dialog must trap focus.

3. **POST `/create-dimension`** grows an optional `config` object: validate server-side (structure names via `resolve_struct`, biomes against biome_params, band strings/range objects via `want_range`, shuns as MAP form only — the mod Gson-crashes on list-form `structures.shuns`), deep-merge over the cloned parent, write, respond with per-field errors on rejection (the JS shows them inline).

4. **Auto-reroll**: on successful create, trigger the existing `/reroll` for the new dim and surface the job progress in the UI (poll pattern already exists — `pollJob`).

## Verify (all of it, yourself)

1. `python3 scripts/seed/test_score_dimensions.py`, `test_biome_pipeline.py`, `test_dimension_profiles.py`, `test_world_type_fidelity.py` — all stay green. Add tests for the server-side validation (happy path + each rejection class).

2. Sync changed files to the stack elfydd actually runs before testing there: `STACK=~/Projects/elfydd/.stack/$(readlink ~/Projects/elfydd/.stack/current)/stack/scripts/seed` (resolve the symlink — a stale hardcoded version cost a session real time).

3. Live loop in `~/Projects/elfydd`: `./dev seed-viewer`, fork a dim from the lightbox with real config changes (different biomes, a want with a custom range, a shun, smaller border), confirm: file written with exactly the edited fields, validation errors render inline for a bad structure name, the auto-reroll completes and the new dim's card appears with candidates. Screenshot-level check of the form at 900px and 600px widths (the viewer's existing breakpoints).

4. The written config must boot: copy it into `data/config/custom-dimensions/dimensions/`, `docker restart mc`, confirm the dimension registers (`customdim list`) with zero `config invalid` log lines. Cave/sky/multi_biome types all valid picks.

5. `./scripts/test-scripts.sh --quick` before committing.

## Final note

I also wanted to emphasise that we should be able to not only fork, but create a new dimension from scratch and edit an existing dimension with the same form. The only difference is that the parent config is empty/defaults or pre-populated, and the form is pre-populated with any defaults or existing config for a given dim. The validation and auto-reroll logic should be identical.
