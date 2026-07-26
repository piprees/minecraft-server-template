# Spike — Structure Noise: Implementation Plan

> **Date:** 2026-07-26 | **Status:** implementation design, ready for build **Prerequisite:** SPIKE-IMPROVED-STRUCTURE-PLACEMENT.md (concept and rationale)

## Kickoff prompt

/goal

Read this spike in full (`~/Projects/minecraft-server-template/mods/custom-dimensions/immersive/SPIKE-STRUCTURE-NOISE-IMPLEMENTATION.md`), then `~/Projects/minecraft-server-template/AGENTS.md`, `~/Projects/minecraft-server-template/README.md`, `~/Projects/minecraft-server-template/TROUBLESHOOTING.md`, `~/Projects/minecraft-server-template/mods/AGENTS.md` (the architecture tree and DimensionStructures component), then `DimensionStructures.java` and `FixedStructurePlacement.java` (the existing pattern to follow). Start with Step 1 (group registry + rarity classification from `~/Projects/minecraft-server-template/scripts/data/structure-dials.csv`).

Implement noise-based structure placement for the custom-dimensions mod. This replaces Minecraft's fixed grid placement (`RandomSpreadStructurePlacement`) with a seeded noise function that distributes structures non-uniformly, respecting radial preferences (near spawn, mid-ring, border), biome compatibility, and configurable density profiles (natural, dense, sparse, cluster).

Noise is the DEFAULT — every dimension gets biome-appropriate structures from its world-type defaults without any config. Fixed placement (`structures.force`) overrides noise for specific structures. `"structureDensity": "none"` suppresses noise entirely.

For testing, use `~/Projects/elfydd` as our local consumer app. Do not push any changes to the elfydd project for now, all of your work should be in the template repo. The elfydd project is only for local verification of the mod, follow the instructions in the AGENTS.md to sync the template repo into elfydd and build a local server container, and use the guidance inside TROUBLESHOOTING.md

Review all of the mentioned documentation, then say "Ready to start" when you are ready.

## Philosophy

Noise placement is the **default** for every dimension. You don't opt into it — you override parts of it or suppress it. A dimension with nothing but `type` and `biomes` gets sensible, biome-appropriate structures at natural density from its world type's defaults.

Fixed placement (`structures.force`) overrides noise for specific structures — you say "put exactly this here" and the noise system never generates that structure elsewhere in the dimension.

`"structureDensity": "none"` suppresses all noise-placed structures entirely (same meaning as today but now it means "turn off the noise" rather than "drop all sets"). Force still works under `"none"`.

## Configuration model

### Zero-config (type + biomes is enough)

```json
{
  "type": "multi_biome",
  "biomes": ["minecraft:jungle", "terralith:rocky_jungle", "minecraft:bamboo_jungle"],
  "borders": { "player": 1024 }
}
```

This dimension gets:

- Structures appropriate to jungle biomes (jungle temples, jungle tree houses, jungle ruins, spider dungeons — NOT igloos, NOT desert pyramids)
- At `natural` density
- Distributed via noise with type-default radial profiles
- Grouped by type-defaults for `multi_biome` (settlements near spawn, dungeons spread, endgame far)

### Suppress all + force one (the_dustbowl pattern)

```json
{
  "structureDensity": "none",
  "structures": {
    "force": [{ "structure": "explorify:farmstead", "x": -87, "z": -312 }]
  }
}
```

Identical to today. `"none"` kills the noise; force is independent.

### Override density globally

```json
{
  "structureDensity": "dense"
}
```

All structure groups use the `dense` noise profile instead of type defaults.

### Override per group

```json
{
  "structures": {
    "noise": {
      "dungeons": "sparse",
      "settlements": "none",
      "landmarks": "cluster"
    }
  }
}
```

Per-group overrides. `"none"` on a group suppresses only that group. Unmentioned groups keep their type-default profile.

### Force excludes from noise

```json
{
  "structures": {
    "force": [
      { "structure": "minecraft:village_plains", "x": 200, "z": -150 },
      { "structure": "minecraft:village_plains", "x": -400, "z": 300 },
      { "structure": "minecraft:village_plains", "x": 100, "z": 500 }
    ]
  }
}
```

Three villages at exact positions. No other villages generate via noise anywhere in this dimension. Other settlement-group structures (taverns, farmsteads) still generate normally — only the specific forced structure ID is suppressed from noise.

### Global noise profile shorthand

```json
{
  "structures": {
    "noise": "sparse"
  }
}
```

String form: every group uses this profile. Equivalent to `{ "noise": { "*": "sparse" } }`.

## Noise profiles

Each profile is a distinct noise SHAPE, not just a threshold value.

| Profile | Frequency | Threshold | Exclusion multiplier | Character |
| --- | --- | --- | --- | --- |
| `natural` | 0.025 | 0.68 | 1.0× | Even distribution, slightly sparser than vanilla. The normal feel. |
| `dense` | 0.04 | 0.45 | 0.6× | Structures everywhere. Smaller exclusion zones = more packed. |
| `sparse` | 0.015 | 0.85 | 1.5× | Wide empty stretches. An occasional structure is an event. |
| `cluster` | dual: 0.008 + 0.05 | 0.90 / 0.40 | 0.4× within clusters | Mostly empty, then a dense pocket. Two noise layers: coarse (region selection) × fine (within-region placement). |
| `none` | — | — | — | No noise generation for this group. |

### Cluster noise (dual-layer)

```
coarse_noise = perlin(seed, x * 0.008, z * 0.008)   // big regions
fine_noise   = perlin(seed ^ salt, x * 0.05, z * 0.05)  // within-region detail

placement = coarse_noise > 0.90                       // only ~10% of the world is "active"
         && fine_noise > 0.40                          // within active regions, ~60% hit rate
```

Result: empty wastelands with dense oases of structures. A player might walk 800 blocks with nothing, then find a cluster of 5-8 structures within 200 blocks of each other.

## Type-based defaults

Every world type ships default group assignments and profiles. These apply when the dimension config doesn't override them.

### Structure meta-groups

| Group         | Contents (structure sets by theme)                                    | Default profile |
| ------------- | --------------------------------------------------------------------- | --------------- |
| `deco`        | Small environmental: ruins, rubble, guide posts, dead trees, boulders | `natural`       |
| `settlements` | Villages, taverns, farmsteads, campsites, outposts, houses            | `natural`       |
| `dungeons`    | Hostile: trial chambers, ancient cities, dungeons, monster lairs      | `sparse`        |
| `landmarks`   | Towers, temples, monuments, castles, watchtowers                      | `sparse`        |
| `maritime`    | Ships, ocean ruins, lighthouses, conduit ruins                        | `natural`       |
| `endgame`     | Mega-dungeons: coliseum, citadels, mega ships, forbidden castles      | `sparse`        |
| `loot`        | Caches, shrines, beacons, enchanting tables                           | `natural`       |

### Type → group defaults

| World type | Groups enabled | Profile overrides | Radial defaults |
| --- | --- | --- | --- |
| `multi_biome` | all (biome-filtered) | — | settlements→inner, dungeons→outer |
| `overworld` | all | — | settlements→inner, dungeons→outer |
| `nether` | deco, dungeons, landmarks, settlements, endgame | dungeons→`natural` | even |
| `end` | deco, dungeons, landmarks, maritime, endgame | endgame→`natural` | even |
| `cave` | deco, dungeons, loot | dungeons→`natural`, loot→`dense` | dungeons→outer |
| `sky_islands` | deco, landmarks, settlements, loot | — | even |
| `nether_islands` | same as nether | — | even |
| `amplified` | all (biome-filtered) | landmarks→`natural` | endgame→outer |
| `paradise_lost:paradise_lost` | deco, landmarks, settlements | — | even |
| `void` | none by default | — | — |
| `superflat` | not rollable, irrelevant | — | — |

### Radial profiles per group

Default radial curves (10-point piecewise-linear, spawn→border):

```
inner:    [1.5, 1.3, 1.0, 0.8, 0.5, 0.3, 0.1, 0.0, 0.0, 0.0]  // settlements near spawn
outer:    [0.0, 0.0, 0.1, 0.3, 0.6, 0.8, 1.0, 1.3, 1.5, 2.0]  // endgame at borders
even:     [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0]  // uniform
mid:      [0.3, 0.5, 1.0, 1.2, 1.2, 1.0, 0.8, 0.5, 0.3, 0.1]  // landmarks in the middle ring
```

The dimension's `difficulty.mobMultiplier` shifts these:

- `mobMultiplier >= 2.0`: dungeons→`even` (threat is everywhere), endgame→`mid` (reachable)
- `mobMultiplier <= 0.5`: dungeons→`none`, endgame→`none` (peaceful world)

