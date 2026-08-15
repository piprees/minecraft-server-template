# Seed scoring audit — can these ten dimensions reach 80?

Scope: whether `RollPipeline.SCORE_THRESHOLD = 80.0` is reachable for
`the_abyssal_shrine`, `the_amplified_reaches`, `the_burning_archipelago`,
`the_boneyard`, `the_bloodroot_wastes`, `the_buried_age`, `the_blighted_maw`,
`the_blackstone_keep`, `the_basalt_spires` and `minecraft:the_end`, given the
criteria their configs pose.

## What this is measured on — read before quoting any number

The bank at `~/Projects/elfydd/.seed-rolling/candidates/` holds **90 scorecards
over 82 dimensions**. `SeedBank` never deletes a card, so that is all of it, and
it is **one seed per dimension** — the seed already written into that
dimension's overlay as its chosen winner, re-measured by
`RollPipeline.primeNamedSeeds`. The only search in the bank is a 12-seed roll of
`minecraft:the_nether`. There is no trace of the 160-candidate
`the_abyssal_shrine` roll the brief mentions; the bank was cleared before it.

That sample supports three different kinds of claim, and this document keeps
them apart:

| Basis | What it can establish | Confidence |
| --- | --- | --- |
| **Config + code** — every dimension JSON, `Criteria.java`, `Scorer.java`. No seed involved. | Which criteria each dimension poses, its tier ceilings, what one lost mark costs, which code path a given outcome takes. This is what "is 80 reachable" reduces to. | Exact |
| **Cross-section** — 81 dimensions × 1 seed. A sample of *configs*, not of seeds. | Claims about config properties: how the score varies with border size, with a want's band, with placement density. | Good for config effects; one draw per dimension, so no per-dimension distribution |
| **Single card** — one seed of one dimension. | Illustration of a mechanism. Nothing about that dimension's best achievable score. | Anecdote |

**What is therefore not established here:** the best score any of these ten
dimensions can actually achieve, how often each clears 80, and whether
`the_end`'s gate rejects most seeds or was unlucky once. Those need a roll of N
seeds per dimension with the bank retained, which this audit was not permitted
to run. § What would settle it says exactly what to run.

Nothing was applied. The dimension JSON changes below are specified, not made:
`seedRoll` feeds `InputHash`, so editing it orphans that dimension's banked
candidates.

> **The two largest findings in this document are not about the threshold.**
>
> 1. The facts layer counts vanilla structures that YUNG's cancels in the live
>    world — 373 ocean monuments and 215 fortresses across 90 cards — while the
>    replacements that actually generate are absent from every pool. **And those
>    cancels are seven booleans we never set**: the platform ships no YUNG's
>    config, so `disableVanillaFortresses = true` and its six siblings are mod
>    defaults we inherited. Six structure types are suppressed by omission, and
>    the fix is to take the decision, not to teach the census about it. See
>    § The pool is nearly complete, and it measures the wrong fortress.
> 2. The committed dumps cannot express the facts that would have caught it.
>    `extract-structure-sets.py` reads `placement.spacing/separation/frequency`
>    and discards `placement.type`, which is the only field that says whether the
>    scorer can see a set at all; `dimension` and `theme` are keyword guesses that
>    are demonstrably wrong in the shipped file (the Towns & Towers village set is
>    recorded as a *nether maritime* set); and `biomes.json` omits climate
>    parameters entirely, which is the field two known silent-failure traps turn
>    on. See § How the dumps are generated, and what they cannot express.
>
> 3. Both of those are symptoms of a coverage gap. A structure our pool builder
>    does not absorb is **outside our placement algorithm entirely** — no
>    density, radial, rarity or difficulty control, only an on/off switch — and a
>    biome with no climate parameters is outside our biome placement the same
>    way, silently taking most of a world. The mod already counts the structures
>    it cannot reach, once per dimension per boot, and throws the number away.
>    See § Coverage.
>
> The actionable part is § Config changes: seven of the ten clear 80 on config
> alone, `minecraft:the_end` included, and the changes tighten the criterion that
> ranks seeds rather than lowering the bar.

## Config + code: the arithmetic that decides reachability

`Scorecard.percentage()` (Scorecard.java:97–114) is the **mean of the two tier
percentages**, not `achieved / ceiling`. Confirmed on `the_abyssal_shrine`'s
card: configured 6.350/11 = 57.73%, general 4.382/5 = 87.64%, headline 72.685 —
where `achieved / ceiling` would be 10.732/16 = 67.08%.

Every criterion is weight 1 within its tier (Scorer.java:98–107). Tier
membership, from the `tier()` overrides:

| Tier | Criteria |
| --- | --- |
| `CONFIGURED` (ceiling **C**) | `spawn_reads_as_namesake` (if `spawnFilter` set), `biome_variety_present` (if `biomes` > 1), `terrain_matches_preset` (if `terrain` is a known word), `water_matches_intent` (if `water` set), `height_range_matches_intent` (if `heightRange` valid), one per `wants` entry, one per `shuns` entry |
| `GENERAL` (ceiling **G**) | `headline_biome_dominates_appropriately`, `biome_edges_near_spawn`, `structures_form_places_not_noise` and `first_encounter_distance` (if structures enabled), `playable_ground_covers_the_disc` (unless `terrain` is `void`/`islands`), `spawn_is_playable` (unless `allowHazardousSpawn`) |

So one configured mark is worth `50/C` headline points, and **with a perfect
general tier a dimension can afford exactly `0.4 × C` lost configured marks and
still reach 80.** Two values of C matter: what the config asks (computed from
the JSON) and what a seed is marked on (the config's total less any criterion
that comes back `Unmeasured` — see below). The allowance runs on the marked one.

| Dimension | C asked | C marked | G | Marks affordable at a perfect general tier | `near_border` wants |
| --- | --- | --- | --- | --- | --- |
| `the_abyssal_shrine` | 11 | 11 | 5 | 4.4 | 1 (`sculk_dungeon`) |
| `the_boneyard` | 13 | 11 | 6 | 4.4 | 1 (`infernal_altar`) |
| `the_buried_age` | 11 | 11 | 5 | 4.4 | 1 (`copper_tower`) |
| `the_basalt_spires` | 10 | 10 | 6 | 4.0 | 2 (`crimson_forge`, `nether_tower`) |
| `the_blackstone_keep` | 11 | 10 | 6 | 4.0 | 1 (`forbidden_castle`) |
| `the_blighted_maw` | 11 | 10 | 6 | 4.0 | 1 (`lost_soul_dungeon`) |
| `the_bloodroot_wastes` | 11 | 10 | 6 | 4.0 | 1 (`pipeline`) |
| `the_burning_archipelago` | 11 | 10 | 4 | 4.0 | 0 |
| `the_amplified_reaches` | 9 | 9 | 6 | 3.6 | 1 (`keep_kayra`) |
| **`minecraft:the_end`** | **5** | **5** | 6 | **2.0** | **2 (`phantom_citadel`, `enderkeep`)** |

Across all 81 rollable dimensions the asked C runs from 2 to 16 (18 dimensions
sit at 11), so one lost configured mark costs between 3.12 and 25.00 headline
points depending only on how many lines the author wrote.

**`minecraft:the_end` is the one dimension this arithmetic rules out as
written.** Its config poses five configured criteria, two of which are
`near_border` wants on structures the dimension places in quantity
(§ Cross-section 2 shows what that means). Losing both spends its entire
allowance, leaving a requirement for a flawless general tier — which includes
`biome_edges_near_spawn`, and the end's central island is one biome for roughly a
thousand blocks around spawn while `FactsEngine.MOSAIC_STEP = 48` samples only to
192 blocks. **80 is unreachable for `the_end` on its current config** — but it is
reachable on a corrected one, without any scorer change: see § Config changes,
which raises C from 5 to 7 and lands every gate-surviving seed at 81–84.

For the other nine the arithmetic does **not** rule 80 out. Each can afford 3.6
to 5.2 lost marks. Whether they actually reach it is an empirical question this
bank cannot answer.

### Two different ceilings, and the invariant that does not hold

`Scorer.ceiling(def, criteria)` (Scorer.java:180–188) counts every applicable
non-gate criterion from config alone. The ceiling written into a scorecard
counts only criteria that produced a `Score` — `Unmeasured` is excluded from
both numerator and denominator (Scorer.java:108–114). The docstring says "the
scorer's own ceiling must equal it for every seed, and a test asserts so".

**It does not.** Comparing the config-only ceiling against the banked ceiling for
all 81 rollable dimensions: they match on 60 and differ on **21**. The extreme
cases are `the_dustbowl` and `the_gritlands`, which each pose **15** configured
criteria and are measured on **3** — `structureDensity: none` makes every want
and shun `Unmeasured`. `the_dustbowl`'s headline of 100.0 is a mark out of seven
questions where its config asked nineteen.

