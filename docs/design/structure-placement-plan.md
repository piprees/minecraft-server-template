# Design — placement that makes places, not noise

Target: structures that read as *settled landscape* rather than a smattering.
Governed by `structure-placement-principles.md`.

## What is wrong today, measured

Exclusion is `groupDefault.exclusion() x profile.exclusionMultiplier()`, scaled
per-position by the radial curve. **No term for structure size. No term for
rarity. No interaction between groups at all.**

| group | base | profile | radius | min gap |
| --- | --- | --- | --- | --- |
| deco | 3 | natural x2.0 | 6c | 96 blocks |
| loot | 4 | natural x2.0 | 8c | 128 |
| settlements | 6 | natural x2.0 | 12c | 192 |
| maritime | 6 | natural x2.0 | 12c | 192 |
| dungeons | 8 | sparse x2.6 | 21c | 336 |
| landmarks | 12 | sparse x2.6 | 31c | 496 |
| endgame | 20 | sparse x2.6 | 52c | 832 |

Consequences:

- Within `landmarks`, a small watchtower and a huge castle get the same 496
  blocks of elbow room.
- Each group's field is computed **independently**, so an endgame complex can
  land on top of a village and a castle: nothing in the model knows the other
  exists.
- Rarity affects only which structure wins a site, never how far apart sites sit.

### Now measured, against the real footprints

Every structure's clearance is its group's one gap divided by its own width.
Inside a single group that ratio spans two orders of magnitude:

| group | gap | n | min span | median | max span | clearance min..max | wider than the gap |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `deco` | 96 | 229 | 5 | 34 | 225 | 0.4x .. 19x | **21** |
| `loot` | 128 | 17 | 1 | 36 | 93 | 1.4x .. 128x | 0 |
| `settlements` | 192 | 167 | 9 | 49 | 240 | 0.8x .. 21x | **5** |
| `maritime` | 192 | 25 | 7 | 45 | 151 | 1.3x .. 27x | 0 |
| `dungeons` | 336 | 147 | 1 | 48 | 256 | 1.3x .. 336x | 0 |
| `landmarks` | 496 | 105 | 1 | 35 | 244 | 2.0x .. 496x | 0 |
| `endgame` | 832 | 77 | 38 | 99 | 255 | 3.3x .. 22x | 0 |

**26 structures are wider than their own group's minimum gap**, so two copies
at minimum spacing physically overlap — 21 in `deco` (`nova_structures:desert_ruins`
is 225 blocks against a 96-block gap) and 5 in `settlements`
(`nova_structures:nether_keep` 240 against 192). The median `landmarks` member
is 35 blocks wide and is given 496 blocks of clearance, fourteen times its own
width, while the widest gets twice its own width.

This is the case for `sizeFactor`, and it is a measurement rather than an
argument.

## 0. Site count must be bounded by POOL VARIETY — the missing constraint

Measured on `the_end_citadel`:

```
biomes named            7
structures able to generate there   42   (25 of them mes alone)
sites generated                 62,556
                    = ~1,489 copies of each structure, on average
```

Worse than average in practice: the weighted draw favours the common tiers, so
the head of the distribution repeats far more than 1,489 times while the tail
barely appears.

**Nothing in the model connects how many sites a dimension gets to how many
different things can fill them.** Density is derived from the profile and the
border alone. A dimension with 42 available structures generates the same site
count as one with 400.

That is the "another church, much like the last" failure at industrial scale,
and it is a separate defect from the threshold problem — fixing `dense` to admit
15% instead of 59% still leaves ~380 copies of each structure.

### The rule

```
targetSites ~= poolSize x repetitionBudget(profile)
```

`repetitionBudget` is a taste parameter — roughly how many times a player should
be willing to meet the same structure in one dimension. A first guess:

| profile | budget | `the_end_citadel` would get |
| --- | --- | --- |
| `sparse` | ~4 | 168 sites (~1 per 1225 chunks, ~560 blocks) |
| `natural` | ~8 | 336 sites (~390 blocks) |
| `dense` | ~15 | 630 sites (~290 blocks) |
| `packed` | ~30 | 1,260 sites (~200 blocks) |

