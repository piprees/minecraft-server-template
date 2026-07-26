# Structure Noise — implementation log

Working notes for `SPIKE-STRUCTURE-NOISE-IMPLEMENTATION.md`. One entry per
decision, finding, or deviation from the spike. Terse by design.

## Corrections to the spike (found before Step 1)

| Spike says | Reality | Action |
| --- | --- | --- |
| "Parse 377 sets" from `structure-dials.csv` | dials has **356** real sets (+1 marker row `dungeons_reborn:(placed features)`) | Use the **union** of both CSVs = **379** sets |
| `len(d) >= 370` sanity assert on the output | would pass only by luck against dials alone | assert against the real union count |
| rarity table has dual spacing+freq criteria that conflict | A1's prose is spacing-only and matches all 3 named test cases | **spacing-only** thresholds |
| `endgame` group implied by rarity tier | 68 sets are spacing>80, incl. 23 `deco` (mss trees, ponds) and `mvs:duck` | endgame group uses the **keyword** rule, not spacing |
| `structure_themes.json` is `Map<String,String>` | true today | widen to accept string OR object; string = theme-only |

## Data sources (A1)

Two CSVs, different jobs. Neither alone is sufficient.

| File | Generated? | Authoritative for |
| --- | --- | --- |
| `scripts/data/structure-sets-extracted.csv` (377) | YES — `extract-structure-sets.py` reads the pinned jars | **census** (which sets exist), `spacing`/`separation`/`frequency`/`dimensions` |
| `scripts/data/structure-dials.csv` (356) | NO — hand-curated | **theme** (6 values, reviewed by a human) |

They disagree on theme for **186 of 354** overlapping sets. The extracted
CSV's theme is first-match-wins regex over the set id with `landmark` as the
catch-all default (hence 163/377 landmark) — it is a heuristic, not a
judgement. **Dials theme wins**; extracted theme is the fallback for the 23
sets dials never covered (all vanilla/paradise_lost/friendsandfoes/
supplementaries).

23 sets exist only in extracted — including `minecraft:igloos`,
`desert_pyramids`, `jungle_temples`, `ocean_monuments`, `strongholds`,
`mineshafts`. **These are exactly the sets the spike's G2 checks name.**
Building the registry from dials alone would have left them unclassified.

2 sets exist only in dials (`ati_structures:aboveground_small`,
`ati_structures:underground_medium`) — a pin drift; kept, spacing from dials.

## Group derivation

```
theme  := dials.theme  ?? map(extracted.theme)      # ruins -> deco
group  := endgame   if ENDGAME_KEYWORDS matches AND theme in
                       {dungeon, landmark, maritime}
          else theme -> {deco, settlements, dungeons, landmarks,
                         maritime, loot}
```

`ENDGAME_KEYWORDS` is lifted verbatim from `extract-structure-sets.py`'s
existing `ENDGAME_PATTERNS` — an already-reviewed "flagship content" list
(coliseum, citadel, mega_ship, forbidden_castle, ancient_city, mansion,
trial_chambers, …). It encodes the spike's stated endgame examples directly.
Spacing does not decide the group: `mss:tree_7` (sp=186) and `mvs:duck`
(sp=94) are decorative variants whose spacing is high only because the mod
splits one feature across many sets.

The theme gate keeps `deco`/`settlement`/`loot` out of endgame, so a
`small_arena` deco set cannot be promoted by the `arena` keyword.

## Rarity derivation

Spacing-only, per the spike's A1 prose:

| spacing | tier |
| --- | --- |
| > 80 | `endgame` |
| 46–80 | `rare` |
| 25–45 | `uncommon` |
| <= 24 | `common` |

Spacing comes from extracted (read from the jar) falling back to the dials
`current` column. Named test cases all pass: `mes:phantom_citadel` sp=31 →
uncommon; `nova_structures:shrine_tower` sp=600 → endgame;
`minecraft:shipwrecks` sp=24 → common.

Two edge cases:

- `nova_structures:end_castle` — dials `current` is literally `/`; extracted
  supplies the real spacing.
- `minecraft:strongholds` sp=0 (concentric_rings), `minecraft:mineshafts`
  sp=1 (per-chunk). Both classify `common`. Harmless: C2 skips any set whose
  placement is not exactly `RandomSpreadStructurePlacement`, checked against
  the live object, so neither ever enters a noise pool.

