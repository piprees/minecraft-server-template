# Customising terrain: scale, realism and per-dimension shape

Research into making terrain wider and more realistically proportioned
(broader mountains and valleys, less craggy/"Minecraft-y"), what knobs
already exist, what mods could help, and how the custom-dimensions mod
could support different terrain per dimension. **Research only —
nothing is implemented.** Verified 2026-07-15 against the exact pinned
versions (jar inspection) and official docs; companion to
`customising-structures.md`.

## Current state — who actually shapes our terrain

Verified by unpacking the pinned jars:

- **Tectonic 3.0.26** is the terrain-shape mod. It ships a built-in
  datapack that **replaces `minecraft:overworld` noise settings and
  density functions wholesale**, wired to its config through custom
  density-function types (`tectonic:config_noise` etc.), plus
  lithostitched modifiers for height limits and conditional features.
- **Terralith 2.6.2** does ship its own terrain files, but when the
  Terralith *mod* is loaded Tectonic activates its built-in
  **Terratonic overlay** (`fabric:all_mods_loaded: ["terralith"]`),
  which supplies the operative overworld noise settings. Net result:
  **all shape control lives in `config/tectonic.json`; Terralith
  contributes biomes and surfaces** (and has no shape knobs of its own).
- **WWOO 2.6.7** and **Geophilic 3.6** contain no noise settings or
  density functions at all — biome decoration only. They are not part
  of the shape problem.
- **We ship no `config/tectonic/` directory** — the server runs
  Tectonic at factory defaults. The craggy look is simply the default
  tune, not an absence of knobs.

Because Tectonic replaces the `minecraft:overworld` noise-settings
*registry entry*, every custom dimension that reuses the overworld
generator (`DimensionManager` copies it for overworld/multi_biome/
single_biome/large_biomes types) inherits Tectonic terrain — one global
shape for all ~60 overworld-flavoured dimensions today.

## The knobs we already have (`config/tectonic.json`)

Exact serialised keys from the 3.0.26 jar/source (the mod writes the
file with self-documenting comments; no numeric ranges are enforced
except height limits — multiples of 16, `min_y ≤ 0`, `max_y ≥ 256`):

| Section.key | Default | What it does (official tooltip semantics) |
| --- | --- | --- |
| `continents.erosion_scale` | 0.25 | **The main "wider mountains" dial.** Lower ⇒ thicker mountain ranges and wider terrain between them |
| `continents.ridge_scale` | 0.25 | Lower ⇒ wider rivers, valleys and plateau systems |
| `continents.continents_scale` | 0.13 | Lower ⇒ larger continents and oceans |
| `continents.flat_terrain_skew` | 0.1 | Higher favours flat/rolling terrain over stepped plateaus |
| `continents.ocean_offset` | -0.8 | Land/ocean skew (above -0.45 no deep oceans; above -0.2 no oceans) |
| `continents.rolling_hills` | true | Smooth hilly plains |
| `global_terrain.vertical_scale` | 1.125 | Height multiplier above sea level; 1.0 ⇒ gentler relief |
| `global_terrain.elevation_boost` | 0 | Extra vertical scale applied to mountains faster than lowlands |
| `global_terrain.min_y` / `max_y` | -64 / 320 | Build/gen height (the old `increased_height` = 640) |
| `global_terrain.ultrasmooth` | false | Removes staircase/terracing artifacts (v2 behaviour); caveat: odd generation in deep oceans + windswept biomes |
| `biomes.temperature_scale` / `vegetation_scale` | 0.25 | Lower ⇒ larger climate regions (biome layout, not shape) |
| `experimental.alternate_erosion_scaling` / `alternate_continents_scaling` | false | Author: "improve terrain near mountains and oceans on larger terrain scales" — intended companions to low scale values. ⚠ "Not compatible with C2ME's hardware acceleration" |
| `oceans.ocean_depth` / `deep_ocean_depth` / `monument_offset` | -0.22 / -0.45 / -30 | Ocean depths + vanilla monument adjustment |

