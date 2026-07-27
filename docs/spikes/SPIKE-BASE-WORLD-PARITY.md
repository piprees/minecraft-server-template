# Spike — Base worlds are managed everywhere except structure placement

> **Date:** 2026-07-27 | **Status:** built; E open **Prompted by:** the owner, 2026-07-27: _"The end, like the nether, is not a base world; just like the nether, overworld, and paradise_lost, our mod manages all worlds and dimensions. That's why we can specify a custom seed etc right? If not, this was one of the design goals of the project so we should add another spike for that."_

## The answer: half right, and the half that is wrong is a real gap

**Seeds: yes, the mod owns them.** `MultiverseConfig.getWorldSeedOverride` reads the `seed` field from `overworld.json`, `the_nether.json`, `the_end.json` and `paradise_lost.json`, and `ServerWorldSeedMixin` applies it — _"Static worlds: the config drives every seed. Each base-world file carries its own `seed`"_. Borders, difficulty and the seed roller's `worlds[]` entries work the same way. So the design goal is real and is mostly delivered.

**Structure placement: no.** Every base world silently gets **none** of it — no noise groups, no `structureDensity`, no `structures.force`, no `structures.spacing`, no `structures.mode`. Their whole `structures` block is inert.

### Why, exactly

Two gates, both in code paths that predate noise placement:

```java
// DimensionStructures.transformed — the entry point for ALL placement work
Identifier key = world.getRegistryKey().getValue();
if (!MultiverseConfig.getInstance().isManagedNamespace(key.getNamespace())) {
    return null;                       // <- every base world exits here
}
```

```java
// MultiverseConfig.applyLoadResult — what fills managedNamespaces
for (DimensionConfig config : this.configs.values()) {
    if (!config.isBaseWorld()) {       // <- base worlds are excluded by name
        this.managedNamespaces.add(config.getNamespace());
    }
}
```

`minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` and `paradise_lost:paradise_lost` are therefore never in a managed namespace, so `transformed()` returns null before it looks at anything else.

**The exclusion is not an accident and should not simply be deleted.** Its stated purpose is a safety rail: _"The mixins' path-based definition lookups must never match another mod's dimensions, so they gate on this first."_ `minecraft:` and `paradise_lost:` are namespaces other mods also populate, and the lookup that follows is by PATH. Widen the namespace set and a third party's `minecraft:whatever` could resolve against one of our configs. The fix has to match base worlds by **exact dimension id**, never by namespace.

### The roller consequence, which is how this surfaced

`dimension_profiles.generation_payload()` returns `None` for any entry without a `type` — and base-world files have no `type`, by construction. So base worlds get no generation fingerprint, no `noisePlacement` key, and no census. During F5 the spike's own assertion _"`the_end` score ≥ 60"_ failed with the score unmoved at 42.7, and it could not have done anything else: **there is no mechanism by which a base world's structure score can respond to this feature.**

That is the cleanest possible demonstration of the gap. The spike author expected base worlds to participate; the implementation never could.

## What parity would require

| Piece | Change |
| --- | --- |
| Gate | `transformed()` accepts a world whose exact dimension id matches a configured base world, in addition to the managed-namespace path |
| World type | Base-world configs carry no `type`. Group resolution needs one: `overworld` -> `overworld`, `the_nether` -> `nether`, `the_end` -> `end`, `paradise_lost` -> `paradise_lost:paradise_lost`. **`structure-type-defaults.json` already defines all four** |
| Radius | `borders.player` per base world; the overworld's 8192 is exactly `MAX_RADIUS_CHUNKS`, so no new maths |
| Roller | `generation_payload` to emit a payload for base worlds (keyed on the new type mapping), `noise_fingerprint`, and the census path |
| Progression | A reachability floor for fortresses and end cities — see the risks |

## Risks, and one of them is a progression blocker

1. **The Nether gates progression on fortresses.** Blaze rods come from blaze spawners, which come from fortresses. Vanilla places `minecraft:fortress` on a dense grid deliberately. Dissolving it into the `dungeons` group at `sparse` could make blaze rods a multi-thousand-block expedition, or — with an unlucky biome filter — absent within the playable border. **Any Nether rollout needs a floor on fortress reachability, or a forced placement, before it can ship.**
2. **The End gates elytra on end cities.** Same shape of problem: `minecraft:end_city` is progression, not scenery.
3. **The overworld is the world everyone is standing in.** Placement is boot-re-read (not creation-time-only), so this needs no world wipe — but already-generated chunks keep their structures and new chunks would not match. Visible inconsistency at the explored border, exactly the trap `worldgen-tuning` documents for `spacing` changes.
4. **Strongholds are `concentric_rings`, not `random_spread`**, so they pass through untouched — that one is safe by construction, and worth stating because it is the obvious thing to worry about first.
5. **Blast radius on the seed roller.** Base worlds currently share measurements with nothing (`generation_fingerprint` is None). Giving them a payload changes their fingerprints and re-keys their candidate stores — every base-world winner would go DRIFTED and need a re-roll.

