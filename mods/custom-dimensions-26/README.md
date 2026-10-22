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
one line per check to the report; a line starting `FAIL` is a failure. CI runs
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
