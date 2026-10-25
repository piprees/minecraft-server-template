# custom-dimensions for Minecraft 26.3

The rebuild of `mods/custom-dimensions` on Minecraft 26.3. It stays out of the
release bundle until the `v6.0.0` switch; the plan of record is
[`docs/design/rebuild-26/`](../../docs/design/rebuild-26/README.md).

| Module | What it holds | Minecraft? |
| --- | --- | --- |
| `core` | The definition model and its maths (zones, …) | No |
| `compiler` | Definitions to datapack JSON, using vanilla function types | No |
| `server` | The Fabric mod: runtime levels, portals, rules, the companion feed, the self-test | Yes |

`core` and `compiler` ship nested inside the mod jar (`META-INF/jars/`).

## Build and test

Java 25 (`mise.toml`). From this directory:

```bash
./gradlew build                                   # all modules, unit tests included
./gradlew :server:runServer -PselfTest=/tmp/st.txt  # boot 26.3, run the self-test, stop
```

`server/run/` needs `eula.txt` (`eula=true`) on first use; its
`server.properties` wants `online-mode=false` and `max-tick-time=-1`. The self-test writes
one line per check to the report; a line starting `FAIL` is a failure. It activates and
unloads every lazy dimension in turn, so it runs for a few minutes; it sets
`pause-when-empty-seconds` to 0 while it runs, because an empty server stops ticking.

With `-PselfTest`, `runServer` reads a copy of `definitions/` with the fixture
dimensions in `server/src/selftest/definitions/` added (a void death, a kept
inventory, every win trigger), and each library terrain's `*_default.json` from
`server/src/selftest/gallery/`. The fixtures are never in the release jar.
`-PselfTestOnly=exits` runs only the compiled-pack and exit sections; `-PselfTestOnly=commands` the compiled-pack and command sections; `-PselfTestOnly=terrain` the compiled-pack and terrain sections.

`runServer` also loads Open Parties and Claims and its Forge Config API Port
dependency from the Modrinth maven (`localRuntime`, versions in
`gradle.properties`), so the aura claim veto is exercised; neither is a compile
dependency or nested in the jar.

The persistence check takes two boots of the same world:

```bash
./gradlew :server:runServer -PselfTest=/tmp/st.txt -PselfTestPersist=write   # places a marker, records 8 surface heights, lights a portal, sets its aura budgets
./gradlew :server:runServer -PselfTest=/tmp/st.txt -PselfTestPersist=verify  # reopens the level, compares them, finds the portal, its link and its aura budgets
``` CI runs
both (`.github/workflows/mod26.yml`), and a weekly canary builds against the
newest snapshot (`.github/workflows/mod26-canary.yml`).

## The datapack compiler

`compiler` turns `definitions/` into an ordinary datapack. Vanilla's files are
inputs, never committed: `:compiler:vanillaData` downloads the server jar for
`minecraft_version`, checks its sha1 against Mojang's manifest, and extracts
`data/minecraft/**` and the data generator's reports into
`compiler/build/vanilla/<version>/`.

```bash
./gradlew :compiler:compileDatapack           # build/datapack/customdimensions/ + compile-report.md
scripts/vanilla-boot-check.sh                 # boot that pack on vanilla, no mods
./gradlew :compiler:test -PupdateGolden=true  # rewrite the golden files after an intended change
```

| Output | Where |
| --- | --- |
| Dimension types | `data/adventure/dimension_type/<slug>.json` |
| Noise settings (liquids, default block, `endIsland: false` or a terrain) | `data/adventure/worldgen/noise_settings/<slug>.json` |
| A definition's terrain from the library (`terrain.base`): height, offset, factor, jaggedness, depth, sloped cheese, final density, surface levels, the stretched climate | `data/adventure/worldgen/density_function/terrain/<slug>/*.json` |
| The terrain library's own noises | `data/adventure/worldgen/noise/terrain/<noise>.json` |
| The End's terrain without its central island | `data/adventure/worldgen/density_function/end_without_central_island/{islands,sloped_cheese}.json` |
| Fixed time of day | `data/adventure/timeline/fixed_time/<timeline>_<tick>.json` |
| Base worlds, loaded at start | `data/minecraft/dimension/{overworld,the_nether,the_end}.json`, `data/adventure/dimension/the_aviary.json` |
| Every other dimension, opened lazily by the runtime | `data/customdimensions/lazy_dimension/<slug>.json` |

