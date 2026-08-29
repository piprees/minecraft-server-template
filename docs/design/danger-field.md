# Design — the danger field (follow-up, after the placement work)

**Status: agreed follow-up, not scheduled. Depends on the occupancy table from
`structure-placement-plan.md` section 5.** Do not start before that exists — the
point is that it reuses that table rather than inventing a second notion of
"dangerous".

## The problem, in the maintainer's words

> I've always hated how mobs are constantly invading villages and bases, and
> having to hammer torches everywhere to nullify the spawning ruins the world a
> lot. Mobs should be reduced near inhabited settlements and emphasised near
> dangerous structures and abandoned old places, that's what mobs literally ARE.
> I can see why the designers did it, it's to encourage moving away from your
> base and exploring, but that only really makes sense in a single-player world.

The torch-spam tax is the tell: a mechanic whose counterplay is to visually
vandalise your own build is fighting the player's other goals. On a shared
server the "leave home to find danger" incentive is weaker still — people build
together and stay.

## What exists today

- **Ours is `f(y)` only.** `DifficultyManager.effectiveMultiplier(config, y)` is
  `mobMultiplier x depthFactor(y)`. No x/z term anywhere.
- **Vanilla's spatial field is backwards for this.** `LocalDifficulty` rises with
  chunk INHABITED TIME, so the longer you live somewhere the more dangerous it
  gets — it makes your own base worse and leaves wilderness soft.
- **The noise infrastructure is built and tested**, and has never been used for
  anything but structure placement: `StructureNoise` (seeded, deterministic,
  normalised to [0,1], lattice-offset fix included),
  `NoiseProfile.frequencyScale` (same feature size at any border), and the
  radial curve machinery.

## The design

```
effectiveMultiplier(config, x, y, z)
    = mobMultiplier
    x depthFactor(y)              // unchanged, existing
    x dangerFactor(x, z)          // new
```

`dangerFactor` is **not** independent noise. It is seeded from the placed
structures themselves, so danger correlates with what the world looks like:

| near | effect |
| --- | --- |
| `inhabited` — villages, settlements, camps in use | **suppress** — people cleared this ground and keep it lit |
| `hostile` — dungeons, lairs, fortresses | **amplify** — this is what the mobs came from |
| `abandoned` — ruins, wrecks, crypts, overgrown | **amplify**, less sharply — nobody has held it in a long time |
| `neutral` / open country | baseline |

Falloff is smooth and radius-scaled from each anchor, so it reads as a gradient
rather than a fence. A low-amplitude noise term on top stops it being perfectly
predictable.

**The invariant:** an inhabited structure's suppression must beat the depth term
inside its own footprint, or a village over a cave system is still a warzone and
the feature does nothing where it matters most.

## Three things to get right

1. **Correlate with placement, do not run independently.** A danger field that
   ignores structures gives a terrifying empty meadow and a gentle dungeon. It
   must share the occupancy table, and probably a salt derived from the same
   dimension seed, with the placement work.
2. **Players cannot see a noise field.** Depth is legible — you know you went
   down. An invisible gradient reads as inconsistent balance unless something
   signals it: ambience, fog, mob variety, particles near hostile anchors.
   Without a tell this feels like a bug rather than a system.
3. **It is a runtime path, not creation-time.** `applyMobModifiers` runs at
   `MobEntity.initialize` TAIL (`MobAttributeMixin`), so unlike worldgen this
   needs **no world wipe** — cheap to try, cheap to revert, safe to iterate on a
   live world.

## Why it is a follow-up, not part of the placement work

The occupancy table does not exist yet; building it is placement's job. Doing
both together would couple a worldgen change that needs a wipe to a runtime
change that does not, and the runtime half would be held hostage to the slower
one.

## Verification

- `effectiveMultiplier` becomes a pure function of (config, x, y, z, anchors) —
  unit-testable with no Bootstrap, like `NoisePoolBuilder.affinityOf`.
- Measured: mean mob multiplier and spawn rate sampled inside a village
  footprint, inside a dungeon footprint, and in open country, on one seed. The
  village figure must sit materially below baseline **and** below the depth term
  at the same y.