## Biome-aware filtering

The key feature for `multi_biome` dims: structures that don't match the dimension's biomes are excluded from the noise pool automatically.

### How it works

Each structure set has **valid biomes** in Minecraft's registry (`structure.biomes()` predicate). The noise system checks at pool-construction time: does this structure's biome predicate intersect with the dimension's biome list?

```java
// During group construction:
for (StructureSet set : groupSets) {
    for (WeightedEntry entry : set.structures()) {
        Structure structure = entry.structure().value();
        Set<Biome> validBiomes = structure.getValidBiomes();
        if (Collections.disjoint(validBiomes, dimensionBiomeSet)) {
            continue; // this structure can't generate here anyway — skip it
        }
        pool.add(entry);
    }
}
```

This means a jungle `multi_biome` dim automatically gets jungle temples and jungle tree houses but not igloos or desert pyramids — without the author specifying any of this. It falls out of the biome list.

### Biome affinity weighting (within the noise)

Beyond binary include/exclude, structures that match MORE of the dimension's biomes get a higher sub-weight within their group:

```java
float affinity = matchingBiomes.size() / (float) structure.validBiomes().size();
float effectiveWeight = baseWeight * (0.5f + 0.5f * affinity);
```

A jungle temple (valid in: jungle, sparse_jungle, bamboo_jungle) in a dim with all three listed gets full weight. A structure valid in 20 biomes where only 2 overlap gets reduced weight. This biases generation toward structures that BELONG.

## Default rarity tiers

Every structure set gets a rarity tier derived from its spacing/frequency data (from `structure-dials.csv`). This determines how much of the noise band it occupies:

| Tier       | Criteria                            | Noise band share | Examples                                     |
| ---------- | ----------------------------------- | ---------------- | -------------------------------------------- |
| `common`   | spacing ≤ 24 or freq ≥ 2.0/k_chunks | 8×               | guide posts, small ruins, rubble, deco       |
| `uncommon` | spacing 25–45, freq 0.5–2.0         | 3×               | villages, taverns, farmsteads, dungeons      |
| `rare`     | spacing 46–80, freq 0.1–0.5         | 1×               | mansions, monuments, ocean fortress, castles |
| `endgame`  | spacing > 80 or freq < 0.1          | 0.3×             | mega ships, shrines (600sp), trident trial   |

The share is relative within a group: if a group contains 3 `common` structures and 1 `rare` structure, the commons each get `8/(8+8+8+1) = 32%` of placement attempts and the rare gets `1/25 = 4%`.

This ensures that common environmental detail fills the world while endgame structures remain genuinely rare — without any per-dimension config needed.

### Override per-structure rarity

```json
"structures": {
  "rarity": {
    "minecraft:trial_chambers": "common",
    "dungeons_arise:coliseum": "uncommon"
  }
}
```

Promotes or demotes specific structures for this dimension only.

## Fixed placement interaction with noise

When `structures.force` lists a structure ID:

1. The forced structure generates at its exact position(s) via `FixedStructurePlacement` (unchanged from today).
2. That structure ID is **removed from the noise pool entirely** for this dimension.
3. Other structures in the same group still generate normally.

This means:

- `"force": [{"structure": "minecraft:bastion_remnant", "x": 200, "z": 100}]` → one bastion at (200, 100), no other bastions anywhere, but fortresses/piglin villages still noise-generate.
- To force one AND allow noise-generated others, use a new flag: `{"structure": "...", "x": 0, "z": 0, "exclusive": false}`.

## Config schema (full)

```json
{
  "type": "multi_biome",
  "biomes": [...],
  "structureDensity": "normal",
  "structures": {
    "noise": {
      "dungeons": "sparse",
      "settlements": "natural",
      "endgame": "none",
      "landmarks": "cluster"
    },
    "radial": {
      "settlements": [1.5, 1.2, 1.0, 0.7, 0.4, 0.2, 0.0, 0.0, 0.0, 0.0],
      "dungeons": [0.0, 0.2, 0.5, 0.8, 1.0, 1.2, 1.5, 1.8, 2.0, 2.0]
    },
    "rarity": {
      "minecraft:trial_chambers": "common"
    },
    "exclude": ["minecraft:villages", "explorify:farmsteads"],
    "include": ["mes:phantom_citadel"],
    "force": [
      { "structure": "explorify:farmstead", "x": -87, "z": -312 }
    ]
  }
}
```

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `structureDensity` | `"none"` / `"sparse"` / `"normal"` / `"dense"` | `"normal"` | Global base threshold. `"none"` kills noise entirely. |
| `structures.noise` | string OR `{group: profile}` | type defaults | String = all groups use that profile. Map = per-group override. |
| `structures.radial` | `{group: float[10]}` | type defaults | Per-group radial curves. |
| `structures.rarity` | `{structure_id: tier}` | derived from spacing | Override rarity tier per structure. |
| `structures.exclude` | `string[]` | `[]` | Structure set IDs to remove from the noise pool. |
| `structures.include` | `string[]` | `[]` | Structure set IDs to force INTO the pool (bypasses biome filter). |
| `structures.force` | `[{structure, x, z, exclusive?}]` | `[]` | Exact placements. `exclusive` defaults true (removes from noise). |

### Backwards compatibility

- A dimension with NO `structures` block: gets type defaults + biome filtering. **New behaviour** (today it gets unmodified grid placement). This is the intentional breaking change — all dims benefit from noise by default.
- `"structureDensity": "none"` + `"structures.force"`: identical to today (`the_dustbowl`).
- `"structures.mode": "none"` + `"structures.force"`: same as `"structureDensity": "none"` + force. Deprecated but honoured for compatibility.
- `"structures.wants"` / `"structures.shuns"` / `seedRoll.wants`: **scoring-only** (roller), no longer map to worldgen at all. The noise system places structures by biome + group + radial curve; the roller evaluates how well a seed's noise-placed layout matches the scoring preferences.

## `NoiseStructurePlacement` — the class

Extends `RandomSpreadStructurePlacement` (same pattern as `FixedStructurePlacement`):

```java
public class NoiseStructurePlacement extends RandomSpreadStructurePlacement {

    // Construction inputs
    private final long noiseSeed;           // world_seed ^ dimension_salt ^ group_salt
    private final NoiseProfile profile;     // natural / dense / sparse / cluster
    private final int exclusionRadius;      // chunks (from rarity tier)
    private final float[] radialCurve;      // 10-point
    private final int worldRadiusBlocks;
    private final int spawnX, spawnZ;

    // Pre-computed at construction
    private final Set<Long> positions;       // all valid placement chunks
    private final Map<Long, ChunkPos> byRegion;  // for /locate

    @Override
    protected boolean isStartChunk(StructurePlacementCalculator calc, int cx, int cz) {
        return positions.contains(ChunkPos.toLong(cx, cz));
    }

    @Override
    public ChunkPos getStartChunk(long seed, int cx, int cz) {
        // Return the nearest resolved position in this region (for locate)
        ChunkPos forced = byRegion.get(regionKey(cx, cz));
        return forced != null ? forced : new ChunkPos(cx, cz);
    }
}
```

### Pre-computation (at world load)

```java
private Set<Long> computePositions() {
    Set<Long> result = new LinkedHashSet<>();
    int rChunks = worldRadiusBlocks / 16;

    // Spiral outward from spawn
    for (int ring = 0; ring <= rChunks; ring++) {
        for each (cx, cz) on ring:
            float noise = profile.evaluate(noiseSeed, cx, cz);
            float radial = sampleRadialCurve(cx, cz);
            if (noise * radial <= profile.threshold()) continue;
            if (withinExclusion(result, cx, cz)) continue;
            result.add(ChunkPos.toLong(cx, cz));
    }
    return result;
}
```

### NoiseProfile evaluation

```java
public sealed interface NoiseProfile {
    float evaluate(long seed, int cx, int cz);
    float threshold();

    record Natural(float freq, float thresh) implements NoiseProfile { ... }
    record Dense(float freq, float thresh) implements NoiseProfile { ... }
    record Sparse(float freq, float thresh) implements NoiseProfile { ... }
    record Cluster(float coarseFreq, float fineFreq,
                   float coarseThresh, float fineThresh) implements NoiseProfile {
        @Override
        float evaluate(long seed, int cx, int cz) {
            float coarse = perlin(seed, cx * coarseFreq, cz * coarseFreq);
            if (coarse <= coarseThresh) return 0;  // not in an active region
            float fine = perlin(seed ^ 0xDEAD, cx * fineFreq, cz * fineFreq);
            return fine;  // fine threshold applied externally
        }
    }
}
```

## Group construction in `DimensionStructures`

