#!/usr/bin/env bash
# render-loop.sh — scheduled uNmINeD static map renders (unmined-render image)
#
# Context: renders every base world + every on-disk custom dimension to a
# static web map (webp tiles + self-contained OpenLayers viewer) under
# $OUT_DIR/maps/<name>/, plus a generated $OUT_DIR/index.html listing them.
# Output is plain files — served by nav-proxy at map.DOMAIN/unmined/ with
# long edge-cache headers. Renders are incremental: uNmINeD only re-renders
# regions whose .mca files changed, and whole dimensions are skipped when
# nothing changed since the last pass (mtime marker).
#
# Usage (container entrypoint; also runnable standalone for testing):
#   UNMINED_INTERVAL=6h render-loop.sh          # daemon: render every 6h
#   UNMINED_INTERVAL=0  render-loop.sh          # disabled: idle forever
#   RUN_ONCE=1 render-loop.sh                   # single pass, then exit
#
# Env:
#   WORLD_DIR   (/world)  — the server's world directory (read-only)
#   CONFIG_DIR  (/config) — custom-dimensions config dir (read-only);
#                           borders.generation bounds each dimension's render
#   OUT_DIR     (/web)    — output root (tiles + index.html)
#   UNMINED_HOME (/opt/unmined) — CLI install dir (override for local tests)
#   PREGEN_BORDER_RADIUS (8192) — base-world bound; nether uses /8
#   UNMINED_INTERVAL (0)  — sleep between passes (sleep(1) syntax); 0 = off
#   UNMINED_ZOOMOUT  (6)  — zoom-out levels for the web viewer
#
# Gotchas:
#   - Custom dimensions are addressed as <namespace>:<slug>; discovery is
#     from $WORLD_DIR/dimensions/<ns>/<slug>/region/*.mca — a dimension
#     renders only once it has generated chunks (visit it or pre-gen it).
#   - --area=b((x1,z1),(x2,z2)) clamps rendering to the generation border
#     so stray far-out chunks (teleports, locates) don't balloon the map.
#   - The CLI must run from its own directory tree (relative native libs).
set -euo pipefail

WORLD_DIR="${WORLD_DIR:-/world}"
CONFIG_DIR="${CONFIG_DIR:-/config}"
OUT_DIR="${OUT_DIR:-/web}"
UNMINED_HOME="${UNMINED_HOME:-/opt/unmined}"
PREGEN_BORDER_RADIUS="${PREGEN_BORDER_RADIUS:-8192}"
UNMINED_INTERVAL="${UNMINED_INTERVAL:-0}"
UNMINED_ZOOMOUT="${UNMINED_ZOOMOUT:-6}"

log() { printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }

if [[ "$UNMINED_INTERVAL" == "0" ]]; then
  log "unmined-render disabled (UNMINED_INTERVAL=0) — idling"
  exec sleep infinity
fi

# Generation-border radius for a dimension slug: dimensions/<slug>.json
# borders.generation, else settings.json defaults.borders.generation,
# else PREGEN_BORDER_RADIUS. Dimension files may be full configs or
# {"overrides": {...}} consumer patches — check both shapes.
generation_radius() {
  slug="$1"
  f="$CONFIG_DIR/dimensions/$slug.json"
  r=""
  if [[ -f "$f" ]]; then
    r=$(jq -r '(.borders.generation // .overrides.borders.generation // empty)' "$f" 2>/dev/null || true)
  fi
  if [[ -z "$r" && -f "$CONFIG_DIR/settings.json" ]]; then
    r=$(jq -r '.defaults.borders.generation // empty' "$CONFIG_DIR/settings.json" 2>/dev/null || true)
  fi
  [[ -n "$r" ]] && echo "$r" || echo "$PREGEN_BORDER_RADIUS"
}

