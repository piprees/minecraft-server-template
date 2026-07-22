# Fork-Dimension Config GUI — Implementation Prompt

You're working in `/Users/pip/Projects/minecraft-server-template`. Implement
the fork-dimension configuration form designed in
`mods/.ideas/fork-dimension-config-gui.md` (read it first, in full) in the
seed viewer, then verify it end-to-end against the local consumer at
`~/Projects/elfydd`.

Read `AGENTS.md` (repo root) first. This is pure Python/HTML work in
`scripts/seed/` — no mod changes, no Java.

## Current state (verified 2026-07-22)

- Fork flow today: lightbox → `Fork dimension (F)` → `#create-dim-dialog`
  (name + description only) → POST `/create-dimension {parent_dim, seed,
  name, description}` → `viewer-server.py:_handle_create_dimension` clones
  the parent config + stamps `seed`/`parentDimension`.
- Winner overlay files for platform-known dims are now
  `{"overrides": {seed, spawn}}` — full-copy overlays are banned because
  they freeze platform config at write time and mask later changes (this
  bug ate two fixes in one day). Forked dims are consumer-added, so a fork
  writes a FULL config file — that's correct for forks; keep the
  distinction straight.
- The viewer is a single generated HTML file (`viewer_template.html` =
  CSS + JS template, `score-dimensions.py` fills it) served by
  `viewer-server.py`. Zero dependencies, inline everything, dark theme.
  `viewer_template.html` ships in the stack bundle — if you add ANY new
  file, add it to the `MANIFEST` array in `scripts/build-stack-bundle.sh`
  or consumers crash (two files were missed this way in one release; the
  lint check does NOT catch Python imports or data files).

## Scope

1. **GET `/fork-schema`** on viewer-server: one JSON blob built at startup
   from `dimension_profiles.py` (types, `MOOD_BLURBS`/`MOOD_WEIGHTS` keys,
   `BANDS`, `STRUCTS` short names ~230), `noise_configs.json` (noise
   presets), and `biome_params.json` (~1800 biome ids grouped by
   namespace + family). Include the schema version so the JS can detect
   drift.
2. **The form** replacing the bare dialog — sections and controls exactly
   per the ideas doc's table (Identity / World / Mood & scoring / Biomes /
   Structures wants + shuns / Difficulty / Portal). Constraints:
   - Plain `<dialog>` + inline JS, no framework, no fetch of external
     assets (works from `file:` minus live actions).
   - Searchable multi-select = filter input over a scrollable checkbox
     list; must stay usable with 1800 biomes (render lazily or virtualise
     with plain JS).
   - Pre-populate every control from the parent config; a one-tweak fork
     must not require re-entering 30 fields.
   - Live derived hints: playable radius from `borders.player`, band
     block-ranges for the chosen radius ("near_spawn = 0–77 blocks" at
     r=256), and a warning chip when `hostileSpawning: false` will strip a
     hostile want (list them; `HOSTILE_STRUCTURES` is in
     dimension_profiles).
   - Keyboard + focus: the existing `:focus-visible` rules and `Escape`
     handling extend to the form; the dialog must trap focus.
3. **POST `/create-dimension`** grows an optional `config` object:
   validate server-side (structure names via `resolve_struct`, biomes
   against biome_params, band strings/range objects via `want_range`,
   shuns as MAP form only — the mod Gson-crashes on list-form
   `structures.shuns`), deep-merge over the cloned parent, write, respond
   with per-field errors on rejection (the JS shows them inline).
4. **Auto-reroll**: on successful create, trigger the existing `/reroll`
   for the new dim and surface the job progress in the UI (poll pattern
   already exists — `pollJob`).

## Verify (all of it, yourself)

1. `python3 scripts/seed/test_score_dimensions.py`,
   `test_biome_pipeline.py`, `test_dimension_profiles.py`,
   `test_world_type_fidelity.py` — all stay green. Add tests for the
   server-side validation (happy path + each rejection class).
2. Sync changed files to the stack elfydd actually runs before testing
   there: `STACK=~/Projects/elfydd/.stack/$(readlink ~/Projects/elfydd/.stack/current)/stack/scripts/seed`
   (resolve the symlink — a stale hardcoded version cost a session real
   time).
3. Live loop in `~/Projects/elfydd`: `./dev seed-viewer`, fork a dim from
   the lightbox with real config changes (different biomes, a want with a
   custom range, a shun, smaller border), confirm: file written with
   exactly the edited fields, validation errors render inline for a bad
   structure name, the auto-reroll completes and the new dim's card
   appears with candidates. Screenshot-level check of the form at 900px
   and 600px widths (the viewer's existing breakpoints).
4. The written config must boot: copy it into
   `data/config/custom-dimensions/dimensions/`, `docker restart mc`,
   confirm the dimension registers (`customdim list`) with zero
   `config invalid` log lines. Cave/sky/multi_biome types all valid picks.
5. `./scripts/test-scripts.sh --quick` before committing.

## Non-goals

- No redesign of the existing expand/collapse or lightbox behaviour.
- No editing of EXISTING dims through this form (that's `/edit-config`'s
  job) — forks only.
- Do not cut a release; leave that to Pip.
