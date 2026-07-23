# Optional-mods hardening: surviving consumer mod removals

Consumers can remove any default mod via `overlay/mods-remove.txt`, so every platform surface should assume **all mods are effectively optional**. The v3 worldgen work added several surfaces that reference mod content; this file records the audit (2026-07-15), what is already handled, and what a future round should investigate. **Partially implemented — see status per row.**

## Failure model

A datapack or mod-jar registry entry that references content from an absent mod fails DYNAMIC REGISTRY LOAD, and a broken world datapack prevents the world from loading — a boot-breaker, not a cosmetic gap. References that are merely _matched lazily_ (biome ids in surface-rule conditions, tag entries) degrade gracefully.

## Audit table

| Surface | On mod removal | Status |
| --- | --- | --- |
| `structures` override datapack (+ dense/sparse presets) | structure_set overrides reference the removed mod's structures → registry load failure → **boot break** | ✅ FIXED: packs carry `ownership.json` (file → modrinth slug, emitted by gen-structure-presets.py); `scripts/filter-datapacks.py` strips owned files at sync time in deploy.sh/dev-up.sh, after both platform and overlay syncs (overlay-swapped presets covered; packs without ownership.json untouched). Covers vanilla-set overrides owned by mods (D&T's woodland_mansions, Incendium's nether_complexes, Nullscape's end_cities). |
| `adventure:wide`/`compressed` noise presets (custom-dimensions jar) | reference ~30 `tectonic:` noises, ~60 `terralith:` noises AND terralith-jar density functions (`terralith:overworld/extra_terrain_sum`, `spikes/tendrils`) → removing Tectonic or Terralith → **boot break** (mod data is always loaded) | ⚠️ OPEN — see "future round" below. Pre-v3, removing Tectonic/Terralith merely changed terrain; post-v3 it breaks boots. Documented as unsupported for now. |
| structureDensity / peaceful runtime rescaling | removed mod's sets simply absent from the registry → no-op | ✅ inherently safe |
| theme map (jar + `config/structure_themes.json` overlay) | unknown/absent ids never match → no-op; consumer extras themable via overlay | ✅ safe |
| `config/tectonic.json` | Tectonic removed → config unread, inert | ✅ safe |
| `multiverse_config.json` biome lists (`terralith:`/`incendium:` biomes) | filtered biome list drops missing entries; empty → plains fallback (DimensionManager) | ✅ safe (graceful degradation, logged) |
| seed profile locate batteries (modded structure ids) | `/locate` returns not-found → measured as miss; scoring treats per direction | ✅ safe |
| Cristel Lib configs (if we ever ship T&T/Explorify tuning) | config files for absent mods are inert | ✅ safe |
| c2me.toml enforcement | c2me removed → file inert | ✅ safe |

## Future round (suggested brief for another agent)

1. **Make the noise presets self-contained.** Extend `scripts/gen-terrain-presets.py` to add the pinned TERRALITH jar as a base layer and clone the full reference closure into the `adventure` namespace: all `tectonic:`/`terralith:` NOISE definitions (static JSONs) referenced by `"noise"` fields and `shift/shift_a/shift_b` arguments, plus the terralith-jar density functions the Terratonic settings reference. Success criterion: `grep -r '"(tectonic|terralith):' ` over the generated tree matches ONLY biome ids in surface-rule conditions (lazy) — zero registry-holder references. Then a Tectonic/Terralith removal degrades to "different terrain" instead of a boot break. Watch: noise Holder dedup is per-id, so cloned noises cost a little memory per preset; verify NoiseConfig instantiation count stays sane.
2. **Removal-matrix smoke coverage.** A smoke-test variant (matrix or a second boot) with a representative `overlay/mods-remove.txt` (when-dungeons-arise + dungeons-and-taverns + one YUNG mod) asserting the server boots and `/locate` for a removed set's structure fails cleanly. This is the regression net for the whole promise.
3. **Ownership for future platform datapacks.** Any new curated datapack that references mod content should emit `ownership.json` — consider a lint check (datapack contains non-vanilla namespaces but no ownership.json → warn).
4. **Client pack parity.** `modpack/adventure.mrpack.json` removals are a separate system (client packs are consumer-forked, not overlay-driven); out of scope here but worth a look in the same round.
