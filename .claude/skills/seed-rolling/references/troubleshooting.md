---
title: Troubleshooting
description: Zero candidates, flat or wrong-family renders, wrong colours, and warmup failures in the seed roller — expanded diagnostics with exact check commands.
tags: [zero-candidates, warmup, rendering, biome_params, troubleshooting]
---

# Troubleshooting

Expanded diagnostics for the seed roller. Check causes in the order given — they're ordered cheapest-to-verify first, not by severity.

## Zero candidates

1. **`seedRoll.spawnFilter` names a biome that doesn't exist in `biome_params.json` for the dimension's family.** This is the overwhelmingly most common cause. Verify:

   ```bash
   python3 -c "
   import json
   params = json.load(open('.seedtest/biome_params.json'))
   family_biomes = {e['biome'] for e in params if e.get('family') == '<family>'}
   filter_biomes = ['<biome1>', '<biome2>']  # from the dimension's seedRoll.spawnFilter
   print('missing:', [b for b in filter_biomes if b not in family_biomes])
   "
   ```

   Or query `GET /fork-schema` from a running `./dev seed-viewer` and check the `biomes` map for the namespace in question — it's grouped by namespace, not family, so cross-reference against the dimension's actual family via `.claude/skills/custom-dimension-authoring/SKILL.md`'s biome catalogue reference instead if you need the family split.

   **Why this zeroes EVERY candidate, not just some:** `fast_roller.tier2_measure` only gates a candidate on the spawn filter when at least one namesake biome is representable by the sampler for that family (`namesake_in_sampler = namesake_set & sampler_biomes`). If literally none of the filter biomes exist in that family's sampled set, `spawn_filter()` (in `biome_sampler.py`) can never find a match at any seed — every single candidate rejects, always, regardless of pool size. Note the corollary: if the dimension mixes families deliberately (e.g. a `multi_biome` dim whose `spawnFilter` happens to include a nether biome that genuinely can't appear under overworld noise), `namesake_in_sampler` being empty means the roller UNGATES the check entirely rather than rejecting everything — so a truly zero-candidate dimension usually means at least one filter biome IS representable but never actually found nearby, which is the config's fault, not a family-mixing false alarm.

2. **The dimension's family wasn't captured by warmup.** Check the tagged family counts:

   ```bash
   python3 -c "
   import json
   from collections import Counter
   params = json.load(open('.seedtest/biome_params.json'))
   print(Counter(e.get('family') for e in params))
   "
   ```

   Any family under 5 entries needs a warmup re-run (`roll-all.sh` triggers this automatically, but only when Docker is available and mod jars exist in the shared cache `${MOD_CACHE_DIR:-~/.cache/adventure-mods}` or, failing that, `data/mods/` — run `./dev up` at least once to fill the cache).

   The live table is `<consumer>/.seedtest/biome_params.json`. `scripts/seed/biome_params.json` inside the bundle is the read-only platform default that seeds it.

3. **The dimension isn't rollable at all.** `dimension_profiles.rollable()` excludes `superflat` unconditionally, and `void` types unless they carry a non-empty `biomes`/`biome` list. Confirm with `./dev seed-status` — a non-rollable dimension simply never appears in its output table, which is easy to misread as "zero candidates" when it's actually "not attempted".

4. **The dimension config itself failed to parse.** `load_dimension_configs()` in `dimension_profiles.py` skips unparseable JSON files with a `warning: skipping unparseable <path>: <error>` to stderr rather than crashing the whole roll — check the roll's console output (or re-run with `--dims <slug>` to isolate it) for this line if a dimension is missing from `seed-status` entirely rather than showing zero candidates.

## Flat renders (no terrain variation)

