# Customising structures: spawn frequency, terrain and biomes

Research into whether consumers of this template (and, later, the
custom-dimensions mod) can configure how often structures — vanilla
dungeons and the ~30 modded structure packs — spawn, plus the adjacent
terrain/biome levers. **Research only: nothing in this document is
implemented.** All mod claims were verified on 2026-07-14 against the
exact versions pinned in `config/modrinth-mods.txt` (jar inspection via
the Modrinth CDN) or against official docs; sources are linked
throughout.

## Current state (the problem)

Structure spawn frequency on this server is governed **entirely by the
structure-set JSONs bundled inside each structure mod**, at each
author's defaults. There is:

- no `worldgen/structure_set` override anywhere in the repo,
- no structure-frequency config file in `config/`,
- no per-dimension variation — all 74 custom dimensions reuse the
  vanilla chunk generators (`DimensionManager.createDimensionOptions()`
  copies the overworld/nether/end generators and only swaps biome
  sources and seeds), so every dimension inherits exactly the same
  global structure placement.

Consumers who want "more dungeons" or "fewer villages" today have no
lever except removing whole mods via `overlay/mods-remove.txt`.

Two relevant capabilities are **already installed** but unused:

- **Lithostitched** (`lithostitched`, required library) — a worldgen
  modifier framework driven by datapack JSON.
- **Cristel Lib** (`cristel-lib`, pulled in for WWOO and Towns and
  Towers) — a structure-placement config framework that generates
  editable config files for mods that opt in. Two installed mods opt
  in: **Towns and Towers** and **Explorify**. Their spacing/separation
  is therefore already runtime-configurable on this server — we've just
  never shipped a config for them.

## How structure placement works (Minecraft 1.21.1)

Two independent data-driven levers, both plain JSON in any datapack:

