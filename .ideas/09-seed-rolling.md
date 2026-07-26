# Skill brief: `seed-rolling`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

The seed roller is the most technically sophisticated subsystem in the repo — a pure-Python reimplementation of Minecraft's multinoise biome source, Terralith's spline terrain, and structure placement maths, scoring thousands of seeds per second. It is also 25 Python modules with an eleven-file test suite and three separate documentation sources (`docs/seed-rolling.md`, `scripts/seed/README.md`, `scripts/seed/spike/*`), none of which is task-shaped.

The existing `custom-dimension-authoring` skill already routes an author *to* the roller ("Seed rolling" section, ~15 lines) but stops at the command list. Everything downstream — why a dimension produces zero candidates, what a fingerprint drift warning means, whether to re-roll or rescore — is unrouted. Those two questions ("zero candidates" and "re-roll vs rescore") are the entire operational surface and both have precise answers.

There is also a hard correctness rule that reaches back into mod development: **any new generation-affecting config field must be added to `generation_payload()`** or seed-group grouping silently lies and dimensions become literal world clones.

## Scope

**In:** running the roller, reading its output, choosing between re-roll / rescore / viewer pick, diagnosing zero or bad candidates, understanding the scoring model well enough to tune a `seedRoll` block, seed groups and fingerprints, and the warmup/extractor lifecycle.

**Out:** authoring the dimension JSON itself → the existing `custom-dimension-authoring` skill (link to it, do not duplicate the schema). Applying a rolled seed to a live world → brief 15.

## Source material

| File | What to mine |
| --- | --- |
| `docs/seed-rolling.md` | The two-phase model, the `seedRoll` block reference, the adaptive spawn-filter gate, the worlds array, the scoring components, the pipeline stages |
| `scripts/seed/README.md` (304 lines) | Architecture, data files, module reference, seed-group rolling, the biome sampling algorithm, terrain height maths, rendering, **the troubleshooting section** |
| `scripts/seed/dimension_profiles.py` | `STRUCTS`, mood weights, placement bands, `generation_fingerprint()` / `generation_payload()`, the design philosophy docstring |
| `scripts/seed/score-dimensions.py` | `manifest` / `finalise` subcommands, winner assignment, injective-within-group rule |
| `scripts/seed/fast_roller.py` | Two-tier screening, `MemoSampler`, group handling |
| `scripts/seed/viewer-server.py` | The fork/create/edit config form; `GET /fork-schema` — **"it IS the documentation of valid values, always in sync with the code"** |
| `scripts/seed/candidates.py`, `config/custom-dimensions/candidates/` | The candidate store format |
| `mods/AGENTS.md § Seed rolling pipeline` | Integration points; the fingerprint corollary; the Gson config traps; the staged-overlay mirror rule |
| `AGENTS.md` platform traps | `od -An -td8` on macOS BSD; `grep -P` unavailable; `collective` must not be stripped; BlueMap/DH configs leaking into seedtest dirs |
| `config/custom-dimensions/SKILL.md § Seed rolling` | The existing entry point — stay consistent with it |
| `docs/dimension-profiles-v3.md` | The profile taxonomy |

## Required structure

```
seed-rolling/
├── SKILL.md
└── references/
    ├── scoring-model.md      # the four components, mood weights, bands, how a seedRoll block maps to a score
    ├── seed-groups.md        # fingerprints, the generation payload, drift, injective winner assignment
    └── troubleshooting.md    # zero candidates, flat renders, wrong colours, warmup failures — expanded
```

### SKILL.md must contain

1. **A decision tree at the top**, because the commands look interchangeable and are not:
   | Situation | Command | Why |
   | --- | --- | --- |
   | Config's worldgen fields changed | `./dev seed-roll --dims <slug>` | Measurements describe a world the config no longer generates |
   | Only `seedRoll` weights/wants changed | `./dev seed-rescore` | Rescoring is free; re-rolling is not |
   | Want to see what's banked | `./dev seed-status` | Counts, winners, freshness |
   | Want to override the score ranking by eye | `./dev seed-viewer` → "Make Winner" | Pins a pick above the ranking |
   | Starting clean | `./dev seed-roll --reset` | Wipes all seed data |
