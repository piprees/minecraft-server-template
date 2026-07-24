# Client Pack Parity Audit

**Date:** 2026-07-24 | **Scope:** server-mod removal impact on client packs | **Status:** gap confirmed

## Executive summary

The server's optional-mods hardening lets consumers remove any default server mod via `overlay/mods-remove.txt` without breaking the boot. The client pack (`modpack/adventure.mrpack.json`) is a completely separate system with no mechanism to consume that removal list. 52 "both-required" mods sit on both the server and client lists — removing any of them server-side while the client pack still ships the matching mod (or vice versa) causes Fabric registry mismatches that refuse the connection. Today a consumer must manually fork and edit the client manifest to stay in sync; nothing warns them when they don't.

---

## 1. Overlap table

75 mods appear on both the server list (`config/modrinth-mods.txt`) and the client manifest (`modpack/adventure.mrpack.json`). Modrinth `client_side`/`server_side` metadata plus `fabric.mod.json` `environment` fields classify the risk when one side is removed without the other.

### 1a. Both-required (52 mods) — server removal WITHOUT client removal = join failure

These mods register blocks, items, entities, or network channels. A Fabric client carrying them cannot join a server that lacks them (registry mismatch at handshake).

| Slug | Client list | On server removal (client keeps mod) | On client removal (server keeps mod) |
|------|------------|--------------------------------------|--------------------------------------|
| 3d-placeable-food | required | kick: missing registry entries | kick: missing registry entries |
| amendments | required | kick | kick |
| animal_feeding_trough | required | kick | kick |
| architectury-api | required | kick (lib; breaks dependents) | kick |
| artifacts | required | kick (custom items/slots) | kick |
| beautiful-enchanted-books-mod-edition | required | kick | kick |
| better-combat | required | kick (custom packets) | kick |
| betterdays | required | kick | kick |
| bookshelf-lib | required | kick (lib) | kick |
| bountiful | required | kick (custom items) | kick |
| carry-on | required | kick (custom packets) | kick |
| charm-of-undying | required | kick (trinket slot) | kick |
| chipped | required | kick (custom blocks) | kick |
| comforts | required | kick (custom blocks) | kick |
| creeper-overhaul | required | kick (custom entities) | kick |
| critters-and-companions | required | kick (custom entities) | kick |
| dramatic-doors | required | kick (custom blocks) | kick |
| dungeonsreborn | required | kick | kick |
| elytra-slot | required | kick (trinket slot) | kick |
| explorers-compass | required | kick (custom item) | kick |
| fabric-seasons-extras | required | kick (custom blocks) | kick |
| farmers-delight-refabricated | required | kick (custom blocks/items) | kick |
| friends-and-foes | required | kick (custom entities) | kick |
| guard-villagers-(fabricquilt) | required | kick (custom entities) | kick |
| handcrafted | required | kick (custom blocks) | kick |
| immersive-armors | required | kick (custom items) | kick |
| inmis | required | kick (custom item) | kick |
| kambrik | required | kick (lib) | kick |
| lets-do-vinery | required | kick (custom blocks/items) | kick |
| lootr | required | kick (custom block entity) | kick |
| macaws-bridges | required | kick (custom blocks) | kick |
| macaws-doors | required | kick (custom blocks) | kick |
| macaws-furniture | required | kick (custom blocks) | kick |
| macaws-roofs | required | kick (custom blocks) | kick |
| moonlight | required | kick (lib) | kick |
| more-villagers-re-employed | required | kick (custom professions) | kick |
| natures-compass | required | kick (custom item) | kick |
| natures-spirit | required | kick (custom blocks/biomes) | kick |
| paradise-lost | required | kick (dimension + custom everything) | kick |
| philips-ruins | required | kick | kick |
| playeranimator | required | kick (lib) | kick |
| prickle | required | kick (lib) | kick |
| resourceful-config | required | kick (lib) | kick |
| resourceful-lib | required | kick (lib) | kick |
| stack-refill | required | kick | kick |
| supplementaries | required | kick (custom blocks/items) | kick |
| terrablender | required | kick (lib; worldgen) | kick |
| trinkets | required | kick (slot framework) | kick |
| universal-sawmill | required | kick (custom recipe type) | kick |
| untitled-duck-mod | required | kick (custom entity) | kick |
| villagerapi | required | kick (lib) | kick |
| yungs-api | required | kick (lib) | kick |

### 1b. Server-required, client-optional (4 mods) — server removal safe for client

| Slug | Client list | Removal impact |
|------|------------|----------------|
| deadly-deadly-dungeon | required | silent: server-only structures |
| fallingtree | required | silent: server-side behaviour |
| open-parties-and-claims | required | degraded: client UI non-functional but no kick |
| when-dungeons-arise | required | silent: server-only structures |

### 1c. Client-required, server-optional (5 mods) — server removal irrelevant

| Slug | Client list | Notes |
|------|------------|-------|
| athena-ctm | required | client rendering only |
| enchantment-descriptions | required | client tooltip only |
| fabric-seasons-terralith-compat | optional | client rendering compat |
| geckolib | required | animation lib, server uses optional |
| sound-physics-remastered | required | client audio only |

### 1d. Both-optional (14 mods) — mismatch degrades gracefully