## Decision

**The mod manages base worlds outright — no opt-in.** Seed, border,
difficulty, portal and structure placement apply to `minecraft:overworld`,
`minecraft:the_nether`, `minecraft:the_end` and
`paradise_lost:paradise_lost` exactly as they apply to a custom dimension.

The namespace gate stays as it is: base worlds resolve by **exact dimension
id** (`MultiverseConfig.getBaseWorld`), because `minecraft:` and
`paradise_lost:` carry other mods' dimensions and the lookup behind that gate
is by path. Their world type comes from their family
(`DimensionConfig.BASE_WORLD_TYPES`, mirrored in
`dimension_profiles.BASE_WORLD_TYPES`) since vanilla owns their generator; an
explicit `type` still wins.

Progression is protected by a **reachability floor** in
`scripts/check-noise-regression.py` rather than by an opt-out.

Deviations and measurements: [`BASE-WORLD-PARITY-LOG.md`](BASE-WORLD-PARITY-LOG.md).

## Task sketch

- [x] **A. Exact-id gate.** `transformed()` resolves a base world by dimension id. Unit test: a foreign `minecraft:` world with a colliding path still returns null.

  _Notes:_ `MultiverseConfig.getBaseWorld(dimensionId)` matches the full id;
  `DimensionStructures.transformed` calls it on the else-branch of the
  managed-namespace gate. `managedNamespaces` is untouched —
  `isManagedNamespace("minecraft")` and `someothermod:the_end` are both
  asserted directly.

- [x] **B. World type for base worlds**, with the four families above. Verify `NoiseGroupPlan.resolve` produces the expected groups for each.

  _Notes:_ `DimensionConfig.getType()` falls back to the family. Tests pin the
  group set per family and that an explicit type overrides it. Live:
  overworld 7/7, nether 4/5, end 5/5, paradise_lost 2/3.

- [x] **C. Roller parity.** `generation_payload` / `noise_fingerprint` / census for base worlds.

  _Notes:_ `monolith_from_dir` stamps the family and carries `structures`,
  `structureDensity` and `exitShrines`; `ensure_censuses` includes `worlds[]`;
  `build_profile`'s `is_world` moved from "no type" to identity, or
  paradise_lost would resolve family `overworld`. The payload carries a
  `baseWorld` key so a base world groups with itself alone.

- [x] **D. Progression floors.** Assert a reachable fortress and end city inside the playable border, via `scripts/check-noise-regression.py`.

  _Notes:_ `reachable` scores EXPECTED instances,
  `positions_within x weight / pool_weight`, plus forced placements in full —
  a noise group is one StructureSet behind one placement and vanilla picks the
  member by weight. 104 assertions, 0 failures; fortress within 512 blocks and
  end city within 2048 both pass.

- [x] **E. Re-score the base worlds** and compare; `the_end`'s 42.7 is the baseline to beat.

  _Notes:_ `the_end` **42.7 -> 86.7**. Every base world now responds to
  placement: overworld 98.4, the_nether 92.2, paradise_lost 91.8. A rescore
  rather than a re-roll is sufficient and correct — battery entries that noise
  owns are answered from the census, which is computed fresh from the seed,
  and the banked locate distances that remain in play belong to the ~155 sets
  that keep grid placement. 17,417 candidates across 81 targets.

- [x] **F. Portals.** Base-world portals are registered and handled by the mod, both directions.

  _Notes:_ `applyLoadResult` registers `hasPortal()` for base worlds too, and
  `CreateWorldsMixin` keeps the four eager so vanilla's `getWorld(NETHER)`
  callers never see null. Carpet bot: a 3x4 obsidian frame lit with flint and
  steel arrives in `minecraft:the_nether` at the configured scale, and the
  return trip lands back in `minecraft:overworld`.

## Files involved

| File                                                    | Why                                                    |
| ------------------------------------------------------- | ------------------------------------------------------ |
| `dimension/DimensionStructures.java`                    | The namespace gate                                     |
| `config/MultiverseConfig.java`                          | `managedNamespaces`, `isBaseWorld`, `getWorld`         |
| `dimension/NoiseGroupPlan.java`                         | Needs a world type for a base world                    |
| `scripts/seed/dimension_profiles.py`                    | `generation_payload` returns None without a `type`     |
| `config/custom-dimensions/structure-type-defaults.json` | Already has overworld/nether/end/paradise_lost entries |
