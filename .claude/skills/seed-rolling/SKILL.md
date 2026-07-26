---
name: seed-rolling
description: Runs and diagnoses the pure-Python seed roller in scripts/seed/ that scores candidate seeds for every custom dimension and the four base worlds (overworld, the_nether, the_end, paradise_lost) against a dimension's seedRoll block — namesake, variety, terrain, and structures — without booting Minecraft after warmup. Covers ./dev seed-roll / seed-rescore / seed-status / seed-viewer, the candidate-bank lifecycle (45s auto-write, Ctrl+C finalises, rejected seeds banked forever), seed-group generation fingerprinting, and the GET /fork-schema oracle. Use when a dimension rolls zero candidates, seed-status reports a winner as STALE or DRIFTED, deciding re-roll vs rescore after a seedRoll edit, tuning mood weights or structure wants/shuns bands, or chasing a "config invalid — skipped" Gson crash. Does not cover the dimension JSON schema — see custom-dimension-authoring for that.
---

# Seed Rolling

`scripts/seed/` is a pure-Python reimplementation of Minecraft's multinoise biome source, Terralith's terrain spline, and structure placement maths — it scores thousands of candidate seeds per second for every custom dimension without running the game (after a one-time warmup that does need Docker). This skill covers running it, reading its output, and diagnosing it when scores are bad or candidates don't appear.

**This skill does not describe the dimension JSON schema.** For `seedRoll` field syntax, biome ids, structure short names, or the mood/difficulty philosophy, read `.claude/skills/custom-dimension-authoring/SKILL.md` (`custom-dimension-authoring`) first — this skill assumes a dimension config already exists and focuses on the roller itself.

## Decision tree — the commands look interchangeable and are not

| Situation | Command | Why |
| --- | --- | --- |
| A dimension's worldgen fields changed (`type`, `noiseSettings`, `biomes`, `structureDensity`, etc.) | `./dev seed-roll --dims <slug>` | Banked measurements describe a world the config no longer generates — only re-rolling produces valid data |
| Only `seedRoll` weights/wants/mood changed, worldgen untouched | `./dev seed-rescore` | Rescoring replays banked measurements against the current config for free; re-rolling is not free |
| Want to see what's banked before doing anything | `./dev seed-status` | Counts, winner, freshness (`fresh`/`STALE`/`DRIFTED`) per target, no Docker needed |
| Want to override the score ranking by eye | `./dev seed-viewer` → pick a candidate → "Use this seed" | Pins a human pick (`winnerPinned: true`) above the score ranking; survives future rescoring |
| Starting completely clean | `./dev seed-roll --reset` | Wipes `.seedtest/` and every `candidates/*.json` store |

All four subcommands (`seed-roll`, `seed-rescore`, `seed-status`, `seed-viewer`) are wired in `examples/consumer/dev` and documented in the root `README.md`. `seed-roll` is a shell wrapper (`scripts/seed/roll-all.sh`) around warmup + `fast_roller.py` + `score-dimensions.py finalise`; the other three call `score-dimensions.py` subcommands or `viewer-server.py` directly.

## Lifecycle facts you must not get wrong

- **Winners auto-write into the individual dimension config files every 45 seconds** during a roll session (one timestamped backup per session — `.config-backed-up` marker in `.seedtest/` prevents repeat backups; `--no-write` disables writing entirely and only measures + scores).
- **Ctrl+C finalises** with whatever has been measured so far — it is not a discard. Re-runs resume from banked state; nothing is re-measured.
- **Rejected seeds are banked in `candidates/{slug}.json`'s `rejected` map and never retried**, even across sessions and resets of `.seedtest/` (the candidate store under `config/custom-dimensions/candidates/` is the durable state — `--reset` wipes both, but a plain re-run of `seed-roll` does not).
- **Void and superflat dimensions are skipped** — `dimension_profiles.rollable()` excludes `superflat` unconditionally and `void` unless it has a `biomes`/`biome` list (a void with no listed biomes has nothing to measure: no terrain, no listed biomes to locate).
- Base-world entries (`overworld.json`, `the_nether.json`, `the_end.json`, `paradise_lost.json` — filenames matching `dimension_profiles.BASE_WORLD_IDS`) roll too, as `worlds[]` entries. The overworld winner becomes the top-level `worldSeed`; nether/end/paradise_lost winners are written as `seed` on their `worlds[]` entry. **New chunks generate on the winning seed; existing chunks keep the old terrain** — only a world wipe (`./ops reset-seed` on production) regenerates everything.

