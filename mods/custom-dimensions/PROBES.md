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

### Addendum — the dimensions neither of the above reaches

`water_matches_intent` applies to 17 of 82 shipped dimensions; `height_range_matches_intent` applies to 6. That leaves 65 and 76 silent respectively. Checked each of those against signal independent of the field itself — dimension name, biome list, `seedRoll.terrain` word — to separate "genuinely no opinion" from "opinion never written down."

It's both, in different proportions:

**Water.** ~10 of the 65 carry independent aquatic signal and set nothing in `seedRoll.water`:

- `the_burning_archipelago` — the dimension's own name.
- `the_icebound_rift` — lists `minecraft:frozen_ocean`.
- `the_crucible`, `the_underdark`, `the_whispering_wilds`, `the_greywoods` — swamp/mangrove_swamp biomes.
- `the_blossom_gardens`, `the_gauntlet`, `the_highland_crossing`, `the_frozen_hearth` — river biomes.

The other ~55 are lava/basalt-deltas/deep-dark/nether-flavoured or plain-terrain dimensions with no water theme anywhere in their config — correctly silent, and forcing a water opinion onto them would be inventing intent the theme never had, the same failure mode `applicable()` exists to prevent.

**Height range.** ~15 of the 76 carry a name or `seedRoll.terrain` word implying a distinctive vertical envelope and set nothing in `seedRoll.heightRange`:

- `the_pillared_void`, `the_icebound_rift`, `the_slatemouth` — `terrain: "void"`.
- `the_burning_archipelago`, `the_shattered_skies`, `the_starwell` — `terrain: "islands"`.
- `the_gilded_pit`, `the_weeping_vault`, `the_souldrift`, `the_luminous_caverns`, `the_underdark`, `the_basalt_spires`, `the_highland_crossing`, `the_verdant_hollow`, `the_miredeep` — vertical naming (pit/vault/rift/caverns/hollow/spires/crossing/deep).

The remaining ~61 are ordinary-height dimensions where the default vertical envelope is genuinely fine — also correctly silent.

**The consequence of fixing it.** `InputHash` hashes the merged dimension config, so writing `seedRoll.water` or `heightRange` onto one of the dimensions above changes that dimension's input hash and invalidates its entire candidate bank — every existing candidate is re-measured from scratch, at up to ~26s a candidate for the more expensive dimensions. Correct behaviour, not a defect, but it means this authoring belongs in one deliberate pass across the listed candidates, not trickled in a field at a time each triggering its own re-roll.

**Why it belongs in this document.** Authoring `seedRoll.water`/`heightRange` into the ~10-15 dimensions per field listed above is a graded criterion gained for zero new code — cheaper than every probe in Groups 2 and 3 below, because the fact is already measured and the criterion is already written. It is a config-authoring decision per dimension, not a code change, and out of scope here: this document lists the candidates and states the case, it does not author the values.

## Addendum — `TerrainMatchesPreset`: the dead criterion

Not a missing probe — the opposite. `TerrainMatchesPreset` is written, tested and wired into every scorecard, and has never once been applicable: `BANDS` keys on `flat`/`gently_rolling`/`rolling`/`hilly`/`mountainous`/`extreme`, and the only `seedRoll.terrain` words in use across the 82 shipped configs are `"islands"` (9) and `"void"` (3), neither a `BANDS` key (already flagged at the top of this document). Worse than dead: `BANDS` was calibrated against `relief = max - min`, and relief has since become the interquartile range (`11735b4`) — even a config that used a real word would score against numbers fitted to a different quantity. The choice is to author terrain words and recalibrate the bands, or delete the criterion. That decision needs data this section supplies, not the decision itself.

**What the banked candidates show.** `~/Projects/elfydd/.seed-rolling/candidates/` holds facts from 35 dimensions. `InputHash` covers the mod's own bytes, so a rebuild after `11735b4` gives every re-measured dimension a new hash bucket — but most dimensions in the bank were only ever measured once, so bucket count alone can't separate old from new. What can: the old formula's `relief` was defined as `maxHeight - minHeight`, so under the old code `relief` equals that span *exactly*, every time, by construction. The new formula is an interquartile range over ~1000+ sampled columns, which is essentially always strictly *less* than the full span for any real terrain — a mathematical identity, not a heuristic. Classifying every bucket by comparing its `relief` to its own `maxHeight - minHeight` splits the bank cleanly into three groups, no timestamps needed (measurement time and commit time don't line up anyway — local rebuilds run ahead of the commit that records them; `the_boneyard`'s one post-fix candidate was measured 36 minutes before `11735b4` landed):

