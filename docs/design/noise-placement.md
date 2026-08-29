# Noise placement — design intent

Governing intent for how `custom-dimensions` decides where structures go, and
what to build next. Implementation as it stands:
[worldgen-structures.md](../mod-internals/worldgen-structures.md). Traps:
[T51](../../TROUBLESHOOTING.md#t51)–[T56](../../TROUBLESHOOTING.md#t56).

## The rule that overrides the rest

**Empty is a valid outcome.** Not every part of a world carries a structure,
and a model that must fill its value space is wrong by construction. Density
targets, repetition ceilings and pool floors all sit underneath this: none of
them may be satisfied by manufacturing content.

Two failures are equally bad and neither may be traded for the other:

- **too many copies of one thing** — the same church over and over;
- **too many things for too few sites** — a pack full of content a player
  never meets.

Trading one for the other is a compromise, not a fix. The cure for both is a
noise model that carries more information, not a site count tuned between two
bad outcomes.

## What the model does today

One octave of 2D improved Perlin (`StructureNoise`), one field per meta-group,
decorrelated by a per-group salt. Each profile is a frequency and a single
scalar threshold:

| profile | freq | threshold | exclusion |
| --- | --- | --- | --- |
| `sparse` | 0.015 | 0.92 | 2.6x |
| `natural` | 0.025 | 0.82 | 2.0x |
| `dense` | 0.040 | 0.72 | 1.6x |
| `packed` | 0.040 | 0.60 | 1.6x |
| `cluster` | dual layer | — | 0.8x |

A chunk above threshold is a candidate; rank-on-white-noise plus an exclusion
radius thins the survivors; the radial curve scales per position; the
[T52](../../TROUBLESHOOTING.md#t52) ceiling then solves exclusion upward.

**The defect is the last step, not the field.** The noise decides only WHERE.
WHAT stands there is a weighted draw over the group's whole pool, unrelated to
the field value. So every structure competes at every site by weight alone,
and nothing in the field ever means "village".

Measured on the overworld, 597 eligible structures over 1506 sites, total pool
weight 4307:

| pool weight | n | appears at least once | median copies |
| --- | --- | --- | --- |
| 1 | 172 | 32% | 0 |
| 3 | 194 | 57% | 1 |
| 8 | 130 | 85% | 6 |
| 20–40 | 4 | 100% | 14–16 |

A weight-1 structure expects `1506 / 4307 = 0.35` copies. Most never appearing
is the arithmetic of the lottery, not a bug in it — and no site-count tuning
fixes it, because the lottery is the part that is wrong.

## The direction: value slices, not a binary cut

Treat a noise field the way an image treats a channel. A channel carries a
value per position, and a palette maps ranges of that value to results. Most
of the range maps to nothing.

- A structure owns a **slice** of the value space (a village at 203–206 of
  255), not "everything above a cut".
- A slice is spatially coherent, because the field is: the same structure
  recurs where the field returns to that band, and is absent elsewhere.
- **Most of the space is unmapped**, and unmapped means empty ground. That is
  the feature, not a gap to fill.
- Rarity becomes slice WIDTH, which is a real, tunable, inspectable quantity
  rather than a lottery weight.

Multiple decorrelated channels multiply the space: three channels give
16,581,375 addressable values, so a structure can occupy a genuinely tiny
region and still be placed deterministically wherever that region occurs.
Channels are the natural home for the axes that already exist — theme, biome
affinity, distance from spawn — so "what belongs here" becomes a lookup in the
field rather than a filter applied after the fact.

**Equalise before slicing.** One octave of Perlin is bell-shaped: values
cluster near the middle, which is why a threshold of 0.82 selects a thin tail.
Cutting that distribution into equal-width bands gives wildly unequal areas.
Map slices through the field's CDF (equal-area bands), or band widths will not
mean what they say.

**Tune the range to the compression and spacing**, not to a fixed central
curve. The playable radius, the portal scale and the group's spacing all
change how much field a player crosses; the mapping has to follow them.

## Ordering: biggest and rarest first

Resolve placements in tiers, largest and rarest first — endgame and
near-border content before the small and common. Each tier's result then
constrains the tiers under it, so a handful of genuinely interesting things
are settled first and the rest arranges itself around them. Decide counts with
arithmetic plus seed-derived jitter, not by filling whatever the field offers.

Tier order stays deterministic; placement within a tier stays order-free
(rank-on-white-noise), which is what keeps the traversal optimisable and
headless — see [worldgen-structures.md](../mod-internals/worldgen-structures.md).

## The author's controls, and their strengths

These are a gradient, and the weak end must stay weak:

| control | strength | meaning |
| --- | --- | --- |
| `structures.wants` | **x1.2** | favour it — a nudge, never a guarantee |
| `structures.shuns` | **x1.5 reduction** | discourage it, slightly harder than a want favours |
| `structures.exclude` | absolute | remove it from the pool |
| `structures.force` | absolute | put exactly this here |

**A want on a force-placed structure is dead config.** `structures.force` is
exclusive by default, so the structure leaves the noise pool entirely and is
guaranteed at its coordinate — there is nothing left to favour, and the census
the roller scores against records only noise groups, so the want scores 0.0 on
every seed forever. Remove the want, or set `"exclusive": false` on the force
entry so organic copies also enter the pool and the census can see them.

A want is a **favouring, not a forcing**. Anything that makes a want
effectively mandatory (a x20 weight, a guaranteed seat) turns authorship into
scripting and produces the T53 failure from the other direction — see
[T53](../../TROUBLESHOOTING.md#t53), where a want reached by re-draw became a
universal filler. Precision is what `force` is for; removal is what `exclude`
is for.

## What the counts do NOT mean

- Total pool weight is not a target site count. 4307 units of weight does not
  ask for 4307 sites.
- Nor does every eligible structure have to appear inside a border. A pack
  larger than any one world is correct; dimensions are meant to differ.
- **Repetition is not automatically a defect.** Two similar villages in one
  dimension is reasonable and often right. Rarity, seed rolling and taste
  decide that — a ceiling exists to stop 393 end cities
  ([T52](../../TROUBLESHOOTING.md#t52)), not to enforce variety per se.
- Placement should read as random-ish, with biome emphasis and thematic sense
  applied — not as a quota being met.

## Dimensions may name any biome

The mod composes a foreign biome onto the host's terrain wearing its own
family's surface rule, deliberately, and that is the pack's strongest lever.
A cave dimension may take nether biomes; an overworld may take End ones.
**Authorship and narrative drive a dimension config** — the mod exists to make
that possible, so "that biome does not belong to this type" is never a reason
on its own. The constraints that are real: the roller needs one family for
noise sampling, and a biome with no climate parameters must be given an
explicit band ([T19](../../TROUBLESHOOTING.md#t19)).

Read a dimension's config before reasoning about it. `the_emberglass_foundry`
is a piglin foundry — blaze-rod igniter, blackstone frame, thermal and
magmatic caves — whose biome list happens to be overworld cave biomes. Its
thin endgame pool is a content decision open to the author, not a fact about
its `type`.

## Borders

`borders.player` is the world border: `WorldBorderManager` applies it as the
vanilla `WorldBorder`, and it bounds where a player can go and therefore where
chunks generate. `borders.generation` is a **tools-only** bound for Chunky
pre-generation and the map renderer, which is why dimensions set it at or
below the player border.

Its value is bounded at both ends: at least 2048 so a small dimension still
has terrain for Distant Horizons to draw, and no more than the server's render
distance beyond `borders.player`, because ground a player can never reach costs
pre-generation time, disk and render passes for nothing. `borders.generation`
is in the generation fingerprint, so changing one re-keys that dimension's
seed bank.

Reason about reachability from `borders.player`. Anything gating generation on
`borders.generation` is wrong — that mistake made six forced placements report
as unreachable when they generate normally on a visit.
