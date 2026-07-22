# uNmINeD as the production map renderer (BlueMap replacement)

**Status: feasibility PROVEN locally (2026-07-22).** uNmINeD CLI 0.19.60-dev
rendered elfydd custom dimensions from real world data on the first attempt.

## Why

BlueMap costs a lot of disk (per-map hires+lowres tile trees across ~79 maps),
runs a persistent Java sidecar, and serves dynamic content that can't be fully
edge-cached. We don't use its 3D features. uNmINeD produces **static** web map
tiles (webp) or single PNGs, renders incrementally (changed regions only), and
static output means map.DOMAIN can be served almost entirely from Cloudflare's
edge cache.

## Verified facts (tested on this Mac against elfydd's world)

- Download: `https://unmined.net/download/unmined-cli-linux-x64-dev/` —
  self-contained .NET binaries for linux-x64 glibc AND musl (Alpine), plus
  macOS arm64. ~36 MB. Free for personal use; check the licence page before
  any public/commercial use.
- Supports MC Java 1.0 → 26.1+, and modded worlds (since 0.19.58-dev).
- **Custom dimensions work**: `--dimension adventure:the_gauntlet` rendered
  243 chunks in 0.78 s (313 chunks/s on an M-series Mac) from
  `data/world/dimensions/adventure/the_gauntlet/`.
- Two modules:
  - `unmined-cli image render --world data/world --dimension adventure:<slug>
    --output out.png --trim` → single PNG (format from the extension).
  - `unmined-cli web render --world data/world --dimension adventure:<slug>
    --output web/<slug> --imageformat webp --zoomout 3` → static
    HTML/JS/tile map (~1.1 MB for a tiny test dim). Re-running updates only
    changed regions; `-f` forces a full re-render.
- Useful flags: `--area` (bounds — clamp to `borders.generation`), `--zoomout`
  / `--zoomin`, `--night`, `--players` (player markers), `--background`,
  `--chunkprocessors`, `--shadows`, `--textures`, `--java-client-jar` (use the
  MC client jar for accurate block colours — worth testing for
  Incendium/Paradise Lost blocks), `--mapsettings` (config file per map).
- The web output has a `custom.markers.js` hook — BlueMap sign markers
  (BMSM-Core.json data) could be transpiled into it if we want to keep sign
  markers on the web map.

## Proposed architecture

1. **Render sidecar or idle-tasks step** (musl build in a thin Alpine
   container, or the glibc binary straight on the host): on a schedule (and/or
   when idle-tasks detects the server empty), loop over the base worlds + every
   custom dimension directory found under `data/world/dimensions/<ns>/` and run
   `web render` into `data/unmined-web/<slug>/`. Incremental by default, so
   steady-state cost is only changed regions. Bound each render with `--area`
   from `borders.generation`.
2. **Serving**: nginx (pack-web or nav-proxy) serves `data/unmined-web/` at
   map.DOMAIN with long-lived `Cache-Control` on tiles; the tiny index/JS gets
   a short TTL. A landing page lists dimensions (generated alongside the
   renders). Cloudflare tunnel path-rule as per pack-web. Result: ~zero origin
   traffic once cached; re-renders naturally produce new tile mtimes (use
   `ETag`/`Last-Modified`, or purge selectively if we see staleness).
3. **Retire the bluemap sidecar** once parity is accepted (keep BlueMap
   working until then — it is the live map today). Reclaims its disk and RAM.

## Chunky integration ("send Chunky in once a dimension is visited")

uNmINeD renders only chunks that exist, so coverage = pre-generation:

- The custom-dimensions mod already knows first-visit/world-load
  (`ServerWorldEvents.LOAD`, portal traversal). Cheapest signal that needs NO
  mod change: a dimension directory containing region files under
  `data/world/dimensions/<ns>/<slug>/region/` has been created/visited.
- idle-tasks currently pre-generates base worlds (`.chunky-*-complete`
  markers). Extend it: after base worlds complete, iterate visited custom
  dimensions and run `chunky world <ns>:<slug>` / `chunky center 0 0` /
  `chunky radius <borders.generation / dimension scale>` / `chunky start`,
  one at a time, only while the server is empty (same guard it already uses).
  Write per-dimension `.chunky-<slug>-complete` markers.
- Priority order: base worlds → visited dims by first-visit time. Pocket
  dimensions (512×512) are ~1 min of pre-gen each — effectively free.

## Open questions / next steps

1. Modded block colours: test a render with `--java-client-jar` against
   Incendium/Paradise Lost/Terralith chunks; compare against default
   inference. (Terralith uses vanilla blocks — fine either way.)
2. Nether-style dimensions: check ceiling handling (`--topY`/`--gndxray` for
   cave-map style) for nether-family dims.
3. Licence: confirm "free for personal use" covers a friends' server
   (almost certainly yes; it is not a commercial deployment).
4. Disk/time budget on the droplet: benchmark one full render of the
   overworld at `PREGEN_BORDER_RADIUS` (8192) on the server before committing.
5. Decide sign-marker parity (custom.markers.js generator from BMSM data) or
   drop web markers.