Against today's 62,556 — a **99% reduction** — and 290 blocks between structures
in a dense End dimension reads as populated rather than paved.

Implementation: the exclusion radius (or the radial curve's scale) is solved for
the target count rather than set as a constant. That makes exclusion a
*derived* quantity, which also fixes the separate problem that exclusion is
currently blind to how much content a dimension actually has.

**This subsumes the border question.** A 4096 border is fine if density is
variety-bounded; it is only "too big" because density is currently a function of
area. Shrinking borders to control density would be treating the symptom.

## The model

Five changes. Each is independently useful; together they produce the effect.

### 1. Every structure gets a footprint radius `r_s` — DONE, measured

`config/custom-dimensions/structure-sizes.json`, baked into the jar as
`structure_sizes.json`. 781 of 783 structures, measured.

**No pregen was needed.** `Structure.createStructureStart` takes the world only
as a `HeightLimitView` and reads no block data, so a real `StructureStart` — and
its `getBoundingBox()` — assembles headlessly at any chunk.
`/customdim structure-sizes <dimension> [samples] [budget]` sweeps the whole
registry that way in five minutes, with the biome predicate always-true so a
structure is measured whether or not it belongs here.

**The jar-field proxy is dead. Do not revive it.** Measured against the real
boxes:

| declared field | n | Spearman vs measured span |
| --- | --- | --- |
| `size` (pool depth) | 740 | **+0.478** |
| `max_distance_from_center` | 501 | **+0.357** |

A declared `size` of 1 spans 5–72 blocks in practice; a declared `size` of 6
spans 1–244. `minecraft:village_plains` declares `max_distance_from_center: 80`
and spans **170**. `minecraft:mineshaft` declares neither field and spans 163.
`scripts/gen-structure-sizes.py --report` reprints this from the artefact.

Properties worth relying on, all measured:

- **A footprint is a constant, not a position.** Across three scattered
  assemblies per structure the spread `(max−min)/median` is 0.00 at the median
  and 0.22 at p90.
- **Two independent sweeps are byte-identical.** Deterministic.
- **Measure on an overworld.** It reaches 781 structures against a nether's
  727, and a low ceiling truncates the expansion — `minecraft:ancient_city` is
  242 blocks in an overworld and 65 in a nether. Where both measure one, 73%
  agree exactly. Merge by the larger value.

The two with no measurement (`betterdungeons:small_nether_dungeon`,
`subsurface:cave_blocker`) are left OUT of the table, not zeroed, and fall back
to their group's median.

### 1b. `rarityFactor` — MEASURED, and the design is wrong here

`rarityFactor` was specified as "rarer means further apart, which is what
'rare' should have meant all along. It currently only changes the draw
weight." Measured, that gradient **already exists**:

`rarityShares` are common 8.0, uncommon 3.0, rare 1.0, endgame 0.3 — a 26.7x
spread. Distance to the nearest other copy of the same structure, full-border
overworld:

| rarity | n | median | p10 | min | within 200 blocks |
| --- | --- | --- | --- | --- | --- |
| common | 477 | 564 | 170 | 107 | 16% |
| uncommon | 906 | 1172 | 241 | 72 | 7% |
| rare | 763 | 1089 | 264 | 122 | 5% |
| endgame | 376 | 2157 | 833 | 113 | 2% |

Endgame copies already sit ~4x further apart than common ones. **The median is
healthy; the defect is entirely in the tail** — minima of 72–122 blocks in
every tier. Scaling every separation in a group to fix its worst 2% is the
wrong shape of instrument, and rarity is not the variable: two DIFFERENT
landmarks near each other is interesting, not repetitive.

Rarity is also not a proxy for size, so this would have been new information
rather than double-counting: Spearman(rarity tier, measured span) = **+0.191**,
with medians common 37, uncommon 36, rare 49, endgame 45.

**A same-structure separation was built and reverted.** It cost 14% of
occupied sites and moved no minimum at all, because a site's assigned
structure is not its occupant 63% of the time
([T56](../../TROUBLESHOOTING.md#t56)). Repetition has to be addressed in the
pool or the re-draw chain, not in the placement field.

### 2. Personal space replaces a flat group radius

```
R_s = base(group) x profile.exclusionMultiplier() x sizeFactor(r_s) x rarityFactor(rarity)
```

- `sizeFactor` — monotonic in footprint, bounded (roughly 0.6x to 2.5x) so a
  huge castle claims more ground than a well without either becoming absurd.
- `rarityFactor` — rarer means further apart, which is what "rare" should have
  meant all along. It currently only changes the draw weight.

The pairwise test becomes `dist >= R_a + R_b` rather than a single group radius,
so a big thing and a small thing negotiate a sensible gap without a matrix.

### 3. Tiered passes, with interaction — and order-freedom preserved

Place in a fixed tier order, largest and rarest first:

```
tier 0  forced placements
tier 1  endgame
tier 2  landmarks
tier 3  dungeons, settlements
tier 4  maritime, loot
tier 5  deco
```

Each tier is still rank-on-white-noise Poisson-disc **within** itself, so it
stays order-free and optimisable. Higher tiers are fully resolved before a lower
tier starts, so the lower tier's acceptance test is a pure function of already
settled state. **Deterministic tier sequence, order-free within a tier** — the
property `docs/mod-internals/worldgen-structures.md` calls load-bearing is kept.
Say this explicitly in the implementation or someone will read the tiers as
order-dependence and "fix" it.

### 4. Forced placements seed the field

Tier 0. They **do not test against each other** — a hand-placed pair is the
author's business — but they contribute full `R_s` and full repulsion to every
organic tier after them. "Put exactly this here" then also means "and the world
arranges itself around it".

### 5. Occupancy classes: one small table, not an N^2 matrix

Tag each structure (via its group, overridable per set) with an occupancy class:

| class | examples |
| --- | --- |
| `inhabited` | villages, outposts, settlements, camps in use |
| `abandoned` | ruins, wrecks, abandoned camps, overgrown things |
| `hostile` | dungeons, lairs, monster strongholds |
| `neutral` | deco, natural features, small scenery |

Interaction:

```
abandoned x inhabited   REPEL strongly    people reuse the stone or clear it away
hostile   x inhabited   REPEL strongly    nobody settles next to the lair
neutral   x anything    ATTRACT           scenery gathers where things happen
inhabited x inhabited   mild repel        villages are not suburbs
abandoned x abandoned   neutral           ruins keep company
```

**Repulsion** = an extra additive term on the pairwise distance test.

**Attraction** = a **local threshold bonus**, not a hard rule: within
`attractRadius` of a placed anchor, the noise threshold for a `neutral` candidate
drops by a bounded amount. Deco therefore *gathers* around settlements and
landmarks without being teleported there, and empty country stays empty. This is
what makes a "place" — a landmark with scenery around it — rather than an evenly
sprinkled field.

Attraction must never lower the threshold below the profile's floor, or `packed`
around a big anchor becomes a carpet.

## Verification — the instrument already exists

`structures_form_places_not_noise` scores **Clark-Evans** nearest-neighbour per
group: 1.0 random, 2.1491 a perfect lattice, below 1.0 clustered, `POCKET = 0.5`
the target for `cluster` groups.

That is exactly the statistic this design should move, and it is already wired
into the scorecard. Targets:

- `deco` should fall **below 1.0** — clustered around anchors. Today it is
  approximately random because its field is independent.
- `settlements`, `landmarks`, `endgame` should sit **near or above 1.0** —
  dispersed, not lattice-like.
- **Cross-group** nearest-neighbour distance from `abandoned` to `inhabited`
  should rise measurably. That statistic does not exist yet and needs adding —
  it is the only way to prove rule 5 works.

`/customdim site-validity` already reports per-site group, coordinates and
biome, so the cross-group statistic is computable from the artefact it writes.

## The scale of the problem, measured

`the_end_citadel` is not a performance stress case. It is the proof that the
current model is broken.

| dimension | sites | disc | density | mean gap |
| --- | --- | --- | --- | --- |
| `the_end_citadel` | 62,556 | 205,887 chunks | **1 per 3.3 chunks** | **~29 blocks** |
| `overworld` | 4,586 | 823,550 chunks | 1 per 180 chunks | ~214 blocks |

**54.6x the overworld's site density. A structure every 29 blocks.** A player is
never out of sight of one, anywhere in that dimension.

`docs/mod-internals/worldgen-structures.md:29` currently defends this:

> Accepted: a large dense dimension takes seconds to build its placements ... Do
> not "optimise" it by capping positions ... The counts are already
> conservative: 39,570 `deco` placements replace 144 structure sets vanilla
> would each place every 20-30 chunks, so noise `deco` is *sparser* than the grid
> it replaces.

Three things wrong with that paragraph, and it must be rewritten:

1. **It frames a gameplay catastrophe as a performance note.** 2.5 seconds at
   world load is fine. 29 blocks between structures is not.
2. **The baseline is hypothetical.** "Sparser than 144 sets each placed every
   20-30 chunks" compares against a stacked grid nobody would ever ship. Sparser
   than an absurdity is not a defence.
3. **The border figure is wrong.** It says 8192; the config says
   `borders.player: 4096`, which is what makes the density twice as bad as the
   sentence implies.

`dense` at threshold 0.45 admits 59% of chunks. That is the direct cause, and it
is why `dense` is renamed `packed` and re-thresholded — see
`DECISIONS-2026-08-29.md` section 4.

**Every `dense` dimension needs re-measuring after the threshold change**, not
just the overworld. This one is the reason the change matters.

## Cost, and the thing most likely to bite

The current build is 457ms for 4586 sites over a 512-chunk radius, once per
world load. Tiered placement with pairwise interaction is worse than the current
per-group independent pass:

- Naive pairwise is O(n^2) across tiers. Use a spatial hash keyed on chunk /
  max-radius; the interaction radius is bounded, so it stays near-linear.
- `the_end_citadel` is the stress case — 8192 border, `dense`, 62,556 positions.
  Measure that one before shipping, not the overworld.

Do NOT optimise by capping positions, shrinking `MAX_RADIUS_CHUNKS`, or raising
exclusion — all three change worldgen.

## Build order

1. ~~**Size table**~~ — **DONE.** `config/custom-dimensions/structure-sizes.json`,
   781 of 783 measured. Section 1 above.
1b. ~~**Site count bounded by pool**~~ — **DONE**, as a CEILING not a target.
   `NoiseStructurePlacement.forGroup`, `NoiseProfile.REPETITION_CEILING = 6`.
   Section 0 above, and [T52](../../TROUBLESHOOTING.md#t52).
1c. ~~**`rarityFactor`**~~ — **DROPPED**, measured. Section 1b above.
2. ~~**Personal space** (`sizeFactor`)~~ — **DONE**, `StructureFootprints`.
   ~~`rarityFactor`~~ dropped. Was inside the existing
   independent per-group pass. Measure Clark-Evans before and after.
3. **Tiered passes with repulsion**, forced placements at tier 0. NOT STARTED.
4. **Occupancy classes and attraction.** NOT STARTED. The occupancy CLASS is
   still inferred from keywords in `analyse-site-validity.py`; the mod has no
   table.
5. **The cross-group statistic** — the statistic itself EXISTS
   (`analyse-site-validity.py` reports abandoned/hostile/neutral distance to
   the nearest inhabited structure). It is not yet provable, because the
   classes it uses are keyword guesses rather than (4)'s table.

Each step is separately measurable against the existing scorecard, so a
regression is attributable to one change rather than to "the new system".
