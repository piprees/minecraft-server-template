# BlueMap Configuration

BlueMap (the Fabric mod, not the Paper/Spigot plugin) reads its config from
the standard Fabric mod-config convention: `data/config/bluemap/` (mapped
from the mod's own `config/bluemap/` relative to the server root). This is
**not** `data/bluemap/` - that directory holds BlueMap's runtime *data*
(rendered web assets, the downloaded Mojang resource jar, debug logs), not
its config. Auto-generated on first boot if not already present.

Files in `config/bluemap/` here (this directory) are synced into
`data/config/bluemap/` on every deploy via `sync_mod_configs()` in
`scripts/lib.sh` - repo is the source of truth, same pattern as every other
mod config in this project.

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