1. **How often** — `data/<ns>/worldgen/structure_set/<name>.json`
   ([format](https://minecraft.wiki/w/Structure_set)). For
   `minecraft:random_spread` placement:
   - `spacing` (0–4096): average distance between generation attempts,
     in chunks (the grid cell size);
   - `separation` (0–4096): minimum distance between attempts — must be
     strictly smaller than spacing;
   - `frequency` (0.0–1.0, default 1.0): probability that a grid cell
     actually attempts generation;
   - `salt`: seed offset for the placement grid; plus
     `frequency_reduction_method`, `exclusion_zone`, `spread_type`.
   - Expected structures per area ∝ `frequency / spacing²`. Lower
     spacing/separation ⇒ more structures; `frequency` thins them out
     without moving the grid.
2. **Where** — the structure's own `biomes` field
   (`data/<ns>/worldgen/structure/<name>.json`), almost always a biome
   tag: `data/<ns>/tags/worldgen/biome/has_structure/<name>.json`
   ([biome tags](https://minecraft.wiki/w/Biome_tag_(Java_Edition))).
   Tags support `"replace": false` (append) — Terralith uses exactly
   this to inject its biomes into 16 vanilla `has_structure` tags so
   villages, temples, strongholds etc. still spawn in Terralith biomes.

A world datapack **shadows a mod's bundled file at the same
namespace/path** (world datapacks sit above mod data in the pack
stack), so `data/dungeons_arise/worldgen/structure_set/major_structures.json`
in our own datapack replaces When Dungeons Arise's copy. Overrides are
**whole-file**: the full `structures` list must be re-declared, and the
file must be re-synced if the mod's own copy changes on update.
1.21.1 datapacks use `pack_format` 48
([pack formats](https://minecraft.wiki/w/Pack_format)).

**Per-dimension behaviour (matters for custom dimensions):** structure
sets are global registry entries with no dimension field. Each
dimension's chunk generator filters the global list by *biome overlap*
(`ChunkGeneratorStructureState`): a set is considered for a dimension
iff at least one of its structures can spawn in a biome that the
dimension's biome source contains. Consequences:

- Our `multi_biome`/`single_biome` dimensions already get **coarse**
  structure control for free — a dimension whose biome list contains no
  `#minecraft:has_structure/village_plains` biome gets no plains
  villages.
- Vanilla **cannot** give the same structure set different
  spacing/frequency per dimension — placement parameters are
  one-per-set, globally.

**World safety:** placement changes only affect chunks that have not
yet generated. `frequency` reductions never move existing placements
(safest knob on a live world); changing `spacing`/`salt` re-rolls the
grid for *future* chunks, which can look inconsistent near the border
of explored terrain but never touches generated chunks. Same class of
caveat as the existing `config/multiverse_config.json` warning in
`AGENTS.md`.

## What each installed mod offers natively

Verified against the pinned jars (namespaces and baseline placements
extracted from the actual files):

| Mod | Namespace(s) | Native frequency config? | Baseline placement (spacing/separation) |
| --- | --- | --- | --- |
| YUNG's suite (11 mods) | `betterdungeons`, `bettermineshafts`, `betterstrongholds`, `betterwitchhuts`, `betteroceanmonuments`, `betterdeserttemples`, `betterjungletemples`, `betterfortresses`, … | **No.** Fabric TOML configs (AutoConfig) cover decoration/behaviour only (chest counts, prop spawn rates); placement is structure-set JSON. Note: several use **custom placement types** (`yungsapi:enhanced_random_spread`, `betterstrongholds:stronghold`, `betterdeserttemples:desert_temple`, `betterjungletemples:jungle_temple`) | dungeons 10/6 (small) to 48/24 (zombie); mineshafts `spacing 1, frequency 0.003` (chance-per-chunk model); strongholds 85/50; fortresses 30/20; ocean monuments 50/20; witch huts 30/8 |
| [When Dungeons Arise](https://modrinth.com/mod/when-dungeons-arise) | `dungeons_arise` | **No** (Fabric 2.1.68 has no config at all — 6 class files; the old Forge TOML doesn't exist here) | major 50/45 (32 structures, weighted); minor 45/40 + exclusion zone |
| [Towns and Towers](https://modrinth.com/mod/towns-and-towers) | `towns_and_towers` | **Yes — via Cristel Lib (installed).** Ships a `PLACEMENT` config (spacing/separation/salt/frequency per set) and an `ENABLE_DISABLE` config, generated under `config/towns_and_towers/` | towns 51/12, towers 48/12 `frequency 0.2`, other 32/16 |
| [Explorify](https://modrinth.com/mod/explorify) | `explorify` | **Yes — via Cristel Lib (installed).** 14 structure sets with placement + toggle configs under `config/cristellib/`. Author's official line: "If you want a config, install Cristel Lib." | 14 sets, various |
| [Structory](https://modrinth.com/mod/structory) / [Towers](https://modrinth.com/mod/structory-towers) | `structory`, `structory_towers` | **No** (pure datapack-in-a-jar, Stardust Labs) | e.g. ruins 30/10, manors 42/16, outcast villagers 44/14 |
| [Dungeons and Taverns](https://modrinth.com/datapack/dungeons-and-taverns) | `nova_structures` | **No.** Author's official method is deleting `structure_set` files from the pack. ⚠ Also **overrides vanilla `minecraft:woodland_mansions`** | 36 sets |
| Moog's suite (MVS/MES/MNS/MSS/MTR) | `mvs` (114 sets!), `mes` (26), `mns` (43), `mss` (36), `mtr` (7) | **No config file**, but the author publishes official **config-pack datapack templates** ([MVS](https://modrinth.com/datapack/mvs-moogs-voyager-structure-config-pack), [MES](https://modrinth.com/datapack/mes-moogs-end-structures-(config-pack))) documenting exactly the spacing/separation/salt/biome-tag edits | many sets, various |
| Philip's Ruins | `philipsruins` | No (21 sets, no config code) | various |
| Deadly Deadly Dungeon | `ddd` | No | `dddungeons`, `dddtowers` |
| Adventure Dungeons | `adventuredungeons` | No | 5 sets |
| Epic Structures | `epic` | No | small/medium/large dungeon tiers |
| Dungeons+ | `dungeons_plus` | No. ⚠ Overrides vanilla `monster_room` placed features | 2 sets |
| [Dungeons Reborn](https://modrinth.com/mod/dungeonsreborn) | `dungeons_reborn` | **No — and no structure sets at all.** Generates via `placed_feature`/`configured_feature`, so **no structure-set tool can tune it**; only feature overrides could | n/a |
| ATI Structures (datapack) | `ati_structures` | No, but sets are rarity-grouped (`aboveground_small/medium/large`, `underground_medium`, `rare`) — one file retunes many structures | 5 tiers |
| [Incendium](https://modrinth.com/mod/incendium) | `incendium` (+ overrides `minecraft:nether_complexes`) | No — datapack-override only | greater 30/16, lesser 20/12 + exclusion zone, nether complexes 16/8 |
| [Nullscape](https://modrinth.com/mod/nullscape) | `nullscape` | No | — |
| [Terralith](https://modrinth.com/mod/terralith) | `terralith` | **Partial**: since 2.6.0 the mod has `config/terralith.json` with a **Custom Structures on/off** toggle (no frequency dial); official [No Structures add-on datapack](https://modrinth.com/datapack/terralith-no-structures) exists. Structures gated by `#terralith:has_structure/*` tags — overridable per structure | — |

Verification method: pinned version IDs from `config/modrinth-mods.txt`
resolved via `api.modrinth.com/v2/version/<id>`, jars unzipped and
`data/*/worldgen/structure_set/*.json` + config classes inspected.

## The options for a frequency lever

### Option A — Override datapack (zero new mods) ✅ recommended foundation

Ship a datapack with `data/<ns>/worldgen/structure_set/*.json`
overrides for the sets we want to tune. The repo already has both
delivery mechanisms: local packs in `config/datapacks/` (precedent:
`adventure-mob-sweep`) and `datapack:` pins in mods lists; consumers
get `overlay/config/datapacks/`.

- Works for **every installed structure mod except Dungeons Reborn**,
  including the ones with custom placement types (we override the
  whole file, keeping the custom `type` and changing its
  spacing/separation fields).
- Deterministic, versioned, reviewable in PRs — fits the repo's
  config-ownership philosophy exactly.
- Example (make When Dungeons Arise ~2× rarer, safest form):

  ```json
  {
    "structures": [ /* copy WDA's full 32-entry list */ ],
    "placement": {
      "type": "minecraft:random_spread",
      "salt": 88371663,
      "spacing": 50,
      "separation": 45,
      "frequency": 0.5
    }
  }
  ```

- Costs: whole-file duplication (must re-sync when a mod update changes
  its own copy — the weekly `mod-updates.yml` PR is the natural
  checkpoint), and one file per structure set (~350 sets across all
  mods; in practice a curated subset of the big offenders is enough,
  and ATI/Moog's rarity-tier grouping keeps file counts low).
- "Preset" packs (e.g. `structures-dense`, `structures-sparse`) are
  just three variants of the same override files with scaled values —
  a consumer picks one via one `datapack:`/overlay line. This is how
  "worlds with lots of structures / few structures" becomes a
  consumer-facing feature without any new mod.

### Option B — Sparse Structures (one mod, one knob)

[Sparse Structures](https://modrinth.com/mod/sparsestructures) (Fabric
1.21.1 build `3.0`, dep: fabric-api only, **server-side only** —
client `unsupported` on Modrinth). Config
`config/sparsestructures.json5`:

- `spreadFactor` (default 2) multiplies spacing/separation of **all**
  structure sets, modded included. **Values below 1 make structures
  more common** (0.5 ≈ double density); very low values slow chunk gen.
- `customSpreadFactors`: per-structure overrides, e.g.
  `{"name": "minecraft:mansion", "spreadFactor": 2}`; factor 0 disables.
- `idBasedSalt` (default true) de-clusters sets that share a salt.

Best effort-to-value if the requirement is literally a global
"lots/normal/few" dial — a consumer preset is a single number. Limits:
restart to reload; no `frequency` handling; effect on the YUNG custom
placement types unverified (they subclass random spread, so it likely
works, but **must be verified in the local loop before adoption**).

### Option C — Structurify (one mod, full GUI + per-set control)

[Structurify](https://modrinth.com/mod/structurify) (Fabric 1.21.1
build `fabric-2.0.28+mc1.21.1`, deps: fabric-api ✅ installed, YACL ✅
already installed, Mod Menu — client-side; [GitHub](https://github.com/Faboslav/structurify)).
Config `config/structurify.json`:

```json
{
  "general": {
    "disabled_all_structures": false,
    "enable_global_spacing_and_separation_modifier": true,
    "global_spacing_and_separation_modifier": 1.0
  },
  "structures":     [ { "name": "minecraft:shipwreck", "is_disabled": false,
                        "biomes": [], "enable_biome_check": false,
                        "biome_check_distance": 32 } ],
  "structure_sets": [ { "name": "minecraft:villages",
                        "spacing": 34, "separation": 8 } ]
}
```

Global multiplier **plus** per-set spacing/separation (and per the
Modrinth description, salt and frequency), per-structure disable and
biome restrictions — the most complete single tool, discoverable
in-game via the YACL screen on clients. Caveats: Modrinth flags it
required on **both** sides (unlike Sparse Structures), so client-pack
impact needs checking; behaviour on custom placement types unverified;
one more mod whose own updates we track.

### Option D — Cristel Lib (already installed; two mods today)

No new mods: shipping `config/towns_and_towers/` and
`config/cristellib/explorify/` files through the existing config-sync
pipeline (`config/<modname>/` → `data/config/`, deploy.sh step 8)
tunes Towns and Towers and Explorify right now. Format documented in
the [Cristel Lib wiki](https://github.com/Cristelknight999/Cristel-Lib/wiki/2.-Creating-a-structure-config);
the T&T placement config even documents its own defaults
(towns/towers 48/24, other 32/16 + frequency 0.2) with "decrease to
spawn more frequently" guidance. Only covers mods that opt in — a
useful adjunct, not a general solution.

### Option E — Lithostitched modifiers (already installed) — **not a frequency dial**

Important negative result: Lithostitched (v1.7.13 line for 1.21.1) has
**no modifier that changes structure-set placement values** — there is
no `modify_structure_set` type ([modifier registry](https://github.com/Apollounknowndev/lithostitched/wiki)).
What it *does* give us, via datapack JSONs in
`data/<ns>/lithostitched/worldgen_modifier/`, all composable and
surgical (no whole-file replacement):

- `lithostitched:remove_structure_set_entries` / `add_structure_set_entries`
  — remove or add individual structures inside any mod's set (e.g.
  strip one WDA structure without touching the other 31);
- `lithostitched:set_structure_spawn_condition` — gate structure starts
  with conditions (`in_biome`, `height_filter`, `grid`, `sample_density`…);
  can make things rarer/constrained but is not a clean probability dial;
- biome/feature/terrain modifiers (`add_features`, `remove_features`,
  `replace_climate`, `add_surface_rule` — the surface-rule one is the
  only dimension-scoped modifier) — relevant to the terrain/biome side.

No dimension-scoped condition exists for structure placement.

## Terrain and biome configurability (the wider consumer question)

- **Tectonic** ([config wiki](https://github.com/Apollounknowndev/tectonic/wiki/Config)):
  the big terrain dial we already ship — `config/tectonic.json` exposes
  vertical scale, continent/ocean skew, erosion/ridge scale, height
  limit, caves, and biome temperature/vegetation multipliers. None of
  this is surfaced to consumers today; it slots straight into the
  existing `config/<modname>/` + overlay pipeline.
- **Terralith 2.6+**: `terralith.json` toggles (custom structures,
  skylands, fog…); official add-on datapacks (No Structures, ReStoned).
  Biome distribution itself is not configurable. ⚠ Cannot be removed
  from an existing world.
- **WWOO**: since 2.6.0 its former add-ons (Navigable Rivers, Cliffs
  and Coves, Towering Tepuis, ore removal) are config options in the
  mod. No frequency/rarity knobs.
- **Geophilic**: no config; the author's official route is editing the
  pack with [Datapack Toolkit](https://everloste.github.io/dptoolkit-web/).
- **TerraBlender** (`config/terrablender.toml`): region size and
  vanilla-vs-modded biome weights — only matters if TerraBlender-based
  biome mods are added (Terralith uses Lithostitched, not TerraBlender).
- **Incendium / Nullscape / Paradise Lost**: no configs; datapack
  overrides only.
- **Biome side of structures**: `has_structure` tag overrides are the
  precise per-structure "where" lever, complementary to the frequency
  options above.

## Applying this to custom dimensions ("lots of structures" worlds)

What's possible, in increasing order of effort:

1. **Today, zero code — biome curation.** A `multi_biome` dimension's
   structure roster is already determined by its biome list. A
   "structure-rich" dimension is one whose biomes sit in many
   `has_structure` tags; a barren one uses biomes that appear in none.
   Coarse (no density control) but real, and already expressible in
   `config/multiverse_config.json`.
2. **Global presets per *world*, not per dimension.** Options A–C are
   world-global: they change density everywhere at once. For consumer
   servers ("my whole server should be dungeon-dense") this is
   sufficient and needs no mod work.
3. **True per-dimension density needs our mod.** Vanilla applies one
   placement per structure set globally; the filtering happens in
   `ChunkGeneratorStructureState`, which is built **per world** from
   the generator's biome source. That is exactly the seam our mod
   already exploits for per-dimension seeds (`ServerWorldSeedMixin`
   feeds a per-world seed into placement). A `structureDensity`
   field per dimension (e.g. `"structures": "dense" | "normal" |
   "sparse" | "none"`, or a float multiplier) could be implemented by
   intercepting the per-world structure-placement calculation and
   scaling each `RandomSpreadStructurePlacement`'s spacing/separation
   (or short-circuiting to empty for `"none"`). Design sketch only —
   sharp edges to respect if we ever build it:
   - custom placement types (YUNG's) need either subclass-aware
     handling or exclusion;
   - the c2me density-function-compiler trap documented in
     `mods/AGENTS.md` shows this layer is sensitive to caching mods —
     any per-dimension placement mixin must be soak-tested with the
     full mod stack;
   - fits naturally into the `config/dimensions.json` rich schema
     proposed in `mod-owned-dimension-lifecycle.md` — `structureDensity`
     belongs next to `peaceful`/`seed` in that schema rather than in a
     second config.

## Recommendation (when we decide to act — not now)

1. **Foundation: Option A** — a repo-owned override datapack with
   named presets (`dense`/`default`/`sparse`), covering a curated set
   of the highest-impact structure sets (WDA, D&T, Moog's tiers, ATI
   tiers, YUNG dungeons, T&T, Incendium). Zero new mods, fits every
   existing pipeline (defaults-seed, overlay, config sync), reviewable.
2. **Convenience layer, evaluate locally first:** Sparse Structures if
   we want one consumer-facing dial; Structurify if we want per-set
   control with an in-game screen. Either must pass the local
   verification loop with attention to YUNG's custom placement types
   before pinning.
3. **Quick win at any point:** ship Cristel Lib configs for Towns and
   Towers + Explorify through the normal config-sync path.
4. **Custom dimensions:** add `structureDensity` to the dimension
   schema as part of the `mod-owned-dimension-lifecycle` refactor, not
   before it.

Whatever is adopted, keep the current world's values untouched at
default (the existing world stays exactly as it is; presets are for
new consumer worlds/dimensions), prefer `frequency` over
`spacing`/`salt` changes anywhere near explored terrain, and respect
the `AGENTS.md` confirm-before-proceeding rule for worldgen config.

## Sources

- Structure sets / biome tags / pack formats: [minecraft.wiki Structure_set](https://minecraft.wiki/w/Structure_set), [Biome_tag](https://minecraft.wiki/w/Biome_tag_(Java_Edition)), [Pack_format](https://minecraft.wiki/w/Pack_format); vanilla baselines via [misode/mcmeta](https://github.com/misode/mcmeta)
- [Lithostitched wiki](https://github.com/Apollounknowndev/lithostitched/wiki) + source ([Apollounknowndev/lithostitched](https://github.com/Apollounknowndev/lithostitched), branches `1.21`/`cloche`)
- [Structurify](https://modrinth.com/mod/structurify) · [GitHub](https://github.com/Faboslav/structurify); [Sparse Structures](https://modrinth.com/mod/sparsestructures)
- [Cristel Lib wiki](https://github.com/Cristelknight999/Cristel-Lib/wiki/2.-Creating-a-structure-config)
- Stardust Labs Modrinth pages ([Terralith](https://modrinth.com/mod/terralith), [Incendium](https://modrinth.com/mod/incendium), [Nullscape](https://modrinth.com/mod/nullscape), [Structory](https://modrinth.com/mod/structory)) and [GitHub org](https://github.com/Stardust-Labs-MC) (their Miraheze wiki blocks automated access — claims sourced from Modrinth/GitHub instead)
- [Tectonic config wiki](https://github.com/Apollounknowndev/tectonic/wiki/Config); [WWOO](https://modrinth.com/mod/wwoo); [Geophilic](https://modrinth.com/datapack/geophilic); [TerraBlender source](https://github.com/Glitchfiend/TerraBlender)
- Moog's config packs: [MVS](https://modrinth.com/datapack/mvs-moogs-voyager-structure-config-pack), [MES](https://modrinth.com/datapack/mes-moogs-end-structures-(config-pack))
- Per-dimension mechanics: 1.21.1 `ChunkGeneratorStructureState` (decompiled), plus this repo's `DimensionManager.java` and `mods/AGENTS.md` (c2me trap)
- Installed-jar inspection: pinned versions from `config/modrinth-mods.pinned.txt`, fetched via `api.modrinth.com/v2/version/<id>`
