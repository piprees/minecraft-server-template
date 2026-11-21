# P8 — Pre-generation and Distant Horizons on the 26.3 world

Chunky writes real chunks out to each dimension's `borders.generation`; Distant Horizons' `/dh pregen` writes LOD out to `borders.player`. One dimension at a time (K6: concurrent first-time generation wedges the main thread), base worlds first. Run after the `v6.0.0` deploy and the P9 archive, before players are invited.

## The numbers that bound it

- Extent: each definition's `borders` in `config/custom-dimensions/dimensions/<slug>.json`, else `settings.json`'s `defaults.borders`: `player 8192`, `generation 2048` (blocks, a half-width round 0,0).
- Chunky at radius 2,048 blocks, square: (2 × 2048 / 16)² = 65,536 chunks per dimension.
- DH at radius 8,192 blocks: `/dh pregen` takes chunks, so 512.
- Per 1,000 chunks, measured in the rehearsal and written here before the production run:

| Dimension | Chunky time per 1,000 chunks | Disk per 1,000 chunks (region) | DH time per 1,000 chunks | DH disk per 1,000 chunks |
| --- | --- | --- | --- | --- |
| overworld | 14 s on the linked local 26.3 consumer, empty (1,089 chunks in 15 s, mspt P99 1.5) | 16.2 MiB | to be measured in the rehearsal | to be measured in the rehearsal |
| the_nether | to be measured in the rehearsal | to be measured in the rehearsal | to be measured in the rehearsal | to be measured in the rehearsal |
| the_end | to be measured in the rehearsal | to be measured in the rehearsal | to be measured in the rehearsal | to be measured in the rehearsal |
| the_aviary | to be measured in the rehearsal | to be measured in the rehearsal | to be measured in the rehearsal | to be measured in the rehearsal |
| one lazy dimension (the heaviest zone set) | to be measured in the rehearsal | to be measured in the rehearsal | to be measured in the rehearsal | to be measured in the rehearsal |
| the_greenreach, the_ashgrove (lazy, local) | 16 s and 18 s (1,089 chunks in 17 s, mspt P99 58; 19,321 in 5:41) | 16.1 MiB and 22.4 MiB | not measured | not measured |

Measure each with `./ops chunky` (progress and rate), `./ops stats --once` (TPS) and `./ops ssh 'du -sh ~/server/data/world/dimensions/minecraft/overworld/region ~/server/data/world/dimensions/adventure/<slug>/region'` before and after a fixed 1,000-chunk slice. Multiply by 65.5 for one dimension and by the dimension count for the total; stop if the projected total exceeds the free disk `./ops doctor` reports, less the backup headroom (`BACKUP_SIZE_CAP_GIB`).

## Before

1. `./ops doctor` green; `./ops rcon "list"` shows nobody online.
2. `./ops backup` and wait for `snapshot ... saved` in `./ops ssh 'docker logs mc-backup --tail 50'`.
3. Stop the idle runner so two pre-generations never overlap: `./ops ssh 'cd ~/server && docker compose --profile cloud stop idle-tasks'`.
4. Keep the server awake while it runs: `./ops ssh "docker exec mc sh -c 'touch /data/.skip-pause-idle /data/.skip-pause'"` (the itzg autopause honours only `/data/.skip-pause`; `.skip-pause-idle` is idle-tasks' owner file, which stops deploy.sh clearing the aggregate).

## Order

1. `minecraft:overworld`
2. `minecraft:the_nether`
3. `minecraft:the_end`
4. `adventure:the_aviary`
5. The lazy dimensions, in the order players reach them (portals from the overworld first), one at a time.

## Per dimension

For a base world (`<id>` = `minecraft:overworld`, …; `<r>` = its `borders.generation`):

```bash
./ops rcon "chunky world <id>"
./ops rcon "chunky center 0 0"
./ops rcon "chunky radius <r>"
./ops rcon "chunky shape square"
./ops rcon "chunky start"
./ops chunky                      # snapshot; repeat by hand, never in a loop
```

When `./ops chunky` reports the task complete:

```bash
./ops rcon "dh pregen start <id> 0 0 <borders.player / 16>"
./ops rcon "dh pregen status"     # snapshot
```

For a lazy dimension, open it first and keep it open: the mod unloads an empty level after `idleUnloadMinutes` (5, `settings.json`).

```bash
./ops rcon "customdim activate <slug>"
./ops rcon "customdim list"        # state reads active
```

then the same Chunky and DH commands with `<id>` = `adventure:<slug>`. To be confirmed in the rehearsal: that Chunky's tickets keep a lazy level from idling out mid-run (T118 shows our own ticket holds a Fantasy level; Chunky's is a different ticket). If `customdim list` shows it `unloading` during the run, stand a carpet player in it for the duration (`./ops rcon "carpet commandPlayer true"`, then `./ops rcon "player Pregen spawn at 0 200 0 facing 0 0 in adventure:<slug>"`) and `./ops rcon "player Pregen kill"` after.

After each dimension: `./ops stats --once` (TPS back to 20), `./ops ssh 'df -h /'`, then create the completion markers idle-tasks reads so it never repeats the work: `./ops ssh 'touch ~/server/data/.chunky-<id>-complete ~/server/data/.dhpregen-<id>-complete'` with `:` and `/` in `<id>` mapped to `_`.

## After

1. `./ops ssh "docker exec mc sh -c 'rm -f /data/.skip-pause-idle; [ -e /data/.skip-pause-deploying ] || rm -f /data/.skip-pause'"`
2. `./ops ssh 'cd ~/server && docker compose --profile cloud start idle-tasks'`
3. `./ops map render`, then `./ops map status` once the pass ends: the base worlds appear on the map from their region files (D8).
4. `./ops backup`: the first restic snapshot of the pre-generated world.

## Stop rules

- `Can't keep up` with TPS under 15 for more than a few minutes: `./ops rcon "chunky pause"`, check `./ops logs mc --tail 200 --grep ERROR`, resume only once it is understood.
- `Error upgrading chunk`: K1/K6. Stop, snapshot the log, do not restart mc by hand (safety rule 8).
- A player joins: `chunky pause` and `dh pregen stop`; resume when the server is empty.

A lazy level's idle unload starts while a Chunky task runs in it: the unload waits on Chunky's chunk tickets, logs `idle unload of <slug> failed: ... still loaded after 2400 ticks` after two minutes, Chunky finishes every chunk, and the level closes once the task ends. A stage of more than `idleUnloadMinutes` therefore completes, with that warning in the log.