Built-in presets: `default`, `large_biomes`, `deserted`,
`frozen_wasteland`, `overkill`. There is no official "realistic"
preset, but **Overkill** ("extremely high mountains and flat terrain
otherwise") shows the author's sanctioned recipe for large-scale
terrain: `erosion_scale 0.08` (⅓ of default), `continents_scale 0.1`,
`flat_terrain_skew 0.5`, `ultrasmooth true`, both experimental
scalings on, `vertical_scale 2.5` + `elevation_boost 1.6` + `max_y 768`.

### Suggested "wider, more realistic" starting point (for evaluation)

Overkill's horizontal ideas without its screenshot-bait verticality:

```json5
{
  "global_terrain": { "vertical_scale": 1.0, "elevation_boost": 0.3,
                      "max_y": 448, "ultrasmooth": true },
  "continents":     { "continents_scale": 0.1, "erosion_scale": 0.12,
                      "ridge_scale": 0.18, "flat_terrain_skew": 0.3 },
  "biomes":         { "temperature_scale": 0.15, "vegetation_scale": 0.15 },
  "experimental":   { "alternate_erosion_scaling": true,
                      "alternate_continents_scaling": true }
}
```

Rationale: halving `erosion_scale`/`ridge_scale` doubles the wavelength
of the mountain/valley rhythm; keeping `vertical_scale` ≈ 1 with a mild
`elevation_boost` means the same heights spread over wider slopes —
i.e. gentler average gradient, which is most of what "realistic
proportions" reads as. Widening climate scales keeps biomes from
striping across the now-larger landforms. All values need seed-rolled
local evaluation (`./dev seed-roll` + spectator flights) — the author
publishes semantics, not taste.

Caveats to respect (all sourced):

- **C2ME**: the experimental scalings conflict with "C2ME's hardware
  acceleration". We already force `useDensityFunctionCompiler = false`
  in `c2me.toml` (deploy.sh step 8c — the per-dimension-seed trap),
  which is very likely the feature in question, but this must be
  verified in the local loop before trusting it; the fallback is
  leaving the two experimental keys false.
- Raising `vertical_scale`/`elevation_boost` without raising `max_y`
  is a documented generation-issue source (3.0.13 changelog); `max_y`
  must be a multiple of 16.
- Changes apply at server restart and **only to newly generated
  chunks** — an existing world gets terrain seams at the border of
  explored terrain. Tectonic's chunk-blending only covers v2→v3
  upgrades, not config changes. For the current world the honest
  options are: accept seams in unexplored regions, or reserve the new
  tune for new consumer worlds and custom dimensions.
- Wider terrain + Distant Horizons is a proven pairing (community
  packs are built on it), but chunk gen gets heavier; Chunky pre-gen
  and DH LOD builds will take longer.

## If the Tectonic knobs aren't enough

In increasing order of meddling:

1. **Continents** (Stardust Labs, Modrinth `continents`, Fabric 1.21.1
   `1.1.12`/`1.1.14`, no deps, server-side, data-only): makes
   landmasses/oceans up to 4× wider via a one-file density-function
   override + island system; author-stated Terralith-compatible; has
   its own sliders (continent scale 25–400%). ⚠ It overrides
   `minecraft:worldgen/density_function/overworld/continents.json`,
   which Tectonic's router replacement may or may not still reference
   — the pairing is commonly run in packs but **must be verified
   locally** before recommending. Only worth adding if we want bigger
   *continents* specifically; Tectonic's `continents_scale` covers
   most of it.
2. **Lithosphere** (Modrinth `lithosphere`, Fabric 1.21.1): explicitly
   the "wider + less craggy + cinematic ranges" brief — but it replaces
   overworld noise settings and is **author-stated incompatible with
   Tectonic/Terralith terrain**. A replacement direction, not an
   add-on. Would mean dropping Tectonic and giving up Terratonic.
3. **Expanded Ecosphere** (WWOO's sibling): also reshapes terrain, also
   the author's declared "bad compatibility with other worldgen mods"
   half — an alternative stack, not an addition.
4. **Big Globe / Terra**: whole-generator swaps (Big Globe is
   client-required and bypasses Terralith/Tectonic entirely; Terra's
   Fabric line is a stale 2024 beta). Not a fit for this pack.

**Recommendation: no mod changes.** The existing stack has the right
architecture (Tectonic owns shape, Terralith owns biomes, decoration
mods are inert) — we've just never turned the dials.

## Per-dimension terrain (custom dimensions)

The user goal: some dimensions realistic/wide, others compressed/
dramatic. What's possible:

- **Already works today** via `multiverse_config.json` `type`:
  `amplified` and `large_biomes` dimensions already use different
  `ChunkGeneratorSettings` (the world-preset entries) — proof the
  per-dimension mechanism exists in `DimensionManager` (the `amplified`
  case looks up the preset registry and borrows its generator).
  Large-biomes dims are, mechanically, "4× wider continents/erosion/
  temperature with vanilla ridges" — already a step toward realistic
  proportions, per the vanilla `_large` noise files.
- **The clean extension — a `noiseSettings` field per dimension.**
  `ChunkGeneratorSettings` is a datapack registry
  (`worldgen/noise_settings`): a datapack can register e.g.
  `adventure:wide` (a copy of the Tectonic/Terratonic overworld file
  with scale constants edited) or `adventure:compressed` (higher
  erosion/ridge frequency, taller `vertical_scale` — features packed
  tighter). `DimensionDefinition` gains an optional
  `"noiseSettings": "adventure:wide"`; `createDimensionOptions()`
  resolves it from `RegistryKeys.CHUNK_GENERATOR_SETTINGS` and passes
  that entry to the `NoiseChunkGenerator` it already constructs —
  a few lines in the same `switch` that handles `amplified`. Ships via
  the existing `config/datapacks/` pipeline. Sharp edges:
  - Tectonic's config constants (`tectonic:config_noise` density
    functions) are global — a copied noise-settings file that
    references them still reads the one `tectonic.json`. Fully
    independent per-dimension tunes need the copies to inline their
    own constants instead (a one-off transformation when authoring the
    datapack files, since `tectonic:` density function types resolve
    against the same config for everyone).
  - Per-dimension seeds (`ServerWorldSeedMixin`) and the c2me DFC
    disable already handle the caching layer; any new settings entry
    must be soak-tested through the `mods/AGENTS.md` verification loop
    (locate-oracle across dims, RestartCount 0).
  - Existing dimensions keep their current terrain only if their
    settings id doesn't change — `noiseSettings` should be additive
    and unset for all 74 current dims.
- **Fits the existing refactor**: like `structureDensity` in
  `customising-structures.md`, the `noiseSettings` field belongs in the
  rich per-dimension schema proposed in
  `mod-owned-dimension-lifecycle.md` — one config, reviewable in PRs.
- Presets that would make sense: `adventure:wide` (realistic — the
  suggested config above, baked), `adventure:compressed` (scales up:
  erosion/ridge ~0.4–0.5, `vertical_scale` 1.5, everything closer
  together — good for small themed dims), plus the existing
  `amplified`/`large_biomes` types.

## Recommendation (when we act — not now)

1. **Phase 1 — zero new mods:** add `config/tectonic/tectonic.json`
   (wired through the existing config-sync pipeline + `MC_PATTERNS`)
   with the suggested wide-terrain values; evaluate with
   `./dev seed-roll` on a throwaway local world; verify the
   experimental-scaling/C2ME question in the same session. Consumers
   would override via `overlay/config/tectonic/`. Decide separately
   whether the *current* production world adopts it (seams) or it
   ships as a new-world default only.
2. **Phase 2 — per-dimension presets:** `noiseSettings` field in the
   dimension schema + an `adventure:wide`/`adventure:compressed`
   noise-settings datapack, as part of the `mod-owned-dimension-
   lifecycle` refactor.
3. **Continents** only if Phase 1 leaves landmass size wanting, and
   only after a local compatibility check against Tectonic's router.

## Sources

- Tectonic: [config wiki](https://github.com/Apollounknowndev/tectonic/wiki/Config), [source `ConfigState.java`/`ConfigPresets.java`](https://github.com/Apollounknowndev/tectonic) (branch `rewrite-squared`), pinned jar `3.0.26-fabric-21.1` contents (built-in datapack, Terratonic overlay, lithostitched modifiers), [Terratonic](https://modrinth.com/datapack/terratonic), issues [#222](https://github.com/Apollounknowndev/tectonic/issues/222)/[#376](https://github.com/Apollounknowndev/tectonic/issues/376)/[#469](https://github.com/Apollounknowndev/tectonic/issues/469)
- Terralith 2.6.2 / WWOO 2.6.7 / Geophilic 3.6 pinned jar inspection (terrain files vs biome-only)
- Vanilla mechanics: [misode/mcmeta `1.21.1-data`](https://github.com/misode/mcmeta) (`noise_settings/{overworld,large_biomes,amplified}.json`, `density_function/overworld*/`, `noise/*_large.json` — large_biomes = firstOctave −9→−11 ⇒ 4× wider), [Custom world generation](https://minecraft.wiki/w/Custom_world_generation)
- [Lithostitched wiki](https://github.com/Apollounknowndev/lithostitched/wiki) (`wrap_density_function`, `lithostitched:shift`/`axis` density functions — coordinate-stretch wrappers are format-legal but unproven; direct file overrides preferred)
- Alternatives: [Continents](https://modrinth.com/mod/continents) (jar-verified: one-file override, `dpconfig.json` sliders), [Lithosphere](https://modrinth.com/mod/lithosphere), [Expanded Ecosphere](https://modrinth.com/mod/expanded-ecosphere) (jar-verified terrain files), [Big Globe](https://modrinth.com/mod/big-globe), [Terra](https://modrinth.com/mod/terra); dead/non-Fabric: TerraForged (Forge 1.18.2), ReTerraForged (no Fabric)
- Repo: `mods/custom-dimensions/.../DimensionManager.java` (amplified/large_biomes preset borrowing — the per-dimension seam), `mods/AGENTS.md` (c2me DFC trap), `mods/.ideas/mod-owned-dimension-lifecycle.md`