Deliberately NOT reusing extracted's own `rarity_class` column: it is a
6-tier attempts-per-1k-chunks scheme (`common`…`legendary`) with a keyword
override. Different contract from the spike's 4 tiers, and the roller has to
mirror whatever we pick bit-for-bit (F4).

## Ownership move

`gen-structure-presets.py` used to write `structure_themes.json` as a
side-effect of building the datapack presets (which need network — it
downloads every pinned jar). The theme map is pure CSV→JSON.

Split: **`scripts/gen-structure-groups.py` now owns
`structure_themes.json`** and also emits
`config/custom-dimensions/structure-groups.json`. It needs no network.
`gen-structure-presets.py` keeps the datapack presets only. Two writers to
one file would have silently fought (presets would revert group/rarity).

## Tests

House convention is `unittest discover -s scripts/seed -p 'test_*.py'`,
wired into `scripts/test-scripts.sh` — **not** pytest as the spike says.
New tests go in `scripts/seed/` as `unittest.TestCase` (pytest collects
these too, so both invocations work).

## Exclusion is a LOCAL MAXIMUM, not a greedy spiral (deviation, A2/B2)

The spike defines placement as a spiral outward from spawn that greedily
keeps a candidate unless something already accepted sits within the exclusion
radius. That is **order-dependent**: the answer depends on the visit order,
so Python must replicate the spiral chunk-for-chunk or F4 fails. F4 is
already flagged as the hardest gate in the plan; this makes it needlessly
harder.

Replaced with an order-free rule. A chunk is a placement iff:

```
score(c)  = noise(c) * radial(c)
placed(c) := score(c) > threshold
             AND no c' within R chunks has a strictly higher score
                 (ties broken on the chunk key, so the rule is total)
```

Properties that matter:

- **Order-free.** Any iteration order yields the same set, so the Java and
  Python sides only have to agree on the noise function and the arithmetic —
  not on a traversal. F4 becomes a comparison of two sets, not two walks.
- **Same guarantee.** A local maximum over a radius-R disc cannot have
  another placement within R, so minimum separation still holds strictly.
- **Still precomputed**, so `getStartChunk` keeps the region-index shape that
  `FixedStructurePlacement` already proved works with vanilla `/locate`
  (locate probes ring by ring in SPACING-sized cells and asks
  `getStartChunk` for each — a coarse SPACING would step over positions, so
  SPACING stays `exclusion * 2` and each cell holds at most one placement).

The spiral survives only as the iteration order used to *build* the set; it
is no longer part of the definition.

Cost: one noise evaluation per chunk in the bounding box (cached in a float
array), then a bounded neighbourhood scan per above-threshold candidate with
early exit on the first higher neighbour. An 8192-radius dimension is
1024x1024 chunks = ~1M evaluations and a transient 4 MB array per group;
typical dimensions are 1024-radius = 16k chunks.

## B1: own Perlin, not vanilla's PerlinNoiseSampler

F4 demands bit-exact Java/Python agreement. Vanilla's sampler derives its
permutation from `net.minecraft.util.math.random.Random`, so parity would
mean mirroring that class's implementations and seed scrambling too, and it
drags Bootstrap-bound static init into unit tests (the reason
`FixedStructurePlacement.Index` already exists as a separate pure class).

