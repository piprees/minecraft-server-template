# Explorify on 26.3

Explorify is the one structure mod of the 24 the register holds as waiting that has a 26.3 Fabric build. This is the [dependency checklist](../../../../DEPENDENCIES.md#adding-a-mod) run as far as it goes without a boot. The mod list and the register are unchanged; the steps to change them are at the end.

## The build

Queried from the Modrinth API (`/v2/project/explorify`, `/v2/project/explorify/version?game_versions=["26.3"]&loaders=["fabric"]`).

| Item | Value |
| --- | --- |
| Project | `explorify` (id `HSfsxuTo`), "A simplistic, vanilla-friendly collection of new structures" |
| Version | `v1.6.6` (mod), version id `oyrAnNG8`, release channel |
| Published | 2026-11-08: past `min_age_days` (3) in `.github/dep-review/policy.json` |
| Loaders | Fabric, Forge, NeoForge in one jar; a separate datapack build of the same version exists |
| Game versions | 24 listed, among them 26.1, 26.1.1, 26.1.2, 26.2 and 26.3 |
| File | `Explorify v1.6.6.mod.jar`, 775,401 bytes, SHA-512 beginning `d4368c83b493c6fc` |
| The pack's 1.21.1 pin | `explorify:CuBdAr31` in `config/modrinth-mods.txt` |

## Licence

`LicenseRef-All-Rights-Reserved`, as the kept ATi Structures, Epic Structures and Dungeons and Taverns are. The pack never redistributes a jar: the seed downloads it from Modrinth. It is not a Stardust Labs mod, so the clean-room rule (RB-13) does not bar it; RB-13 still bars copying its builds into set pieces.

## Side

Modrinth: server required, client optional. It belongs on the server list only, with nothing in `_clientMods`. The project describes itself as a datapack in a mod jar: structures, structure sets, template pools, processors and loot tables, all vanilla blocks (the register's own reason for welcoming it).

## Dependencies

| Dependency | Source | In the 26.3 list |
| --- | --- | --- |
| Fabric API | The project description ("Fabric mod version requires Fabric API"); the version's Modrinth `dependencies` is empty | Yes, `fabric-api:EQKgpeKu` |
| Cristel Lib | Optional, for a config | Yes, `cristel-lib:rWxmPrGC` |

## What it registers

Read from our own records, not the jar (the jar's own list comes from the bridge tool, step 3 below).

| Record | Explorify entries |
| --- | --- |
| `structure_themes.json` | 14 sets: `badlands_pyramids`, `black_spirals`, `campsites`, `dark_forest_settlements`, `desert_shrines`, `end_shipwrecks`, `farmsteads`, `guide_posts`, `mangrove_huts`, `mausoleums`, `ruins`, `supply_caches`, `taverns`, `watchtowers` |
| `structure_aliases.json` | 13 structures: `badlands_pyramid`, `black_spiral`, `campsite`, `dark_forest_settlement`, `desert_shrine`, `farmstead`, `guide_post_cold`, `guide_post_warm`, `mangrove_hut`, `mausoleum`, `ruins`, `supply_cache/desert`, `watchtower/plains` |
| `definitions/id-mapping.csv` | 13 rows, each with a 26.3 stand-in; three are weak: `farmstead` (a ruin stands in for a living holdout), `guide_post_warm` and `guide_post_cold` (lanterns stand in for signposts) |

28 of the 82 dimensions name an explorify structure in their 1.21.1 config: overworld, the_amplified_reaches, the_blossom_gardens, the_chalk_meadows, the_claymarsh, the_crystal_vale, the_darkpine_depths, the_dustbowl, the_endless_expanse, the_frozen_hearth, the_frozen_strait, the_greenreach, the_greywoods, the_highland_crossing, the_lantern_pools, the_miredeep, the_needlefall, the_rosebluff, the_ruined_timberland, the_scorched_mesa, the_shattered_skies, the_snowbound_isle, the_souldrift, the_sun_kingdoms, the_verdant_hollow, the_whispering_wilds, the_whitestone_ford, the_wuthering_wisteria. The most named are `campsite` (13), `guide_post_warm` (9), `farmstead` (8) and `watchtower/plains` (6).

## What a boot must answer

1. Does the jar add an entry to any synced registry? The smoke-test-26 join gate (an unmodded 26.3 client joins and stays 60 s) answers it; a datapack-only mod should pass.
2. The firewall switches off every structure set outside the allowed namespaces until a reviewed bridge adopts it, so the mod places nothing until step 3 lands.
3. Whether Cristel Lib's placement config for explorify fights our structure control. On 1.21.1 Cristel Lib owned explorify's placement (`scripts/gen-structure-presets.py`); on 26.3 structure control places every bridged set, so its Cristel config must be left at defaults.

## Steps to add it

1. **Mod list.** Add `explorify:oyrAnNG8` to `config/modrinth-mods-26.3.txt` under `# === boss dungeons & extra structures ===`, with a comment block above the line: a structure mod, vanilla blocks only, placed by our structure control through its bridge, chunk-zero, ships in a major. Pin with `./scripts/pin-mod-versions.sh --version 26.3 --apply`. That section is in `never_automerge_sections`; the 26.3 list's `# === structures ===` header matches none of them (they name the 1.21.1 list's `structures (need yungs-api)`).
2. **Register.** `docs/design/rebuild-26/mod-register.csv`: the `explorify` row becomes `kept`, `why` the kept structure rows' wording, `build_26_3` `v1.6.6`; `namespaces.csv`: `explorify` becomes `kept`.
3. **Bridge.** `./gradlew :core:bridge -Pjar=<the jar> -Pout=<dir> -Pmod=explorify`; review it (drop `end_shipwrecks` if End dimensions should not take it, tag `mood`, `climate`, `depth`), set `"reviewed": true`, copy it to `mods/custom-dimensions-26/bridges/explorify.json`; add its ids to `core/src/test/resources/firewall/unbridged-26.3.txt` so `ShippedBridgesTest` covers it.
4. **Sizes.** `-PmeasureStructures` refreshes `structure-sizes.json` with the explorify structures.
5. **Stand-ins.** For each of the 13 `id-mapping.csv` rows, keep the stand-in or restore the explorify id (owner's decision, README); re-run the converter so the definitions name the chosen ids.
6. **Checks.** `./gradlew build` (bridge and compiler tests), the structures self-test section, and `smoke-test-26.yml`'s join gate.
7. **Release.** A structure mod is worldgen: it ships in a major, here the `v6.0.0` switch.