Per-release format differences live in `VersionTable`; the self-test decodes
every emitted file with the game's own codecs.

### Generators

Every generator starts from the vanilla file of record and changes only what
the definition sets.

| `generator` | Compiles to | Tuning |
| --- | --- | --- |
| `noise`, preset `overworld`, `large_biomes`, `amplified`, `caves`, `floating_islands`, `nether`, `end` | `minecraft:noise` with the preset's noise settings; the overworld family and the Nether filter vanilla's multi-noise list to the definition's `biomes`; the End keeps `minecraft:the_end` | `liquids.seaLevel` → `sea_level`, `liquids.defaultFluid` → `default_fluid`, `materials.defaultBlock` → `default_block` on 26.3 and the last entry of the material rule on 26.4; `endIsland: false` (End only) swaps vanilla's `end/islands` for a copy that keeps only `end_outer_islands` |
| `checkerboard` | overworld noise, `minecraft:checkerboard` source | `cellScale` → `scale`: cells of 2^(cellScale+4) blocks |
| `single_biome` | overworld noise, `minecraft:fixed` source | `biome` |
| `flat` | `minecraft:flat`, no features or lakes | `layers`, `biome` |
| `void` | `minecraft:flat` with no layers | its first vanilla biome, else `minecraft:the_void` |
| `clone` | the generator of the definition `of` names, under the clone's own dimension type, seed and atmosphere | its own `liquids` and `materials` over the source's; its own `biomes` are reported unused |

`terrain` picks a terrain from the library for any noise generator but the
`caves`, `nether` and `end` presets (`compiler/.../terrain/`, one enum constant
per terrain with its parameters, ranges, defaults and `gentle`/`extreme`
settings); the terrains and their parameters are in
[worldgen.md § Terrain library](../../docs/design/rebuild-26/worldgen.md#terrain-library).

A clone's `of` resolves to a slug: `paradise_lost:paradise_lost` to
`the_aviary`, a base world's level stem (`minecraft:the_nether`) to its slug,
and `<namespace>:<slug>` to that definition. A clone of a clone resolves
through the chain; a cycle stops the compile with the chain in the message.
A clone whose `of` no definition provides is reported and not compiled.

The compile report flags every dimension type whose height leaves the range
of the Bedrock dimension Geyser shows it as (`BedrockHeights`): the type's
`skybox` picks it (`none` the Nether, y 0–128, bottom only; `end` the End,
y 0–256; otherwise the Overworld, y -64–320). It is a warning; the pack is
written either way.

The self-test's generator section (`GeneratorSelfTest`, after the runtime-level
checks) opens one shipped definition per variant, force-loads a fixed area,
and reads its heightmaps, blocks and biomes: overworld heights and water,
large biomes against the overworld, amplified heights, the caves ceiling,
floating-island undersides, a clone against The Aviary, the Nether's roof and
lava sea, the End's island and void ring, the flat layer stack, checkerboard
cells, an empty void and the definition whose `liquids.seaLevel` departs
furthest from its preset's. The terrain section (`TerrainSelfTest`, after it) opens each terrain's gallery
fixture at its default setting, scans its base height off the server thread,
force-loads real chunks where the terrain's feature is and measures it (land
and ocean, basin floors, peak rise, terrace treads, canyon cut, river water,
air under sky islands, open water around islands, a void column and its rim,
cracks against continents, spire rise), checks every block lies inside
Bedrock's overworld range, measures each library noise's spread and the cost
per column against the overworld's. `-PterrainGallery=<dir>` adds every
gallery setting and renders each as a greyscale height PNG with an
`index.html`; `-PselfTestOnly=terrain` runs that section alone.
No shipped definition uses `single_biome`, so its
line reads `SKIP` and `GeneratorVariantsTest` covers it.

## Runtime levels