`StructureNoise` is therefore ours: SplitMix64-driven Fisher-Yates over
0..255, one octave, **doubles everywhere** (Java `float` would round
differently from Python's always-double floats and lose F4 for nothing).

### Two real bugs the B1 tests caught

**1. Lattice points are seed-independent.** Perlin is exactly 0 at every
lattice point, which normalises to 0.5 for *every* seed. Frequency 0.025 is
1/40, so without an offset every 40th chunk on both axes scored precisely 0.5
in every world ever generated — and under `dense` (threshold 0.45) all of
them would place. A fixed grid of structures, produced by the system whose
entire purpose is removing fixed grids, invisible to any "looks random"
inspection. Fixed by irrational origin offsets (1/pi, Euler-Mascheroni)
inside `sampleChunk`, which no rational chunk coordinate can turn into an
integer at any frequency. Pinned by `differentSeedsGiveDifferentFields` and
`noChunkIsSeedIndependent`.

**2. Four gradient directions is too few to test against.** With `hash & 3`
a cell's value depends on 2 bits from each of 4 permutation entries — 256
outcomes, so two unrelated seeds agree at roughly 1 cell in 256. Harmless for
placement but it makes "did the field actually change" unassertable. Widened
to 8 vectors (`hash & 7`), all length sqrt(2) so the normalisation bound is
unchanged. Done before the Python mirror existed, so it cost nothing.

### Measured hit rates

Over a 1200x1200-chunk window, seed 12345 (`noiseprobe.py` in the session
scratchpad, superseded by the F1 mirror):

| profile | frequency | threshold | chunks above threshold |
| --- | --- | --- | --- |
| `natural` | 0.025 | 0.68 | 21.6% |
| `dense` | 0.040 | 0.45 | 59.2% |
| `sparse` | 0.015 | 0.85 | 5.4% |
| `cluster` | 0.008 / 0.05 | 0.90 / 0.40 | 3.2% active, 2.1% placed |

Value distribution at frequency 0.025: mean 0.500, stdev 0.224, spanning
0.0-1.0 (p5 0.117, p50 0.505, p95 0.866). These are *before* the radial curve
and exclusion, so they are candidate rates, not structure counts.

**Test windows must span many lattice cells.** `sparse`'s 0.015 frequency has
a 67-chunk period and `cluster`'s coarse layer 125 chunks, so the spike's
suggested 100x100 probe covers ~1.5 cells and measured `sparse` as *denser*
than `natural`. The tests use a 2000-chunk window with a step of 4.

## B2: ranking by white noise, not by the placement field

The local-maximum rule above was implemented, tested, and **found wrong**.
Local maxima of a *smooth* field occur about once per noise feature, so their
density is fixed by the frequency alone: the threshold barely participates (a
peak clears it comfortably) and the exclusion radius is inert. Measured: an
8192-radius dimension produced **283** placements for a group, and a
1024-radius pocket dimension produced **one**. Changing the exclusion radius
from 3 to 20 changed nothing.

The fix keeps the order-free property and restores both dials:

```
eligible(c) := noise(c) * radial(c) > threshold
placed(c)   := eligible(c)
               AND no eligible c' within R chunks outranks c
                   rank = mix64(seed ^ cx*GOLDEN ^ cz*OTHER), unsigned
                   ties on the chunk key
```

Now the smooth field plus the radial curve decide *what fraction of the world
qualifies* (the density dial) and the white-noise rank thins the qualifying
chunks to a Poisson-disc set with a hard minimum separation (the spacing
dial). This is the standard parallel formulation of dart throwing, so it is
also still order-free. Same dimension now yields **7033** placements, and a
1024-radius pocket gets roughly 110 — populated without being silly.

Ranks are compared **unsigned**; a signed comparison would systematically
favour whichever half of the range came out negative.

### Locate: one placement per cell is locatable, and that is fine

`spacing = exclusion * 2`, so a cell occasionally holds two placements. Both
GENERATE (`isStartChunk` is set membership); locate returns the registered
one. Identical accepted degradation to `FixedStructurePlacement`, whose
javadoc already documents it. Measured collision rate is well under a third
of placements. Two properties are load-bearing and tested instead:
`startFor` must answer *within the cell it was asked about*, and a cell
containing placements must never answer with a non-placement.

### Performance

512-chunk radius (8192 blocks, the largest shipped border), `natural`,
exclusion 3: **240 ms**, 7033 positions. The spike's target was 200 ms with
"log a warning if exceeded, no fallback needed" — C2 does that. Typical
dimensions are 64-chunk radius and build in single-digit milliseconds.

`NoiseFieldIndex` holds all of it; `NoiseStructurePlacement` is a thin
Minecraft-facing shell, so none of the above needs Bootstrap to test.

## C2 first live boot: three problems the unit tests could not see

Six dimensions loaded on elfydd. Noise placement worked, `the_dustbowl` fell
through to `density=none` unchanged — and three things were wrong that only a
real spread of dimension sizes could show.

### 1. A fixed noise frequency makes small dimensions all-or-nothing

`the_overgrowth` (1024-block border = 64 chunks) produced **0 settlements**,
and 53 positions across all seven groups.

`sparse`'s frequency of 0.015 is a **67-chunk lattice period**. A 64-chunk
radius world is 128 chunks across — about two periods. The noise over the
whole dimension is therefore essentially one blob, and whether a group gets
anything at all is a coin flip on where that blob's peak lands. The radial
curve makes it worse: `inner` confines settlements to the middle 30%, which
is a *fraction of a single lattice cell*.

Fixed by scaling frequency to the playable radius, so every dimension sees
the same NUMBER of noise features regardless of size:

```
effectiveFrequency = profile.frequency * (REFERENCE_RADIUS / radiusChunks)
REFERENCE_RADIUS = 512 chunks (8192 blocks, the largest shipped border)
```

`natural` is then ~25 lattice periods across the playable diameter and
`sparse` ~15, in a pocket dimension and a full-size one alike. Dimension size
now changes the scale of the pattern, not its character.

### 2. The ring walk was O(r^3)

`the_end_citadel` (8192 border, `dense`, 5 groups) took **3417 ms** to build.
Two suspects were wrong before the real one turned up, which is worth
recording because both looked obviously guilty:

- **`NoiseProfile.sampler()` hashing per evaluation.** A `ConcurrentHashMap`
  lookup in the hottest loop there is, one per chunk per group. Binding the
  samplers once in the constructor is clearly right and is kept — but it
  moved the number by nothing at all (3417 -> 3400 ms).
- **Recomputing the priority hash per neighbour test.** Also real, also kept
  (ranks are now cached beside eligibility), also worth ~nothing on its own
  (3400 -> 3387 ms).

The actual cost was the outward ring walk, which scanned each ring's whole
square and discarded the interior:

```java
for (ring = 0..r)
  for (dz = -ring..ring)
    for (dx = -ring..ring)
      if (max(|dx|, |dz|) != ring) continue;   // discards O(ring^2) per ring
```

That is **O(r^3)** — about 1.8e8 iterations at radius 512, to visit 1e6
chunks. Replaced with a single O(r^2) row scan plus a sort by
(distance, chunk key), which is cheaper AND a stronger ordering guarantee:
nearest-first rather than ring-first.

**512-chunk radius: 3387 ms -> 67 ms.** The Python mirror got the same
treatment (its test suite went 11.9 s -> 3.2 s).

The order-free placement rule paid for itself here: replacing the traversal
wholesale produced **exactly the same 7033 positions**. Under the spike's
greedy spiral, this optimisation would have silently changed worldgen.

Lesson worth keeping: profile before optimising, even when the candidate is
as obvious as a hash lookup in an inner loop. Two plausible fixes moved the
number by 0.9% between them.

### 3. `structureDensity` was overriding the peaceful difficulty shift

`the_luminous_caverns` has `mobMultiplier: 0.0`, so the peaceful shift should
suppress `dungeons` and `endgame` — it kept both. Its `structureDensity:
"sparse"` was applied AFTER the shift and put a profile back on a group the
shift had set to `none`.

The spike's precedence list does put `structureDensity` above the difficulty
shifts, but the effect is wrong: a coarse density dial should not resurrect a
group that the dimension's own difficulty says does not exist there. The
shift now applies after the global density and before per-group config, so
the rule reads: **a peaceful world has no dungeons unless the author names a
profile for dungeons specifically.**

### Not a problem: the position counts

`the_end_citadel` produced 39,570 `deco` placements over 823k chunks — 1 per
21 chunks, which looks alarming until compared with what it replaces. The
`deco` group stands in for 144 separate structure sets, each of which vanilla
would place at a spacing of roughly 20-30 chunks; collectively that is about
1 per 3 chunks. Noise `deco` is SPARSER than the grid it replaces, not
denser.

### Also observed: over half of all sets use custom placement types

155 of ~280 sets pass through untouched because their placement is not
exactly `RandomSpreadStructurePlacement` (YUNG's, and everything Cristel Lib
rewrites at runtime — explorify and towns_and_towers). They keep grid
placement, exactly as the existing density path has always left them. Worth
knowing when reading a census: noise does not own the whole world's
structures, and never claimed to.

## F2: census scoring, and the bug that made F3 a no-op

`want_score(nearest_dist)` is replaced for every dimension noise owns.
New module `scripts/seed/census_scoring.py` (not inside
`score-dimensions.py` — that file is already 1400 lines and a module is
importable by tests and `fast_roller` without the importlib dance), wired in
through `score_candidate`.

```
structures = 0.6 * census + 0.4 * battery      (whichever exist)
census     = mean over resolved groups of
             0.7 * distribution_match + 0.3 * count_satisfaction
```

`distribution_match` bins a group's census positions by radial decile,
divides each bin by its ANNULUS AREA, and takes the cosine similarity
against the group's own radial curve. The area step is load-bearing: equal-
width radial bins cover unequal areas, so a perfectly uniform layout puts
more structures in the outer bins and a raw-count comparison reads that as a
border bias. Pinned by `test_raw_counts_are_area_normalised`.

The battery is kept, not deleted, because it is still TRUE for the sets
noise never took over — forced placements, and everything whose runtime
placement is not `RandomSpreadStructurePlacement`. Each battery entry is
routed by looking its structure up to a set and the set to a group: mapped
to an active group -> band occupancy from that group's histogram; mapped to
a group the dimension suppressed -> the structure genuinely does not
generate (want 0.0, shun 1.0); unmapped -> the old positional scoring,
unchanged. 673 of 676 shipped battery entries map.

### The F3 bug: `noisePlacement` never reached a real fingerprint

`generation_payload()` resolves noise groups through module state set by
`set_noise_defaults_dir`. `monolith_from_dir` calls
`load_dimension_configs(p)` (sets it) and then
`load_dimension_configs(p / "overlay")` — which set it AGAIN, to a directory
that holds dimension files but none of the noise data. Every real
`load_config()` therefore ended with `_NOISE_DEFAULTS = None`, `_noise_payload`
returned None for everything, and NO dimension gained the key.

F3's tests passed because they call `set_noise_defaults_dir` by hand. The
73/5 split it reports was measured the same way. Live, the DRIFTED wave the
handoff predicted had not actually been reachable. Fixed with an explicit
`set_noise_defaults=False` on the overlay scan; the consumer now fingerprints
73 of 78 dimensions, exactly as predicted, and `seed-status` reports the
drift.

Lesson, and it is the third instance of this shape in this spike: a test
that reproduces the production wiring by hand tests the maths and not the
wiring.

### Two more bugs found by measurement

1. **Nested datapack copies shadow real set ids.** `load_structure_sets`
   derives a namespace from the first `data/` segment of the path, so
   `…/.structure_sets/data/structures/data/dungeons_arise/…/major_structures.json`
   becomes `structures:major_structures`. Whichever copy the filesystem
   yielded first won the structure -> set map, and when it was the bogus one
   every Dungeons Arise want fell back to grid scoring silently. The lookup
   now prefers a set id that is actually classified. Unmapped battery
   entries: 100+ -> 3.
2. **The banked summary was 8x bigger than it needed to be.** The first
   version stored the profile, exclusion and radial curve per CANDIDATE —
   identical for all 200 candidates of a dimension, 2.9 MB for
   `the_burning_archipelago` alone. The store now holds counts and the
   histogram only; the scorer re-attaches the per-dimension settings from
   config (`_with_group_settings`).

### Cost, and why it is banked rather than recomputed

A census is a pure function of (seed, placement config), so it is computed
once per candidate and cached in the candidate store under `noiseCensus`,
keyed by a NEW `noise_fingerprint()` — the noise payload only, so a biome or
seedRoll edit does not throw the cache away.

Measured, 8 workers: 373 pocket-dimension candidates in 4.9 s; 199
`the_burning_archipelago` (8192 border, radius capped at 512 chunks)
candidates in 171 s. A cold full bank is therefore about an hour, dominated
by the 21 dimensions at the radius cap, and free on every rescore after.

The mirror was optimised first to make that affordable, with parity re-run
after each step (`test_noise_parity` still exact):

- `sample_row` — the z half of the Perlin lattice hoisted per chunk row,
  `_fade`/`_lerp`/`_grad` inlined, gradients as a coefficient table. Four
  million Python calls per group at radius 512 became zero. Exactness argued
  in the code: IEEE negation is exact and `+ 0.0 * z` is a no-op, so the
  table reproduces `_grad` bit for bit.
- radial weight cached by integer `dist_sq`, and an early-out for
  `weight <= threshold` (the sampler is clamped to [0, 1], so such a chunk
  can never place — whole deciles of `inner`/`outer`/`mid` are 0).
- `priority` inlined `mix64`; the chunk key in `_outranks` is derived only
  on a rank tie rather than once per candidate chunk.

Net: `the_end_citadel`'s five groups 8.0 s -> 6.2 s, `the_burning_archipelago`
6.6 s -> 4.9 s. The Java side is untouched — the owner's ruling on
`the_end_citadel` stands and none of this changes a position.

### Scores

`the_burning_archipelago` 48.6 -> **91.3** (F5 target was >= 65).
`the_overgrowth` 74.1 -> 84.3 with a different winner, so the census
re-ranks rather than just re-basing. `the_dustbowl` 86.0 -> 86.0 exactly:
the suppressed path is untouched, which is the regression that mattered.

`scripts/seed/census_scoring.py` and `noise_placement.py` were BOTH missing
from `build-stack-bundle.sh`'s MANIFEST — F1's module had never been added,
so `./dev seed-roll` on a consumer would have died on import the moment this
shipped. `test-scripts.sh --quick` catches it; it is why that gate exists.

## E1/G2: the world was the test rig, and it was stale

**G2's first run failed on the headline feature, and the feature was fine.**
`the_overgrowth` — biome list: jungles plus `dark_forest`, nothing else —
had `minecraft:igloo` and `minecraft:desert_pyramid` in its structure pool.
Worse, its pool was **398 structures, exactly the same size as
`the_frozen_strait`'s**, and those two dimensions share no biomes. Two
`multi_biome` dimensions cannot legitimately agree to the structure on how
many structures they support. The obvious read was that
`NoisePoolBuilder`'s biome filter was a no-op for `multi_biome`.

It was not. `customdim sample-biome-grid` on the live world returned
`minecraft:forest`, `birch_forest`, `natures_spirit:chaparral`,
`lavender_fields`, `white_cliffs`, `lukewarm_ocean` — a general overworld
spread, none of it in the config. The filter was filtering correctly against
a biome source that genuinely could produce all of that.

**The generator was older than the config.** Worldgen is creation-time-only
(`TROUBLESHOOTING.md#d2`): the biome source is serialised into `level.dat` at
world creation and `registerDimensions` skips keys already in the registry.
Only **15 of 78** dimensions logged `biome source built` on that boot — the
rest were running generators built under an earlier config. Every
biome-filter assertion on that world was measuring history.

Wiping `data/world` and letting every dimension be created fresh: **75 biome
sources built**, and `the_overgrowth`'s pool became what the spike promised —
`minecraft:jungle_pyramid`, `bettermineshafts:mineshaft_jungle`,
`towns_and_towers:village_jungle`, `dungeons_plus:lush_dungeon`; no igloos,
no desert pyramids; and the whole `maritime` group dropped out because no
maritime structure's biomes intersect a jungle world (7 groups/169 positions
-> 6 groups/130). Then 72 assertions, zero failures.

