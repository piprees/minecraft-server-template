# Profile-driven seed rolling: multi-metric scoring and dimension rolls

Research into evolving the seed-rolling pipeline
(`scripts/seed/roll-seeds.sh`, `score-seed.sh`, `report-top.sh`) so it
can (a) score against a *desired world* profile instead of one
hard-coded taste, (b) report several named metrics instead of a single
0–100 score, and (c) load and score the custom dimensions themselves.
Companion to `customising-structures.md` and `customising-terrain.md`.
**Research/design only — nothing is implemented.**

## Current state

- `roll-seeds.sh`: one Docker boot per world seed (stripped mod set,
  ~40s bad-seed rejection via spawn-biome check at 0,64,0, then a fixed
  five-structure `/locate` battery: stronghold, village, ruined portal,
  fortress, bastion), writes one CSV row per seed.
- `score-seed.sh`: computes a single score **at roll time** from six
  weighted categories with hard-coded weights, caps and biome tier
  lists. Direction is baked in: *closer is always better*.
- `report-top.sh`: sorts the CSV by that one score.
- **Blind spots given the new goals:** a "sparse structures, very
  natural" overworld wants *far* mega-structures and *near* villages —
  the current scorer can't express "far is good". Terrain shape (the
  new Tectonic tune) isn't measured at all. And the 74 custom
  dimensions aren't evaluated — yet the seed-roll server already boots
  the custom-dimensions mod and creates all of them (the jar is in
  `data/mods/`, `multiverse_config.json` rides along in the `config/`
  copy), so today they only add boot time without being scored.

## The decoupling insight (checked against the repo)

All 74 entries in `config/multiverse_config.json` carry **explicit
per-dimension seeds** (`ServerWorldSeedMixin` feeds them into noise and
structure placement). Consequences:

1. **Dimension quality is independent of the world `SEED`.** Rolling
   the world seed only changes the main overworld/nether/end. We never
   need one miracle seed that's good in 75 places.
2. **Each dimension's seed can be rolled and locked separately**, and
   the winner is written back into `multiverse_config.json` — the
   config is already the source of truth.
3. **Many candidate seeds per boot.** Dimensions are cheap runtime
   worlds: a temporary roll config can define N clones of one
   dimension definition with N candidate seeds
   (`the_gauntlet__s01`…`s16`), boot **once**, and measure all of them
   via `execute in adventure:the_gauntlet__s01 run …`. One boot ≈ one
   *batch* of dimension-seed candidates instead of one world seed —
   orders of magnitude more throughput than the world-seed loop.
   (Idle unloading must be disabled or `idleUnloadMinutes` raised for
   the roll; `tick freeze` is already used.)

## Proposed architecture: measure once, score later

The single most valuable change is splitting **measurement** from
**judgement**:

- `roll-seeds.sh` (and a new dimension mode) only *measures* and
  appends raw facts to a long-format CSV:
  `target,seed,metric,value` (target = `world` or a dimension name).
  No score column at roll time.
- Scoring moves to report time: `report-top.sh --profile <name>`
  applies a **profile** (weights + directions + tier lists) to the
  measurements. Re-weighting, new profiles, or "actually I want fewer
  villages" never require re-rolling — the expensive boots are already
  banked. (`score-seed.sh` becomes a pure function from measurements ×
  profile → metric scores; today's behaviour becomes the `classic`
  profile.)

### The metrics (measured, per target)

All obtainable over RCON with existing techniques; per-dimension via
`execute in <dim> run …`:

