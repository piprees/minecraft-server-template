# Missing seed-rolling probes

10 criteria are registered against a designed set the mission implies is
roughly twice that. This is not a gap in coverage of what the facts layer
already measures — every measured fact has a criterion. It is a gap in what
gets measured at all: block-level questions a player actually cites when
rejecting a world (spawn buildability, traversability, landmark visibility,
progression reachable on foot) have no fact behind them yet.

This document specs what each missing criterion needs — the question, the
fact, whether it is a gate or graded, what absence means, and what it costs
— so `facts/**` can build the right thing once and `score/Criteria.java`
never gets a criterion pointed at a fact that does not exist. That failure
mode is already live: 12 of 82 shipped dimension configs set a `seedRoll`
`"terrain"` word (9 `"islands"`, 3 `"void"`), and neither is a key in
`TerrainMatchesPreset.BANDS` (`flat`/`gently_rolling`/`rolling`/`hilly`/
`mountainous`/`extreme`) — the criterion has run against real config for
every dimension that sets a word and never once been applicable. Fixing
that is a config or band-map problem, not a new probe, and is out of scope
here — flagged so it does not get rediscovered as a mystery later.

No criteria are written in this document. A criterion against a fact that
does not exist is dead code twice over.

## Group 1 — free: the fact already exists, nothing new to measure

Two `seedRoll` config fields are already authored into a meaningful share of
dimensions and read by nothing at runtime (`SeedRoll`'s own javadoc: "the
mod ignores this at runtime — it exists so per-dimension files are
self-contained for the Python roller"). The facts they'd compare against are
already in `SeedFacts.TerrainFacts`, measured on every grid pass whether or
not anything reads them.

### `water_matches_intent`

- **Question a player would ask:** "this dimension calls itself a
  desert/void world but half of it is ocean" (or the opposite — "`water:
  sea` and I can't find any").
- **Fact:** `terrain().waterFraction()` — already measured, always computed
  when the generator has a sea level. Compare against `seedRoll.water`
  (currently `"none"` / `"sea"` / `"high"`, in use on 17 of 82 dimensions).
- **Gate or graded:** Graded. A world that promised `"sea"` and delivered a
  smaller ocean than hoped is a worse world, not a broken one — same
  register as `TerrainMatchesPreset`, and should reuse its ramp-to-band
  shape rather than a new one.
- **Absent:** `seedRoll.water` unset → not applicable (config never asked).
  `waterFraction()` absent (flat/superflat generator, no sea level) →
  applicable-but-unmeasured only if `seedRoll.water` names a band that
  implies water should exist; if the config also has no sea level to give
  it one, that is arguably a config authoring error, not a scoring
  question — recommend not applicable when the generator has no sea level
  at all, mirroring how `TerrainMatchesPreset` treats an unrecognised word.
- **Cost:** Zero. No new sampling; the fact is a byproduct of the terrain
  grid pass every dimension already pays for.

### `height_range_matches_intent`

- **Question:** "this was supposed to be a deep-cave world and the terrain
  barely goes below sea level" / "a sky-islands dimension whose lowest
  point is the void floor".
- **Fact:** `terrain().minHeight()` / `terrain().maxHeight()` — already
  measured. Compare the measured `[min, max]` against `seedRoll.heightRange`
  (`[min, max]`, in use on 6 of 82 dimensions) as an overlap fraction rather
  than a containment test — a measured range that is a subset of, or
  overlaps most of, the configured one should not be punished for not
  matching it exactly.
- **Gate or graded:** Graded, same reasoning as `water_matches_intent`.
- **Absent:** `seedRoll.heightRange` unset → not applicable.
  `minHeight()`/`maxHeight()` absent (no terrain measurable at all) →
  unmeasured.
- **Cost:** Zero, same reason.

## Group 2 — cheap: needs a new fact, but from data already in hand or a handful of extra columns

### `playable_ground_covers_the_disc`

- **Question:** "I kept falling into holes" / "half the world is entombed
  rock with no floor."
- **Fact:** A coverage ratio — how many of the grid columns the engine
  actually attempted (inside the playable disc) came back with a floor,
  versus how many did not. **This needs a new fact.** The persisted
  `SeedFacts.Grid` carries `height` with nulls for a cell, but by
  `FactsEngine.persistedGrid`'s own contract a null there is deliberately
  ambiguous between "outside the disc, never attempted" and "attempted, no
  answer" — exactly the distinction this criterion needs to draw. The
  internal (non-persisted) `Grid` record already carries `sampled` (cells
  attempted), so the missing piece is small: persist `sampled` alongside a
  count of cells that returned a non-null height, or persist the two
  reasons for a null cell distinctly instead of collapsing them. No new
  sampling — the grid pass already visits every one of these columns for
  biome and height.
- **Gate or graded:** Graded, not a gate. A cave dimension or a
  deliberately sparse sky-islands one is SUPPOSED to have real gaps in its
  floor coverage; a blanket floor here would reject legitimate worldgen
  intent rather than a broken seed. Score against a band, the same shape as
  every other "matches intent" criterion, rather than a fixed cutoff.
- **Absent:** Unmeasured when the grid pass itself produced zero attempted
  cells (radius is zero, or the generator could not be built at all —
  matches the existing `unmeasurable()` path).
- **Cost:** Cheap. One new field on the persisted grid record; the sampling
  it describes already happens.

### `spawn_is_safe_to_build_on`

- **Question:** "I spawned in lava" / "spawn floats over open water with no
  ground under it."
- **Fact:** The block classification (solid ground / hazardous fluid — lava
  or fire — / open water / no floor at all) at the spawn column's floor,
  and ideally at the 9 points `SpawnFacts.localRelief` already samples
  around it (`SPAWN_PROBE_STEP = 16`, one per neighbouring chunk) so the
  criterion judges an immediate buildable footprint, not one pixel.
  **Needs a new fact.** Neither `ColumnScan` (an opacity predicate only,
  no block identity) nor the persisted grid (biome + height, no block
  type) can answer "is this lava". This is a genuinely new capability: read
  the actual `BlockState` at each probed column's floor and classify it.
- **Gate or graded:** Gate. This is the same register as
  `NothingIsImmediatelyLethal` (a sheer drop) — spawning in a hazard is not
  a deduction to be bought back by good biome variety, it is a no.
- **Absent:** When the column has no floor at all, this is the existing
  `spawn.surfaceHeight` absence — unmeasured, not a hazard (a hole is a
  different failure mode, arguably better caught by
  `playable_ground_covers_the_disc` than by this gate misreading "no floor"
  as "safe floor"). When a floor exists but the block-state read throws,
  unmeasured with the exception recorded, same pattern `Scorer` already
  uses for a criterion that throws.
- **Cost:** Cheap and bounded — the same 9 columns `SpawnFacts` already
  visits for local relief, plus one classification read per column instead
  of just a height read. Does not scale with dimension size.

## Group 3 — moderate: bounded extra sampling, scoped to specific structures

### `landmark_has_presence_over_its_surroundings`

- **Question:** "the castle everyone talks about is buried in a hillside —
  you're standing on top of it before you know it's there."
- **Fact:** A coarse prominence measure for the nearest `landmarks`-group
  structure: sample a small ring of columns (8–16 points) at some approach
  radius around its position and compare their heights against the
  structure's own column. A structure that sits at or above its
  surroundings has presence; one buried well below them does not. **Needs
  a new fact** — the persisted grid's resolution (41×41 over the whole
  playable disc) is far too coarse to say anything about the terrain
  immediately around one specific structure; this needs its own small,
  targeted probe centred on that structure's coordinates, using the same
  height-sampling path the grid pass already calls per column.