Lesson, and it is the same shape as the F3 bug earlier in this log: **the
thing that looked broken was the thing measuring it.** A structure-placement
assertion is only meaningful on a world created under the config being
asserted. `scripts/check-noise-regression.py` carries that as its first
gotcha.

### Chunky does not fix locate, and the latency was never ours

The handoff's plan for E1 was "pre-generate with Chunky, then re-run the
batteries". Carried out in full — `the_overgrowth`'s entire playable area,
16,384 chunks at 59 chunks/s, task completed, container healthy. The same
async locate then timed out at **240 s**, having timed out at 180 s
*before* pre-generation.

The control settles it, and it is stronger than `the_dustbowl`:

```
customdim locate structure minecraft:overworld "minecraft:village_plains" 120
  -> timed_out
```

Stock vanilla overworld, stock vanilla structure, placement code this
platform has never touched — and `LocateManager` calls the identical
`ChunkGenerator.locateStructure(world, entries, origin, 100, false)` that
`/locate structure` calls. The cost is vanilla's 100-ring search across ~150
structure mods. Nothing in noise placement, custom dimensions or RCON is
implicated, and pre-generation is irrelevant because the search does not
read saved chunks.

`mods/AGENTS.md` and the spike are updated: the "Chunky will fix it"
assumption is retired, with the measurement behind it.