```java
// In transformed(), when noise is the placement mode:

// 1. Determine which groups are active and at which profile
Map<String, NoiseProfile> activeGroups = resolveGroupProfiles(dimConfig, typeDefaults);
// e.g. {"deco": Natural, "settlements": Natural, "dungeons": Sparse, "endgame": None}

// 2. Biome-filter the structure registry into groups
Set<Identifier> dimBiomes = biomeIdsFromSource(biomeSource);
Map<String, List<WeightedEntry>> groupPools = new HashMap<>();

for (RegistryEntry<StructureSet> entry : allSets) {
    String setId = entry.getKey().map(...).orElse(null);
    String theme = StructureThemes.themeOf(setId);
    String group = themeToGroup(theme);  // dungeon→dungeons, settlement→settlements, etc.
    if (group == null || activeGroups.get(group) == NONE) continue;
    if (isExcluded(setId, dimConfig)) continue;
    if (isForcedExclusive(setId, dimConfig)) continue;

    for (WeightedEntry struct : entry.value().structures()) {
        if (!biomeIntersects(struct.structure(), dimBiomes) && !isIncluded(setId)) continue;
        float rarityWeight = rarityWeight(setId, dimConfig);
        groupPools.computeIfAbsent(group, k -> new ArrayList<>())
            .add(reweight(struct, rarityWeight));
    }
}

// 3. Build one NoiseStructurePlacement per active group
for (var e : groupPools.entrySet()) {
    String group = e.getKey();
    NoiseProfile profile = activeGroups.get(group);
    float[] radial = resolveRadialCurve(group, dimConfig, typeDefaults, mobDifficulty);
    int exclusion = defaultExclusionForGroup(group);

    NoiseStructurePlacement placement = new NoiseStructurePlacement(
        worldSeed, dimSalt, groupSalt(group),
        profile, exclusion, radial,
        playableRadius, spawnX, spawnZ
    );
    transformed.add(RegistryEntry.of(new StructureSet(e.getValue(), placement)));
}

// 4. Forced placements (unchanged — FixedStructurePlacement)
appendForcedPlacements(transformed, dimConfig, structureRegistry);
```

## Exclusion radius by rarity tier

Exclusion radius scales with rarity to prevent clustering of rare structures while allowing deco to fill naturally:

| Rarity tier | Base exclusion (chunks) | At `dense` (×0.6) | At `sparse` (×1.5) |
| ----------- | ----------------------- | ----------------- | ------------------ |
| `common`    | 3                       | 2                 | 5                  |
| `uncommon`  | 6                       | 4                 | 9                  |
| `rare`      | 12                      | 7                 | 18                 |
| `endgame`   | 20                      | 12                | 30                 |

Within a group, all structures share the group's exclusion. But rarer structures within the group naturally appear less often because they occupy a smaller noise-band share.

## Seed roller parity

### Full census

The roller computes every structure position in the dimension (mirrors `NoiseStructurePlacement.computePositions()` exactly):

```python
def noise_census(seed, dim_config, groups, playable_radius):
    """Returns {group: {structure_id: [(x, z), ...]}}."""
    all_positions = {}
    for group_name, group_cfg in groups.items():
        profile = PROFILES[group_cfg["profile"]]
        positions = compute_noise_positions(
            seed, group_cfg["salt"], profile,
            group_cfg["radial_curve"], playable_radius,
            group_cfg["exclusion"])
        # Assign positions to structures by rarity-weighted sub-ranges
        all_positions[group_name] = assign_structures(
            positions, group_cfg["structures"], seed)
    return all_positions
```

### Scoring by distribution

Replace `want_score(nearest_dist)` entirely:

```python
def structure_score(census, profile):
    """Score a seed's structure layout against the dimension's preferences."""
    score = 0.0
    n = 0
    for group_name, desired_curve in profile["radial_curves"].items():
        positions = census.get(group_name, {})
        all_pos = [p for structs in positions.values() for p in structs]
        if not all_pos and desired_curve != ZERO_CURVE:
            score += 0.2  # group exists but nothing placed = mild penalty
            n += 1
            continue
        # Radial distribution match
        score += distribution_match(all_pos, desired_curve, profile["radius"])
        # Count satisfaction (enough structures for the density profile?)
        expected = expected_count(profile["profile"], profile["radius"], group_name)
        actual = len(all_pos)
        score += min(1.0, actual / max(expected, 1)) * 0.3
        n += 1
    return score / max(n, 1)
```

### `seedRoll.wants` / `seedRoll.shuns` reinterpretation

These remain in the config for scoring but change meaning:

- `wants`: "I want this structure to be REACHABLE in this dimension" — the roller checks it exists in the census at all, and scores by count + proximity-to-desired-band.
- `shuns`: "I want this structure to NOT appear" — the roller penalises seeds where the noise happened to place it (some structures appear despite being excluded from the group if they share a set with an included one).

## Task list

> **Estimate:** 13 days total. Tasks are sequential unless noted otherwise. Mark each `[x]` when complete and add handoff notes below the task. Each task has a **Verify** section describing exactly what "done" means.

### Phase A: Data + classification (2 days, no mod build)

- [x] **A1. Build structure group registry from `structure-dials.csv`** Parse 377 sets → `config/custom-dimensions/structure-groups.json`. Map theme column → meta-group (deco/settlements/dungeons/landmarks/maritime/endgame/loot). Derive rarity from spacing (>80→endgame, >45→rare, >24→uncommon, else common). Include dimension family from `dims` column. Update `src/main/resources/structure_themes.json` with `group` + `rarity` fields.

  **Verify (unit test + data check):**
  - `python3 -m pytest` on a new `test_structure_groups.py`: assert every set in structure-dials.csv has a group + rarity in the output JSON; assert rarity derivation matches known cases (e.g., `mes:phantom_citadel` sp=31 → uncommon, `nova_structures:shrine_tower` sp=600 → endgame); assert no set is orphaned (missing from output).
  - `python3 -c "import json; d=json.load(open('config/custom-dimensions/structure-groups.json')); assert len(d) >= 370"` — sanity count.
  - The baked `structure_themes.json` is valid JSON and round-trips: `python3 -c "import json; json.load(open('src/main/resources/structure_themes.json'))"`.

  _Handoff notes:_ **Done.** New script `scripts/gen-structure-groups.py` (no
  network), 20 tests in `scripts/seed/test_structure_groups.py`, all pass;
  388 Java tests still green.

  Four deviations, all recorded with evidence in `NOISE-IMPL-LOG.md`:

  1. **`structure-dials.csv` is not a census — it has 356 sets, not 377, and
     it never covered `minecraft:igloos`, `desert_pyramids`,
     `jungle_temples`, `ocean_monuments`, `mineshafts` or `buried_treasures`,
     which are exactly what G2 asserts on.** The registry is the *union* of
     dials (authoritative theme) and `structure-sets-extracted.csv`
     (authoritative census + spacing) = **379 sets**. The two disagree on
     theme for 186 of 354 shared sets; extracted's theme is a
     first-match-wins regex defaulting to `landmark`, so dials wins and a
     hand-reviewed `CURATED` table covers the 23 sets dials lacks.
  2. **The endgame *group* is keyword-derived, not spacing-derived.** Spacing
     >80 catches 68 sets including 23 `deco` (`mss:tree_7` at sp=186,
     `mvs:duck` at 94) — decorative variants whose spacing is high only
     because the mod splits one feature across many sets. The group rule is
     `ENDGAME_KEYWORDS` (lifted from `extract-structure-sets.py`) AND theme ∈
     {dungeon, landmark, maritime} AND rarity ∈ {rare, endgame}. The rarity
     guard is what keeps `friendsandfoes:citadel` (spacing 16),
     `minecraft:ancient_cities` (24) and `minecraft:trial_chambers` (34) in
     `dungeons` — a set the mod tuned to appear every 16 chunks is not
     content you journey to the border for. Result: 18 endgame sets.
     Promoting one per-dimension via `structures.rarity` moves its group too.
  3. **Ownership moved.** `gen-structure-presets.py` used to write
     `structure_themes.json` as a side effect of a *network-dependent* run;
     it would have reverted group/rarity on every invocation. The new script
     owns both outputs and needs no network. `--check` gates staleness.
  4. **Tests are `unittest`, in `scripts/seed/`**, not pytest — that is what
     `scripts/test-scripts.sh` discovers. pytest collects them too.

  `structure_themes.json` changed shape (`Map<String,String>` →
  `Map<String,{theme,group,rarity}>`), so `StructureThemes.java` was widened
  in the same step rather than left crash-loading until C1. It accepts both
  shapes; a bare theme string still works and derives its group.