`server/.../level/` opens the dimensions. The four base worlds load at start
from the pack; every lazy stem is opened through Fantasy as a persistent level
`<namespace>:<slug>`, stored in `<world>/dimensions/<namespace>/<slug>/`.

| Piece | What it does |
| --- | --- |
| `PackInstaller` | Compiles the definitions into `<world>/datapacks/customdimensions/` at mod initialisation, before the world loads ([below](#the-pack-at-start-up)) |
| `LevelIndex` | At start, decodes every lazy stem from the server's resources and pairs it with its definition (seed, spawn, borders) |
| `LevelManager` | Activation queue, one at a time (K6); spawn chunk loaded through a ticket, never on the tick; unload and delete polled until the level leaves `levelKeys()`; idle unload after `idleUnloadMinutes` |

`-Pdefinitions=<dir>` points `runServer` at another definitions folder. Definitions are read from `-Dcustomdimensions.definitions`, else from
`config/custom-dimensions/dimensions/` (with its `settings.json`) under the game directory. Each level's
border is `2 × borders.player` around 0,0; a definition without one takes
`settings.json`'s `defaults.borders.player`. Fantasy never writes its level
stems to `world_gen_settings.dat`, so a deleted or unloaded level is not loaded
on the next boot.

### The pack at start-up

The mod compiles its own pack (RB-33). `PackInstaller` reads vanilla's
`data/minecraft/**` from the Minecraft jar the server runs and builds the
multi-noise parameter lists from `MultiNoiseBiomeSourceParameterList.knownPresets()`
with the codec of the data generator's biome-parameters report
(`RuntimeVanilla`); `-Dcustomdimensions.vanilla=<dir>` swaps in an extracted
copy. The pack is compiled at every start-up (about 3 s) and rewritten only
when the result differs from the files on disk, so a compiler change reaches
the pack whatever the mod's version; `inputs.sha256` records the hash of the
definition files, `settings.json`, the mod version and the Minecraft version.
A rewrite that changes a dimension's noise
settings or level-stem generator while its region folder holds chunks logs one
WARN naming [D2](../../TROUBLESHOOTING.md#d2) per dimension. Without a
definitions folder the mod logs one INFO and the world keeps the pack it has.
`runServer` takes this path; the self-test's equivalence line compiles the
definitions from `-Dcustomdimensions.vanilla.reference` (the
`:compiler:vanillaData` folder) and from the running game and compares the
files byte for byte.

### Commands

`/customdim`, permission level 4. Replies are the `customdim.*` templates in
`config/messages.json`. One class per concern in `server/.../command/`.

| Command | What it does |
| --- | --- |
| `list` | Every dimension: slug, state (`base`, `lazy`, `queued`, `opening`, `active`, `unloading`, `deleting`), players in it, loaded chunks |
| `info <dimension>` | Generator (type, preset or clone source), seed, dimension type height, difficulty, peaceful, portals (frame, igniter), exits, wins, borders, state and any Bedrock height warning |
| `tp <dimension> [player]` | To the spawn column's surface (0,0 without a spawn), opening a lazy level first; a level with a ceiling or an empty column keeps the spawn's y. The console names the player |
| `activate <dimension>`, `unload <dimension>` | Open or close a lazy level; a base world is always loaded |
| `delete <dimension> [confirm]` | Without `confirm`, says which level folder would go; with it, `LevelManager.delete`. Base worlds are refused; the definition stays |
| `create <slug> [template]` | Writes `<definitions>/<slug>.json` from the template (default `overworld`) with a fresh seed and one new portal colour, without the template's `spawn`, portal `anchor`s and `wins`; reads it back, compiles it with the other definitions in a temporary folder, and replies that it opens after the next restart (RB-34). The copy keeps the template's frame and igniter |
| `audit` | Writes `<world>/customdimensions/audit.md`: definitions compiled afresh against the pack (stale, missing, in the pack with no definition), level folders with no definition, portal links to unknown dimensions, frame blocks and igniters the server lacks, Bedrock height warnings, skipped biomes and unsupported fields |
| `catalogue` | Writes `../.seed-rolling/catalogue/registries.json` beside the game directory for `scripts/extract-registries.py`: biomes, biome tags, structures, structure sets (placement as the game's codec writes it), blocks, items, features, placed features, entity types, and every tag of those registries with its members |

## Difficulty

`server/.../difficulty/` applies each definition's `difficulty` block over
`settings.json`'s `defaults.difficulty`, read into core's `Difficulty`. Fabric
events only; no mixin.

| Field | What it does |
| --- | --- |
| `mobMultiplier` | A monster-category mob takes `customdimensions:dimension_difficulty` (`add_multiplied_total`, multiplier − 1) the first time it enters any level; 0 or less leaves it unscaled |
| `attributes` | `health`, `damage`, `armor` on unless set false; `speed` (a quarter of the excess) and `knockback` off unless set true |
| `depthScaling` | Multiplies `mobMultiplier` linearly from `minMultiplier` at `startY` (default 64, 1.0) to `maxMultiplier` at `endY` (default −64, 1.5), by the mob's height when it is scaled |
| `playerLuck` | `customdimensions:dimension_luck`, `playerLuck − 1` added to luck, set on join, respawn and every level change, removed in a dimension without it |
| `hostileSpawning: false` | The natural spawner skips the monster category there, and any monster that enters (spawner, egg, command, portal, chunk load) is discarded before it ticks |

A mob is scaled once: the persistent `customdimensions:difficulty_scaled`
attachment marks it, so a chunk reload or a dimension change keeps the scaling
it spawned with (RB-32). A scaled mob starts at full health. Per-dimension spawn
tables are not written: no definition field asks for one, and vanilla's
`gameplay/natural_mob_spawns` attribute cannot make a dimension peaceful (RB-32).

The self-test scales a zombie in the dimension with the highest
`mobMultiplier`, unloads and reloads its chunk, moves it to the overworld, checks
the overworld's depth factor at two heights, runs a peaceful dimension's natural
spawner for 1200 ticks against a 600-tick control with the rule lifted, places a
spawn-egg and a spawner zombie there, and moves a Fabric fake player in and out
to check its luck.

## Portals

`server/.../portal/` runs every portal through one Polymer block,
`customdimensions:portal`, implementing vanilla's `Portal`. Upright on X or Z a
vanilla client sees a Nether portal; flat it sees an End portal. The geometry,
ignition sweep, refusal reasons, arrival-site search, decay and the links file
are game-free in `core/.../portal/`.

| Piece | What it does |
| --- | --- |
| `Ignition` | Fabric's use-block event: igniter on a frame, every definition the item lights a candidate, the closest refusal logged (`portal ignition refused`) |
| `PortalTravel` | The destination: scaled column or anchor, an existing arrival, the far end's own lit portal, or a new arrival at an open or carved site; the way back by exit mode, origin or source column. Every entity crosses; a lazy target opens through `LevelManager` for a player only, and the entity waits in the portal until it is ready (RB-29) |
| `PortalRegistry` | Source and arrival zones and per-cell return targets, saved to `<world>/data/customdimensions/portal_links.json` in the 1.21.1 record format; a world without one reads `config/portal_links.json` |
| `Portals` | Per-level pass: single-use countdown, particles in the portal's colour, frame check (a covered nylium or grass block that decays still counts, T124); a frame break closes both ends |
| `ExitPortals` | `exitPortal`: built near spawn while the level is open, rebuilt when broken |
| `PortalTickets` | Arrival and exit-portal chunk tickets, released before `LevelManager` unloads a level |
| `PortalAuras` | Each end of a link slowly takes on the far side's nature (below) |
| `ClaimsCompat` | Open Parties and Claims' server API by reflection: fail-open, one WARN and off if a lookup throws |

### Auras

The first time a source portal links to an arrival (an existing one, the far
end's own lit portal or a new one), both ends are sampled: the top five solid
blocks, plants, logs as their `worldgen/feature` trees and still fluids in a
9x7x9 box. The source zone keeps the far side's sample and an aura site at the
arrival keeps the source's. An `aura.palette` replaces a sample, and a side
with nothing resident to sample takes its definition's `materials.defaultBlock`.
Nothing loads a chunk to sample.

Passes run from the level's own tick, so a lazy level that is not ticking runs
none and an aura holds no ticket. Every `interval` ticks a pass tries
`blocksPerPass` random cells within `radius` (and 4 below to 5 above), until
the lifetime `budget` is spent: fire at `fireChance`, a fluid only in a hollow
(two units), a configured `aura.trees` feature, a plant, an `aura.conversions`
entry, or the dominant palette block on an exposed solid face. `sides` picks
the ends. Writes use `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE`.

What an aura may replace is the live `subsume` of the dimension whose nature
leaks (the link's target, at both ends): `none` replaces nothing and adds no
fire or fluid, `natural` (the default) spares `#adventure:aura_protected`, and
`everything` replaces anything. Under every policy it never touches a claimed
chunk, the opening and its neighbours (the frame), a block entity, bedrock or a
portal. The tag lists crafted blocks and is checked against the 26.3 block list
by `AuraDataTest`. Budgets and palettes are saved with the portal links every
1200 ticks while they change, on exhaustion and at shutdown; 1.21.1 aura fields
and `aura-site-v1` records load.

The self-test's portal matrix lights a bay for every definition's portal in
the overworld, plus a flat bay where the definition allows one, drops an item
through, follows it back, breaks one frame block and checks both ends closed.
The aura checks stand a portal into a `none`, a `natural` and an `everything`
dimension in a cobblestone box with planks and stone set in it, drive 300
seeded passes each, then repeat it in claimed chunks, and check the frames,
the per-pass and lifetime budgets, link sampling and the palette fallback.

## Exits, deaths and wins

`server/.../exit/` runs a definition's `exits`, `deaths` and `wins` through
Fabric's events; the mod has no mixin for them.

| Piece | What it does |
| --- | --- |
| `ExitConditions` | `exits`: `void` (8 blocks below the floor, from the start of the level's tick), `fallFrom` (`minHeight`, default 100), `enderPearl` (the throw is cancelled and the pearl kept), `death`, `death:<damage type>` and `death:mob:<entity>`, most specific first. `teleport` leaves at once and cancels a death, `respawnAt` moves the respawned player once the target is ready, `kill` keeps vanilla's death. A 100-tick cooldown per player stops loops; sky drops arrive with slow falling |
| `DeathRules` | `deaths.inventory` and `deaths.experience`: kept ones are taken in a late allow-death phase and handed back by the copy-from event; dropped ones fall even with `keepInventory` on. A death a totem will stop, or that another listener cancels, takes nothing |
| `Wins` | Grants `<namespace>:wins/<slug>/<id>` and the root tab, broadcasts the `message` key from `config/messages.json`, sends the player on when `exit` is set. `kill` counts through the advancement's criteria; the other triggers are checked every 10 ticks for players in the level |
| `ExitShrines` (`portal/`) | `exitShrines`: a beacon under a 2x3 frame ring, found on chunk load, registered from the level's tick as a permanent exit zone with the shrine's target, its ring laid in the dimension's frame block; saved with the portal links |
| `ExitHooks` | Registers the events |

Exit targets resolve through `PortalTravel.exitTransition`, the same code
an arrival's exit mode uses. `MessageFile` reads `config/messages.json` under
the server directory once; a missing key or file falls back to built-in
defaults (`portal.no_site`, `win.earned`).

The self-test's exit section uses Fabric's fake player in the fixtures: a void
death with `keepInventory` on drops everything and respawns in
`selftest_wins`; a death in `selftest_keep` keeps the inventory and level; the
void and `death:lava` rules send the player to world spawn; `the_pillared_void`'s
ender-pearl rule reaches `the_tiled_expanse`; a hand-built shrine registers and
leads to the overworld; each of the six win triggers fires once and never
twice. The persistence pass records a shrine and a granted win and finds both
after the restart.

## Companion feed

`server/.../companion/` streams a portal's far side to clients running the
optional companion mod, the only path for rendered previews (RB-03). A vanilla
client is sent nothing from this package: every payload leaves through
`CompanionNetwork.send`, which refuses any player whose hello did not name this
jar's exact version. The choices are game-free in `core/.../companion/`.

| Piece | What it does |
| --- | --- |
| `CompanionNetwork` | Registers the payloads; admits a client whose `Hello` names this release, serves any other as vanilla; holds each companion's declared view |
| `CompanionFeed` | Every 4 ticks, for each companion whose view draws locally: the nearest lit opening within 16 blocks, its `PortalFrame`, its destination chunks nearest-first through the opening, the entities within 48 blocks of the far portal, and a `ProjectionClear` when the companion walks off, the portal breaks, the view is declined or the destination unloads |
| `FeedPlan` (core) | The chunk order: a filled core round the arrival, then the wedge through the opening, capped at the declared render distance |
| `FeedBudget` (core) | Per companion: 4 chunks a pass and a 256 KiB/s byte bucket holding one second; frames, snapshots and clears always go and are charged |
| `EntitySnapshot` (core) | Up to 24 entities nearest the far portal; a snapshot equal to the last at wire resolution sends nothing |

Reads never load a chunk (T126): chunks come from `getChunkNow`, entities from
the entity sections already held. The one load is the pre-load: while a
companion watches, `PortalTickets` holds the arrival's 5x5 chunks (refreshed
every 100 ticks, released before `LevelManager` unloads the level, T118). A lazy
destination opens only for a companion that draws it, once per approach
(RB-29); after an unload it reopens only when the companion leaves and comes
back. A block change reaches viewers through one mixin on
`ServerChunkCache.blockChanged`, which drops that chunk from every viewer's sent
set. The destination's time needs no payload: 26.3's world clocks are
server-wide and vanilla syncs them to every client.

The self-test's companion section lights a portal into an open lazy dimension
and stands a companion, a vanilla player and a mismatched-version companion at
it, each on a listener recording every custom payload. It checks that only the
companion is fed, within the budget; that live payloads round-trip with the
server's registries; that a block changed on the far side is sent again; that
an item crossing is handed over; that walking away,
breaking the frame and unloading the destination each send a clear; that the
pre-load opens a lazy destination only for a declared companion and does not
reopen one unloaded while the companion stays; and that no lazy level is left
active.

## Wire contract

Channel ids and field order match the 1.21.1 mod's. Bodies marked vanilla are
written by the running game's own codec. The bytes of every channel are pinned in
`server/src/test/resources/companion-fixtures/` (`CompanionWireFixturesTest`;
`-PwriteFixtures` rewrites them), and `mods/custom-dimensions-client-26` keeps a
copy its own codecs must decode and re-encode. The release that built each jar is
the protocol version (`Hello`).

| Channel (`customdimensions:`) | Direction | Fields | When |
| --- | --- | --- | --- |
| `handshake/v2` | client to server | `modVersion` string | Once on join |
| `portal-view/v2` | client to server | `renderLocally` bool, `keepSlab` bool, `maxRenderDistance` varint, `viewDepth` varint | On join and on every change; only `renderLocally` true is fed, `keepSlab` is the client's own setting |
| `preloaded/v1` | server to client | `destination` id | Just before a companion crosses into a destination whose arrival is resident |
| `portal-frame/v1` | server to client | `destination` id, `dimensionType` id, `apertureOrigin` pos, `aperture` varint count + pos, `portalAxis` varint, `normal` varint, `dx` `dy` `dz` int, `skyColor` `fogColor` int RGB (-1 unset) | When a watch starts and whenever the frame changes (the viewer crosses to the other side) |
| `destination-chunk/v1` | server to client | `destination` id, vanilla `ClientboundLevelChunkWithLightPacket` | Up to the budget each pass, until the wedge is sent; again after a block in it changes |
| `destination-entities/v1` | server to client | `destination` id, varint count + vanilla `ClientboundAddEntityPacket`, varint count + vanilla `ClientboundSetEntityDataPacket`, varint count + varint departed ids (each at most 256) | The first pass of a watch, then every 4 ticks when anything moved or left |
| `entity-handover/v1` | server to client | `destination` id, `fromId` varint, `tick` varlong, vanilla `ClientboundAddEntityPacket`, varint count (at most 1) + vanilla `ClientboundSetEntityDataPacket` | The tick a non-player entity crosses into a destination the companion is watching |
| `projection-clear/v1` | server to client | `apertureOrigin` pos | When a watch stops |