2. **The lifecycle facts an agent must not get wrong:** winners are auto-written into the individual dimension config files every 45 s (one timestamped backup per session; `--no-write` disables); **Ctrl+C finalises** with whatever has been measured; re-runs resume; rejected seeds are banked and never re-tried.
3. **The scoring model in one table** — namesake / variety / terrain / structures, with the note that a mob difficulty ≥ 2.0 shifts weight into structures because dangerous worlds must be *worth* it.
4. **Rollability rules**: not `skip: true`, not `superflat`, `void` requires a `biomes` list. Base-world entries never group.
5. **Zero candidates as the headline diagnostic**, with the ranked causes: `spawnFilter` names a biome absent from `biome_params.json` for that family; the family wasn't captured by warmup; the dimension isn't rollable. Give the check commands.
6. **`GET /fork-schema` as the canonical valid-values oracle.** The viewer server builds it at startup from `dimension_profiles.py` + `biome_params.json`, so it is always in sync with the code — better than any hand-maintained list. Tell the agent to query it rather than guessing.
7. **Warmup**, and when it must re-run: after worldgen mod changes (`extract-biome-catalog.py`), on a fresh machine, or when a family's biome count is implausibly low. Note it needs Docker and a local server that has booted at least once.

### Traps to capture

1. **The fingerprint corollary.** Any new generation-affecting config field the mod grows must be added to `generation_payload()` or grouping silently lies — two members sharing a fingerprint and a seed are literal world clones. Add conditionally so pre-existing fingerprints stay byte-stable (worked instance: `shrineSpacing`, 2026-07-24).
2. **Fingerprint drift is a warning, not an error, and only a re-roll fixes it.** Rescoring cannot: the banked measurements describe a different world.
3. **Measurements never transfer across differing biome lists**, even "similar" ones. Same-or-nothing.
4. **`structures.wants` vs `seedRoll.wants` use different value formats** — object vs band-name string; mixing them crashes Gson. `structures.shuns` must be MAP form; `seedRoll.shuns` accepts a list. (Duplicated from the authoring skill on purpose — the roller is where the crash surfaces.)
5. **Overlay-written dims need the staged-overlay mirror.** Tools creating consumer dimensions at runtime must also write into `<config>/custom-dimensions/overlay/dimensions/`, or `fast_roller`/`finalise` can't see them until the next `./dev up`.
6. **`collective` must not be stripped from seed rolls** — 9+ mods depend on it; Fabric fails with a `FormattedException` listing every missing dep.
7. **BlueMap/DistantHorizons configs leak into seedtest dirs.** Copying `data/config/` wholesale brings per-dimension state and causes 70+ map-loading warnings at boot. Delete `config/bluemap` and `config/DistantHorizons` from worker dirs after copying.
8. **macOS: `od -An -td8` produces single-byte values, not 64-bit longs.** Use `-tu8`, or `python3 -c "import os,struct; print(struct.unpack('<q', os.urandom(8))[0])"` for signed 64-bit randoms. **`grep -P` does not exist** — use `grep -oE`.
9. **`LEVEL_TYPE=flat` on the overworld breaks structure placement in ALL custom dimensions** — `createDimensionOptions` templates off `overworldOpts`, and a `FlatChunkGenerator` fails the `instanceof NoiseChunkGenerator` check. Confirmed by A/B test.
10. **Production `sample-noise` diverges from pure-vanilla evaluation** of the same seed and settings — stable and pre-existing, prime suspect c2me. Don't trust absolute coordinates from headless prediction against production without checking.

### Validation section

```bash
./dev seed-status                       # counts, winners, freshness, drift warnings
./dev seed-roll --dims <slug> --no-write   # measure + score without committing
python3 -m pytest scripts/seed/           # the existing test suite (11 test modules)
```

Note the test suite exists — `test_fast_roller.py`, `test_score_dimensions.py`, `test_seed_worker.py`, `test_biome_pipeline.py`, `test_dimension_profiles.py`, `test_preset_terrain.py`, `test_map_renderer.py`, `test_render_integration.py`, `test_viewer_server.py`, `test_world_type_fidelity.py` — and that changing scoring logic means running it.

## Done when

- An agent asked "why does `the_x` have no candidates" checks the spawn filter against `biome_params.json` first, not the pool size.
- An agent that changes a generation-affecting mod field knows to update `generation_payload()` in the same commit.
- The skill never restates the dimension JSON schema — it links to `custom-dimension-authoring`.