- **Missing height-function branch for the family.** `biome_renderer.py`'s render loop has explicit height formulas for overworld (Terralith spline), nether, end, and paradise_lost; any other family/clone type falls through to the generic fallback formula (`63 + cont*40 - ero*20 + rf*15`, clamped 0–200) — noticeably flatter and less characterful. If you've added a new clone-type family, check whether it needs its own `elif family == "..."` branch (see `scripts/seed/README.md § Adding a New Dimension`, step 6).
- **`terrain_splines.json` missing or stale.** Overworld-family renders need this file (Terralith's offset spline, extracted from the JAR) for real relief; without it they silently fall back to the much-flatter generic formula. Re-extract if Terralith's version bumped.
- **Wrong hillshade coefficient for the family.** `shade_k` varies per family (0.12 overworld, 0.15 nether/paradise_lost, 0.18 end) precisely because their height ranges are more or less compressed — a flat-LOOKING render (even with real relief underneath) can be a hillshade tuning issue rather than a data issue. Compare relief/grain numbers from the candidate's own metrics (shown in the viewer) against the render before assuming the data is flat.

## Wrong colours

- **`surface_rules.py`'s `_BIOME_SURFACE` table doesn't have an explicit entry for the biome.** The keyword-based fallback (matching on biome name fragments) handles most cases reasonably but can misclassify biomes with unusual names — add an explicit mapping for anything that looks wrong.
- **`biome_params.json` has the wrong (or missing) family tag for a biome.** A biome tagged `overworld` but used in a nether-type dimension won't be found by the nether-family sampler at all — the renderer/roller falls back to whatever IS in the nether params, producing wrong colours (or, per the zero-candidates case above, rejecting every candidate).

## All green (everything renders as grass)

- **Family tags missing from `biome_params.json` entirely.** If no entries carry a `family` key, the sampler falls back to considering EVERY entry regardless of the dimension's actual family — producing generic overworld biomes everywhere, including in nether/end dimensions. Check with the `Counter` snippet under "zero candidates" above; if `None` dominates the counts, warmup needs re-running against a jar set where `/customdim dump-biome-params` actually tags families.
- **Noise config mismatch** — a dimension uses nether biomes but the sampler is running overworld noise for it (a family-resolution bug in `fast_roller._build_sampler` or a mis-set `seedRoll.family`/`terrain` override). The sampled climate values won't be anywhere near the nether biome parameter ranges, so nearest-neighbour lookup returns the closest OVERWORLD biome instead — every point looks green because it's genuinely finding overworld biomes, just under the wrong label.

## Warmup failures

- **Docker Desktop isn't running.** `roll-all.sh`'s warmup phase hard-requires `command -v docker` and exits with an explicit error if it's absent — this isn't a silent failure, but it's easy to miss in a long roll log.
- **No mod jars anywhere.** Warmup's `prepare_base_dir()` builds its boot directory from the shared mod cache (`${MOD_CACHE_DIR:-~/.cache/adventure-mods}`), falling back to `data/mods/` only when the cache is empty. If neither has jars — a fresh checkout, or a consumer whose `data/` was deleted to reset a world — warmup exits with `Error: no mod jars in <cache> or <data>/mods`. Run `./dev up` once to fill the cache.
- **The MC server needs ~90 seconds to boot ~129 mods for the biome-param dump.** If `ROLL_MEMORY` is too low for the host, or the boot is otherwise slow, `warmup_biomes.py`'s timeout can expire before the dump completes — this fails **silently** in the sense that the roll continues rather than erroring loudly, but leaves `biome_params.json` stale or incomplete, which then surfaces downstream as zero candidates or all-green renders per the sections above. If a fresh warmup produces suspiciously low family counts, suspect a truncated boot before suspecting the config.

## The clean rig — proving a dimension outside the full stack

`scripts/seed/clean-rig.sh <dimension-json>` boots a minimal itzg server
(fabric-api + customdimensions only, watchdog disabled) with the dimension
config pre-seeded, loads it, and asserts it becomes queryable. Use it when
a dimension misbehaves and the question is "is this the config, or another
mod's mixins?" — `--keep` leaves the container up for `docker exec
cleanrig rcon-cli` probing.

Two ordering rules decide whether ANY rig works, and both have burned real
sessions:

- **The config file must exist before boot.** `MultiverseConfig` loads once,
  at `createWorlds`; a dimension JSON written after boot is never read, and
  `customdim load <slug>` then looks queued while nothing happens.
- **`SEED_ROLL_MODE` decides which door is open.** With it set (every
  measurement worker), `registerDimensions()` is skipped at boot: configured
  dimensions have no `DimensionOptions` and only `/customdim create` works.
  Without it (warmup pool dumps, this rig), configured dimensions register
  and `customdim load` works. The failure signature for either mistake is
  the same silent nothing — the mod WARNs
  `No DimensionOptions registered for configured dimension ...` when the
  load hits it, so grep the boot log for that line first.
