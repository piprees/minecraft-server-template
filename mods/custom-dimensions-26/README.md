# custom-dimensions for Minecraft 26.3

The rebuild of `mods/custom-dimensions` on Minecraft 26.3. It stays out of the
release bundle until the `v6.0.0` switch; the plan of record is
[`docs/design/rebuild-26/`](../../docs/design/rebuild-26/README.md).

| Module | What it holds | Minecraft? |
| --- | --- | --- |
| `core` | The definition model and its maths (zones, …) | No |
| `compiler` | Definitions to datapack JSON, using vanilla function types | No |
| `server` | The Fabric mod: runtime levels, portals, rules, the self-test | Yes |

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
| Noise settings (liquids or default block changed) | `data/adventure/worldgen/noise_settings/<slug>.json` |
| Fixed time of day | `data/adventure/timeline/fixed_time/<timeline>_<tick>.json` |
| Base worlds, loaded at start | `data/minecraft/dimension/{overworld,the_nether,the_end}.json`, `data/adventure/dimension/the_aviary.json` |
| Every other dimension, opened lazily by the runtime | `data/customdimensions/lazy_dimension/<slug>.json` |

Per-release format differences live in `VersionTable`; the self-test decodes
every emitted file with the game's own codecs.

## Runtime levels

`server/.../level/` opens the dimensions. The four base worlds load at start
from the pack; every lazy stem is opened through Fantasy as a persistent level
`<namespace>:<slug>`, stored in `<world>/dimensions/<namespace>/<slug>/`.

| Piece | What it does |
| --- | --- |
| `PackInstaller` | With `-Dcustomdimensions.definitions` set, compiles the pack into `<world>/datapacks/customdimensions/` at mod initialisation, before the world loads |
| `LevelIndex` | At start, decodes every lazy stem from the server's resources and pairs it with its definition (seed, spawn, borders) |
| `LevelManager` | Activation queue, one at a time (K6); spawn chunk loaded through a ticket, never on the tick; unload and delete polled until the level leaves `levelKeys()`; idle unload after `idleUnloadMinutes` |
| `LevelCommands` | `/customdim list`, `activate <slug>`, `tp <slug>`, `unload <slug>` (permission level 2) |

`-Pdefinitions=<dir>` points `runServer` at another definitions folder. Definitions are read from `-Dcustomdimensions.definitions`, else from
`config/customdimensions/definitions/` under the game directory. Each level's
border is `2 × borders.player` around 0,0; a definition without one takes
`settings.json`'s `defaults.borders.player`. Fantasy never writes its level
stems to `world_gen_settings.dat`, so a deleted or unloaded level is not loaded
on the next boot.

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