- [x] **A2. Define type-default group profiles + radial curves** Create `config/custom-dimensions/structure-type-defaults.json`. Per world-type: enabled groups, profile overrides, radial curve assignments. Define named curves (inner/outer/even/mid). Include difficulty-based shifts (mobMultiplier ≥2.0 → dungeons=even/endgame=mid; ≤0.5 → dungeons=none/endgame=none).

  **Verify (unit test + schema validation):**
  - `test_structure_type_defaults.py`: assert every world type in `OVERWORLD_FAMILY ∪ NETHER_FAMILY ∪ END_FAMILY ∪ {"void","superflat","paradise_lost:paradise_lost"}` has an entry; assert each entry's group list only contains valid group names; assert radial curves are all length 10 with values 0.0–3.0; assert difficulty shifts produce expected overrides for known multipliers.
  - JSON schema validation: `jsonschema` against a schema that enforces structure.

  _Handoff notes:_ **Done.** 14 tests in
  `scripts/seed/test_structure_type_defaults.py`; `./scripts/test-scripts.sh
  --quick` green.

  - **14 types covered, not 11.** The spike's table omits `large_biomes`,
    `checkerboard` and `single_biome`; all three are in the roller's
    `OVERWORLD_FAMILY` (or reachable as a `type`) and take the overworld
    defaults. `test_no_unknown_types` also fails on an entry for a type the
    roller would reject, so the two lists cannot drift apart.
  - **Exclusion radius is per GROUP, not per rarity.** The spike gives a
    per-rarity exclusion table *and* says all structures in a group share the
    group's exclusion — both cannot hold, because one
    `NoiseStructurePlacement` serves a whole group and carries one radius.
    Per-group wins; rarity governs a set's *share* of its group's placements
    (`rarityShares`), which is where common-vs-endgame is actually expressed.
  - **No `jsonschema` dependency.** The repo pins no schema library and the
    quick gate is stdlib-only, so validation lives in two places instead:
    `gen-structure-groups.py` fails the build on an unresolvable group,
    curve, or profile name (verified with a deliberate bad curve), and the
    unit test asserts shape plus the spike's exact type table.
  - **Shipping**: the mod reads a jar-baked copy
    (`structure_type_defaults.json`, comments stripped, written by the
    generator) per the self-containment rule, so a stale or seed-reverted
    config dir can never change worldgen. The authored file also ships via
    two new `docker/defaults-seed/Dockerfile` COPY lines because
    `roll-all.sh` resolves consumer-mode config from
    `data/config/custom-dimensions/`, not a repo checkout — without them the
    roller would silently lose group data on every consumer.

  **Deviation carried into B2 (recorded in `NOISE-IMPL-LOG.md`):** exclusion
  is enforced as a **local maximum over a radius-R disc**, not the spike's
  greedy outward spiral. The spiral is order-dependent, so Python would have
  to replicate the traversal chunk-for-chunk to pass F4 — the gate the plan
  already calls the hardest. The local-max rule gives the identical minimum-
  separation guarantee, is order-free (F4 becomes a set comparison), and
  still precomputes, so `getStartChunk` keeps the region-index shape
  `FixedStructurePlacement` already proved works with vanilla `/locate`.

### Phase B: Core placement classes (2 days, unit-testable without Docker)

- [x] **B1. Implement `NoiseProfile` sealed interface** New file: `dimension/NoiseProfile.java`. Four records: Natural (freq=0.025, thresh=0.68), Dense (freq=0.04, thresh=0.45, excl×0.6), Sparse (freq=0.015, thresh=0.85, excl×1.5), Cluster (dual-layer: coarse 0.008/0.90 + fine 0.05/0.40, excl×0.4). Each implements `evaluate(seed, cx, cz)` and `threshold()`. Static `fromString()` factory. Use vanilla `PerlinNoiseSampler`.

  **Verify (JUnit, no server needed):**
  - `NoiseProfileTest.java`:
    - Determinism: `evaluate(seed=12345, cx=100, cz=200)` returns same float across 1000 calls.
    - Range: 10k random inputs all produce values in [0.0, 1.0].
    - Profile distinction: for a fixed seed, count positions where `evaluate > threshold` across a 100×100 grid. Assert: Dense > Natural > Sparse. Assert Cluster produces ≤20% non-zero chunks.
    - `fromString("natural")` returns Natural, `fromString("none")` returns null, `fromString("garbage")` throws or returns null.
  - Compile gate: `mise exec -- ./gradlew compileJava` passes.

  _Handoff notes:_ **Done.** `NoiseProfile.java` + `StructureNoise.java`, 19
  tests in `NoiseProfileTest`, 0.13s, no Bootstrap needed.

  **Own Perlin, not vanilla's `PerlinNoiseSampler`.** F4 needs bit-exact
  Python agreement; vanilla derives its permutation from
  `net.minecraft.util.math.random.Random`, so parity would mean mirroring
  that class too, and it drags Bootstrap-bound static init into unit tests.
  `StructureNoise` is SplitMix64 Fisher-Yates, one octave, **doubles
  throughout** — Java `float` rounds differently from Python's always-double
  floats and would cost F4 for nothing.

  **Two real bugs the tests caught:**

  1. **Lattice points are seed-independent.** Perlin is exactly 0 at a
     lattice point, normalising to 0.5 for *every* seed. Frequency 0.025 is
     1/40, so without an offset every 40th chunk on both axes scored exactly
     0.5 in every world ever generated — and under `dense` (threshold 0.45)
     every one of them would place. A permanent structure grid, produced by
     the system whose purpose is removing grids, and invisible to any "looks
     random" inspection. Fixed with irrational origin offsets inside
     `sampleChunk`, which no rational chunk coordinate can turn into an
     integer at any frequency.
  2. **Four gradient directions is too few.** `hash & 3` gives a cell only
     256 possible outcomes, so unrelated seeds agree at ~1 cell in 256 —
     harmless for placement, but it makes "did the field change" unassertable.
     Widened to 8 vectors of equal length, so the normalisation bound is
     unchanged. Done before the Python mirror existed, so it cost nothing.

  **Measured hit rates** (1200x1200 chunks): natural 21.6%, dense 59.2%,
  sparse 5.4%, cluster 3.2% active / 2.1% placed. Value distribution at
  frequency 0.025: mean 0.500, stdev 0.224, full 0-1 span.

  **The spike's 100x100 test window is too small** — `sparse`'s frequency has
  a 67-chunk period and `cluster`'s coarse layer 125 chunks, so a 100x100
  probe sees ~1.5 lattice cells and reported `sparse` as DENSER than
  `natural`. Tests use a 2000-chunk window, step 4.

- [x] **B2. Implement `NoiseStructurePlacement` class** New file: `dimension/NoiseStructurePlacement.java`. Extends `RandomSpreadStructurePlacement`. Constructor: noiseSeed, NoiseProfile, exclusionRadius, radialCurve[10], worldRadiusBlocks, spawnX/Z. Pre-computes all valid positions at construction via outward spiral (noise × radial > threshold, exclusion enforced). `isStartChunk` = set membership. `getStartChunk` = nearest-in-region (region index). Register type. SPACING = exclusionRadius × 2.

  **Verify (JUnit, no server needed):**
  - `NoiseStructurePlacementTest.java`:
    - Determinism: same inputs → same positions set (compare two constructions).
    - Exclusion: for every pair of positions in the set, assert manhattan chunk distance ≥ exclusionRadius.
    - Radial shaping: with `inner` curve + radius 1024, assert >60% of positions are in the inner 30% of the radius. With `outer` curve, assert >60% in outer 55%.
    - Performance: construct with radius=8192, Natural profile. Assert <200ms (`System.nanoTime` delta). Log actual time.
    - Locate: `getStartChunk` for a chunk inside a known position's region returns that position. `isStartChunk` for a known position returns true. Both return false/non-match for empty regions.
    - Empty result: threshold=1.0 (nothing passes) → positions is empty, no crash.
    - Boundary: positions never exceed worldRadiusBlocks/16 from spawn.

  _Handoff notes:_ **Done.** `NoiseStructurePlacement.java` (thin
  Minecraft-facing shell) + `NoiseFieldIndex.java` (all the maths, no
  Bootstrap), 24 tests in `NoiseFieldIndexTest`. Full Java suite: 431 tests,
  0 failures.

  **The local-max-of-the-noise rule from A2 was implemented and found
  wrong.** Local maxima of a *smooth* field occur about once per noise
  feature, so density is fixed by frequency alone — the threshold barely
  participates and the exclusion radius is completely inert. Measured: an
  8192-radius dimension produced **283** placements for a whole group, and a
  1024-radius pocket dimension produced **one**; changing exclusion from 3 to
  20 changed nothing at all.

  Fixed by ranking on **white noise** instead of on the placement field:

  ```
  eligible(c) := noise(c) * radial(c) > threshold      <- density dial
  placed(c)   := eligible(c) AND no eligible c' within R outranks c
                 rank = mix64(seed ^ cx*G ^ cz*H), compared UNSIGNED
                 ties broken on the chunk key          <- spacing dial
  ```

  Still order-free (this is the standard parallel formulation of dart
  throwing, so F4 stays a set comparison), but both dials now work. Same
  dimension yields **7033** placements; a 1024-radius pocket gets ~110.
  Unsigned comparison matters — a signed one systematically favours whichever
  half of the range came out negative.

  **SPACING = exclusion x 2, and a cell may hold two placements.** Both
  generate (`isStartChunk` is set membership); locate returns the registered
  one — the identical accepted degradation `FixedStructurePlacement` already
  documents. Measured collision rate is under a third. The two properties
  that ARE load-bearing are tested instead: `startFor` must answer within the
  cell it was asked about, and a populated cell must never answer with a
  non-placement.

  **Performance: 240ms** at 512-chunk radius (7033 positions) versus the
  spike's 200ms target — the spike's own guidance is "log a warning, no
  fallback needed", which C2 does. Typical 64-chunk-radius dimensions build
  in single-digit milliseconds.