- **Gate or graded:** Graded. A buried landmark is a worse landmark, not an
  unplayable world.
- **Absent:** Not applicable when the dimension's `landmarks` group has no
  pool at all (same reasoning as `structuresEnabled`/group-pool checks
  elsewhere). Unmeasured when the pool exists but this seed placed nothing
  in it, or the ring probe itself could not be sampled.
- **Cost:** Cheap and bounded per candidate — a fixed small ring (8–16
  columns) around one structure, not a function of dimension size or
  border radius.

### `progression_structure_has_a_walkable_corridor`

- **Question:** "the fortress looks close on the map, but there's an ocean
  the whole way there."
- **Fact:** A coarse walkability proxy along the straight line from spawn
  to the nearest hostile/progression structure (the same structure
  `FirstEncounterDistance` and the reachability gates already measure
  distance to): sample columns at fixed spacing along that line and flag
  each as passable or not (deep water, a drop relative to its neighbours
  larger than a player can descend safely, or an entombed column via
  `ColumnScan`). **Needs a new fact** — this is the first probe in this
  document that needs both the hazard/passability classification from
  Group 2 AND `ColumnScan` together, applied along a line rather than at a
  point.
- **Gate or graded:** Graded, and deliberately NOT a tightening of
  `FortressReachableInNether`/`EndCityReachableInEnd`. Those two gates stay
  exact straight-line distance floors — a hard progression promise. This
  criterion is a softer, walkability-flavoured signal layered on top:
  a coarse per-sample proxy will have false positives (a narrow ravine a
  player just jumps) and should never be trusted to hard-reject a seed on
  its own.
- **Absent:** Not applicable when there is no such structure in the pool at
  all (mirrors the reachability gates' own not-applicable case). Unmeasured
  when the structure exists in the pool but this seed placed none, or the
  line itself could not be sampled (e.g. crosses outside the playable
  border).
- **Cost:** Moderate, and the only one in this document that scales with
  distance rather than being a fixed handful of columns. A corridor sampled
  every ~128 blocks over a 2048-block gap is 16 extra columns — reasonable
  per candidate, but worth watching if it is ever asked to run over the
  larger borders (`the_end_citadel` is 8192; the same spacing there is 64
  columns for one criterion, not free at `the_crystal_vale`'s already-24ms
  a column).

## Not proposed — needs something neither `ColumnScan` nor the grid provides

- **True pathfinding-based reachability.** A real "can a player actually
  walk this route" answer needs an actual pathfinder, not a sampled-line
  proxy. Cost is unbounded relative to the corridor length and the terrain
  complexity; `progression_structure_has_a_walkable_corridor` above is the
  cheap approximation, not a stepping stone to this.
- **Mob-spawn safety / night danger density.** Needs live light-level
  simulation across time, which a headless, no-`ServerWorld` sampler has no
  way to answer — this is inherently a live-world question.
- **Structure integrity (intact vs half-generated into terrain).** Whether
  a structure's own jigsaw pieces resolved cleanly against the terrain
  needs the structure's own generated `StructureStart`, which only exists
  once a chunk has actually generated — `/customdim structure-census`
  already answers this for a LOADED world; a headless seed-rolling pass
  cannot without generating the chunk, which defeats the point of
  screening seeds before committing to one.
- **`spawnRadius` / `allowEndgameNearSpawn`-driven criteria.** Both fields
  exist on `DimensionConfig.SeedRoll` and both facts they would need
  already exist (`nearestByStructure`, `byGroup`). Not proposed here
  because neither field is set by a single one of the 82 shipped
  dimensions — building a criterion against them now repeats exactly the
  `TerrainMatchesPreset` mistake this document opens with. Revisit once at
  least one dimension authors a value for either.