| Metric | How measured | Cost |
| --- | --- | --- |
| `spawn_biome` | existing `execute if biome` battery (per-dimension: at that dimension's 0,~,0) | ~instant |
| `biome_variety` | `execute if biome X 64 Z <candidate>` sampled on a grid (e.g. 3×3 to 5×5 points, 512-block pitch) — count distinct tier-listed biomes; alternatively `/locate biome` distances to a profile's wanted list | seconds |
| `structure_<id>_dist` | existing `/locate structure` parsing, but the battery comes **from the profile** (e.g. `#minecraft:village`, `dungeons_arise:*` sets, `terralith:fortified_village` "castles", per-dimension the sets from `customising-structures.csv`) — store raw distance + found/not-found | main cost; ~1–3s each |
| `terrain_relief` / `terrain_grain` | surface height at the same sample grid via binary search with `execute if block X <y> Z #minecraft:replaceable` (~8 calls per point ⇒ height ±1). Relief = stddev/range of heights; grain = mean |Δh| between adjacent grid points (craggy = high grain; wide rolling terrain = low grain, moderate relief). Directly validates the Tectonic tune per dimension | ~100–200 RCON calls per target; fine at `tick freeze` |
| `water_fraction` | same grid: `execute if block X 62 Z minecraft:water` — ocean-heavy starts score down (or up, for island profiles) | cheap |
| `errors` | existing filtered error count | free |

Store raw values; **profiles decide polarity** — each profile entry is
`{metric, weight, direction: near|far|window|tier, cap or window}`.
"Sparse natural overworld" = villages `near` (cap 1500), WDA major
`far` (full marks if not found within locate radius — `/locate`'s
bounded search radius makes "not found" a *positive* signal for
sparseness), `terrain_grain` low-is-better, `biome_variety`
high-is-better.

### Profiles

`scripts/seed/profiles/*.json` (or `.env`-style to keep the bash-3.2
rule — **no `declare -A`**; a flat `metric|direction|weight|cap` line
format parses with `while IFS='|' read`). One profile per desired
world/dimension archetype, e.g.:

- `overworld-natural`: near villages/taverns, far mega-dungeons, low
  grain, high variety, green spawn tiers.
- `dim-hard`: near dungeons (density proxy), relief high, spawn tier
  irrelevant.
- `dim-pastoral`, `dim-sky`, `dim-nether-rolling`, `dim-end-chaos`…

`multiverse_config.json` (or the future `dimensions.json` schema from
`mod-owned-dimension-lifecycle.md`) gains an optional advisory field
per dimension — `"seedProfile": "dim-hard"` — so the roll tool knows
which profile scores which dimension without a side table.

### Dimension roll mode (sketch)

```
./roll-seeds.sh --dimension the_gauntlet --candidates 16 --rounds 4
```

1. Copy `multiverse_config.json` → roll copy containing ONLY the
   target dimension cloned ×16 with fresh random seeds (plus
   `idleUnloadMinutes: 9999`); boot once with any fixed world seed.
2. For each clone: biome battery, grid heights, profile's locate
   battery — append measurements keyed `the_gauntlet@<seed>`.
3. Tear down, repeat rounds; report tool ranks per profile; human
   picks; winning seed is hand-written into the real
   `multiverse_config.json` entry.

Notes: nether/end-type dimensions locate against their namespaces
(`mns:*`, `incendium:*`, `mes:*` — ids in `customising-structures.csv`);
void/superflat dims are excluded (nothing to measure). Boot cost is
amortised across all clones, and the roll copy means the 74 real
dimensions are NOT created during rolls — dimension rolls should
actually be *faster* per data point than world rolls are today.
Overworld world-seed rolls keep the existing flow (biome-first
rejection stays — it's the big win) but with profile-driven batteries
and the long-format CSV.

### Terrain-tune synergy

Because measurement is profile-independent, the same machinery
evaluates *config changes* as well as seeds: roll the same 10 seeds
before and after a `tectonic.json` change and compare `terrain_grain`/
`terrain_relief` distributions — an objective check that the
"wider/realistic" tune (see `customising-terrain.md`) does what it
claims before anyone flies a world.

## Compatibility constraints (from AGENTS.md / existing scripts)

- macOS bash 3.2: no `declare -A`, no `mapfile`, no `${var,,}`; keep
  the flat-file profile format or move scoring/reporting to python3
  (already a repo dependency via `resolve-mods.py` — recommended for
  the report/scoring side; measurement stays bash).
- Snapshot-not-stream logging, resumable CSV, `flock` for parallel
  workers — all preserved; long-format CSV appends are strictly
  simpler under `flock` than the current wide row.
- The CSV schema change breaks `report-top.sh`'s column maths — both
  change together; keep `seed-results.csv` name for the world roll or
  version it (`seed-measurements.csv`).
- Strip list: the custom-dimensions jar must NOT be stripped (it's the
  point); DH/BlueMap etc. stay stripped.

## Recommendation

1. Refactor to measure/score split + long-format CSV + `classic`
   profile (behaviour-preserving).
2. Add profile files + report-time scoring with per-metric direction;
   add the sparse/natural overworld profile.
3. Add terrain grid metrics (relief/grain/water) to the world roll.
4. Add the dimension roll mode with cloned-seed batching, and
   `seedProfile` advisory fields per dimension.
5. Roll world seed with `overworld-natural`; roll dimension seeds for
   the handful of dimensions whose terrain/structure presets change
   under the new terrain work; lock winners into config.