### What replaced locate as the verification surface

The census file. Every G2 assertion — pool membership after biome filtering,
which groups the world type and the peaceful shift left active, each group's
radial curve, forced structures being removed from the pool — is answered
exactly from `/customdim structure-census` output by
`scripts/check-noise-regression.py`. RCON's only job is to say "go".

That pattern already existed in three places invented independently to dodge
the same wall (`structure-audit.txt`, the census dumps, `biome_grid.csv`).
It is now named and argued in `docs/spikes/SPIKE-REPLACE-RCON.md`.

## Status at handoff

**12 of 17 spike tasks complete.** Gates: 472 Java tests, 269 Python tests,
`./scripts/test-scripts.sh --quick` — all green. Local server healthy,
`Restarts=0`.

| Task | State |
| --- | --- |
| A1 group registry + rarity | done |
| A2 type defaults + curves | done |
| B1 `NoiseProfile` + `StructureNoise` | done |
| B2 `NoiseStructurePlacement` + `NoiseFieldIndex` | done |
| C1 `StructureGroupRegistry` + `structure-audit` | done |
| C2 noise path wired as the default | done |
| D1 config fields + backwards compatibility | done |
| E1 `/locate` | **partial** — contract tested, live run blocked (see below) |
| E2 `structure-census` | done |
| F1 Python mirror | done |
| F2 `distribution_match()` scoring | **not started** |
| F3 fingerprinting | done |
| F4 bit-exact parity | done — 2383 positions, exact |
| F5 rescore banked candidates | **not started** (needs F2) |
| G1 full seed-roll pass | **not started** (needs F2) |
| G2 regression suite for 10 dims | **not started** (needs a generated world) |
| G3 skills + docs | done for everything that exists |

