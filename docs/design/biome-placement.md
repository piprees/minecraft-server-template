# Biome placement — design intent

What a dimension's `biomes` list is FOR. Implementation:
`DimensionManager.buildMixedSource`. Traps: [T19](../../TROUBLESHOOTING.md#t19),
[T35](../../TROUBLESHOOTING.md#t35), [T58](../../TROUBLESHOOTING.md#t58).

## The rule that overrides the rest

**A listed biome must be ENCOUNTERABLE.** A player exploring the dimension
meets it, and finds a place rather than a speck when they do.

That is the bar, and it rules out both failures either side of it:

- **Not "present".** A biome holding one cell of 1257 passes every band check
  and gives a player one 50-block patch in a whole world. `/locate` finds it;
  nobody else will. Boolean thinking — nonzero therefore fine — excuses
  generation that does not work for the person playing it.
- **Not "equal".** Equal parts of every biome is a quadrant world, and
  `checkerboard` is the type for that. A dominant biome with the others each
  occupying somewhere real is a good world, and often the authored one.

The measure is what a player meets, so it is expressed in ground rather than in
cells or percentages. A cell of a 41x41 grid is ~50 blocks at a 1024 border and
~200 at 4096, so any threshold in cells is wrong across borders by construction.

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
