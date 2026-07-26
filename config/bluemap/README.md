# BlueMap Configuration

BlueMap runs as a **standalone CLI sidecar container** (the `bluemap` service in
`docker-compose.yml`), not as a server mod. It renders the map and serves the
web UI completely decoupled from the mc process: mc boots with zero map work,
and the map stays online while the server is autopaused or restarting.

Consequences of the sidecar model:

- **No `/bluemap` in-game or RCON commands** — manage it with Docker
  (`docker logs bluemap`, `docker restart bluemap`) or `./ops map ...`.
- **No live player markers** and no sign markers (both required the in-process
  mod; accepted trade-off).
- Updates are automatic: the CLI's watch mode (`-u`) picks up region-file
  changes as mc saves them. Nothing needs to "trigger" a render.

## Where things live

The sidecar reads the **same config tree the mod used**, so nothing moved:

```
data/config/bluemap/      # config (this directory is the repo source for it)
  core.conf               # render threads, resource download, data dir
  webapp.conf             # web-app settings (flat view, start position)
  webserver.conf          # HTTP listener (port 8100, Docker network only)
  maps/*.conf             # one file per map - repo-synced on every deploy
data/bluemap/             # runtime data: rendered tiles (web/), resources, logs
```

Container mounts (see `docker-compose.yml`): `/app/config` ← `data/config/bluemap`,
`/app/world` ← `data/world` (read-only), `/app/bluemap` ← `data/bluemap`,
`/app/mods` ← `data/mods` (read-only, so modded blocks render with real
textures). Relative paths in the confs (`data: "bluemap"`, `webroot:
"bluemap/web"`, `world: "world"`) resolve against the CLI's `/app` workdir and
land on the same files the mod used — rendered tiles carry over unchanged.

`config/bluemap/maps/*.conf` is force-copied over `data/config/bluemap/maps/`
on every deploy (`deploy.sh` step 8), so edits in the repo always win. The
sidecar is force-recreated at the end of every deploy, which is when config
changes take effect.

## Key settings

### Render threads (`core.conf`)

`render-thread-count: 1` — deliberately pinned for the 4-vCPU production host.
The sidecar has its own `mem_limit`, but CPU is still shared with mc; leave
this at 1 unless the host has cores to spare. `./ops map render` bumps it
temporarily for force-renders and resets it after.

### Keeping the custom dimensions lazy (`maps/the_*.conf`)

With ~70 `adventure:*` dimensions from `dimensions.txt`, most are never
visited. `min-inhabited-time: 1` in every custom-dimension `.conf` filters
by the chunk's vanilla `inhabitedTime` — chunks no player has ever been near
are skipped entirely, so an unvisited dimension costs the renderer nothing.
`world.conf` / `world_the_nether.conf` / `world_the_end.conf` /
`paradise_lost.conf` deliberately keep `0` since those are actively played.

(The old `/bluemap freeze` mechanism is gone with the mod — `min-inhabited-time`
is now the only laziness lever, and it has proven sufficient.)

### Render bounds (`maps/*.conf`)

`min-x`/`max-x`/`min-z`/`max-z` cap how far each map renders; deploy.sh
templates these from the world-border radius.

## Forcing a re-render

Only needed after texture/terrain-mod changes or tile corruption — normal play
is picked up automatically. From your Mac:

```bash
./ops map render          # all maps, progress streamed to your terminal
./ops map render world    # one map id
./ops map status          # container state + recent render activity
```

Nuclear option for one map: stop the sidecar, delete
`data/bluemap/web/maps/<map>/`, start the sidecar — it re-renders that map
from scratch. Full re-renders can take hours depending on explored area.

## Web access

The web UI is served by the sidecar on port 8100 (Docker network only,
`http://bluemap:8100`), fronted by nav-proxy and tunnelled via Cloudflare:

```
https://map.DOMAIN
```

Because the webserver lives in the sidecar, the map stays up 24/7 — including
while mc is autopaused. If the map is down, check the sidecar
(`docker logs bluemap --tail 30`), never mc.