### What is left, and what it depends on

**F2 is the gate for F5 and G1.** The roller still scores structures by
nearest-instance distance (`want_score`). Everything it needs to score by
distribution now exists — `noise_census()` returns the full census, the
fingerprint accounts for it, and parity is proven — but
`distribution_match()` has not been written. Until it is, `./dev seed-rescore`
would rescore against the old model, so F5's before/after comparison would
measure nothing.

**G2 needs a generated world.** So does the rest of E1. Both should be done
together, after Chunky has pre-generated the test dimensions — this is the
accepted fix for the locate latency, not a workaround:

- A bare synchronous `/locate` into an ungenerated custom dimension does not
  return on this hardware and can wedge RCON (no crash — the game log just
  stops advancing; recover with `docker stop -t 90 mc && docker start mc`).
- This is NOT a noise regression. The control is `the_dustbowl`: it has
  `structureDensity: "none"`, runs the untouched `FixedStructurePlacement`
  path with exactly ONE placement in the whole dimension, and is equally
  slow. It is the same latency `DimensionCommands`' async locate was built
  for.

**Expect a DRIFTED wave on the next `./dev seed-status`.** 73 of the shipped
dimensions gain a `noisePlacement` fingerprint key and will report DRIFTED.
That is correct — noise genuinely changed their worlds, and only a re-roll
produces valid measurements. 5 suppressed dimensions and the 4 base worlds
keep byte-identical fingerprints.