`appleskin`, `collective`, `configured-defaults`, `distanthorizons`, `fabric-api`, `fabric-language-kotlin`, `fabric-seasons`, `ferrite-core`, `forge-config-api-port`, `lithium`, `no-chat-reports`, `puzzles-lib`, `simple-voice-chat`, `yacl`.

These are designed to work on either side independently. Mismatch = silent feature degradation, never a kick.

---

## 2. Mechanism trace

### Build inputs for the client pack

`build-modpack.sh` reads exactly these inputs:

1. `modpack/adventure.mrpack.json` — the `_clientMods`, `_resourcePacks`, `_shaderPacks` sections
2. `modpack/overrides/` — static client configs (Configured Defaults, servers.dat, resource/shader packs)
3. `.env` values — `MC_VERSION`, `DOMAIN`, `BRAND_NAME`, `BRAND_SLUG`, etc.
4. Modrinth API — resolves slugs to download URLs at build time

### Does `overlay/mods-remove.txt` feed the client pack build?

**No.** Confirmed by:

- `grep -n "mods-remove" scripts/build-modpack.sh` returns zero hits
- The build script reads only the manifest file, never the overlay directory
- `deploy-reusable.yml` hashes `mods-remove.txt` into the pack-change detector (line 451) but only for the Discord notification decision — the actual `pack-build.sh` invocation passes no removal context
- The consumer README's "Remove a default mod" section explicitly states: "Client packs are a separate system (consumer-forked, not overlay-driven) and have not been audited for removal safety."

### Consumer client pack workflow

Consumers fork the client manifest entirely. The consumer README documents `./dev pack` which runs the modpack-builder, but the manifest it builds from is a copy the consumer maintains independently. There is no merge, diff, or sync mechanism between the server overlay and the client manifest.

---

## 3. Failure taxonomy

For the 52 both-required overlap mods, removal on one side without the other:

| Failure mode | Trigger | Count | Examples |
|---|---|---|---|
| **(a) Connection refused / kick** | Fabric registry mismatch — client has blocks/items/entities the server doesn't (or vice versa) | 52 | All 52 both-required mods. Fabric's handshake rejects unknown registry entries. |
| **(b) Silent degradation** | Mod present on one side with `client_side: optional` or `server_side: optional` | 18 | voice chat (no voice), OPAC (no claims UI), distant horizons (no LOD), appleskin (no HUD overlay) |
| **(c) Client crash on join** | Mod's networking code throws an unhandled exception when the expected channel is absent | 0 confirmed | Fabric's handshake catches mismatches before mod code runs. Theoretical: a mod with a broken `ClientPlayNetworking` handler, but none identified in this pack. |

**Key finding:** The dominant failure mode is (a) — a clean kick with a "Incompatible mod set" message. Fabric 1.21.1 rejects the connection at the handshake phase before any mod code executes, so crashes are unlikely. The failure is binary and immediately obvious to the player.

---

## 4. Recommendations

### R1. Lint: flag server removals with client overlap (Size: S)

Add a CI step (or extend `check-pack-coherence.py`) that reads `overlay/mods-remove.txt` and cross-references against `_clientMods.required` in the manifest. If a removed slug appears in the client required list with `client_side: required` on Modrinth, the build warns or fails with a message like: "Removing `supplementaries` from the server requires removing it from the client pack too — edit `modpack/adventure.mrpack.json` or fork it."

**Cost:** ~50 lines of Python; runs in existing CI. No architectural change.

### R2. Document the fork-sync checklist in consumer README (Size: S)

The consumer README's "Remove a default mod" section already notes the gap ("Client packs are a separate system... not audited"). Expand it with a concrete checklist:

1. For each slug in `mods-remove.txt`, check if it appears in `_clientMods.required` in the client manifest.
2. If yes, remove it from the client manifest too (and remove any of its client-only dependents).
3. Rebuild: `./dev pack`.

**Cost:** ~20 lines of documentation. Zero code.

### R3. Auto-filter: make `build-modpack.sh` consume removals (Size: M)

Pass `overlay/mods-remove.txt` (or `${SERVER_DIR}/overlay/mods-remove.txt`) into the build script. Before resolving client mods, filter out any slug present in the removal list. This closes the gap automatically for consumers who use the overlay system.

**Cost:** ~30 lines of bash/Python in `build-modpack.sh`, plus plumbing the overlay path through `pack-build.sh` on the server (the CI wrapper). Needs testing: a removed both-required mod's dependent libraries (e.g. removing `trinkets` should also drop `charm-of-undying` and `elytra-slot`) aren't automatically handled — the coherence checker would catch dangling deps.

### R4. Client-side removal matrix in CI smoke test (Size: L)

Extend the existing server removal-matrix smoke test to also rebuild the client pack with each removal applied and verify the pack passes coherence. This is the full parity guarantee but adds significant CI time (one pack build per removed mod, each hitting Modrinth).

**Cost:** Workflow matrix extension + caching strategy for Modrinth lookups. Possibly batched with the existing server smoke to share the resolution cache.

### Recommended order

**R1 + R2 first** (both S, immediate value, no breaking changes). **R3 next** if consumers regularly remove mods. **R4** only if the removal matrix becomes a release-gating concern.
