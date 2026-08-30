# Biome placement — design intent

What a dimension's `biomes` list is FOR. Implementation:
`DimensionManager.buildMixedSource`. Traps: [T19](../../TROUBLESHOOTING.md#t19),
[T35](../../TROUBLESHOOTING.md#t35), [T58](../../TROUBLESHOOTING.md#t58).

## The rule that overrides the rest

**A listed biome must be ENCOUNTERABLE.** A player exploring the dimension
meets it, and finds a place rather than a speck when they do.

That is the bar, and it rules out both failures either side of it:

- **Not "present".** A biome holding one cell of 1257 passes every band check
  and gives a player a single patch one grid cell across — 25 blocks at a 512
  border, 51 at 1024, 205 at 4096. `/locate` finds it; nobody else will.
  Boolean thinking — nonzero therefore fine — excuses generation that does not
  work for the person playing it.
- **Not "equal".** Equal parts of every biome is a quadrant world, and
  `checkerboard` is the type for that. A dominant biome with the others each
  occupying somewhere real is a good world, and often the authored one.

The measure is what a player meets, so it is expressed in ground rather than in
cells or percentages. A cell of a 41x41 grid is ~50 blocks at a 1024 border and
~200 at 4096, so any threshold in cells is wrong across borders by construction.

### What a place is

Stated by the maintainer, 2026-08-30. This is the specification.

| span | what it is |
| --- | --- |
| **32 blocks** across | a campsite. The floor for being anywhere at all |
| **128–256 blocks** across | **a place.** People go here; something lives here; someone named this area |

**And a dimension has a budget of places, not a share per biome.** This is the
part that inverts the obvious reading:

- A **pocket** dimension (512, 1024) has **one place at most**.
- A **4096** dimension could hold up to sixteen, and usually wants fewer.
- A dense dimension past that is "all still kind of one big place".

**So most biomes in a small dimension are not places, and are not meant to be.**
They are the material a place sits in. A 512-border world listing thirteen
biomes should not contain thirteen places; it should contain one, and twelve
biomes' worth of surroundings.

That retires the speck question as posed. "This biome holds only a small share"
is not a defect — it is what a supporting biome looks like. The real questions
are whether the dimension has **a** place, and whether the biome that got to be
that place is **the one the author chose** rather than whichever entry the
nearest-point lookup happened to favour.

A per-biome floor was proposed earlier (~320 blocks, from two render distances)
and is **withdrawn**: its own author found the derivation conflated a radius
with a diameter, and the corrected figure was still untested. Do not reinstate a
per-biome minimum; the budget above is per dimension.

## Gaps are the design; specks are not

A gap between explicit bands is where native biomes live, and a dimension that
is mostly natives by intent is correct — `check-biome-bands.py` tests for
starved natives precisely because total band coverage is the failure mode, not
the target. `the_ember_fields` at 4% banded is a nether dimension meant to be
that way.

A speck is different. It is a band the author wrote, that survives every check,
and that no player will stand in.

## Every dimension may name any biome

The mod composes a foreign biome onto the host's terrain wearing its OWN
family's surface rule. A nether biome in a cave world comes out as nylium and
basalt on cave terrain and brings its mobs. That is deliberate and it is the
pack's strongest lever for making a place feel like somewhere rather than like a
preset. **"That biome does not belong to this type" is never a reason on its
own.**

The constraints that are real: the roller needs one family for noise sampling,
and a biome the source cannot place needs a band ([T19](../../TROUBLESHOOTING.md#t19)).

## An explicit band is an override, not a requirement

A band exists so an author can say where something belongs. It is not the
mechanism by which a biome becomes placeable, and treating it as one produces
hand-written guesses at placements the biome's own author already declared.

The same relationship holds elsewhere in the mod: `portal.aura.palette`
overrides sampling the destination, and omitting it is the richer default.
Where a biome carries its own declared placement, use it.

**Two separate questions, and only the first is the author's:**

| question | answered by |
| --- | --- |
| WHICH biomes this dimension has | the author's `biomes` list |
| WHERE each one goes | the biome's own mod, by default |

**And the list is exhaustive only where the type curates.** `multi_biome`,
`single_biome` and `checkerboard` exist to name a set, so there the list is the
world. Everywhere else it is a set of asks laid over a world that already works
— bring this one in, put that one here — and a biome missing from it is not
thereby excluded.

`overworld` is the type that shows the difference, and it does not currently
work: it takes the base generator whole, so its biomes do arrive naturally, but
the list never reaches the biome source and no ask is catered.
`amplified` and `large_biomes` clone the world preset and drop it the same way.
That is a gap, not a design — see [K9](../../TROUBLESHOOTING.md#k9).

A band answers the second question, so it is for the case where the author
wants a specific placement — a crimson forest at the heart of a nexus, a band
of ice along one climate edge. **Using a band merely to make a biome appear is
the anti-pattern**: it substitutes our guess for a placement its author already
described, and it does it silently, because a hand-written band looks identical
to a deliberate one.

So a compat for a mod's placement mechanism is not an optimisation. It is what
keeps bands meaning something. Every band written as plumbing is a band that
cannot be read as intent, and a pack whose biomes only appear because someone
hand-placed them is a pack of patterns rather than places.

**Once a mechanism can be read, the bands that were standing in for it come
out.** Leaving them means the compat never takes effect — explicit outranks
natural — so the work is inert and the guesses remain. Removing them is the
point of doing it, not a follow-up.

## Where a band's boundaries may sit

A `ParameterRange` contains both its endpoints, so two bands sharing a boundary
are **both at distance zero** from it. The winner is then decided by
`SearchTree`'s incumbent — a `ThreadLocal` holding whatever the previous lookup
on that thread returned — so it turns on which column a worker resolved
immediately before, and nothing in the config decides it. That is the mechanism
behind "this biome is missing" reports nobody can reproduce.

Two rules follow, and neither is negotiable:

- **Never let two bands share a boundary. Leave a gap.** Not an epsilon — an
  epsilon gap leaves the loser a sliver, and a sliver was worthless in the first
  place. A real gap is where natives live, which is the design.
- **No boundary may sit on a clamp rail** (±0.5, ±1.0, ±2.0). Clamping a fit to
  a measured range puts a boundary on that range's endpoint, and a rail IS a
  range endpoint — so equal-area fitting collides with the rails by
  construction. The fitter and the defect are the same mechanism.

  **The noise really does saturate there, and it is density-stable.** Measured
  per dimension on the same axis at two grid densities, the fraction of samples
  sitting exactly on ±0.5/±1.0 agrees to within a few points:

  | dimension | axis | grid 11 | grid 41 |
  | --- | --- | ---: | ---: |
  | `the_ashgrove` | weirdness | 67.8% | 68.6% |
  | `the_claymarsh` | weirdness | 59.5% | 61.6% |
  | `the_blossom_gardens` | weirdness | 36.4% | 37.8% |
  | `the_chalk_meadows` | weirdness | 27.3% | 24.7% |

  So a generator cutting on round numbers puts its boundaries exactly where the
  noise piles up. **`the_ashgrove` carries a 14-band contiguous weirdness chain
  and 68.6% of its weirdness samples sit on a rail** — two thirds of that world
  divided by a tie-break rather than by its boundaries.

  Three alternatives were tested and refused. Storing samples at three decimal
  places could fake a rail by collapsing −0.4996 onto −0.500; rounding the dense
  grid to 3dp moves the counts by under 1%. An apparent reversal at density was
  a composition effect, not a density one — only 10 of 68 dimensions band at a
  rail, and the dense subset contained almost none of them. And the sampling
  lattice cannot be the cause: `2B/10` is exactly four times `2B/40` at every
  border, so the 121 coarse points are 121 of the 1681 dense points. A lattice
  artefact would be diluted roughly fourteen-fold by the superset, and the rate
  is unchanged.

  The rule does not depend on the figure — a shared boundary ties whatever the
  noise does — but the figure explains why fitters keep landing there.

**A band whose only reachable territory IS the rail cannot be fixed by moving
its boundary.** It needs a different axis, or it becomes a plain string and
takes its own declared cells. That second route is what fixed all seven of the
dimensions this rule was derived from.

## What "fixed" means when a biome is not encounterable

In order of preference, because each preserves more of the author's intent than
the next:

1. **Read the placement its author declared**, where one exists.
2. **Fit the band to the world's own measured climate**, at the density the game
   reads — never to the theoretical -2..2, which no world crosses, and never to
   another dimension's window.
3. **Move it to an axis the world actually varies across.** Rank by DISTINCT
   values with span as the tiebreak; span alone picks the worst axis on a
   clamped one.
4. **Change the authorship** — a different biome, or fewer of them. That is the
   author's call, not a fitter's.

Measure at the geometry the game uses. A grid coarser than the game's overstates
absence: 121 points against the game's 1257 cells over-called empty bands by
**3.8x**, and produced a fault report of 75 where the real figure was ~18.