## Scoring model — one table

Every candidate gets 0–100 from four weighted components (weights come from `MOOD_WEIGHTS` in `dimension_profiles.py`, keyed by `seedRoll.mood`):

| Component | What it measures | Notes |
| --- | --- | --- |
| **namesake** | Spawn biome matches `seedRoll.spawnFilter` (or first 4 config biomes if unset) | Full credit (1.0) only within 48 blocks of origin; partial credit scales down to 1024 blocks away; total absence within the 768-block sampling window rejects the candidate outright (see Zero candidates below) |
| **variety** | Listed biomes actually locatable near spawn (`locate`-equivalent), proximity-weighted so fringe/border-only biomes don't inflate a monoculture | Sampled down to 8 evenly-spaced biomes for lists longer than 8 |
| **terrain** | Relief / grain / water fraction from a 3×3 climate grid vs targets keyed by `noiseSettings` (`compressed` wants violence, `wide` wants gentle rolling, voids want zero land, islands want real gaps) | Mood shifts the targets: `hard`/`dramatic` want more relief, `serene`/`pastoral` want less |
| **structures** | Every `want` scored by placement-band distance, every `shun` scored by absence/distance | A mob difficulty ≥ 2.0 shifts weight FROM namesake/variety INTO structures — dangerous worlds must be worth it |

