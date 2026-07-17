# Seed rolling — the all-dimensions roller

`./dev seed-roll-all` rolls candidate seeds for **every configured
dimension AND the shared world seed** (overworld / the_nether / the_end /
paradise_lost), **indefinitely**: workers cycle their dimensions forever
(one accepted candidate per dimension per cycle, unbounded attempts per
acceptance), report every acceptance and rejection live on the console,
and regenerate the self-refreshing `.seedtest/viewer.html` every 45s.
**Ctrl+C finalises** — winners are scored and written into the config
(timestamped backup) from everything measured so far. Re-runs resume;
accepted candidates persist and rejected seeds are never re-tried.

```bash
./dev seed-roll-all                          # everything, 6x parallel, renders
./dev seed-roll-all --dims the_gauntlet --workers 1   # focused session
./dev seed-roll-all --render off --no-write           # measure + score only
```

Crashes are survivable by design: a broken candidate is logged and skipped,
a dead container is rebooted (unlimited retries), a dead worker process is
respawned by the orchestrator. The world seed rolls in parallel with the
dimensions — overworld/nether/end candidates are measured as runtime clones
inside the same containers (an overworld-type clone with seed S generates
identically to a world booted with SEED=S), gated by the overworld's spawn
filter; a dedicated boot stream covers paradise_lost (static mod dimension)
and prioritises seeds the clone stream already accepted.

## One source of truth: `seedRoll` in multiverse_config.json

Every dimension entry (and every `worlds` entry) carries an advisory
`seedRoll` block — the mod ignores it; the roller and scorer read ONLY this.
Consumer repos customise their own config copy; nothing is hard-coded in
the scripts.

```jsonc
{
  "name": "the_gritlands",
  "type": "overworld",
  "seed": 123,                       // written by the roller (winner)
  "seedRoll": {
    "mood": "desolate",              // weighting archetype (see below)
    "description": "A roughed-up cluster of gravel, basalt and ash ...",
    "spawnFilter": ["terralith:gravel_desert", "terralith:basalt_cliffs"],
    "water": "none",                 // optional: none | high | sea
    "wants": {                       // structures that BELONG, by band
      "ruined_portal": "near_spawn", //   near_spawn  0–30% of radius
      "field_ruins": "spread",       //   spread     15–65%
      "scorched_tomb": "near_border" //   near_border 45–100%
    },
    "shuns": ["village", "tavern", "mansion"]  // presence costs points
  }
}
```

- **spawnFilter** — namesakes represent SPAWN. Candidates whose spawn biome
  misses the filter are rejected immediately (cheap: create → probe →
  destroy) and the worker re-rolls a fresh seed; rejected seeds are banked
  so they never repeat. An empty filter accepts anything. The gate is
  **adaptive**: after `ROLL_SPAWN_ATTEMPTS` (default 10) misses it widens
  from "filter biome at spawn (≤48 blocks)" to ≤256, then ≤768 blocks,
  then force-accepts a keeper — so a narrow filter can never stall a
  worker. Widened acceptances bank `spawn_filter_dist` and earn partial
  namesake credit by proximity (always below a true namesake spawn).
- **wants** — keys are short names from the STRUCTS library
  (`scripts/seed/dimension_profiles.py`, every id verified against the
  shipped jars) or raw `namespace:path` ids. Bands are fractions of the
  **playable radius** (world border ÷ portal scale) and always fit inside
  the world; `structureDensity: dense` shifts bands closer, `sparse`
  further.
- **shuns** — structures that have no business existing there ("who could
  live here?"). Found inside the playable radius = 0 for that entry.
- **mood** — weighting archetype: `hard`, `adventurous`, `dramatic`,
  `scenic`, `pastoral`, `serene`, `desolate`, `standard`. When omitted it is
  derived from the mob difficulty multiplier
  (`config/configurable-difficulty/configurable-difficulty.json5`), the
  nether smaller-is-harder rule, `structureDensity` and `hostileSpawning`.
- **description** — shown in the viewer; say what the dimension is FOR.
- **family** (`overworld|nether|end`), **terrain** (`solid|islands|void`),
  **heightRange** (`[minY, maxY]` for column probes) — explicit scoring
  identity for clone-typed and unusual dimensions. The config dictates
  these; type-string heuristics are only fallbacks for entries that omit
  them.

`type` also accepts any registered dimension id (`"paradise_lost:
paradise_lost"`) — the mod clones that dimension's generator, and biome
lists/noiseSettings/seeds apply to the clone like any other type, so whole
modded world families cross-pollinate with everything else.

Entries without a `seedRoll` block still roll: spawn filter defaults to the
first biomes of the `biome` list, wants to a modest family battery.

## Worlds (the shared world seed)

`config/multiverse_config.json` has a top-level `worlds` array for the
dimensions that share the vanilla world seed. One candidate seed = one
container boot (`SEED=<candidate>`), all four worlds measured per boot, the
overworld's spawnFilter gates the seed. The combined score weights the
overworld at 0.5 (override per world with `seedRoll.weight`). The winner
lands in the config as `worldSeed` and is printed as the `.env` `SEED=`
line — applying it needs a world reset (`./ops reset-seed`).

## Scoring model

Per candidate, 0–100 from four components (weights by mood; mob difficulty
≥ 2.0 shifts weight into structures — dangerous worlds must be WORTH it):

| component | measures |
| --- | --- |
| namesake | spawn biome is on the spawn filter (surface-level probe) |
| variety | listed biomes actually locatable nearby (`locate biome`) |
| terrain | relief / grain / water vs the noiseSettings targets (`compressed` wants violence, `wide` wants rolling; voids must be void, islands must have gaps) |
| structures | every want in its band, every shun absent |

Cross-family biome mixing is real: the mod builds multi-noise sources from
the FULL biome registry, so nether/end/cave biomes mix into any dimension
(and `biome` lists now work on `nether`/`end` types too).

## Pipeline

1. `score-dimensions.py manifest` — splits dims across workers; each
   candidate slot carries spare seeds for spawn-filter re-rolls; every
   attempt seed gets a config entry in the worker's roll config (the
   seed/density/peaceful mixins resolve by config name — without an entry a
   candidate silently clones the main world).
2. `seed_worker.py` (one per worker) — boots `SEED_ROLL_MODE=true`
   containers, `customdim create → measure → destroy` over a native RCON
   socket (~10ms/call), writes `worker-<n>.csv`.
3. World pass — fresh boots per world-seed candidate.
4. Winners render pass — top N per dimension re-created and rendered via
   BlueMap (spawn 144×144, per-family lighting, nether roof cut).
5. `finalise` — merge, score, write winners + `worldSeed`, generate
   `viewer.html`, sync the consumer `data/config` copy.

## Reference extractors

- `scripts/seed/extract-biome-catalog.py <data-dir>` — regenerates
  `scripts/seed/biome_catalog.json` (228 biomes: colours, mobs, features)
  from the installed jars. Re-run after worldgen mod changes.
- Structure ids were extracted the same way (`worldgen/structure/` in the
  jars); the curated map is `STRUCTS` in `dimension_profiles.py`.
