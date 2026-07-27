# Base-world parity — implementation log

Working notes for `docs/spikes/SPIKE-BASE-WORLD-PARITY.md`. Decisions,
deviations and measurements. Terse by design.

## Decision

**Base worlds are managed like every other dimension.** No opt-in: seed,
border, difficulty, portal and structure placement all apply to
`minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` and
`paradise_lost:paradise_lost` the same way they apply to a custom dimension.

Two things make that safe rather than a widening of the namespace gate:

- **Exact-id resolution.** `MultiverseConfig.getBaseWorld(dimensionId)` matches
  the full id. `managedNamespaces` still excludes `minecraft:` and
  `paradise_lost:`, because the lookup behind that gate is by PATH and those
  namespaces carry other mods' dimensions.
- **Family-derived type.** A base world's generator is vanilla's, so its file
  names no `type`; `DimensionConfig.getType()` supplies one from
  `BASE_WORLD_TYPES` and an explicit value still wins. The roller mirrors this
  in `monolith_from_dir`.

## Deviations from the spike

| Spike says | Built instead | Why |
| --- | --- | --- |
| Opt-in per base world, default off | Always on | The floors below are the safety mechanism; an opt-in would have shipped a feature nobody used. |
| `transformed()` gains an exact-id path | Same, plus `CreateWorldsMixin` keeps base worlds eager | The mixin deferred every non-overworld world, so `server.getWorld(World.NETHER)` was null and nothing lazily created it. Vanilla asks for base worlds by key from paths with no hook. |
| Base worlds have no scaled arrival | They do | Their portals are registered now, so `portal.scale` vs `borders.player` applies to them exactly as to a custom dimension. |

## Measurements

### Structure placement, live (elfydd, fresh world)

```
overworld      noise radius=512c groups=7/7 positions=13775  (309ms)
the_nether     noise radius=64c  groups=4/5 positions=442
the_end        noise radius=512c groups=5/5 positions=9545
paradise_lost  noise radius=512c groups=2/3 positions=6898
Lazy world creation: 78 custom dimension(s) deferred to first entry, 4 base world(s) created now
```

`paradise_lost` resolves `settlements` from its type and still shows only
`deco` and `landmarks`: no settlement set's biomes intersect the paradise
biome source, so the pool is empty and the group is skipped. The checker
asserts the world's actual pools, not the type's list.

### Progression floors

`scripts/check-noise-regression.py`, 104 assertions, 0 failures. The two that
gate the release:

```
the_nether  reachable: >= 1.0 expected minecraft:fortress within 512 blocks   PASS
the_end     reachable: >= 1.0 expected minecraft:end_city within 2048 blocks  PASS
```

Expectation is `positions_within x weight / pool_weight`, because a noise
group is one StructureSet behind one placement and vanilla picks the member by
weight — presence in a pool says nothing about reach.

### Portal travel, Carpet bot

| Frame | Result |
| --- | --- |
| 3x4 obsidian interior, flint and steel | `minecraft:the_nether` at (388.5, 124, 388.5) — 3101 / 8, the configured scale |
| Step out, cooldown 0, step back in | `minecraft:overworld` |
| 2x3 obsidian interior, flint and steel | `minecraft:the_nether` |

`the_obsidian_sanctum` is `shape: "doorway"`, which `PortalShape.isDoorway`
defines as exactly a 2x3 interior — the vanilla minimum portal. Sharing
obsidian and flint-and-steel with the Nether left shape as the only
discriminator, so a minimum-size portal reached the sanctum. Its igniter is
`minecraft:netherite_ingot` instead (owner's call): obsidian plus flint and
steel now means the Nether at every size, and the sanctum is gated on the
metal the piglins who built it would have used.

### Scores

`score-dimensions.py rescore`, 17,417 candidates across 81 targets:

| Base world | Score | Winner spawn |
| --- | --- | --- |
| overworld | 98.4 | `minecraft:plains` |
| the_nether | 92.2 | `incendium:quartz_flats` |
| paradise_lost | 91.8 | `paradise_lost:highlands` |
| the_end | 86.7 (was 42.7) | `minecraft:end_highlands` |

A rescore rather than a re-roll: battery entries that noise owns are answered
from the census, computed fresh from the seed, and the banked locate distances
still in play belong to the ~155 sets that keep grid placement. Three of the
four picked a new winner, so the census re-ranks rather than re-bases.

### The End's border

`the_end` was 4096 with `portal.scale: 1.0` against an 8192 overworld, so an
end portal built beyond 4096 arrived outside the End's own border, where
vanilla forbids placing and breaking. Raised to 8192 to match the scale.
`ShippedDimensionReachabilityTest` now covers base worlds, so the pair can
never drift apart again.
