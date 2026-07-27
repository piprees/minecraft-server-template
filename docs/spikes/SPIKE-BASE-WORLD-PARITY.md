# Spike — Base worlds are managed everywhere except structure placement

> **Date:** 2026-07-27 | **Status:** research, not scheduled
> **Prompted by:** the owner, 2026-07-27: *"The end, like the nether, is not a
> base world; just like the nether, overworld, and paradise_lost, our mod
> manages all worlds and dimensions. That's why we can specify a custom seed
> etc right? If not, this was one of the design goals of the project so we
> should add another spike for that."*

## The answer: half right, and the half that is wrong is a real gap

**Seeds: yes, the mod owns them.** `MultiverseConfig.getWorldSeedOverride`
reads the `seed` field from `overworld.json`, `the_nether.json`,
`the_end.json` and `paradise_lost.json`, and `ServerWorldSeedMixin` applies
it — *"Static worlds: the config drives every seed. Each base-world file
carries its own `seed`"*. Borders, difficulty and the seed roller's
`worlds[]` entries work the same way. So the design goal is real and is
mostly delivered.

**Structure placement: no.** Every base world silently gets **none** of it —
no noise groups, no `structureDensity`, no `structures.force`, no
`structures.spacing`, no `structures.mode`. Their whole `structures` block
is inert.

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

`minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` and
`paradise_lost:paradise_lost` are therefore never in a managed namespace, so
`transformed()` returns null before it looks at anything else.

**The exclusion is not an accident and should not simply be deleted.** Its
stated purpose is a safety rail: *"The mixins' path-based definition lookups
must never match another mod's dimensions, so they gate on this first."*
`minecraft:` and `paradise_lost:` are namespaces other mods also populate,
and the lookup that follows is by PATH. Widen the namespace set and a third
party's `minecraft:whatever` could resolve against one of our configs. The
fix has to match base worlds by **exact dimension id**, never by namespace.

### The roller consequence, which is how this surfaced

`dimension_profiles.generation_payload()` returns `None` for any entry
without a `type` — and base-world files have no `type`, by construction. So
base worlds get no generation fingerprint, no `noisePlacement` key, and no
census. During F5 the spike's own assertion *"`the_end` score ≥ 60"* failed
with the score unmoved at 42.7, and it could not have done anything else:
**there is no mechanism by which a base world's structure score can respond
to this feature.**

That is the cleanest possible demonstration of the gap. The spike author
expected base worlds to participate; the implementation never could.

## What parity would require

| Piece | Change |
| --- | --- |
| Gate | `transformed()` accepts a world whose exact dimension id matches a configured base world, in addition to the managed-namespace path |
| World type | Base-world configs carry no `type`. Group resolution needs one: `overworld` -> `overworld`, `the_nether` -> `nether`, `the_end` -> `end`, `paradise_lost` -> `paradise_lost:paradise_lost`. **`structure-type-defaults.json` already defines all four** |
| Radius | `borders.player` per base world; the overworld's 8192 is exactly `MAX_RADIUS_CHUNKS`, so no new maths |
| Roller | `generation_payload` to emit a payload for base worlds (keyed on the new type mapping), `noise_fingerprint`, and the census path |
| Opt-in | A base world must NOT get noise placement by default — see the risks |

## Risks, and one of them is a progression blocker

1. **The Nether gates progression on fortresses.** Blaze rods come from
   blaze spawners, which come from fortresses. Vanilla places
   `minecraft:fortress` on a dense grid deliberately. Dissolving it into the
   `dungeons` group at `sparse` could make blaze rods a multi-thousand-block
   expedition, or — with an unlucky biome filter — absent within the playable
   border. **Any Nether rollout needs a floor on fortress reachability, or a
   forced placement, before it can ship.**
2. **The End gates elytra on end cities.** Same shape of problem:
   `minecraft:end_city` is progression, not scenery.
3. **The overworld is the world everyone is standing in.** Placement is
   boot-re-read (not creation-time-only), so this needs no world wipe — but
   already-generated chunks keep their structures and new chunks would not
   match. Visible inconsistency at the explored border, exactly the trap
   `worldgen-tuning` documents for `spacing` changes.
4. **Strongholds are `concentric_rings`, not `random_spread`**, so they pass
   through untouched — that one is safe by construction, and worth stating
   because it is the obvious thing to worry about first.
5. **Blast radius on the seed roller.** Base worlds currently share
   measurements with nothing (`generation_fingerprint` is None). Giving them
   a payload changes their fingerprints and re-keys their candidate stores —
   every base-world winner would go DRIFTED and need a re-roll.

## Proposed shape

**Opt-in per base world, default off.** A base world gets noise placement
only when its config says so explicitly — e.g. `"structures": {"noise":
{...}}` or a `"type"` field naming its family. Absent that, behaviour is
byte-identical to today, which keeps every existing world stable and keeps
the vanilla progression guarantees intact until someone deliberately opts a
world in and tests it.

That also matches what the codebase already does elsewhere: the
`structures.noise` map is the explicit-intent escape hatch, and as of
2026-07-27 an entry there adds a group rather than merely re-profiling one.

## Task sketch

- [ ] **A. Exact-id gate.** `transformed()` resolves a base world by
  dimension id. Unit test: a foreign `minecraft:` world with a colliding path
  still returns null.
- [ ] **B. Type mapping for base worlds**, opt-in only, with the four
  families above. Verify `NoiseGroupPlan.resolve` produces the expected
  groups for each.
- [ ] **C. Roller parity.** `generation_payload` / `noise_fingerprint` /
  census for opted-in base worlds; confirm a non-opted-in base world's
  fingerprint stays `None` so no candidate store is disturbed.
- [ ] **D. Progression floors.** Before any Nether/End opt-in ships: assert
  a reachable fortress and end city inside the playable border, via
  `scripts/check-noise-regression.py`. This is the gate, not a nice-to-have.
- [ ] **E. Re-roll the opted-in worlds** and compare scores; `the_end`'s 42.7
  is the baseline to beat, and this is the first change that could move it.

## Files involved

| File | Why |
| --- | --- |
| `dimension/DimensionStructures.java` | The namespace gate |
| `config/MultiverseConfig.java` | `managedNamespaces`, `isBaseWorld`, `getWorld` |
| `dimension/NoiseGroupPlan.java` | Needs a world type for a base world |
| `scripts/seed/dimension_profiles.py` | `generation_payload` returns None without a `type` |
| `config/custom-dimensions/structure-type-defaults.json` | Already has overworld/nether/end/paradise_lost entries |