- **Old formula** (`relief == span`, exactly): ~20 dimensions — `the_ashgrove`, `the_blossom_gardens`, `the_burning_archipelago`, `the_catalyst_maw`, `the_chalk_meadows`, `the_crucible`, `the_crystal_vale`, `the_ember_fields`, `the_end_citadel`, `the_frozen_hearth`, `the_frozen_strait`, `the_gauntlet`, `the_greenreach`, `the_greywoods`, `the_gritlands`, `the_highland_crossing`, `the_lantern_pools`, `the_lost_outpost`, `the_needlefall`, `the_red_monument`, `the_roothold`, and one of `the_obsidian_sanctum`'s four buckets.
- **Degenerate** (`relief == 0`, `span == 0` — every column read the same height): ~12 dimensions, nearly all Nether-hosted/ceilinged — `the_blackstone_keep`, `the_blighted_maw`, `the_bloodroot_wastes`, `the_buried_age`, `the_emberglass_foundry`, `the_forged_depths`, `the_fungal_lanterns`, `the_furnace_halls`, `the_gilded_pit`, `the_icebound_rift`, `the_luminous_caverns`, `the_molten_flats`, and one of `the_boneyard`'s three buckets. This is a third, older state — almost certainly pre-`70082ed` (measuring the roof, not the playable floor, in a ceilinged dimension gives a flat roof height on every column), not a max-min-vs-IQR question at all.
- **New IQR formula** (`relief < span`): exactly **2 dimensions** — `the_boneyard` (1 candidate, relief 88 against a span of 168 — min 17, max 185: proof in one row that IQR and max-min disagree substantially on real data) and `the_obsidian_sanctum` (7 candidates across two rebuild buckets, reliefs 75–88).

**The IQR sample is too small and too narrow to calibrate anything.** 8 values from 2 dimensions, both `"hard"`-mood Nether-hosted dimensions with no config terrain word — nothing overworld-flavoured, nothing serene or pastoral, nothing sky/void, is represented. All 8 values sit inside a 13-block spread (75–88). Fitting six named bands (`flat` through `extreme`) to that would mean inventing five of the six from zero data — exactly the thing every band in this codebase has so far avoided doing.

**Independent-signal check, same test as water and height range.** 23 of 82 dimensions already carry an explicit, pre-existing statement about vertical intensity that has nothing to do with `seedRoll.terrain`: `noiseSettings` is set to `adventure:compressed` (13) or `adventure:wide` (10) — real presets a person chose, not a guess. Of the remaining 59, 40 carry name or biome signal (`the_ashgrove` lists `terralith:volcanic_peaks`/`volcanic_crater`; `the_rosebluff` is named for a cliff and lists a cliff-adjacent biome; `the_claymarsh` lists a `valley`-parameterised biome; and so on). That leaves 19 with no signal from any of the three sources — three of them are the literal base worlds (`overworld`, `the_nether`, `the_end`), correctly opinion-free, and one is a plain miss: `the_amplified_reaches`'s own name is a vanilla Minecraft terrain-intensity preset. In total, up to 63 of 82 (77%) could plausibly get an authored terrain word from signal that already exists in their config or name; as few as 16 are genuinely without vertical character worth naming.

**Recommendation: author first, recalibrate later, do not delete.** Deleting throws away a mechanism (word → band → ramp score) that is structurally identical to `WaterMatchesIntent` and sound in shape — its only defect is a config vocabulary nobody uses and numbers fitted to a quantity that no longer exists. Recalibrating now means fitting six bands to eight points from two similar dimensions, which is choosing numbers, not measuring them. The path that uses what was actually found here: reconcile what `adventure:compressed`/`adventure:wide` numerically do to relief against candidate data (not attempted in this pass — needs specifics of what those presets parametrise), author `seedRoll.terrain` into the ~63 dimensions with real signal, then recalibrate `BANDS` once genuine IQR data exists across a real spread of moods and themes. Both the authoring and the eventual recalibration are Pip's call.

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