# One web render, skipped when no region file changed since the last pass.
# args: <map name> <dimension spec> <region dir> <radius>
render_one() {
  name="$1" dim="$2" region_dir="$3" radius="$4"
  out="$OUT_DIR/maps/$name"
  marker="$out/.last-render"

  if ! find "$region_dir" -maxdepth 1 -name '*.mca' -size +8k 2>/dev/null | head -1 | grep -q .; then
    return 1  # no real chunk data yet
  fi
  if [[ -f "$marker" ]] \
      && ! find "$region_dir" -maxdepth 1 -name '*.mca' -newer "$marker" 2>/dev/null | head -1 | grep -q .; then
    log "skip $name (no region changes)"
    return 0
  fi

  mkdir -p "$out"
  log "render $name (dimension=$dim radius=${radius}b)"
  if "$UNMINED_HOME/unmined-cli" web render \
      --world "$WORLD_DIR" \
      --dimension "$dim" \
      --output "$out" \
      --imageformat webp \
      --zoomout "$UNMINED_ZOOMOUT" \
      --area="b((-${radius},-${radius}),(${radius},${radius}))" \
      -c >/dev/null; then
    touch "$marker"
  else
    log "WARN: render failed for $name (leaving previous tiles in place)"
  fi
  return 0
}

# Landing page: one link per rendered map.
write_index() {
  idx="$OUT_DIR/index.html"
  {
    printf '<!doctype html>\n<html lang="en"><meta charset="utf-8">\n'
    printf '<meta name="viewport" content="width=device-width,initial-scale=1">\n'
    printf '<title>World Maps</title>\n'
    printf '<style>body{font-family:system-ui,sans-serif;background:#0c1319;color:#c5cdd8;margin:2rem auto;max-width:40rem;padding:0 1rem}h1{font-size:1.3rem}a{color:#7db3e8;text-decoration:none;display:block;padding:.35rem .5rem;border-radius:6px}a:hover{background:#1c2835}</style>\n'
    printf '<h1>World Maps</h1>\n'
    for d in "$OUT_DIR"/maps/*/; do
      [[ -f "$d/index.html" ]] || continue
      n=$(basename "$d")
      printf '<a href="maps/%s/">%s</a>\n' "$n" "$n"
    done
  } > "$idx.tmp"
  mv "$idx.tmp" "$idx"
}

render_all() {
  rendered=0
  # Base worlds. Vanilla layouts: region/ (overworld), DIM-1 (nether),
  # DIM1 (end). Nether coordinates are 1/8 scale.
  render_one overworld overworld "$WORLD_DIR/region" "$PREGEN_BORDER_RADIUS" && rendered=$((rendered + 1)) || true
  render_one nether nether "$WORLD_DIR/DIM-1/region" "$((PREGEN_BORDER_RADIUS / 8))" && rendered=$((rendered + 1)) || true
  render_one end end "$WORLD_DIR/DIM1/region" "$PREGEN_BORDER_RADIUS" && rendered=$((rendered + 1)) || true

  # Custom dimensions: dimensions/<ns>/<slug>/region
  for nsdir in "$WORLD_DIR"/dimensions/*/; do
    [[ -d "$nsdir" ]] || continue
    ns=$(basename "$nsdir")
    for dimdir in "$nsdir"*/; do
      [[ -d "$dimdir/region" ]] || continue
      slug=$(basename "$dimdir")
      radius=$(generation_radius "$slug")
      render_one "$slug" "$ns:$slug" "$dimdir/region" "$radius" && rendered=$((rendered + 1)) || true
    done
  done
  write_index
  log "pass complete: $rendered map(s) considered"
}

log "unmined-render starting: interval=$UNMINED_INTERVAL zoomout=$UNMINED_ZOOMOUT world=$WORLD_DIR"
# Help exits 1 by design (pipefail would flag it) — capture, then check
# the binary runs and lists its verbs.
help_out=$("$UNMINED_HOME/unmined-cli" image help 2>&1 || true)
case "$help_out" in
  *render*) ;;
  *) log "WARN: unmined-cli self-check failed" ;;
esac

while :; do
  render_all
  if [[ "${RUN_ONCE:-0}" == "1" ]]; then
    log "RUN_ONCE set — exiting after single pass"
    exit 0
  fi
  sleep "$UNMINED_INTERVAL"
done