A void dimension overrides the weights entirely (`namesake 30 / variety 55 / terrain 15 / structures 0` — there's no terrain to score). `clearSpawnRadius` (from `structures.clearSpawnRadius` or the mood default in `MOOD_CLEAR_SPAWN`) penalises any battery structure found too close to spawn regardless of its want range.

## Rollability rules

A dimension rolls only if `dimension_profiles.rollable()` says yes:

- Not `"seedRoll": {"skip": true}` (the explicit opt-out).
- Not `type: "superflat"`.
- If `type: "void"`, it must carry a `biomes` (or legacy `biome`) list — an empty void has nothing to measure.
- Everything else (including `checkerboard`, whose biome grid is seed-independent but whose terrain/structures still vary) rolls.

## Zero candidates — the headline diagnostic

Check causes **in this order**, cheapest first:

1. **`seedRoll.spawnFilter` names a biome absent from `biome_params.json` for that dimension's family.** This is by far the most common cause. `fast_roller.tier2_measure` only gates on the spawn filter when at least one namesake biome is representable by the sampler for that family (`namesake_in_sampler = namesake_set & sampler_biomes`); if NONE of the filter biomes exist in the sampled family, every candidate is rejected outright. Cross-check every `spawnFilter` entry against `.claude/skills/custom-dimension-authoring/references/biome-catalogue.md` (the `custom-dimension-authoring` skill) (or `GET /fork-schema`, below) for the dimension's actual family — not just "does this biome exist in the game", but "does it exist for THIS family's noise config".
2. **The dimension's family wasn't captured by warmup.** `biome_params.json` needs ≥5 entries tagged with a family for it to be usable; `roll-all.sh`'s warmup phase re-triggers automatically when a family's count is implausibly low, but only if Docker is available and `data/mods/` is populated (`./dev up` must have run at least once).
3. **The dimension isn't rollable** — see Rollability rules above. Check with `./dev seed-status` (a non-rollable dim never appears in its output at all) or by re-reading the config against `dimension_profiles.rollable()`.

**How the actual spawn gate works (verify this against `fast_roller.py`, not older prose):** for each survivor seed, `tier2_measure` samples a 768-block-radius, 256-block-step grid (`biome_sampler.spawn_filter`) for the nearest matching namesake biome. If nothing matches anywhere in that grid, the candidate is rejected and banked (never re-rolled) and the worker draws a fresh seed. If something matches, the candidate is **always accepted** — a match within 48 blocks scores full namesake credit; anything further out (up to the 768-block search radius) still banks `spawn_filter_dist` and earns partial proximity credit in scoring, capped below a true 48-block spawn. There is no multi-stage widening gate keyed on an attempt counter in the current pipeline — if you see that description elsewhere (older prose in `docs/seed-rolling.md` describes one), trust `fast_roller.py`'s `tier2_measure` instead; it's the code that actually runs for `./dev seed-roll`.

## `GET /fork-schema` — the canonical valid-values oracle

`scripts/seed/viewer-server.py` builds this JSON blob lazily on first request (cached for the server's lifetime) from `dimension_profiles.py` + `biome_params.json`: every dimension `type`, `noiseSettings` preset, `structureDensity` value, mood (with its blurb), band name and its fraction range, every `STRUCTS` short name, every hostile structure name, water preference, and **every installed biome id grouped by namespace**. It is built from the same source the roller scores against, so it can never drift out of sync the way a hand-written reference table can.

```bash
./dev seed-viewer                                       # starts the server on :8765
curl -s http://127.0.0.1:8765/fork-schema | python3 -m json.tool | less
```

Prefer this over guessing a biome id or structure short name from memory — it's what the viewer's own fork/create/edit form validates against (`_validate_fork_config` in `viewer-server.py`), and a bad value there returns a field-level error rather than silently producing a zero-candidate dimension.

## Warmup — when it must re-run

Warmup (`roll-all.sh` phase 1) extracts structure sets from mod JARs into `.seedtest/.structure_sets/` and dumps `biome_params.json` from a short-lived, stripped-down MC server boot (`/customdim dump-biome-params`, ~90s). It needs **Docker** and a **local server that has booted at least once** (`./dev up`, so `data/mods/` is populated). It re-runs automatically when:

- `.seedtest/.structure_sets/` doesn't exist yet.
- `biome_params.json` doesn't exist, or a family's tagged biome count is implausibly low (< 5) — this is the automatic re-trigger `roll-all.sh` checks before every roll.
- You've changed worldgen mods and need `biome_catalog.json`/`biome_params.json` refreshed by hand: `scripts/seed/extract-biome-catalog.py <data-dir>`.

A warmup failure ("ERROR: biome param dump failed") leaves scoring incomplete but does not stop the roll — dimensions in the affected family will simply zero-candidate per the diagnostic above.

## Traps

1. **The fingerprint corollary.** Any new generation-affecting config field must be added to `dimension_profiles.generation_payload()` or seed-group rolling silently lies — two dimensions sharing a fingerprint AND a seed are literal world clones sharing a doubly-measured world. Worked instance (2026-07-24): derived exit-shrine spacing made `borders.player` generation-affecting for `exitShrines` dims — the payload gained a *conditional* `shrineSpacing` key, added only when `exitShrines.enabled` and no explicit `structures.spacing` override exists, specifically so every pre-existing non-shrine fingerprint stayed byte-stable (an always-present key would have DRIFTED every candidate store at once).
2. **Fingerprint drift is a warning, not an error, and only a re-roll fixes it.** `./dev seed-status` prints `DRIFTED (generation config changed since measurement — re-roll)` when a winner's stamped fingerprint no longer matches the dimension's current `generation_fingerprint()`. Rescoring cannot fix this — the banked measurements describe a world the config no longer generates. `STALE` (a plain config-hash mismatch, no fingerprint involved) is the milder case `seed-rescore` DOES fix for free.
3. **Measurements never transfer across differing biome lists, even "similar" ones.** `generation_payload()` includes the FULL ordered biome list — one biome's difference re-deals the whole layout and re-keys the fingerprint. Same-or-nothing.
4. **`structures.wants` vs `seedRoll.wants` use different value formats — mixing them crashes Gson with "config invalid — skipped".** `structures.wants` is `{"short_name": {"min": N, "max": M}}` (absolute blocks, `Map<String, StructureWant>` server-side); `seedRoll.wants` is `{"short_name": "near_spawn"|"spread"|"near_border"}` (band-name strings, roller-only, free-form). Putting a band string in `structures.wants` or a `{min,max}` object in `seedRoll.wants` crashes the mod's Gson parser at boot. Same family of bug: `structures.shuns` must be MAP form (`{"village": {}}`); `seedRoll.shuns` accepts a bare list. This is duplicated from `custom-dimension-authoring` on purpose — the roller (and the viewer's fork-form validation in `viewer-server.py`) is where the crash actually surfaces.
5. **Overlay-written dimensions need the staged-overlay mirror.** Anything that creates or edits a consumer dimension at runtime — the viewer's fork/create/edit form, or any other tool — must also write the same file into `<config>/overlay/dimensions/` (the staged copy `dev-up.sh` produces inside `data/config/custom-dimensions/overlay/`), or `fast_roller.py`/`score-dimensions.py` can't see the dimension until the next `./dev up` re-stages it. `viewer-server.py`'s `_handle_create_dimension` does this automatically when `--winner-overlay` is set; a hand-rolled script would not.
6. **`collective` must not be stripped from a seed-rolling warmup base dir.** 9+ mods depend on it; without it Fabric throws a `FormattedException` listing every missing dependent at boot. Check `roll-all.sh`'s `STRIP_PATTERNS` list before adding new mods to strip.
7. **DistantHorizons configs leak into seedtest dirs.** `roll-all.sh`'s `prepare_base_dir` already deletes `config/DistantHorizons` from the warmup base dir — if you build a custom warmup harness, copy this or expect 70+ map-loading warnings at boot.
8. **macOS: `od -An -td8` produces single-byte values, not 64-bit longs** — the `8` is a field-width argument, not a byte-size one. Use `-tu8` for unsigned, or `python3 -c "import os,struct; print(struct.unpack('<q', os.urandom(8))[0])"` for a signed 64-bit random seed (this is exactly what `fast_roller.random_seed()` and `score-dimensions.random_signed_seed()` do). **`grep -P` does not exist on macOS BSD grep** — use `grep -oE` instead.
9. **`LEVEL_TYPE=flat` on the overworld breaks structure placement in every custom dimension**, not just the flat one. The mod's `createDimensionOptions` templates off `overworldOpts`; a flat overworld makes that a `FlatChunkGenerator`, which fails the `instanceof NoiseChunkGenerator` check the `multi_biome` case (and others) rely on. Confirmed by A/B test (`AGENTS.md` platform traps). If every locate suddenly returns "Could not find" across the whole roll, check the overworld's `LEVEL_TYPE` before suspecting the roller.
10. **Production `sample-noise` diverges from pure-vanilla evaluation of the same seed and settings** — stable, pre-existing, and prime suspect is c2me's chunk-system/noise modules (`mods/AGENTS.md`). The roller's climate model is vanilla-semantics, so any bias is shared and scoring stays relative between candidates — but don't trust an ABSOLUTE coordinate predicted headlessly against what production actually generates without checking `customdim sample-noise` first.

## Validation — do not skip

```bash
./dev seed-status                          # counts, winner, freshness, DRIFTED warnings — no Docker needed
./dev seed-roll --dims <slug> --no-write   # measure + score without committing a winner
python3 -m pytest scripts/seed/            # the existing test suite (10 test modules)
```

The test suite exists — `test_biome_pipeline.py`, `test_dimension_profiles.py`, `test_fast_roller.py`, `test_map_renderer.py`, `test_preset_terrain.py`, `test_render_integration.py`, `test_score_dimensions.py`, `test_seed_worker.py`, `test_viewer_server.py`, `test_world_type_fidelity.py` — and changing any scoring logic in `dimension_profiles.py`, `fast_roller.py`, or `score-dimensions.py` means running it before trusting the change.

## References

- `references/scoring-model.md` — mood weight tables, placement bands, density shifts, terrain targets, and how a `seedRoll` block maps to a score component by component.
- `references/seed-groups.md` — generation fingerprints, `generation_payload()` field-by-field, drift, and injective winner assignment within a group.
- `references/troubleshooting.md` — zero candidates, flat/wrong-family renders, wrong colours, warmup failures, expanded with the exact check commands.
- `.claude/skills/custom-dimension-authoring/SKILL.md` (`custom-dimension-authoring`) — the dimension JSON schema itself. Read this first if you haven't already; this skill assumes it.