### Decisions made by the owner (2026-07-26) — do not relitigate

**1. `the_end_citadel`'s ~2.5 s placement build is ACCEPTED. Do not cap it.**

8192 border x `dense` x 5 groups = 62,556 positions, against the spike's
200 ms target. It logs the warning the spike specifies, it runs once per
world load, and it is off the tick loop. Owner's ruling: *"I'm OK with
the_end_citadel taking that long, as you say it's not any worse than vanilla
anyway."*

The reasoning that supports it: 39,570 `deco` placements stand in for 144
structure sets that vanilla would each place every 20-30 chunks — about 1 per
3 chunks collectively, against noise's 1 per 21. Noise `deco` is SPARSER than
the grid it replaces. A future agent should not "optimise" this by capping
positions, shrinking `MAX_RADIUS_CHUNKS`, or raising exclusion for large
dimensions: that would change worldgen to fix a number nobody is paying for.

**2. RCON slowness is PRE-EXISTING and out of scope.**

Owner's ruling: *"rcon truly is slow as shit, but that's not our problem,
it's been this way since we first installed it. Pre-warming a world down the
line with chunky will help us there."*

Do not spend time investigating it. The evidence that it is not ours: a
synchronous `/locate` into `the_dustbowl` — `structureDensity: "none"`, the
untouched `FixedStructurePlacement` path, exactly ONE placement in the whole
dimension — is just as slow as one into a noise dimension. It is why
`DimensionCommands` grew an async locate long before this work.

The fix is Chunky pre-generation, and it belongs with G1/G2 (which need a
generated world regardless), not with a placement change.
