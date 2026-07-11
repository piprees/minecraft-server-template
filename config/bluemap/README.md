# BlueMap Configuration

BlueMap (the Fabric mod, not the Paper/Spigot plugin) reads its config from the standard Fabric mod-config convention: `data/config/bluemap/` (mapped from the mod's own `config/bluemap/` relative to the server root). This is **not** `data/bluemap/` - that directory holds BlueMap's runtime _data_ (rendered web assets, the downloaded Mojang resource jar, debug logs), not its config. Auto-generated on first boot if not already present.

Files in `config/bluemap/` here (this directory) are synced into `data/config/bluemap/` on every deploy by `deploy.sh` step 8 (before mc starts) - repo is the source of truth, same pattern as every other mod config in this project.

## Where configs live at runtime

```
data/config/bluemap/
  core.conf          # global settings (render threads, webserver, metrics)
  webserver.conf      # HTTP listener (port, address) - not repo-synced yet
  maps/
    overworld.conf    # per-map render settings - not repo-synced yet
    nether.conf
    end.conf           # repo-synced (config/bluemap/maps/world_the_end.conf)
  storages/
    file.conf         # where rendered tiles are stored - not repo-synced yet
```

## Key settings to tweak after first boot

### Webserver port (`webserver.conf`)

The Docker Compose file maps port 8100. If you change it, update both `webserver.conf` and the Compose port mapping:

```hocon
webserver {
    port = 8100
}
```

### Render distance (`maps/overworld.conf`)

- `hiRes.viewDistance`: how many chunks are rendered in full detail. Default 5 is fine for a small server. Increase to 8-10 if you have CPU headroom.
- `lowRes.viewDistance`: how far the low-resolution map extends. Default 200 gives a good overview. Increase to 500 for a wider world map.

```hocon
map "overworld" {
    hiRes {
        viewDistance: 5
    }
    lowRes {
        viewDistance: 200
    }
}
```

### Map quality

- `hiRes.resolution`: pixels per block face. Default 32 is a good balance. Lower to 16 for faster renders, raise to 64 for sharper detail (more disk/CPU).
- `renderThreads` in `core.conf`: defaults to CPU count minus 1. On a shared server, set this to 1-2 to avoid starving the game server.

### Keeping the custom dimensions lazy (`maps/the_*.conf`)

With ~70 `adventure:*` dimensions from `dimensions.txt`, most are never actually visited — BlueMap still detects each one as soon as `custom-dimensions` creates its `ServerWorld` and would otherwise start scanning/watching it immediately. Two settings keep that cost near-zero until a player actually shows up:

- **`render-thread-count: 1`** in `core.conf` — global cap on how many CPU cores BlueMap uses for rendering, regardless of how many maps are configured. Deliberately pinned at 1 for the 4-vCPU production host (BlueMap's own wiki recommends 1 for ≤4-core hosts). Raising this doesn't make individual maps render faster in parallel with the game server — it just gives BlueMap more of the host's CPU, so leave it at 1-2 unless the host has cores to spare.
- **`min-inhabited-time: 3600`** in every custom-dimension `.conf` — filters by the chunk's vanilla `inhabitedTime` (accumulated player-presence ticks, 20/tick-second). Chunks nobody has spent time in are skipped entirely rather than rendered at low priority, so a freshly created dimension with zero player visits costs BlueMap nothing until someone actually plays there. `world.conf`/`world_the_end.conf`/ `world_the_nether.conf`/`paradise_lost.conf` deliberately keep `0` since those are the actively-played dimensions.

If a specific dimension should never be mapped at all (not even after a visit), freeze it explicitly — this persists across restarts, unlike `min-inhabited-time` which re-activates rendering the moment someone visits:

```
/bluemap freeze the_claymarsh
/bluemap unfreeze the_claymarsh   # to resume later
```

`config/bluemap/maps/*.conf` is force-copied over `data/config/bluemap/maps/` on every deploy (see `deploy.sh`), so edits here always win over anything BlueMap wrote to disk at runtime.

## Triggering a re-render

After adding or changing terrain mods (Tectonic, Terralith, etc.), the existing rendered tiles will be stale. Force a full re-render:

```
# In-game (op required)
/bluemap purge overworld
/bluemap force-update overworld

# Or via the web UI controls panel
```

To re-render all maps:

```
/bluemap purge world
/bluemap purge world_nether
/bluemap purge world_the_end
/bluemap force-update
```

Re-renders can take hours depending on explored area and render distance. Monitor progress with `/bluemap` or via the web UI.

## Web access

The BlueMap web UI is tunnelled via Cloudflare and available at:

```
https://map.DOMAIN
```

Replace `DOMAIN` with your actual domain. The tunnel routes to the BlueMap webserver running on port 8100 inside the Docker network.

Internal Docker network address: `http://mc:8100`