### Phase C: Mod integration (2 days, needs local verification loop)

- [x] **C1. Extend `StructureThemes` with `groupOf()`, `rarityOf()`, auto-inference** Add `groupOf(setId)`, `rarityOf(setId)`, `groupsForType(type)`. Auto-inference for unknown sets: spacing heuristics → group, spacing thresholds → rarity. Default unknown = deco + derived rarity. Log auto-classified sets at INFO. `/customdim structure-audit` command lists all sets with classification source.

  **Verify (JUnit + local server):**
  - `StructureGroupRegistryTest.java` (unit):
    - `groupOf("minecraft:villages")` → "settlements"; `groupOf("epic:large_dungeons")` → "dungeons"; `groupOf("unknown:new_mod_thing")` → inferred based on spacing.
    - `rarityOf("minecraft:shipwrecks")` → "common" (spacing 24); `rarityOf("nova_structures:shrine_tower")` → "endgame" (spacing 600).
    - Consumer overlay: mock a `structure_themes.json` with override → assert it wins over baked.
  - Local server (after C2):
    - `docker logs mc 2>&1 | grep "Auto-classified"` — verify unknown sets are logged.
    - `docker exec -i mc rcon-cli "customdim structure-audit"` — verify output lists all sets with source column.

  _Handoff notes:_ **Done.** `StructureGroupRegistry` (classify / groupOf /
  rarityOf / groupsForType / type defaults), 16 tests. Live: **380 sets, only
  1 inferred** (`adventure:exit_shrines`, our own jar datapack set, which
  `NoisePoolBuilder` excludes from groups anyway) — so classification
  coverage against the real mod list is effectively complete.

  **`structure-audit` writes a file, it does not print rows.** RCON
  concatenates feedback lines with no separator AND truncates the response at
  a few KB, so 380 rows came back as one unreadable, half-missing string —
  which looks like a working command until you try to read it. Rows now go to
  `config/custom-dimensions/structure-audit.txt`; the command returns only
  the summary and the path.

  Inference for unknown sets is `deco` + spacing-derived rarity, logged once
  at INFO. A spacing of -1 (not a random_spread placement) maps to
  `uncommon`, the middle tier — treating it as spacing 0 would say `common`
  and let an unclassifiable set flood its group.

- [x] **C2. Wire noise path into `DimensionStructures.transformed()`** New `transformedNoise()` called as the DEFAULT path. Dissolves organic grid sets → groups by theme. Biome-filters structures against dimension's biome source (registry `getValidBiomes()` intersection). Applies biome affinity weighting. Force-exclusion removes forced structure IDs from noise pool. Per-group profile resolution (config → type defaults → global density). Builds one `NoiseStructurePlacement` per active group.

  **Verify (local server — the big integration gate):**
  - Build jar → install into consumer `data/mods/` → `docker restart mc` → wait healthy.
  - Boot log: `grep "structure profile" /data/logs/latest.log` shows noise-mode lines for managed dims (not "density=normal … 0 rescaled" like today).
  - `the_dustbowl`: `grep "the_dustbowl" /data/logs/latest.log` → "density=none" + forced farmstead. NO noise groups. Unchanged behaviour.
  - `the_overgrowth` (jungle multi_biome): `docker exec -i mc rcon-cli "execute in adventure:the_overgrowth run locate structure betterjungletemples:jungle_temple"` → finds one (biome filter allowed it). `locate structure minecraft:igloo` → fails or finds nothing (biome filter excluded it).
  - A nether dim: `locate structure betterfortresses:fortress` → finds one. `locate structure minecraft:village_plains` → fails (not in nether pool).
  - `the_gilded_pit` (none + force): forced structures still appear at exact positions; no noise structures.
  - Container stays healthy for 5 minutes (no CME, no crash): `docker inspect mc --format '{{.State.Health.Status}} Restarts={{.RestartCount}}'` → "healthy Restarts=0".

  _Handoff notes:_ **Done.** `transformedNoise()` is the default path;
  `NoisePoolBuilder` does the registry work (biome filter, affinity
  weighting, rarity share, force-exclusion) and `NoiseGroupPlan` the
  precedence. Container healthy, `Restarts=0` across ~10 restart cycles.

  Live boot, six dimensions:

  ```
  the_overgrowth  noise radius=64c  groups=7/7 positions=169   22ms
  the_dustbowl    density=none +1 forced          (unchanged)
  the_gilded_pit  noise radius=42c  groups=4/5 positions=665    6ms
  the_blackstone_keep noise radius=64c groups=4/5 positions=1448 9ms
  the_end_citadel noise radius=512c groups=5/5 positions=62556 2506ms
  the_luminous_caverns noise radius=64c groups=2/2 positions=101 0ms
  ```

  **The first live boot found three bugs no unit test could have** (all in
  `NOISE-IMPL-LOG.md` with evidence):

  1. **A fixed noise frequency makes small dimensions all-or-nothing.**
     `sparse`'s 0.015 is a 67-chunk lattice period; a 1024-block dimension is
     128 chunks across, so the whole world is ~2 periods — one blob, and
     whether a group gets anything is a coin flip on where its peak lands.
     `the_overgrowth` came out with **0 settlements** and 53 total positions.
     Frequency now scales as `REFERENCE_RADIUS / radiusChunks`, so every
     dimension sees the same NUMBER of features whatever its size. 53 -> 169,
     every group populated.
  2. **`structureDensity` was resurrecting groups the peaceful shift had
     suppressed.** `the_luminous_caverns` (`mobMultiplier: 0.0`) kept its
     dungeons because its `"sparse"` density was applied after the shift. The
     shift now sits above the density dial: a peaceful world has no dungeons
     unless the author names a profile for dungeons specifically. 3/3 -> 2/2.
  3. **The ring walk was O(r^3).** See the log — 3387ms -> 67ms in isolation.

  **Two behaviours worth knowing when reading a census.** Over half the sets
  (155 of ~280) pass through untouched because their placement is not exactly
  `RandomSpreadStructurePlacement` — YUNG's, and everything Cristel Lib
  rewrites at runtime (explorify, towns_and_towers). They keep grid placement,
  as the density path has always left them. And a group whose pool comes out
  empty after biome filtering is skipped, which is why the log reads
  `groups=4/5`: a nether dimension resolving `endgame` and finding no
  endgame structure whose biomes it contains is normal.

  `the_end_citadel` at 2506ms exceeds the spike's 200ms target and logs the
  warning the spike specifies. It is the largest border (8192) crossed with
  the densest profile, once per world load, off the tick loop.

  **Owner's ruling (2026-07-26): ACCEPTED, do not cap it.** *"I'm OK with
  the_end_citadel taking that long, as you say it's not any worse than vanilla
  anyway."* A future agent must not "optimise" this by capping positions,
  shrinking `MAX_RADIUS_CHUNKS`, or raising exclusion for large dimensions —
  that changes worldgen to fix a number nobody is paying for.

### Phase D: Config + compatibility (1 day)