This is not per-seed instability: across the nether's 8 scored seeds the measured
ceiling is 5.0/6.0 every time, because pool membership is config-derived. It is a
systematic gap between what a config asks and what a seed is marked on, and it
means a headline is not comparable between two dimensions that pose different
numbers of measurable questions.

### The code path that turns an unsatisfiable want into a counted zero

The brief's hypothesis is **false for the case it names and true for a
neighbouring one**. `Criteria.WantedStructure.evaluate` has two absence paths:

- **Criteria.java:1220–1228** — the structure is not in the dimension's pool:
  `Result.Unmeasured`, excluded from achieved *and* ceiling. `wants:fortress`
  (`betterfortresses:fortress`) on eleven nether dimensions takes this path and
  costs no marks. It is why `the_bloodroot_wastes` poses 11 configured criteria
  and is marked out of 10. Costing no marks is not the same as being harmless —
  the fortress those dimensions asked for is never assessed at all.
- **Criteria.java:1238–1242** — the structure *is* in the pool and the seed
  placed none: `Result.Score(0.0, …)`, which Scorer.java:98–107 adds to achieved
  (0.0) **and** ceiling (1.0). A full mark forfeited.

Pool membership is a weight in `NoisePoolBuilder`, not a promise of placement, so
a low-weight structure sits in the pool permanently and pays out rarely.

But "not in the pool" is the mod's own wording, and for `wants:fortress` it is
not a config error at all — it is a measurement gap. See the next section.

## The pool is nearly complete, and it measures the wrong fortress

`FactsEngine.structureFacts` claims in its docstring to be "**not a model of the
placement, it is the placement**". That is true of the noise-managed path and
false as a statement about the world, in two directions.

### What is in the pool

The extractor at `config/custom-dimensions/extractors/structures.json` records
**380 structure sets declaring 721 structure ids** on this stack. Taking the
union of `facts.structures.pool` across all 90 cards (82 dimensions, so nearly
every config in the pack):

| | Count |
| --- | --- |
| Structure ids declared by installed sets | 721 |
| Ids appearing in at least one dimension's pool | **713** |
| Ids ever actually placed | 705 |
| **Ids never in any pool** | **9** |

So the pool is not systematically missing bulk data. The nine exceptions are:

| Structure | Source |
| --- | --- |
| `adventure:exit_shrine` | `customdimensions.jar` — mod-placed, not organic; expected |
| `betterfortresses:fortress` | YUNG's Better Nether Fortresses |
| `betterstrongholds:stronghold` | YUNG's Better Strongholds |
| `betterdeserttemples:desert_temple` | YUNG's Better Desert Temples |
| `betterjungletemples:jungle_temple` | YUNG's Better Jungle Temples |
| `supplementaries:galleon` | Supplementaries |
| `mvs:crimson_enchanting_table` | Moog's Voyager Structures |
| `minecraft:stronghold` | vanilla (concentric rings; `spacing: 0`) |
| `nova_structures:end_castle` | Dungeons and Taverns (`spacing: 0`) |

These are the sets whose placement type `NoisePoolBuilder` does not absorb, so it
never sees them. **They still generate in the world on their own grid — which
means they are outside our placement control, not merely unscored.** Other
YUNG's mods whose sets *are* plain random-spread (`bettermineshafts`,
`betteroceanmonuments`, `betterwitchhuts`) appear in pools normally, so this is a
placement-type boundary, not a mod boundary. § Coverage establishes the exact
rule and what it costs.

### What is in the pool that we chose to suppress by accident

