# In-House Mods

Fabric mods built and maintained as part of this platform, on Minecraft 26.3. Each directory is a standalone Gradle project; `mods/local-mods.manifest` (server) and `mods/client-mods.manifest` (the optional client pack) say which ship in the bundle. This file is what you must know **before** touching mod code; the root [`AGENTS.md`](../AGENTS.md) covers delivery and the rest of the platform.

| Project | What it is | Detail |
| --- | --- | --- |
| `custom-dimensions-26/` | The server mod: definitions compiled to a datapack at start-up, lazy levels, portals and auras, difficulty, exits, deaths and wins, the worldgen firewall and bridges, survey, render and roll | [README](custom-dimensions-26/README.md) |
| `custom-dimensions-client-26/` | The optional companion: live portal previews, and the rig that screenshots a real client | [README](custom-dimensions-client-26/README.md) |

The design of record is [`docs/design/rebuild-26/`](../docs/design/rebuild-26/README.md): decisions (RB-ids), worldgen, schema v2, placeness, the roller, portals and bridges.

## Environment

Java 25 (temurin), pinned by each project's `mise.toml`. Mojang names in source and in the jar; Loom's non-remapping plugin, so a jar carries no refmap.

```bash
cd mods/custom-dimensions-26
mise exec -- ./gradlew build                 # core, compiler and server, unit tests included
```

**Always `mise exec --`** (or a `JAVA_HOME` on Java 25) — it is what applies the toolchain pin ([P4](../TROUBLESHOOTING.md#p4)). On a shared machine, `./gradlew --max-workers=3 build` keeps the build from starving a running server.

## Conventions

- **Modules.** `core` (the definition model and its maths) and `compiler` (definitions to datapack JSON) import nothing from Minecraft and ship nested in the jar; `server` is the Fabric mod. Keep Minecraft types out of `core` and `compiler`.
- **Data before code.** A behaviour 26.x expresses as data (dimension types, environment attributes, timelines, world borders, noise settings) is compiled, not mixed in. Per-release format differences live in the compiler's `VersionTable`.
- **Mixins are the last resort.** At most 12, each with a game test in the self-test; `customdimensions.mixins.json` sets `"defaultRequire": 1`, so a missing target fails the boot. Every mixin, accessor and invoker is listed there: an unlisted accessor hangs the 26.3 boot ([T175](../TROUBLESHOOTING.md#t175)).
- **Server-side only.** Nothing the server sends needs the companion; a vanilla client must see the full game (decisions.md RB-02, RB-25). Polymer carries our blocks.
- **The mod id stays `customdimensions`** and is never published (RB-23).
- **Clean room** (RB-13): never open the files of Terralith, Incendium, Nullscape, Structory, Tectonic, Regions Unexplored, Nature's Spirit, WilderWild or any Stardust Labs mod.

## Threading and lifecycle invariants

- **One level activates at a time** (`LevelManager`'s queue). Concurrent first-time generation wedges the main thread ([K6](../TROUBLESHOOTING.md#k6)).
- **A server-thread task over 60 seconds kills the server** ([T120](../TROUBLESHOOTING.md#t120)). Render, roll and survey run off the thread (`RenderJob`'s pool) and hand results back with `server.execute`.
- **Level work is never continued from a worker thread** — it corrupts the distance manager ([T144](../TROUBLESHOOTING.md#t144)).
- **`ServerLevel.getBlockState` loads the chunk it reads** on 26.x ([T126](../TROUBLESHOOTING.md#t126)); probe loaded chunks only from tick paths.
- **An empty 26.x server stops ticking** ([T119](../TROUBLESHOOTING.md#t119), [T176](../TROUBLESHOOTING.md#t176)); the self-test sets `pause-when-empty-seconds` to 0 while it runs.
- **Our ticket holds a Fantasy level open**; release it or the level never unloads ([T118](../TROUBLESHOOTING.md#t118)).
- **Log counts, not just events.** An "activated" line alone looks healthy in every broken state.

## Verification loop

1. **Build:** `mise exec -- ./gradlew build` in `custom-dimensions-26/` (and `custom-dimensions-client-26/` for the companion).
2. **Self-test:** `./gradlew :server:runServer -PselfTest=/tmp/st.txt` boots 26.3 with the fixture definitions added, runs every section and stops; a line starting `FAIL` is a failure. `-PselfTestOnly=<section>` runs one: `exits`, `commands`, `materials`, `biomes`, `render`, `firewall`, `mixins`, `roll`, `rollperf`, `structures`, `terrain`, `zones`. `server/run/` needs `eula.txt` on first use; its `server.properties` wants `online-mode=false` and `max-tick-time=-1`. The persistence check is two boots: `-PselfTestPersist=write`, then `verify`.
3. **Survey:** on a running dev server, `/customdim survey <slug>` writes the dossier (`customdim-survey/<slug>/dossier.json` and `dossier.md` under the game directory); `/customdim survey <slug> 1 1` is the fast placeness-only form. `custom-dimensions-client-26/scripts/check-targets.py <dossier.json> <targets.json>` checks it against a dimension's targets.
4. **Rig:** `custom-dimensions-client-26/scripts/rig.sh` drives a real 26.3 client under Xvfb: portal variants, or `biomes <shot list>` from `pick-shots.py`. `review-packet.py` puts a dimension's 1.21.1 description, its v2 definition, the dossier and the shots side by side for review.
5. **Stack:** `./dev up` on a linked consumer (root AGENTS.md § In-house mods), then `scripts/smoke.sh` for the boot gate the release runs.

CI: `.github/workflows/mod26.yml` builds, tests and self-tests on every change to either project, its manifest or `config/custom-dimensions/`; `mod26-canary.yml` builds weekly against the newest snapshot.

## Where things live

| Thing | Path |
| --- | --- |
| Definitions (schema v2) and `settings.json` | `config/custom-dimensions/dimensions/` |
| Structure bridges | `config/custom-dimensions/bridges/` |
| Schemas | `custom-dimensions-26/schema/` (`definition`, `bridge`, `biome`) |
| Biome library | `custom-dimensions-26/compiler/src/main/resources/customdimensions/biomes/` |
| Self-test fixtures | `custom-dimensions-26/server/src/selftest/` |
| The 1.21.1 conversion's record | `custom-dimensions-26/definitions/` (`v1/`, `id-mapping.csv`, `conversion-report.md`) |
| Player-facing replies | `config/messages.json` (`customdim.*`) |