- [x] **D1. Config parsing for noise placement fields** Extend `DimensionConfig.Structures`: add `noise` (string or map), `radial` (map of float[10]), `rarity` (map of tier strings), `exclude` (string[]), `include` (string[]). `force[].exclusive` field (default true). Backwards compat: absent `structures` block = noise with type defaults. `"structureDensity": "none"` = no noise. `"structures.mode": "none"` = deprecated alias.

  **Verify (JUnit + local server boot):**
  - `DimensionConfigTest.java` (extend existing):
    - Parse a config with all new fields populated → assert fields read correctly.
    - Parse a config with NO structures block → assert defaults resolve (noise with type defaults).
    - Parse `"structureDensity": "none"` → assert no noise groups active.
    - Parse `"structures.mode": "none"` → assert treated as density=none (deprecated compat).
    - Parse invalid: `"noise": {"badgroup": "natural"}` → warn + ignore unknown group.
  - Local server: boot with ALL 84 shipped dimension configs. `docker logs mc 2>&1 | grep -i "error\|exception\|invalid" | grep -v "expected"` → nothing new. Every dim loads without parse errors.

  _Handoff notes:_ **Done.** New fields on `DimensionConfig.Structures`:
  `noise` (JsonElement — string, map, or `false`), `radial`
  (`Map<String, List<Double>>`), `rarity`, `exclude`, `include`, plus
  `force[].exclusive` (defaults TRUE via `isExclusive()`). 25 tests in
  `NoiseGroupPlanTest` cover the whole precedence chain and every
  backwards-compatibility rule.

  **`radial` is `List<Double>`, not `List<Float>`** — and the curves are
  `double[]` throughout the mod. `1.3f` is `1.29999995231628418`, which would
  have diverged from Python on the very first curve sample and put F4 out of
  reach. Caught before the mirror was written, so it cost one mechanical
  refactor instead of a parity hunt.

  Backwards compatibility verified: `structureDensity: "none"`,
  `structures.mode: "none"` (deprecated alias) and `structures.noise: false`
  (the grid escape hatch, open decision 1) all suppress noise and leave
  `force` working. Unknown group names warn and are ignored; an unknown
  profile name suppresses that group rather than silently becoming
  `natural`.

### Phase E: Locate + census command (1 day)

- [ ] **E1. Verify `/locate` works with noise placements** Noise-placed structures must be locatable via vanilla `/locate structure`. Verify the region-index approach produces correct results.

  **Verify (local server, RCON):**
  - Pick 3 dims with different profiles (a natural overworld, a sparse nether, a cluster end dim).
  - For each: run `/locate structure <structure_id>` for a structure that should be in the pool → returns coordinates.
  - Verify the returned position matches: `execute positioned <x> 0 <z> run locate structure <id>` → same result (confirms `isStartChunk` at that chunk is true).
  - Negative: `/locate` a structure excluded by biome filter → "Could not find" (not a crash, not a timeout).
  - Negative: `/locate` in a `structureDensity: "none"` dim → "Could not find" for everything except forced.
  - `/locate` a forced structure → returns the forced position exactly.

  _Handoff notes:_ **Partially verified — the contract is tested, the live
  run is blocked by pre-existing locate latency, and it is NOT a noise
  regression.**

  What IS verified (24 tests in `NoiseFieldIndexTest`, plus the Python
  mirror): the two properties vanilla's locate actually depends on.
  `getStartChunk` must answer **within the cell it was asked about** (vanilla
  walks rings of `spacing`-sized cells and asks about each; an answer from a
  different cell makes locate report a position it then fails to confirm),
  and a cell containing placements must **never** answer with a
  non-placement. `SPACING = exclusion * 2` keeps cell collisions to a small
  minority, and a colliding cell is the accepted degradation
  `FixedStructurePlacement` already documents: both placements generate,
  locate returns the registered one.

  What is NOT verified live: a synchronous `/locate` into an ungenerated
  custom dimension does not return on this hardware. It wedged RCON once
  (recovered by restart; **no K1 signature** — no `Error upgrading chunk`, no
  `DungeonZombie`, the game log had simply stopped advancing on a long
  main-thread search). The mod's async `customdim locate structure` stays
  responsive but was still `pending` after 4 minutes.

  **The control that settles it: `the_dustbowl` is equally slow, and it has
  no noise at all** (`density=none`, so it runs the untouched
  `FixedStructurePlacement` path with exactly ONE placement in the whole
  dimension). Locate latency in an ungenerated dimension is therefore
  pre-existing — which is precisely why `DimensionCommands` grew an async
  locate in the first place ("each call would otherwise block the server
  thread for minutes with 130+ structure mods").

  **Owner's ruling (2026-07-26): RCON slowness is pre-existing and out of
  scope.** *"rcon truly is slow as shit, but that's not our problem, it's been
  this way since we first installed it. Pre-warming a world down the line with
  chunky will help us there."* Do not investigate it.

  **To finish this properly:** pre-generate chunks with Chunky first, then
  re-run the batteries above. That belongs with G1/G2, which need a generated
  world anyway. Do not run a bare synchronous `/locate` against a fresh custom
  dimension — it will wedge RCON.

- [x] **E2. Implement `/customdim structure-census <dim>` command** Dumps all noise-placed positions grouped by group name. Output format matches roller's `structure_all` for comparison. Handles: empty groups, force-only dims, very small dims.

  **Verify (local server, RCON):**
  - `docker exec -i mc rcon-cli "customdim structure-census the_overgrowth"` → outputs JSON-like grouped positions. Assert: settlements group has positions, dungeons group has positions, no igloos in any group.
  - `docker exec -i mc rcon-cli "customdim structure-census the_dustbowl"` → outputs only forced placements (no noise groups).
  - Position count sanity: a 1024-radius dim with `natural` profile should have roughly 20-80 total positions across all groups (not 0, not 10000).
  - Save output to a file for F4 comparison.

  _Handoff notes:_ **Done.** Writes
  `config/custom-dimensions/census/<ns>__<slug>.json`; the command returns a
  one-line summary. A file rather than command output for the same reason as
  the audit — a large dimension holds tens of thousands of positions.

  Reads the world's **live** `StructurePlacementCalculator` (via the public
  `ServerChunkManager.getStructurePlacementCalculator()`, no mixin needed) —
  the same objects generation and `/locate` consult. Recomputing from config
  would have been the one approach guaranteed to agree with itself while
  disagreeing with the world.

  Each group records its **resolved** inputs (profile, noiseSeed, exclusion,
  radial, radiusChunks, spawn, spacing) alongside the positions, so F4 tests
  the placement maths rather than re-deriving config — a parity failure then
  means the maths diverged and nothing else. Forced placements are included
  separately so a `none + force` dimension doesn't read as empty.

  Counts match the boot log exactly (169 / 0+1 forced / 665+9 / 1448+1 / 101).
  One bug: `structures` was first emitted as `["id":weight]`, which is invalid
  JSON — the files parsed nowhere until it became an object.

### Phase F: Roller parity (3 days, Python, parallel with Phase E)

- [x] **F1. Implement `noise_census()` in Python** Mirror `NoiseStructurePlacement.computePositions()` exactly in `structure_placement.py`. Same Perlin implementation, same spiral, same exclusion logic. `compute_noise_positions()` + `assign_structures()` (rarity-weighted sub-ranges).

  **Verify (pytest, no Docker):**
  - `test_noise_placement.py`:
    - Determinism: same seed → same positions across 10 calls.
    - Exclusion: all position pairs have chunk-distance ≥ exclusionRadius.
    - Profile behaviour: Dense produces more positions than Natural produces more than Sparse, for same seed/radius.
    - Cluster: positions form identifiable clusters (define: ≥3 positions within 5 chunks of each other, separated from other clusters by ≥15 empty chunks).
    - Radial: inner curve biases positions toward centre (>60% in inner 30% of radius).
    - Edge: empty result for threshold=1.0.

  _Handoff notes:_ **Done.** `scripts/seed/noise_placement.py` — a
  line-for-line mirror of `StructureNoise`, `NoiseProfile`, `NoiseFieldIndex`,
  `saltOf` and `NoiseGroupPlan.resolve`. 36 tests in
  `test_noise_placement.py`.

  The translation rules that actually bite are in the module docstring: Java
  longs wrap and `>>>` is unsigned (every SplitMix64 step masks to 64 bits);
  ranks compare UNSIGNED (a signed comparison systematically favours half the
  range); `Math.round` is `floor(x + 0.5)` where Python's `round` is banker's
  — pinned by its own test.

  Not a separate `find_all_in_radius`-style function bolted onto
  `structure_placement.py`: noise placement shares nothing with the vanilla
  grid maths, and mixing them would have made both harder to read.