[T25](../TROUBLESHOOTING.md#t25) states that all seven YUNG's mods
`@Inject(HEAD, cancellable)` into `ChunkGenerator.trySetStructureStart` and
cancel every start of the vanilla type they replace, and its corollary reads:
"vanilla fortresses, mineshafts, strongholds, desert and jungle temples, ocean
monuments and witch huts never generate ORGANICALLY on this stack."

**That corollary describes an unconfigured stack, not a constraint, and this
audit initially treated it as the latter.** T25's own cause line says the cancels
are "config-gated, on by default". They are — and each gate is a single boolean
that the platform has never set. Read live from
`~/Projects/elfydd/data/config/`:

| Mod config file | Key | Value |
| --- | --- | --- |
| `betterfortresses-fabric-1_21.toml` | `disableVanillaFortresses` | `true` |
| `betteroceanmonuments-fabric-1_21.toml` | `disableVanillaMonuments` | `true` |
| `bettermineshafts-fabric-1_21.toml` | `disableVanillaMineshafts` | `true` |
| `betterdeserttemples-fabric-1_21.toml` | `disableVanillaPyramids` | `true` |
| `betterjungletemples-fabric-1_21.toml` | `disableVanillaJungleTemples` | `true` |
| `betterwitchhuts-fabric-1_21.toml` | `disableVanillaWitchHuts` | `true` |
| `betterdungeons-fabric-1_21.toml` | `removeVanillaDungeons` | `true` |
| `betterstrongholds-fabric-1_21.toml` | — | **no such key exists** |

Every one of those files was written by the mod on first boot with its own
default. `config/` in this repo ships **no YUNG's config at all** — the only
`better*` entry is `config/betterdays/`, an unrelated mod — and
`docker/defaults-seed/Dockerfile` has exactly one matching `COPY` line, also for
`betterdays`. So six structure types are suppressed on this stack by omission.
Nobody decided it; nobody wrote it down as a decision; it is a mod default we
inherited and then documented as though it were physics.

`structureFacts` runs pool builder → noise field → weighted pick. It never calls
`trySetStructureStart`, so it never sees the cancel. Counted across the 90 cards:

| Structure | In pool on | Placed on | Copies counted |
| --- | --- | --- | --- |
| `minecraft:monument` | 7 cards | 6 cards | **373** |
| `minecraft:fortress` | 29 cards | 25 cards | **215** |
| `minecraft:mineshaft` | 41 cards | 10 cards | 36 |
| `minecraft:jungle_pyramid` | 10 cards | 5 cards | 29 |
| `minecraft:swamp_hut` | 10 cards | 7 cards | 17 |
| `minecraft:desert_pyramid` | 5 cards | 4 cards | 9 |
| `betterfortresses:fortress` | **0** | **0** | **0** |

Every count in the top six is a structure T25 says the live world cancels. The
replacement that actually generates is unmeasurable.

### What that does to scoring and rolling

- **`wants:fortress` is not a config mistake.** `StructureAliases` resolves it to
  `betterfortresses:fortress` — the structure that really generates — and the
  facts layer cannot see that structure, so the criterion returns `Unmeasured`
  and vanishes. Eleven nether-family dimensions ask for a fortress and none of
  them is scored on one. The want is right and the measurement is missing.
- **`wants:monument` is scored against a phantom.** It resolves to
  `minecraft:monument`, which *is* pooled, so `the_abyssal_shrine` was marked
  0.142 on the distance to an ocean monument that will not exist. The alias table
  points at the replacement for fortresses and at vanilla for monuments; those
  two choices cannot both be right.
- **`FortressReachableInNether` gates on a phantom.** Criteria.java:872 tests
  `minecraft:fortress` within 512 blocks, and the gate's stated purpose is that
  blaze rods have no source but a fortress. On this stack the blaze rod source is
  `betterfortresses:fortress`. The 4 rejections out of 12 nether seeds were
  decided on positions of a structure that never generates, and the 8 passes
  proved nothing about blaze access either.
- **Aggregate facts are contaminated.** `nearestHostile` (which
  `first_encounter_distance` reads), the per-group clustering behind
  `structures_form_places_not_noise`, and the total placement counts all include
  these positions.

### The fix is to un-suppress, not to teach the census about the suppression

An earlier draft of this audit proposed adding `suppress.structures` to
`config/custom-dimensions/settings.json` so the census would stop counting
vanilla structures. **That was the wrong direction** — it would have made the
measurement agree with a suppression nobody chose, and permanently written off
six structure types.

The right fix is to take the decision we never took. Ship the YUNG's configs
with the toggles set deliberately. Per AGENTS.md § Config sync these mods read a
bare path, so it is a flat file plus a `COPY` line — two places, both required:

```toml
# config/betterfortresses-fabric-1_21.toml
[general]
disableVanillaFortresses = false
```

```dockerfile
# docker/defaults-seed/Dockerfile
COPY config/betterfortresses-fabric-1_21.toml   /defaults/config/
COPY config/betteroceanmonuments-fabric-1_21.toml /defaults/config/
COPY config/bettermineshafts-fabric-1_21.toml  /defaults/config/
COPY config/betterdeserttemples-fabric-1_21.toml /defaults/config/
COPY config/betterjungletemples-fabric-1_21.toml /defaults/config/
COPY config/betterwitchhuts-fabric-1_21.toml   /defaults/config/
```

What that buys, immediately and without touching the mod:

- **Six structure types come back under our control.** Vanilla sets are plain
  `minecraft:random_spread`, so they are noise-managed — `structureDensity`,
  group profiles, radial curves, rarity tiers and the difficulty shifts all apply
  to them, unlike their YUNG's replacements.
- **The census stops lying about them.** The 373 monuments and 215 fortresses it
  counted become real placements rather than phantoms, and `wants:monument`
  becomes an honest question.
- **`FortressReachableInNether` becomes correct** without a scorer change. It
  tests `minecraft:fortress` within 512 blocks; once vanilla fortresses generate,
  that is a real structure with real blaze spawners again.

What it does not buy: `betterfortresses:fortress` is still a pass-through and
still unmeasurable and unmanaged. Re-enabling vanilla is **orthogonal** to the
coverage work in § Coverage, not a substitute for it. Both YUNG's and vanilla
versions would then generate, which is a design call — if you want only one, the
lever is now yours either way, which is the point.

**Two caveats, both real.** Structure density rises: six sets re-enter the pool
on every overworld and nether dimension, and `structureDensity` is the dial for
that. And `betterstrongholds` exposes no toggle at all in the version we pin —
its config carries only `enableStructureRuin` and `filledPortalFrameChance` — so
vanilla strongholds cannot be restored by config. That one needs either an
upstream config option or our own mixin; we already inject in that exact area
(`ChunkGeneratorForcedStartMixin`, priority 900, per T25), so it is tractable in
our mod rather than impossible.

**This is a worldgen change.** It affects newly generated chunks only
([D2](../TROUBLESHOOTING.md#d2)), so an existing world shows a boundary, and it
changes every dimension's structure fingerprint — which re-rolls seeds. Take it
before a world reset, not after.

## Coverage: what our placement can reach, and what it silently cannot

The previous section framed the unpooled structures as a scoring problem. That
undersells it. **A structure outside the pool is outside our placement algorithm
entirely** — it is not merely unscored, it is unmanaged. The same is true of
biomes that carry no climate parameters. This section establishes the exact
boundary and what it costs.

### The structure boundary, verified in code

`NoisePoolBuilder.noiseManaged` (NoisePoolBuilder.java:72–84) is the whole
decision:

```java
if (placement.getClass() == RandomSpreadStructurePlacement.class) return true;
if (!(placement instanceof RandomSpreadStructurePlacement)
        || placement instanceof FixedStructurePlacement) return false;
Identifier type = Registries.STRUCTURE_PLACEMENT.getId(placement.getType());
return type != null && ABSORBED_PLACEMENT_TYPES.contains(type.toString());
```

`ABSORBED_PLACEMENT_TYPES` (NoisePoolBuilder.java:62) holds **exactly one
entry**: `moogs_structures:advanced_random_spread`. So a set is noise-managed if
it uses vanilla `random_spread`, or Moog's subclass; everything else passes
through on its own grid.

The allowlist is deliberate and its reasoning is stated in the code: Moog's
`getStartChunk` is "byte-identical vanilla maths" so it is absorbed; YUNG's four
types and Supplementaries' galleons "stay pass-throughs — their cross-set
exclusion zones are real behaviour"; `concentric_rings` "is not grid-compatible
at all". This is not an oversight to be patched away.

**It does correct a shipped doc.** The worldgen skill states that "227 of 367"
sets keep their own grid placement, "dominated by Moog's
`moogs_structures:advanced_random_spread`". That is no longer true — Moog's is
absorbed, and 238 Moog's structure ids appear in pools across the bank. The skill
predates the absorption and should be fixed.

### What a pass-through costs, and it is not scoring

For a pass-through set we retain a switch and nothing else:

| Lever | Applies to a pass-through? |
| --- | --- |
| `structureDensity` | **no** |
| `structures.noise` group profile | **no** |
| `structures.radial` curves | **no** |
| `structures.rarity` tier | **no** |
| Difficulty shifts (`mobMultiplier` suppressing dungeons/endgame) | **no** |
| `structures.mode` / `structures.exclude` | yes — on/off only |
| Any scoring criterion | **no** — invisible to the facts layer |

So `betterfortresses:fortress` — the flagship Nether structure on this stack,
and the only blaze-rod source — generates at its jar-declared spacing of 30/20 in
every dimension whose biomes match, and we cannot make it rarer, commoner,
clustered, pushed to the border, or kept from spawn. We can only delete it. The
same holds for strongholds, desert temples, jungle temples, galleons and Moog's
crimson enchanting table.

### We already compute the size of this gap and throw it away

`NoisePoolBuilder.Result` carries `setsSkippedCustomPlacement`, and
`DimensionStructures.java:411` logs it per dimension on every world load:

```
Dimension {} structure profile: noise radius={}c groups={}/{} positions={}...
    ({} sets passed through, {} pass-through filtered, {} custom-placement, {}ms)
```

That number is printed into `docker logs mc` at every boot, for every dimension,
and **nothing captures it**. The audit's "8 unmeasurable structures" was
reconstructed by unioning 82 dimensions' pools — a slow, indirect route to a
figure the mod computes directly and discards. Sizing this properly needs no new
instrumentation, only somewhere to write a number we already print.

### Biomes have the same shape of gap

`DimensionManager` (lines 303–360) builds a multi-noise source from three tiers:

1. **Explicit** — a listed biome with a valid `parameters` object gets one
   hypercube, placed exactly where the author asked.
2. **Native** — the family preset's own entries (`entries.getEntries()`), i.e.
   biomes that already carry a hypercube in the overworld/nether/end preset.
3. **Leftover** — everything else is dealt the remaining climate regions
   round-robin.

Tier 3 is the trap. A biome with no native hypercube and no explicit
`parameters` block is not dropped and does not error — it is handed leftover
regions, and per [T19](../TROUBLESHOOTING.md#t19) one such biome ends up taking
74–100% of the world while the rest of the list never appears.

**Nothing in the committed data says which biomes are in tier 2.**
`extractors/biomes.json` records 229 biomes with `temperature`, `downfall`,
`has_precipitation`, `effects` and `spawners` — and no climate parameters at all.
All 47 Nature's Spirit biomes, `minecraft:end_barrens` and
`minecraft:end_midlands` sit in it looking perfectly usable; they are precisely
the ids T19 and the authoring skill's trap 13 warn will swallow a dimension. An
author has no way to tell tier 2 from tier 3 before booting a world.

That is the biome equivalent of the placement-type gap: a usable-looking id that
is silently outside what the algorithm can place correctly.

### Making YUNG's structures work — the mechanism, checked in the jars

The code comment says YUNG's "stay pass-throughs — their cross-set exclusion
zones are real behaviour", and an earlier draft of this audit accepted that and
filed absorption as a vague "design decision". That was a punt. Here is what is
actually in the jars, read from the installed mods in
`~/Projects/elfydd/data/mods/`.

**First: there is no single "YUNG's problem". Half of them already work.**

| Mod | Placement type | Status |
| --- | --- | --- |
| YUNG's Better Mineshafts | `minecraft:random_spread` | **already noise-managed** |
| YUNG's Better Ocean Monuments | `minecraft:random_spread` | **already noise-managed** |
| YUNG's Better Witch Huts | `minecraft:random_spread` | **already noise-managed** |
| YUNG's Better Dungeons | `minecraft:random_spread` | **already noise-managed** |
| YUNG's Better Nether Fortresses | `yungsapi:enhanced_random_spread` | pass-through |
| YUNG's Better Jungle Temples | `betterjungletemples:jungle_temple` | pass-through |
| YUNG's Better Desert Temples | `betterdeserttemples:desert_temple` | pass-through |
| YUNG's Better Strongholds | `betterstrongholds:stronghold` | pass-through |

Those four are pooled and scored today; the bank confirms it (13 Better
Mineshafts ids, 4 Better Dungeons, 2 Better Witch Huts, 1 Better Ocean
Monuments). So this is four placement types to deal with, not a mod family.

**Second: all four custom types subclass vanilla, so the allowlist already
reaches them.** Verified with `javap` on the installed jars — every one reports
`extends net.minecraft.class_6872`, and `class_6872` is confirmed as
`RandomSpreadStructurePlacement` because our own `FixedStructurePlacement`,
whose source declares `extends RandomSpreadStructurePlacement`, compiles to the
same superclass.

That matters because of the shape of `noiseManaged` (NoisePoolBuilder.java:72–84).
Its second guard — `!(placement instanceof RandomSpreadStructurePlacement)` — is
what rejects a foreign placement outright. For all four YUNG's types that guard
**passes**, so control falls through to the `ABSORBED_PLACEMENT_TYPES` lookup.
**Absorbing them is adding ids to a one-line `Set.of(...)`, not writing a
placement engine.**

| Type id | Beyond spacing/separation/salt | Absorb? |
| --- | --- | --- |
| `yungsapi:enhanced_random_spread` | `enhanced_exclusion_zone` → `{other_set: "#betterfortresses:fortress_avoid", chunk_count: 10}` | **yes** |
| `betterjungletemples:jungle_temple` | `enhanced_exclusion_zone` → `{other_set: "#betterjungletemples:jungle_temple_avoid", chunk_count: 4}` | **yes** |
| `betterdeserttemples:desert_temple` | `exclusion_zone` | **yes** |
| `betterstrongholds:stronghold` | `chunk_distance_to_first_ring`, `ring_chunk_thickness` | **no — ring placement** |

```java
// NoisePoolBuilder.java:62
private static final Set<String> ABSORBED_PLACEMENT_TYPES = Set.of(
        "moogs_structures:advanced_random_spread",
        "yungsapi:enhanced_random_spread",
        "betterjungletemples:jungle_temple",
        "betterdeserttemples:desert_temple");
```

**Third: the stated blocker is smaller than it sounds.** I traced the fortress
exclusion to its tag —
`data/betterfortresses/tags/worldgen/structure_set/fortress_avoid.json` contains
exactly `["minecraft:nether_complexes"]`. So the rule is "keep a YUNG's fortress
10 chunks away from a vanilla fortress or a bastion". Our noise placement already
has a per-group exclusion radius (`NoiseGroupPlan.Group.exclusion`, scaled by the
profile and passed to `NoiseStructurePlacement`), and `minecraft:nether_complexes`
is themed `dungeon` — the same group a fortress lands in. Absorbing therefore
replaces a tag-scoped keep-away with a group-scoped one, which covers the same
collision. It is a change of shape, not a loss of the behaviour. The comment is
true in general and overstated for this case.

**Fourth: strongholds is the one real exception.** Its placement carries
`chunk_distance_to_first_ring` and `ring_chunk_thickness` — concentric rings, the
same reason vanilla `minecraft:strongholds` is excluded. Ring placement is
load-bearing for eye-of-ender progression: dissolving it into a noise field would
scatter strongholds and change how a player finds the End. Leave it a
pass-through and **measure** it instead (item 2 below), which needs no placement
change at all.

**Fifth, a correction to this audit's own list.** `mvs:crimson_enchanting_table`
appears in the nine never-pooled ids, but its placement type is
`moogs_structures:advanced_random_spread` — already absorbed. Its absence is
therefore **not** a placement-type problem, and I do not yet know what it is; the
likeliest cause is a biome predicate matching no dimension in the pack, which
would make it a biome-coverage finding. It needs its own look.
`supplementaries:galleon` uses `supplementaries:random_spread_with_exclusion`
(fields: `exclusion_zones`, `salt`, `spacing`, `separation`) — the shape suggests
another `RandomSpread` subclass and therefore another one-line absorption, but I
could not locate its placement class to confirm the superclass, so verify before
adding it.

**Sixth, incidental proof of census staleness.** The committed census records
Supplementaries as `supplementaries-fabric-1.21.1-3.8.2.jar`; the installed jar
is `supplementaries-1.21.1-3.8.10-fabric.jar`. Eight patch versions of drift in
the file the noise groups are built from.

### None of this removes anything

Worth stating plainly because both changes read as subtractive and are not:

- **Absorbing a YUNG's set does not remove the YUNG's structure.** It means our
  noise field chooses where its pieces go instead of the mod's own grid. The
  structure, its pieces, its loot tables and its mobs are untouched — and it
  gains everything a managed set has: `structureDensity`, group profiles, radial
  curves, rarity tiers, difficulty shifts, and a place in the scorecard.
- **Re-enabling vanilla structures does not remove the YUNG's replacements.**
  Setting `disableVanillaFortresses = false` adds vanilla fortresses back
  *alongside* `betterfortresses:fortress`. If only one is wanted, that is then a
  choice we make rather than a default we inherited.

### Bringing everything in — what it actually takes

The goal is right: nothing should sit outside our control without us knowing.
Taken literally, though, "absorb every structure" would delete behaviour the code
deliberately preserves, so this is staged, cheapest and safest first.

1. **Write down what we already know (free, no behaviour change).** Capture
   `setsSkippedCustomPlacement` and the skipped set ids into an artefact instead
   of a log line, and record `placement.type` in `extract-structure-sets.py`.
   Between them these give the true count of unreachable sets per dimension,
   replacing this audit's lower bound of 8.
2. **Measure pass-throughs even where we cannot manage them.** This is the
   highest-value item and it is *independent* of any placement change. A
   pass-through set still has spacing, separation and salt, so its positions are
   computable without absorbing it. A second pass in `FactsEngine.structureFacts`
   that walks pass-through sets and records their positions would make
   `betterfortresses:fortress` scoreable, let `wants:fortress` work on eleven
   dimensions, and let the Nether gate test the structure that actually holds
   blaze rods — **without moving a single structure**.
3. **Absorb the three YUNG's types that are absorbable.** Add
   `yungsapi:enhanced_random_spread`, `betterjungletemples:jungle_temple` and
   `betterdeserttemples:desert_temple` to `ABSORBED_PLACEMENT_TYPES` — verified
   above to be `RandomSpreadStructurePlacement` subclasses, so the allowlist
   already reaches them. That brings Better Nether Fortresses, Better Jungle
   Temples and Better Desert Temples under full density/radial/rarity control and
   into the scorecard. Hold `betterstrongholds:stronghold` back: ring placement
   is progression-critical. Check `supplementaries:random_spread_with_exclusion`'s
   superclass and add it too if it is a subclass.
4. **Emit the biome parameter table.** A live dump of each family's preset
   entries — which biomes carry a native hypercube, and what it is — turns T19's
   silent world-swallow into a lint error and lets an author see that a biome
   needs an explicit `parameters` block *before* they ship it. This is the single
   biggest gap in `biomes.json` and it cannot be filled by a jar scan, because
   the hypercubes live in the preset rather than the biome file.
5. **Document what remains out of scope.** `concentric_rings` is not
   grid-compatible and never will be. Once items 1 and 4 exist, the dimension
   schema can say so, and `customdim lint` can reject a want naming a structure
   we cannot place rather than scoring it zero forever.

Items 1, 2 and 4 are pure additions — no existing placement changes, no world
wipe, no re-roll. Item 3 is the only one that alters what generates.

## How the dumps are generated, and what they cannot express

The previous section asks why nobody noticed that eight structures are
unmeasurable. The answer is that **the committed data has no field that could
say so.** This section audits the generators.

| Dump | Generator | Source of truth | In CI? | Last regenerated |
| --- | --- | --- | --- | --- |
| `extractors/structures.json` | `scripts/extract-structure-sets.py` | Pinned Modrinth jars + in-house jars + misode/mcmeta (vanilla) + `config/datapacks/` | `mod-updates.yml:147–150` runs it and `--check` | 2026-08-14 |
| `structure-groups.json` | `scripts/gen-structure-groups.py` | the above + hand-curated `scripts/data/structure-dials.json` | same | 2026-08-14 |
| `extractors/biomes.json` | `scripts/extract-biomes.py` | **a developer's local consumer checkout** (`~/Projects/elfydd/data/mods/*.jar`) | no | 2026-07-19 |
| `extractors/blocks.json` | `scripts/extract-blocks.py` | same local checkout | no | — |
| `extractors/entities.json` | `scripts/extract-entities.py` | same local checkout | no | — |

Every one of them is a **static scan of jar contents**. None reads the live game.

### A. `placement.type` is read and thrown away

`extract-structure-sets.py` `parse_structure_set` (lines 190–193) takes exactly
three values out of the placement block:

```python
placement = data.get("placement", {})
spacing = placement.get("spacing", 0)
separation = placement.get("separation", 0)
frequency = placement.get("frequency", 1.0)
```

`placement.type` is never read. It is the field that decides whether
`NoisePoolBuilder` can manage a set at all — `minecraft:random_spread` is
managed, everything else passes through to its own grid. **The census therefore
cannot say, for any of its 380 sets, whether the scorer can see it**, and
neither can `structure-groups.json`, which is built from it, nor `customdim
lint`, which is built on that. This is why `wants:fortress` fails silently on
eleven dimensions: nothing in the pipeline holds the fact that would have caught
it at authoring time.

The 8 unmeasurable structures in the previous section are a **lower bound**, not
a count. They were found by taking the union of 82 dimensions' pools, which
misses two cases: a pass-through set whose biomes match no dimension in the pack
would be absent from every pool for an innocent reason, and a structure that
appears in both a managed and a pass-through set (as `minecraft:mineshaft_mesa`
does, in `minecraft:mineshafts` and
`nova_structures:badlands_miner_outpost_mineshafts`) shows up as pooled while
half its placements are invisible. Only the placement type answers this
properly.

Fixing it is one line at extraction plus a lint rule. It is the highest
value-per-effort change in this entire audit.

### B. A missing `spacing` key is coerced to 0, and 0 means "common"

`placement.get("spacing", 0)` returns 0 for any placement type that has no
`spacing` — `minecraft:concentric_rings` (vanilla strongholds) and fixed
placements. `classify_rarity` then reads:

```python
if spacing <= 0:
    return "common"
```

So a structure with no random-spread spacing is filed as the most frequent
rarity tier there is. In the committed census that hits
`nova_structures:end_castle` — `spacing: 0, separation: 0, rarity: "common"`.
`minecraft:strongholds` escapes only because the `ENDGAME_PATTERNS` name regex
matches "stronghold" first. Two of 380 sets today, and the coercion is silent,
so any future non-random-spread set inherits it.

### C. `dimension` is inferred from substrings, and it is wrong in the shipped data

`infer_dimension` matches keyword sets against the set id, its structure names
and the jar filename concatenated together. Any hit anywhere flips the whole
set. Three demonstrable errors in the committed census:

| Set | Recorded | Actually | Why |
| --- | --- | --- | --- |
| `towns_and_towers:towns` | `dimension: nether`, `theme: maritime` | 27 overworld village variants | one member is `exclusives/village_piglin` ("piglin" ∈ `NETHER_KEYWORDS`); another is `village_ocean` (maritime regex) |
| `minecraft:ruined_portals` | `dimension: nether`, `theme: maritime` | the overworld ruined-portal set | one member is `ruined_portal_nether`; another is `ruined_portal_ocean` |
| `philipsruins:ocean_fortress_main` | `dimension: nether` | an ocean structure | "fortress" ∈ `NETHER_KEYWORDS` |

`towns_and_towers:towns` is not an obscure entry — it is the village set that
`the_abyssal_shrine`'s `shuns:village` scored against. 66 sets are labelled
nether and 33 end on this basis, and there is no way to tell from the file which
of them were guessed correctly.

### D. `theme` is a regex guess with a `landmark` default

`classify_theme` runs seven regexes over the set id plus structure names and
returns `landmark` when none matches. **164 of 380 sets are `landmark`** —
43% of the census sitting on the fallback. Theme is the input
`gen-structure-groups.py` uses to assign the noise meta-group, which decides a
structure's density profile, whether the difficulty shifts suppress it, and
whether it counts toward `nearestHostile`. A mod that names a dungeon
something evocative is placed and scored as a landmark.

`rarity` has the same shape: `ENDGAME_PATTERNS` is tested *before* the
attempts-per-1000-chunks arithmetic, so any set whose name contains "citadel",
"arena", "mansion" or "stronghold" is `endgame` regardless of its real spacing.

### E. A jar scan cannot see runtime mutation

Cristel Lib patches Towns & Towers' and Explorify's spacing numbers at runtime;
the census records what the jar shipped. Sets injected by code rather than by a
data file are invisible entirely. Datapack layering *is* handled (later sources
override, and `config/datapacks/` wins), but only for datapacks in this repo —
not for anything a consumer overlays.

### F. `biomes.json` omits the field that decides whether a biome works

It records 229 biomes with `source, temperature, downfall, has_precipitation,
effects, spawners`. It records **no climate parameters** — no continentalness,
erosion, weirdness, depth or humidity. Those decide whether a biome can be
placed by a multi-noise source at all.

This is not academic. [T19](../TROUBLESHOOTING.md#t19) and the authoring skill's
traps 13 and 14 both turn on exactly that distinction: a listed biome with no
climate parameters takes 74–100% of the world and swallows the dimension, and a
`spawnFilter` biome absent from the parameter table rejects every candidate and
yields zero. All 47 Nature's Spirit biomes, `minecraft:end_barrens` and
`minecraft:end_midlands` — the exact ids those entries name — are present in
`biomes.json` looking entirely valid. The skill tells authors this file is "the
only biome ids that will actually work". It cannot answer that question.

### G. Three of the five dumps depend on one developer's machine

`extract-biomes.py`, `extract-blocks.py` and `extract-entities.py` all default
to `~/Projects/elfydd` and scan `data/mods/*.jar` — whatever that consumer had
installed when someone last ran them. They do not read `config/modrinth-mods.txt`,
they are not reproducible from the repo, and no workflow runs them.
`biomes.json` was last regenerated on 2026-07-19; the mod list has moved since.

### H. Only the structure census is staleness-checked, and only on one workflow

`mod-updates.yml:147–150` runs `extract-structure-sets.py` and
`gen-structure-groups.py` with `--check`. `lint.yml` runs neither, and nothing
runs the biome/block/entity extractors. A hand-edited pin outside the weekly
update PR — such as commit `eedb4cf` on 2026-08-15 — regenerates nothing and
trips no check.

Separately: `extract-structure-sets.py`'s own docstring says "Not currently run
by any `.github/workflows/*.yml`". That is false, and it is a live example of
why this repo treats comments as unverified until checked.

### I. Nothing reconciles any dump against the live game

The mod holds the real registry at runtime — `FactsEngine` walks
`RegistryKeys.STRUCTURE_SET` directly. Nothing ever compares that against the
committed census. The phantom-fortress problem in the previous section *is* a
census-versus-registry divergence, and there is no mechanism that could have
surfaced it.

### What to change

In value order. The first is a line of code; the last is a project.

1. **Record the whole `placement` object, `type` included.** One line in
   `parse_structure_set`, one field in both renderers. Then add a `customdim
   lint` rule that fails a `wants`/`shuns` naming a structure whose set is not
   `minecraft:random_spread`, because no seed can satisfy it. This alone would
   have caught `wants:fortress` on eleven dimensions at authoring time.
2. **Stop inferring `dimension` from names.** The structures' own biome
   predicates are in the jars; derive the dimension family from them, or read
   the `dimension` a set's structures declare, and fail loudly rather than
   defaulting to `overworld`.
3. **Make `theme` authoritative or absent.** `scripts/data/structure-dials.json`
   is already the hand-curated theme source; a set with no row there should
   fail the build rather than silently become `landmark`. 164 sets currently
   ride the default.
4. **Make `rarity` arithmetic only,** with an explicit override table instead of
   a name regex, and no `spacing <= 0 → common` branch — a non-spread placement
   should be recorded as such (item 1), not scored as if it were dense.
5. **Generate the census from the live game, not from jars.** The mod can already
   walk every registry; a `/customdim extract` that dumps structure sets with
   their real placement types, biome predicates and post-Cristel-Lib spacing —
   plus biomes with their actual climate parameters — would capture runtime
   mutation, datapack layering and mod-on-mod patching in one pass. The jar scan
   would become a fast offline approximation to diff against, not the source of
   truth.
6. **Reconcile, and fail on divergence.** With a live dump in hand, a boot-time
   or CI check that diffs it against the committed census turns the whole class
   of problem — phantoms, pass-throughs, stale pins, mis-inferred dimensions —
   into a loud failure instead of a silent mis-score.
7. **Pin the biome/block/entity extractors to `config/modrinth-mods.txt`** rather
   than a local consumer directory, and wire all five `--check` runs into
   `lint.yml` so any pin change trips them.

Until at least items 1 and 5 are done, treat every `theme`, `dimension` and
`rarity` value in `structures.json` as a hint, and treat `biomes.json` as an
inventory of ids rather than a statement about what will generate.

## Cross-section: 81 dimensions, one seed each

These are claims about *config properties*. Each dimension contributes one draw,
so they describe how the score responds to a config choice, not the distribution
for any one dimension.

### 1. `structures_form_places_not_noise` tracks the border, not the world

| Playable radius | Dimensions | Mean | Range |
| --- | --- | --- | --- |
| 256–1024 | 58 | **0.496** | 0.300–0.563 |
| 2048 | 2 | 0.578 | 0.572–0.584 |
| 8192 | 24 | 0.894 | 0.728–0.995 |

58 different configs with 58 different seeds, and not one pocket dimension
reaches 0.6; 24 large ones and not one falls below 0.72. The nether's 8 seeds
read 0.500 / 0.513 / 0.535 — near-constant across seeds of one dimension, which
is the signature of a statistic measuring the container rather than the contents.

The mechanism is visible in the code. `StructuresFormPlacesNotNoise`
(Criteria.java:380–480) scores `ramp(LATTICE − ce, 0, LATTICE − 1.0)` with
`LATTICE = 2.1491` over a raw Clark-Evans ratio with no edge correction; in a
bounded disc with few points, nearest-neighbour distances are inflated and `ce`
is biased upward. The class comment asserts "Poisson-disc placement cannot fall
below 1.0 by construction" while the 8192 cards read 0.46–0.83, so the statistic
is not the one the comment describes.

Cost: a fixed 4–5 headline points on every dimension with `borders.player ≤ 1024`
— which is 58 of the 81, and eight of the ten under audit.

### 2. `near_border` is unsatisfiable in proportion to placement density

`Criteria.Band.NEAR_BORDER` is 0.55–1.00 of the playable radius with
`Band.TOLERANCE = 0.25` (Criteria.java:1062–1100), scored against `nearestOf(...)`
— the **closest** placement (Criteria.java:1238, 1250–1256). Anything inside 0.30
of the radius scores exactly 0, and the nearest of N copies shrinks as N grows.
The 105 `near_border` wants in the bank, bucketed by how many copies the seed
placed:

| Copies placed | Wants | Full marks | Scored 0.000 |
| --- | --- | --- | --- |
| 0 | 39 | 0 | 39 (100%) |
| 1–2 | 23 | **15 (65%)** | 1 (4%) |
| 3–4 | 13 | 6 (46%) | 3 (23%) |
| 5–9 | 13 | 2 (15%) | 6 (46%) |
| **10+** | **17** | **0 (0%)** | **14 (82%)** |

Seventeen `near_border` wants across seventeen different dimensions had ten or
more copies placed; **none scored full marks and the best of them was 0.237**.
The band as implemented does not mean "some of these sit out by the rim"; it
means "this structure is rare *and* the only one is far away".

Both of `the_end`'s configured `near_border` wants are in that bucket:
`phantom_citadel` with 38 copies (nearest at 7.1% of the border) and `enderkeep`
with 10 (nearest at 2.4%). Of the other nine dimensions, only
`the_basalt_spires` has one — `nether_tower` on `incendium:abandoned_tower`, 11
copies, nearest at 14.4%. The remaining eight each pose a single `near_border`
want on a structure placed 0–5 times, where the band is winnable.

### 3. How often a want pays at all

Across the 491 want criteria that were scored (one seed per dimension):

| Band | n | Full marks | Zero: nothing placed | Zero: placed, out of band |
| --- | --- | --- | --- | --- |
| `near_spawn` (0.00–0.15) | 127 | 31 (24%) | 30 | 30 |
| `spread` (0.10–0.75) | 259 | 108 (42%) | 94 | 0 |
| `near_border` (0.55–1.00) | 105 | 23 (22%) | 39 | 24 |

`spread` never fails for being out of band — its 94 zeros are all "in the pool,
placed none". `near_spawn` fails both ways: its 0.15 band plus 0.25 tolerance
means anything beyond 0.40 of the radius is a zero. 163 wants in total scored
zero because the structure did not generate.

### 4. The chosen winners

Every card in the bank is a seed a human already accepted for that dimension.
**Eighteen of eighty-one score 80 or above. The median is 71.9.** That is exact
for those specific seeds and needs no inference — and it is the single most
useful number here, because it says the bar rejects four out of five seeds that
were considered good enough to commit.

Mean headline also falls as a dimension is marked on more questions: the 9
dimensions with a marked C of 4 or less average 82.8, and the 18 with a marked C
of 13 or more average 68.9. One seed each, so this is a config effect and not a
per-dimension result — but the direction is what the tier-mean design predicts.

## Single-dimension detail (illustrative — one seed each)

The values below are one measurement per dimension. They show *which* criteria
were weak on that seed, not which criteria cap the dimension. Treat the "why"
column as a lead to check, not a finding.

| Dimension | That seed | Weakest criteria on it, in headline points lost |
| --- | --- | --- |
| `the_abyssal_shrine` | 72.69 | `structures_form` 0.473 (−5.27); `wants:sculk_dungeon` 0.000 and `wants:para_palace` 0.000 (−4.55 each); `spawn_reads_as_namesake` 0.072 (−4.22) |
| `the_amplified_reaches` | 63.59 | `headline_biome` 0.000 (−8.33); `spawn_is_playable` 0.000 (−8.33); `spawn_reads_as_namesake` 0.007 (−5.52) |
| `the_burning_archipelago` | 75.66 | `biome_edges_near_spawn` 0.000 (−12.50; G is only 4); `shuns:piglin_village` 0.127 (−4.36); `wants:crimson_forge` 0.232 (−3.84) |
| `the_boneyard` | 66.28 | `biome_edges_near_spawn` 0.104 (−7.47); `wants:nether_graveyard` and `wants:lost_soul_dungeon` 0.000 (−4.55 each) |
| `the_bloodroot_wastes` | 79.81 | `spawn_is_playable` 0.320 (−5.67); `wants:nether_bridge` 0.000 (−5.00); `structures_form` 0.472 (−4.40) |
| `the_buried_age` | 82.71 | `wants:start_nether_ruin` and `wants:nether_lava_ruins` 0.000 (−4.55 each); `structures_form` 0.556 (−4.44) |
| `the_blighted_maw` | 84.98 | `wants:lost_soul_dungeon` 0.000 (−5.00); `spawn_is_playable` 0.480 (−4.33); `structures_form` 0.531 (−3.91) |
| `the_blackstone_keep` | 74.51 | `wants:copper_tower` and `wants:blackstone_walls` 0.000 (−5.00 each); `wants:forbidden_castle` 0.149 (−4.25) |
| `the_basalt_spires` | 71.34 | four wants at 0.000 (−5.00 each): `nether_tower`, `nether_bridge`, `warped_greatsword`, `blackstone_pillars` |
| `minecraft:the_end` | REJECTED | gate: nearest end city 2391 blocks against a 2048 floor |

Two of the ten — `the_buried_age` at 82.71 and `the_blighted_maw` at 84.98 —
scored **above** the threshold on the one seed measured. Whatever is stopping
those two banking five candidates is a yield problem, not a ceiling problem, and
the bank cannot distinguish the two.

### The nether roll — the only real search in the bank

Twelve seeds of `minecraft:the_nether` under one `inputHash`: **4 rejected by
`fortress_reachable_in_nether`** at 549, 822, 849 and 1009 blocks against a
512-block floor, and 8 scored at 56.0, 59.6, 63.4, 67.3, 68.3, 73.4, 77.5 and
80.2. One candidate banked from twelve seeds. Per-criterion across those 8:

| Criterion | min | mean | max |
| --- | --- | --- | --- |
| `wants:nether_bridge` | 0.000 | 0.000 | 0.000 |
| `wants:sanctum` | 0.000 | 0.780 | 1.000 |
| `wants:bastion` | 0.000 | 0.591 | 1.000 |
| `wants:piglin_village` | 0.000 | 0.820 | 1.000 |
| `structures_form_places_not_noise` | 0.500 | 0.513 | 0.535 |
| `spawn_is_playable` | 0.160 | 0.545 | 1.000 |

Eight seeds is not a distribution either, but it is the only place in the bank
where a criterion can be seen holding still while its siblings move.
`mns:bridge_1` reads 0.000 on all eight, and on 14 of the 16 cards pack-wide that
want it — it paid once at 71.4% of the border on `the_ember_fields` and once at
98.8% on `the_boneyard`. A criterion worth 10 headline points that pays roughly
one time in eight ranks luck.

## Verdicts

"After" is that dimension's one measured seed recomputed under the config in
§ Config changes — exact arithmetic on its recorded facts, not a prediction.

| Dimension | Verdict | After | Basis |
| --- | --- | --- | --- |
| `minecraft:the_end` | **config** — unreachable as written, reachable re-banded | 81–84 | C=5 with two `near_border` wants on structures placed 38 and 10 times. The re-band lands every gate-surviving seed above 80 without touching the scorer |
| `the_blighted_maw` | **config** (yield, not ceiling) | 89.78 | One want on a structure that places 9% of the time |
| `the_basalt_spires` | **config**, scorer secondary | 85.79 | A `near_border` want on a structure placed 11 times, plus two other band/density mismatches. `structures_form` costs it a further ~4 points it cannot recover |
| `the_bloodroot_wastes` | **config** (yield, not ceiling) | 84.61 | One want on a structure that places 14% of the time |
| `the_blackstone_keep` | **config** | 83.77 | Two bands that contradict their structures' measured density |
| `the_boneyard` | **config**, scorer secondary | 83.60 | Two dead wants and two band mismatches; `biome_edges` 0.104 on this seed |
| `the_abyssal_shrine` | **config**, scorer secondary | 80.80 | `spawnFilter` names a 2.4% biome when the dimension is 57.7% `calcite_craglands`. `structures_form` costs ~5 points at a 512 border |
| `the_buried_age` | **threshold** | 82.71 | Nothing structural; already clears |
| `the_burning_archipelago` | **threshold** | 75.66 | Ceiling 99.9; this seed lost 12.50 to `biome_edges` = 0, which varies |
| `the_amplified_reaches` | **scorer** — config gets it to 77.55 only | 77.55 | `type: amplified` draws every overworld biome, so `headline_biome` (needs a 0.20 largest share) and `spawn_reads_as_namesake` (needs 0.33) have no satisfying config. `allowHazardousSpawn` would reach 84.17 if the dimension means it |

Seven of the ten clear 80 on config alone. `the_burning_archipelago` and
`the_buried_age` need nothing. `the_amplified_reaches` is the only one left
genuinely short, and its blocker is a criterion that cannot be satisfied by any
`amplified` dimension.

## Config changes

Each is a `seedRoll` edit. `seedRoll.terrain` and `seedRoll.water` are read only
by `Criteria.java` and `ViewerPage.java` — grepped, no generator reads either —
so none of this touches worldgen. All of it changes `InputHash` and invalidates
that dimension's banked candidates.

### The rule applied, so this is not just "lower the bar"

A criterion earns its place by **varying between seeds**. One that is 0 for every
seed, or 1 for every seed, ranks nothing and only shifts the whole distribution.
Three rules follow, and every change below obeys them:

1. **Drop a want only when its structure places on under 15% of the cards that
   pool it.** Measured: `mns:bridge_1` 4/29 (14%), `philipsruins:lost_soul_dungeon`
   1/11 (9%) — dropped. `philipsruins:start_nether_ruin` 20/32 (62%),
   `philipsruins:nether_lava_ruins` 10/29 (34%), `mns:copper_tower` 6/30 (20%) —
   **kept**, those are ordinary variance and they are what ranks seeds.
2. **Re-band a want only when the band contradicts the structure's measured
   density** — a `near_border` on something placed 11 times, or a `near_spawn` on
   a one-off that landed at 42%. Never re-band one that already varies.
3. **Every dimension must keep at least one configured criterion below full
   marks**, or its configured tier has become a rubber stamp. Checked below.

Scores quoted as "after" are the **one measured seed of that dimension
recomputed under the proposed config** — exact arithmetic on its recorded facts,
not a prediction of what a roll would yield.

### `minecraft:the_end` — the one that cannot reach 80 unchanged

```json
"seedRoll": {
  "mood": "hard",
  "terrain": "islands",
  "water": "none",
  "spawnFilter": ["minecraft:end_highlands", "minecraft:the_end", "minecraft:end_midlands"],
  "wants": {
    "end_city": "near_spawn",
    "phantom_citadel": "near_spawn",
    "enderkeep": "spread",
    "monolith": "spread"
  },
  "shuns": []
}
```

Four changes, and only one of them is a loosening:

- **`end_city: spread` → `near_spawn`.** This is a **tightening**, and it is the
  whole point. On `spread` (0.10–0.75 of an 8192 border = 819–6144 blocks) every
  end seed scores 1.0 and the criterion ranks nothing. On `near_spawn` it asks
  for a city within **1229 blocks of the gateway** — a short elytra run — which
  is the single property that actually separates a great end from an ordinary
  one, and it varies seed to seed.
- **`phantom_citadel: near_border` → `near_spawn`.** 38 copies with the nearest
  at 7.1%; the band was describing a world that cannot exist. `near_spawn` states
  what it is.
- **`enderkeep: near_border` → `spread`.** 10 copies, nearest at 2.4%. `spread`
  keeps some bite (this seed scores 0.696) because the keep arguably should not
  be on the doorstep, while `near_border` was a permanent zero.
- **`terrain: "islands"` and `water: "none"` added.** Both are true statements
  the config never made. `terrain` moves the void-floor question off
  `playable_ground_covers_the_disc` (which scored the raw ground fraction, 0.474)
  and onto `TerrainMatchesPreset`'s 0.05–0.70 band, where an island world belongs;
  `water` reads the measured `waterFraction = 0.0` against the `none` band. Both
  are near-constant marks — they lift the floor, they do not rank — and they are
  the reason the package clears the bar rather than sitting just under it.

**Result, and the good/great separation.** C goes 5 → 7, G goes 6 → 5. Holding
every other criterion at this seed's measured values and varying only the
distance to the nearest end city:

| Nearest end city | `end_city` mark | Headline | |
| --- | --- | --- | --- |
| ≤ 1229 blocks (≤15%) | 1.000 | **83.83** | a great end |
| 1500 | 0.868 | 82.89 | |
| 1800 | 0.721 | 81.84 | |
| 2048 — the gate floor | 0.600 | **80.97** | the worst end that survives |
| 2391 — this seed | 0.433 | 79.78 | rejected by the gate anyway |
| 3000 | 0.135 | 77.65 | rejected |

`EndCityReachableInEnd` already rejects anything beyond 2048 blocks, so **every
seed that clears the gate lands between roughly 81 and 84** under this config,
ranked by exactly the thing you would care about. The gate does "is this a
usable end"; the `near_spawn` band does "how good an end is it". No scorer change
is needed for the_end to work.

Two caveats, stated rather than buried. The table varies one criterion and holds
five others at one seed's values, so the band is the shape of the answer, not a
guarantee. And the End is a dimension with genuinely few varying properties: of
the seven configured marks, five are near-constant (`spawn_reads_as_namesake`,
`terrain`, `water`, `phantom_citadel`, `monolith`) and two discriminate
(`end_city`, `enderkeep`). Its general tier stays capped at 72.0% by
`biome_edges_near_spawn` = 0 (one biome for a thousand blocks round the central
island) and `headline_biome` = 0.630 (the End is 66% `end_highlands`) — neither
reachable from config. That cap is why the two near-constant marks are worth
adding: without them the same package lands at 78.9.

### The other nine

Recomputed on each dimension's measured seed:

| Dimension | Change | Before | After | Configured marks still below full |
| --- | --- | --- | --- | --- |
| `the_bloodroot_wastes` | drop `nether_bridge` | 79.81 | **84.61** | `crimson_well` 0.648 |
| `the_blighted_maw` | drop `lost_soul_dungeon` | 84.98 | **89.78** | `ruined_lab` 0.648 |
| `the_basalt_spires` | drop `nether_bridge`; `nether_tower` and `blackstone_pillars` → `spread` | 71.34 | **85.79** | `warped_greatsword` 0.000 |
| `the_blackstone_keep` | `blackstone_walls` and `forbidden_castle` → `spread` | 74.51 | **83.77** | `copper_tower` 0.000, `shuns:piglin_village` 0.330, `shuns:crimson_fungus` 0.299 |
| `the_boneyard` | drop `nether_bridge` + `lost_soul_dungeon`; `nether_graveyard` and `infernal_altar` → `spread` | 66.28 | **83.60** | `nether_dungeon` 0.869 |
| `the_abyssal_shrine` | add `calcite_craglands` to `spawnFilter`; `monument` → `spread` | 72.69 | **80.80** | `para_vault` 0.592, `para_palace` 0.000, `sculk_dungeon` 0.000, `shuns:village` 0.563 |
| `the_amplified_reaches` | drop `spawnFilter`; `campsite` and `keep_kayra` → `spread` | 63.59 | 77.55 | `shuns:monument` 0.111 |
| `the_buried_age` | none | 82.71 | 82.71 | already clears |
| `the_burning_archipelago` | none | 75.66 | 75.66 | ceiling is 99.9; this seed lost 12.50 points to `biome_edges` = 0, which varies |

Every one keeps at least one configured criterion below full marks, so rule 3
holds throughout.

Notes on the two that need explaining:

**`the_abyssal_shrine`'s `spawnFilter` names the wrong biome.** Its measured
shares are `paradise_lost:calcite_craglands` **0.577**,
`minecraft:deep_ocean` 0.289, `paradise_lost:continental_plateau` **0.024**. The
config filters on `continental_plateau` alone — 2.4% of the world — so
`SpawnReadsAsNamesake` scores `ramp(0.024, 0, 0.33)` = 0.072 on any seed whose
spawn misses it. Adding `calcite_craglands` (and `highlands_grand_glade`, 0.020)
puts the namesake share at 0.62, past the 0.33 `RELOCATABLE` cap, so the
criterion reads 1.0. This is not loosening — it is naming the biome the dimension
actually generates:

```json
"spawnFilter": [
  "paradise_lost:calcite_craglands",
  "paradise_lost:continental_plateau",
  "paradise_lost:highlands_grand_glade"
]
```

**`the_amplified_reaches` cannot be fixed to 80 by config, and its `spawnFilter`
should go rather than be widened.** Its eight mountain biomes total 0.24% of the
world; the largest biome present is `minecraft:ocean` at 8.1%. There is no wider
filter that reaches the 0.33 cap, because `type: amplified` draws from every
overworld biome and no themed subset can cover a third of it. Dropping
`spawnFilter` is the honest statement — this dimension has no namesake biome —
and removes a permanent near-zero. That takes it to 77.55; the remaining gap is
`headline_biome` = 0.000 (same root cause) plus `spawn_is_playable` = 0.000, and
`headline_biome` is not reachable from config at all.

Optionally, `"allowHazardousSpawn": true` withdraws `spawn_is_playable` and takes
it to **84.17**. That is a legitimate design statement — the description is
"cliffs beyond reason", and `the_burning_archipelago` and `the_buried_age`
already use it — but `spawn_is_playable` ranges 0.160–1.000 across the nether's
eight seeds, so it is a criterion that genuinely discriminates elsewhere. Use it
only if that dimension really means "you arrive somewhere dangerous"; otherwise
leave `the_amplified_reaches` short of 80 and let the threshold change carry it.

### What was deliberately not changed

- `the_buried_age`'s `start_nether_ruin` and `nether_lava_ruins` — 62% and 34%
  placement rates. They scored 0.000 on the one measured seed, which is bad luck,
  not a broken want. An earlier draft of this audit proposed dropping them; the
  placement data says otherwise.
- `the_buried_age`'s `copper_tower` on `near_border` — `mns:copper_tower` places
  on 20% of the cards that pool it, and it scored 1.000 here at 62.2% of the
  border. That is exactly the case `near_border` exists for.
- `the_basalt_spires`' `crimson_forge` on `near_border` — 3 copies at 64.2%,
  scored 1.000.
- `the_abyssal_shrine`'s `para_vault` on `near_spawn` (0.592) and
  `sculk_dungeon`/`para_palace` at 0.000 — these are the criteria that rank its
  seeds. Re-banding them would take it to a comfortable 90 and tell nobody
  anything.
- `the_blackstone_keep`'s `bastion` on `near_spawn`. It is force-placed at
  (185, 42) — 190 blocks, 18.5% of a 1024 border — so the want sits just outside
  its own band by construction. Either move it to `spread` or move the forced
  position inside 154 blocks; both are correct, and it is a design call rather
  than a scoring one.

## Scorer changes (specified, not made)

1. **`structures_form_places_not_noise` needs an edge correction or a size
   guard** — Criteria.java:380–480. It awards 0.300–0.563 to all 58 dimensions at
   `borders.player ≤ 1024` and 0.728–0.995 to all 24 at 8192, and moves 0.035
   across eight seeds of one dimension. Either apply an edge correction to the
   Clark-Evans ratio in `FactsEngine.clusteringByGroup`, or make the criterion
   `applicable` only above a placement count where the statistic is stable.
2. **`near_border` should read the far tail, not the nearest instance** —
   Criteria.java:1238 and 1250–1256. Measured: 0 of 17 wants with 10+ placements
   scored full marks, against 15 of 23 with 1–2. The fact needed is a farthest or
   median placement distance per structure, which `SeedFacts.structures` does not
   measure — a new fact plus a criterion change.
3. **The two ceilings should be reconciled** — Scorer.java:180–188 versus
   Scorer.java:98–114. They disagree on 21 of 81 dimensions, and the docstring
   claiming a test asserts their equality is wrong. Either the config ceiling
   should exclude criteria that can never be measured, or a headline should carry
   how many of the config's questions it was actually marked on.
4. **The census does not model third-party start cancels** —
   `FactsEngine.structureFacts`, whose docstring claims it "is the placement",
   models pool → noise field → pick and never reaches
   `ChunkGenerator.trySetStructureStart`, where YUNG's cancels the vanilla types
   it replaces ([T25](../TROUBLESHOOTING.md#t25)). **The primary fix is config,
   not code** — un-suppress the six types (§ The fix is to un-suppress) and the
   census becomes true without touching the scorer. What remains afterwards is
   narrower: any mod that cancels starts and cannot be configured off, currently
   just `betterstrongholds`. For that residue, either soften the docstring's
   claim or model the cancels.
5. **`FortressReachableInNether` gates on a structure that does not currently
   generate** — Criteria.java:872 tests `minecraft:fortress`, which
   `disableVanillaFortresses = true` cancels today; the blaze rod source is
   `betterfortresses:fortress`, which the facts layer cannot see. The gate
   rejected 4 of 12 nether seeds on phantom positions. Setting that toggle to
   `false` makes the gate correct with no code change at all.
   `EndCityReachableInEnd` (Criteria.java:912) does not have this problem —
   `minecraft:end_city` is genuinely pooled and placed (2251 copies across 7
   cards) — but its 2048-block floor is still inherited from the deleted Python
   roller and worth re-deriving against the end's real spacing.
6. **Pass-through structures are invisible to every criterion** —
   `NoisePoolBuilder.noiseManaged` (NoisePoolBuilder.java:72–84) absorbs vanilla
   `random_spread` plus the single allowlisted
   `moogs_structures:advanced_random_spread`; everything else is never measured
   even though it generates. A want naming one can never be satisfied by any
   seed. The fix that does not touch placement is a second pass in
   `FactsEngine.structureFacts` computing grid positions for pass-through sets
   from their spacing/separation/salt — see § Coverage item 2, which is the
   single highest-value change in this report. Failing that, `customdim lint`
   must reject such a want so the failure is loud at authoring time.
7. **`biome_edges_near_spawn` has no applicability guard** — Criteria.java:311–351,
   reading `FactsEngine.MOSAIC_STEP = 48` out to 192 blocks. A dimension whose
   spawn neighbourhood is one biome by construction scores a permanent zero.
   Compare `BiomeVarietyPresent`, which declines to apply when no palette is
   configured.
8. **The alias table is inconsistent about replaced structures** —
   `StructureAliases` maps `fortress` to `betterfortresses:fortress` (the real
   one, unmeasurable) and `monument` to `minecraft:monument` (the phantom,
   measurable). Whichever way items 4–6 are resolved, these need to point the
   same way. The class javadoc still says `"fortress"` maps to
   `minecraft:fortress`, which the scorecards disprove.

## Threshold recommendation

**Replace the absolute threshold with "best N regardless of absolute score", and
keep 80 as a display flag.**

The argument does not rest on the ten — the config work above fixes seven of
them. It rests on one exact fact: **of the 81 dimensions whose chosen winner is
in the bank, 18 score 80 or above, and the median is 71.9.** These are seeds a
human looked at and committed. A bar that rejects four out of five of them is not
measuring acceptability.

The same audit applied pack-wide would lift many of the other 71 — there are 105
`near_border` wants across the pack and 17 of them sit on structures placed ten
or more times — but that is eighty-odd config reviews, and it does not change the
fact that the score is not comparable between dimensions in the first place.

Against the alternatives:

- **Keep 80.** Every dimension below the bar spends its whole per-dimension
  budget on every roll. Rejected.
- **Per-dimension thresholds.** Treats the symptom. `the_end`'s problem was two
  mis-banded wants, and a lower bar would have banked five seeds all missing the
  same two structures with the board saying nothing about why. Fixing the config
  was the right move there and the threshold would have hidden it.
- **Best N regardless of absolute score.** `RollPipeline.rollOne`
  (RollPipeline.java:387–449) already stops at `WANTED` and already records
  `STARVED`. Change `banked()` (RollPipeline.java:451–461) to count every scored
  candidate, keep `SeedBank.leaderboard`'s descending sort as the ranking, and let
  `ViewerPage.java:175` — which already reads `SCORE_THRESHOLD` — flag anything
  under 80 as "best available, not good".

The structural reason: `Criterion.applicable`'s own docstring (Criterion.java:57–66)
says the ceiling exists so that **two seeds of the same dimension** can be ranked
against each other. It never claimed comparability *between* dimensions, and the
measured ceiling gap (21 of 81) plus the border-size effect on `structures_form`
say it does not hold. A single cross-dimension cut-off on a number defined within
a dimension is the fault.

## What would settle it

This audit could not run a roll. Five checks would convert most of the above from
"config says" to "measured". Items 4 and 5 are the cheapest and need no roll at
all — do those first.

1. **Roll 200 seeds for each of the ten, bank retained.** Gives a real
   distribution per dimension: best achieved, the fraction clearing 80, and
   whether `the_buried_age` and `the_blighted_maw` (82.71 and 84.98 on one seed)
   are yield problems or were flukes.
2. **Roll 100 seeds for `minecraft:the_end` and `minecraft:the_nether` and count
   gate rejections.** Settles whether the 2048 and 512 floors reject a few
   per cent or a third. The current evidence is 1 seed and 12 seeds respectively.
3. **Re-measure one pocket dimension across 50 seeds and read
   `structures_form_places_not_noise` alone.** If its range stays inside ~0.05 as
   it did across the nether's eight, the criterion is confirmed as a constant of
   the border and can be fixed or dropped without further argument.
4. **Re-extract with `placement.type` recorded and count the damage.** Add the
   field to `parse_structure_set`, re-run `extract-structure-sets.py`, and list
   every set that is not `minecraft:random_spread`. This audit found 8 such
   structures by taking the union of 82 dimensions' pools — a slow, indirect
   route to a fact the extractor could state directly. The count may be larger
   than 8: a set that is pass-through *and* whose biomes match no dimension in
   the pack would be invisible to both methods.
5. **Flip `disableVanillaFortresses` to `false` on the local stack and look.**
   The seven toggles are read evidence, so the mechanism is not in doubt; what is
   untested is the outcome. Set it in
   `~/Projects/elfydd/data/config/betterfortresses-fabric-1_21.toml`, wipe a
   throwaway dimension, regenerate, then `/locate structure minecraft:fortress`
   and probe the region NBT at the hit. That single flip settles three things at
   once: whether T25's corollary holds, whether the census's 215 fortresses were
   phantoms, and whether the config fix works — before anyone edits `config/` or
   the Dockerfile. Cheapest check on this list, no roll needed.