- [x] **F2. Implement `distribution_match()` scoring** Replace `want_score(nearest_dist)` in `score-dimensions.py` with band-occupancy scoring. Bin positions by radial decile, compare to desired curve via cosine similarity. Count-satisfaction bonus (enough structures for the profile?). Keep `seedRoll.wants`/`shuns` as existence/absence checks.

  **Verify (pytest):**
  - `test_distribution_scoring.py`:
    - Perfect match: positions that exactly follow the desired curve → score ≈ 1.0.
    - Inverse: positions concentrated at border with an `inner` desired curve → score < 0.4.
    - Empty: no positions → score = 0.2 (mild penalty, not zero).
    - Count bonus: 50 positions when 30 expected → bonus capped at 0.3. 5 positions when 30 expected → partial bonus.
    - wants/shuns: a `wants` structure present in census → positive contribution. A `shuns` structure present → penalty.
  - Run `score_candidate()` on a few hand-crafted measurement dicts and assert scores are sensible (in 0-100 range, not negative, not >100).

  _Handoff notes:_ **Done.** New module `scripts/seed/census_scoring.py`, 32
  tests in `test_distribution_scoring.py`, 301 Python tests green,
  `./scripts/test-scripts.sh --quick` green. Full detail in
  `NOISE-IMPL-LOG.md` § F2.

  ```
  structures = 0.6 * census + 0.4 * battery
  census     = mean over groups of 0.7*distribution_match + 0.3*count_satisfaction
  ```

  - **`distribution_match` divides each radial bin by its ANNULUS AREA**
    before the cosine comparison. Equal-width bins cover unequal areas, so a
    uniform layout genuinely puts more structures in the outer bins and a
    raw-count comparison scores that as a border bias.
  - **The battery is kept, not deleted.** It is still exactly true for
    forced placements and the ~155 sets that keep grid placement. Each entry
    is routed structure -> set -> group: an active group answers from its
    histogram (band occupancy), a suppressed group means the structure does
    not generate (want 0.0 / shun 1.0), an unmapped set keeps the old
    positional score. 673 of 676 shipped entries map.
  - **`expected_count` is a FLOOR, not a model.** Placement density is a
    nonlinear eligibility function thinned by a rank filter; any closed form
    would be a fitted constant wearing a derivation. The floor
    (`max(3, radiusChunks/16)`) targets the failure that actually happened —
    a group that resolves and places nothing.
  - **Censuses are banked per candidate** (`noiseCensus` in the candidate
    store, keyed by a new `noise_fingerprint()` covering the noise payload
    only) because recomputing is 0.03 s for a pocket dimension and ~0.9 s for
    a radius-capped one. Cold bank ≈ 1 hour, free thereafter.

  **Three bugs found, all recorded with evidence in `NOISE-IMPL-LOG.md`:**

  1. **F3's `noisePlacement` key never reached a real fingerprint.**
     `monolith_from_dir` scanned the staged overlay through
     `load_dimension_configs`, which re-pointed the noise type-defaults at a
     directory that has none — so every `load_config()` ended with the
     defaults nulled and NO dimension gained the key. F3's tests passed
     because they set the directory by hand. Fixed; the consumer now
     fingerprints 73 of 78, exactly as F3 predicted, and the DRIFTED wave is
     finally real.
  2. **Nested datapack copies shadowed real set ids** (`structures:
     major_structures` for Dungeons Arise), silently sending whole mods back
     to grid scoring.
  3. **`noise_placement.py` (F1) and `census_scoring.py` were missing from
     the bundle MANIFEST** — `./dev seed-roll` would have died on import on
     every consumer.

  Scores: `the_burning_archipelago` 48.6 -> **91.3**, `the_overgrowth` 74.1 ->
  84.3 *with a different winner* (the census re-ranks, not just re-bases),
  `the_dustbowl` 86.0 -> 86.0 exactly (suppressed path untouched).

  The Python mirror was optimised to make the backfill affordable (row-wise
  Perlin, cached radial weights, threshold early-out) with
  `test_noise_parity` re-run after every step — still exact. **No Java
  changed**; no position moved.

- [x] **F3. Update `generation_payload()` fingerprinting** Add noise-placement config to the fingerprint when present: profile per group, radial curves, frequency, group membership. Conditional keys (only when `placement == "noise"`) so non-noise dims don't DRIFT.

  **Verify (pytest):**
  - `test_dimension_profiles.py` (extend):
    - Two dims with identical noise config → same fingerprint.
    - Change one group's profile (natural→sparse) → different fingerprint.
    - Change a radial curve → different fingerprint.
    - A dim with NO noise config (grid mode / `structureDensity: "none"`) → fingerprint is byte-identical to the same dim before this change (no spurious DRIFT).
    - Adding a new noise key to a dim that already had candidates → `./dev seed-status` shows DRIFTED (correct — the world changed).

  _Handoff notes:_ **Done.** Conditional `noisePlacement` key in
  `generation_payload()`, 10 new tests in `test_dimension_profiles.py`
  (40 total, all pass).

  Measured against the shipped set: **73 dimensions gain the key** (they will
  report DRIFTED, which is correct — noise genuinely changed their worlds),
  **5 keep byte-identical fingerprints** (`the_canvas`, `the_dustbowl`,
  `the_gritlands`, `the_icebound_rift`, `the_slatemouth` — all suppressed),
  and the 4 base worlds still return None. That split is the whole point of
  making the key conditional.

  **Noise placement promotes two fields from scoring-only to
  generation-affecting for the first time**, which the payload now reflects:
  `borders.player` (it sets both the scanned radius and the frequency scale,
  so it moves every position in every group) and `difficulty.mobMultiplier`
  (the peaceful/hostile shifts can suppress a whole group or change its
  radial curve). Both have explicit tests. Pool composition
  (`rarity`/`exclude`/`include`/exclusive `force`) is fingerprinted too —
  two dims agreeing on positions but not on which structures land there are
  not clones.

- [x] **F4. Bit-exact verification: Python positions == Java positions** Test with 5 known seeds across 3 dimension types. Use `/customdim structure-census` output as ground truth. Zero tolerance for positional divergence.

  **Verify (cross-platform comparison):**
  - Save Java census output from E2 for 5 seeds × 3 dims (15 data files).
  - Run Python `noise_census()` with the same seeds + same config.
  - Assert: for every group, every position in Java output is in Python output and vice versa. Zero mismatches.
  - If any diverge: log the first divergent position + the noise values at that point from both sides. Fix before proceeding.
  - This is the hardest gate — Perlin implementations across languages can drift on edge cases (rounding, permutation table). Verify the permutation table is identical.

  _Handoff notes:_ **PASSED, exactly.**

  ```
  F4: 5 dimensions, 17 groups, 2383 positions — exact match
  ```

  `scripts/seed/test_noise_parity.py`, against real
  `/customdim structure-census` output committed under
  `scripts/seed/testdata/census/`. Zero divergence, first run.

  Three decisions made this cheap rather than the hardest gate in the plan:

  1. **The placement rule is order-free** (rank-based, not a greedy spiral),
     so parity is a set comparison rather than two traversals that must agree
     step for step. Proved out in practice: replacing the O(r^3) ring walk
     with an O(r^2) scan produced *exactly the same 7033 positions*.
  2. **Doubles everywhere, our own Perlin.** No vanilla `PerlinNoiseSampler`
     to mirror, no `float` rounding to reconcile.
  3. **The census records resolved inputs**, so the maths and the config
     resolution are tested separately and a failure points at one of them.

  The suite also keeps server-free self-consistency tests (order-independence
  rebuilt from the rule in reverse, unsigned rank spread, `ChunkPos.toLong`
  against vanilla's formula, salt collisions across every shipped name), so a
  change that would break parity fails locally rather than waiting for
  someone to re-dump a census.

- [ ] **F5. Full rescore of banked candidates** Run `./dev seed-rescore` with new scoring. Compare before/after scores. Large-radius dims (the_burning_archipelago, the_end, the_end_citadel) should climb from 40-60 range to 70+.

  **Verify (seed roller output):**
  - Snapshot current scores BEFORE: `./dev seed-status > /tmp/before-noise-scores.txt`.
  - Run `./dev seed-rescore`.
  - Snapshot AFTER: `./dev seed-status > /tmp/after-noise-scores.txt`.
  - Assert: `the_burning_archipelago` score ≥ 65 (was 48.6). `the_end` score ≥ 60 (was 42.7). `the_end_citadel` ≥ 85 (was 93.7 — shouldn't regress).
  - Assert: no dim that was ≥ 80 drops below 65 (no regressions in already-good dims).
  - Assert: zero-candidate dims stay at zero (no false positives introduced).
  - Assert: `the_dustbowl` and other `none` dims are unaffected (structure score = 0, unchanged).

  _Handoff notes:_

### Phase G: Final validation + docs (2 days)

- [ ] **G1. End-to-end: full seed-roll pass with new system** Roll all dimensions with the noise system active. This is the ultimate integration test — the roller exercises every config, every profile, every biome filter.

  **Verify (seed roller, local server running):**
  - `./dev seed-roll --count 50` (abbreviated run for all dims).
  - Zero new errors/warnings from the roller.
  - No dim drops to zero candidates that previously had candidates.
  - Winner scores across all dims: median ≥ 75 (currently ~82, should stay or improve).
  - `./dev seed-viewer` → visually inspect 5 dims: do structure placements look natural? Are there visible clusters in cluster-profile dims? Are settlements near spawn in multi_biome dims?

  _Handoff notes:_

- [ ] **G2. Regression suite for shipped dimensions** Check 10 representative dims explicitly against expected behaviour.

  **Verify (local server, RCON):**
  - `the_dustbowl` (none+force): only the forced farmstead. No other structures. `locate` for anything else fails.
  - `the_gilded_pit` (forced-only): forced bastions/fortresses at exact coords. No noise structures. `locate bastion_remnant` → returns forced position.
  - `the_overgrowth` (jungle multi_biome): jungle temples present, igloos absent, structures biased toward spawn (inner curve).
  - `the_burning_archipelago` (large nether_islands): nether structures present, spread across the world (even curve), NOT all within 500 blocks of spawn.
  - `the_frozen_strait` (pocket, maritime): shipwrecks + igloos present, villages absent (shunned or excluded).
  - `the_blackstone_keep` (nether): fortresses + bastions present, overworld structures absent.
  - `the_end_citadel` (end, dense): end cities + phantom citadels present at high density, overworld/nether absent.
  - `the_luminous_caverns` (cave): dungeons + loot present, no settlements or maritime.
  - `the_shattered_skies` (sky_islands): sky structures present, ocean/maritime absent.
  - `the_sunken_temple` (paradise_lost): paradise_lost structures present.

  _Handoff notes:_

- [x] **G3. Update skills and documentation** Update: `.claude/skills/custom-dimension-authoring/SKILL.md` (noise placement), `references/schema-reference.md` (new fields), `.claude/skills/seed-rolling/SKILL.md` (census scoring). Update `mods/AGENTS.md` architecture tree (NoiseStructurePlacement in the component list).

  **Verify (documentation correctness):**
  - Every new config field documented in schema-reference.md has a type, default, and example.
  - The kickoff prompt in this spike still accurately describes what was built.
  - `mods/AGENTS.md` architecture tree includes `NoiseStructurePlacement`, `NoiseProfile`, `StructureGroupRegistry`.
  - Seed-rolling skill accurately describes the census scoring model.
  - Run `./scripts/test-scripts.sh --quick` — no lint/compile failures from doc changes touching Python.

  _Handoff notes:_ **Done for everything that exists.** `./scripts/test-scripts.sh --quick` green.

  - `mods/AGENTS.md`: architecture tree now shows `NoiseGroupPlan`,
    `NoisePoolBuilder`, `NoiseStructurePlacement`, `NoiseFieldIndex`,
    `NoiseProfile`, `StructureNoise`, `StructureGroupRegistry`. New
    "Noise structure placement (2026-07-26)" section carries the invariants
    an agent can break silently: the rule is order-free (and why that
    matters), rank on white noise not on the field, frequency scales with
    radius, chunk coordinates must never hit the lattice, doubles only, the
    peaceful shift outranks density, data is jar-baked, over half of all sets
    never enter a group, and never run a bare synchronous `/locate` into an
    ungenerated dimension.
  - `custom-dimension-authoring/SKILL.md`: new "Noise structure placement —
    the DEFAULT, no config needed" section (group table, profile table with
    measured hit rates, the full precedence chain, difficulty shifts,
    `force` exclusivity, how to switch it off, five noise-specific traps).
  - `custom-dimension-authoring/references/schema-reference.md`: every new
    field with type, default and notes, plus the warning that
    `borders.player` and `difficulty.mobMultiplier` are now
    generation-affecting.
  - `seed-rolling/SKILL.md`: new section on `noise_census`, the parity gate,
    the bit-exactness rules, and the expected DRIFTED wave (73 dims drift, 5
    stay stable, 4 base worlds unaffected).
  - `scripts/data/README.md`: why neither CSV alone is sufficient and how
    `gen-structure-groups.py` joins them.

  **Not written, because the thing it would describe does not exist yet:**
  the census SCORING model in the seed-rolling skill. F2 (`distribution_match`
  replacing `want_score`) is not implemented, so the skill documents the
  census and the fingerprint change but still describes the existing
  nearest-distance scoring. Update it with F2.

## Estimate summary

| Phase                       | Days   | Parallelisable with      |
| --------------------------- | ------ | ------------------------ |
| A: Data + classification    | 2      | —                        |
| B: Core classes             | 2      | —                        |
| C: Mod integration          | 2      | —                        |
| D: Config + compat          | 1      | —                        |
| E: Locate + census          | 1      | F                        |
| F: Roller parity            | 3      | E                        |
| G: Migration + verification | 2      | —                        |
| **Total**                   | **13** | E+F overlap saves ~1 day |

## Risks

| Risk | Mitigation |
| --- | --- |
| Biome filter too aggressive (removes structures that SHOULD generate) | `structures.include` overrides the filter; log excluded structures at boot |
| Noise positions diverge Java vs Python | `/customdim structure-census` dumps Java positions; unit test compares to Python; same Perlin implementation (Ken Perlin's improved noise) |
| Cluster profile produces structure deserts too large | Cap max empty radius at `playable_radius / 3`; if no placement within that distance, force one |
| Pre-computation too slow for 8192-radius | Profile shows ~80ms for 800k chunks; if >200ms, log warning (no fallback needed) |
| `/locate` too slow in sparse dims | Region index makes it O(rings); cap at 100 rings same as vanilla |
| Breaking change for dims that relied on unmodified grid placement | World type defaults are tuned to produce similar-or-better variety; `"structures": {"noise": false}` forces grid mode (escape hatch for one release cycle) |

## Open decisions

1. **Should there be a `"noise": false` escape hatch to force grid mode?** Proposed: yes, for one major release (v4), then removed in v5. Gives authors time to adapt without rushing.

2. **How do exit shrines interact with noise?** Proposed: exit shrines stay on their own dedicated `FixedStructurePlacement`-like system (derived spacing from border, as today). They're not part of any group — they're infrastructure, not adventure content.

3. **Should `structures.spacing` overrides still work?** Proposed: no. They only made sense for grid mode. If you want a specific structure denser, promote its rarity tier. If you want it at an exact position, force it.

4. **What about `structures.mode: "allow"` / `"reject"`?** Proposed: reinterpret as pool filters. `"allow"` = only these set IDs enter the noise pool (equivalent to only having those groups). `"reject"` = these set IDs are excluded. Same semantics, different mechanism.

## Files touched

### Mod (Java)

| File | Change |
| --- | --- |
| `dimension/NoiseStructurePlacement.java` | **New.** Core placement class. |
| `dimension/NoiseProfile.java` | **New.** Sealed interface: Natural/Dense/Sparse/Cluster. |
| `dimension/StructureGroupRegistry.java` | **New.** Type defaults, group resolution, rarity tiers. |
| `dimension/DimensionStructures.java` | New `transformedNoise()` path as DEFAULT |
| `dimension/StructureThemes.java` | Add `groupOf()`, `rarityOf()` |
| `config/DimensionConfig.java` | New fields on `Structures` |
| `config/DimensionConfigLoader.java` | Parse noise config, load presets |
| `command/DimensionCommands.java` | `/customdim structure-census` |
| tests | `NoiseStructurePlacementTest`, `StructureGroupRegistryTest`, extended `DimensionStructuresTest` |

### Config / Data

| File                                                    | Change                                                     |
| ------------------------------------------------------- | ---------------------------------------------------------- |
| `config/custom-dimensions/structure-groups.json`        | **New.** Group definitions + rarity classification.        |
| `config/custom-dimensions/structure-type-defaults.json` | **New.** Per-type group profiles + radial curves.          |
| `src/main/resources/structure_themes.json`              | Add `group` and `rarity` fields alongside existing `theme` |

### Roller (Python)

| File                     | Change                                                     |
| ------------------------ | ---------------------------------------------------------- |
| `structure_placement.py` | `noise_census()`, `compute_noise_positions()`              |
| `fast_roller.py`         | Census-based tier-1, `structure_all` in measurements       |
| `score-dimensions.py`    | `distribution_match()` replaces `want_score(nearest_dist)` |
| `dimension_profiles.py`  | Parse noise config, fingerprint updates                    |

### Skills / Docs

| File                                                                       | Change                   |
| -------------------------------------------------------------------------- | ------------------------ |
| `.claude/skills/custom-dimension-authoring/SKILL.md`                       | Document noise placement |
| `.claude/skills/custom-dimension-authoring/references/schema-reference.md` | New fields               |
| `.claude/skills/seed-rolling/SKILL.md`                                     | Census scoring           |
