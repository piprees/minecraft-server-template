#!/usr/bin/env bash
# render-loop.sh — scheduled uNmINeD static map renders (unmined-render image)
#
# Context: renders every on-disk dimension to a
# static web map (webp tiles + self-contained OpenLayers viewer) under
# $OUT_DIR/maps/<name>/, plus a generated $OUT_DIR/index.html listing them.
# Output is plain files — served by nav-proxy at map.DOMAIN/unmined/ with
# long edge-cache headers. Renders are incremental: uNmINeD only re-renders
# regions whose .mca files changed, and whole dimensions are skipped when
# nothing changed since the last pass (mtime marker).
#
# Only dimensions with generated chunks reach manifest.json, so the sidebar
# lists where players have actually been.
#
# Sidebar preview cards read a thumbnail this script never renders. The seed
# roller draws a low/high PNG for every candidate it scores, and `web/Picker`
# (the mod's /pick route) copies the chosen candidate's pair beside the
# dimension's own JSON when a seed is picked — committed to git, not generated
# server-side. This script's only job for a card is to publish whatever
# committed PNG exists into $OUT_DIR so nginx can serve it, and record its path
# in manifest.json; a dimension with no picked seed simply has none, and the
# sidebar shows a placeholder frame instead.
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
#   PREGEN_BORDER_RADIUS (8192) — bound for the reserved four; nether uses /8
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
WEBSHELL_DIR="${WEBSHELL_DIR:-/app/webshell}"
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

# Config file for a map name (the reserved four use their v4 config slugs).
config_file_for() {
  case "$1" in
    nether) echo "$CONFIG_DIR/dimensions/the_nether.json" ;;
    end) echo "$CONFIG_DIR/dimensions/the_end.json" ;;
    *) echo "$CONFIG_DIR/dimensions/$1.json" ;;
  esac
}

# The committed thumbnail for a map name, at one of the two sizes Picker
# writes ("low" | "high"). Checked overlay-first, same precedence
# config_file_for's own dimension JSON gets — deploy.sh/dev-up.sh route
# overlay/config/custom-dimensions/ to data/config/custom-dimensions/overlay/
# rather than merging it into dimensions/ directly (the mod resolves
# replace/"overrides"/empty-{} itself), so a picked-but-not-yet-exported
# consumer seed is staged there, not beside the platform default. The file's
# basename always matches config_file_for's own JSON basename (Picker writes
# both under the same dimension slug), so there is one mapping to know, not
# two. Empty output means no committed render exists yet.
thumb_file_for() {
  name="$1" size="$2"
  base="$(basename "$(config_file_for "$name")" .json)"
  for dir in "$CONFIG_DIR/overlay/dimensions" "$CONFIG_DIR/dimensions"; do
    f="$dir/${base}_${size}.png"
    [[ -f "$f" ]] && { echo "$f"; return; }
  done
  return 0
}

# Title-case a slug or namespaced id: strips a leading "namespace:", splits
# on "_", capitalises each word. "terralith:blooming_plateau" -> "Blooming
# Plateau"; "the_wuthering_wisteria" -> "The Wuthering Wisteria".
titlecase_slug() {
  jq -rn --arg s "$1" '$s | split(":") | last | split("_") | map(select(length > 0) | (.[0:1] | ascii_upcase) + .[1:]) | join(" ")'
}

# Display name for a dimension slug. Overrides for names that don't
# mechanically title-case (the reserved four: single words, or a
# deliberately different name from the slug) live in
# config/custom-dimensions/display-names.json, next to the dimension
# configs — not here — so a new override doesn't need a script change.
# Everything else follows the "the_x" slug convention and title-cases
# correctly with no override needed.
display_name() {
  slug="$1"
  overrides="$CONFIG_DIR/display-names.json"
  if [[ -f "$overrides" ]]; then
    named=$(jq -r --arg s "$slug" '.[$s] // empty' "$overrides" 2>/dev/null || true)
    [[ -n "$named" ]] && { echo "$named"; return; }
  fi
  titlecase_slug "$slug"
}

# Friendly label for a dimension's raw "type" config value (see
# custom-dimension-authoring skill's type guide for the full enum). Base
# worlds carry no "type" field at all — their world type is implied by
# which of the four base configs they are, so it's keyed on dim name first.
type_label() {
  name="$1" raw="$2"
  case "$name" in
    overworld) echo "Overworld"; return ;;
    nether)    echo "Nether"; return ;;
    end)       echo "End"; return ;;
    paradise_lost) echo "Paradise Lost skylands"; return ;;
  esac
  case "$raw" in
    multi_biome)                 echo "Curated overworld" ;;
    overworld)                   echo "Overworld" ;;
    nether)                      echo "Nether" ;;
    end)                         echo "End islands" ;;
    cave)                        echo "Cave world" ;;
    void)                        echo "Void" ;;
    sky_islands)                 echo "Sky islands" ;;
    nether_islands)              echo "Nether islands" ;;
    amplified)                   echo "Amplified overworld" ;;
    large_biomes)                echo "Large biomes" ;;
    superflat)                   echo "Superflat" ;;
    paradise_lost:paradise_lost) echo "Paradise Lost skylands" ;;
    single_biome)                echo "Single biome" ;;
    checkerboard)                echo "Checkerboard" ;;
    "")                          echo "Overworld" ;;
    *)                           titlecase_slug "$raw" ;;
  esac
}

# Difficulty bucket from difficulty.mobMultiplier / hostileSpawning — see
# the custom-dimension-authoring skill's size<->difficulty table for the
# philosophy (0.0 peaceful, ~1.0-1.5 standard, 2.5+ brutal). This is a
# display label, not a scoring value — the mod's own difficulty math is
# unaffected by this bucketing.
difficulty_label() {
  cfg="$1"
  jq -r '
    (.difficulty // .overrides.difficulty // {}) as $d
    | ($d.mobMultiplier // 1.0) as $m
    | if ($d.hostileSpawning == false) or ($m <= 0) then "Peaceful"
      elif $m <= 1.0 then "Easy"
      elif $m <= 1.75 then "Normal"
      elif $m <= 2.5 then "Hard"
      else "Deadly"
      end
  ' "$cfg" 2>/dev/null || echo "Normal"
}

# Spawn marker for one map: the dimension's spawn point labelled with its
# name. This file is the dynamic-marker hook — anything may rewrite it
# between renders (structures, POIs, sign data); the shell fetches it with
# ?v=<render stamp> and merges it into the uNmINeD marker layer.
write_markers() {
  name="$1"
  cfg="$(config_file_for "$name")"
  pretty=$(display_name "$name")
  spawn="[0, 64, 0]"
  if [[ -f "$cfg" ]]; then
    s=$(jq -c '(.spawn // .overrides.spawn // empty)' "$cfg" 2>/dev/null || true)
    [[ -n "$s" ]] && spawn="$s"
  fi
  jq -n --arg text "$pretty" --arg img "/maps/$name/custom.pin.png" --argjson spawn "$spawn" '[{
      x: $spawn[0], z: $spawn[2], text: $text,
      image: $img, imageAnchor: [0.5, 1], imageScale: 0.5,
      font: "bold 14px system-ui", textColor: "#ffffff",
      textStrokeColor: "#000000", textStrokeWidth: 3, offsetY: 16
    }]' > "$OUT_DIR/maps/$name/markers.json"
}

# Dimensions a player has entered, written by the custom-dimensions mod
# (visited-dimensions.json in the world dir). Chunky pre-generates the base
# worlds, so region files on disk never meant anyone had been there. No file
# yet, or one that isn't a JSON array — an older mc, a world predating the
# registry, a truncated write — lists everything rather than emptying the map.
dimension_visited() {
  reg="$WORLD_DIR/visited-dimensions.json"
  [[ -f "$reg" ]] || return 0
  jq -e 'type == "array"' "$reg" >/dev/null 2>&1 || return 0
  case "$1" in
    overworld) want="minecraft:overworld" ;;
    nether)    want="minecraft:the_nether" ;;
    end)       want="minecraft:the_end" ;;
    *)         want=":$1" ;;
  esac
  jq -e --arg w "$want" 'any(.[]; . == $w or endswith($w))' "$reg" >/dev/null 2>&1
}

# Emit one manifest entry for a rendered dimension. The version stamp comes
# from the last-render marker and versions every asset URL the shell requests.
manifest_entry() {
  name="$1"
  cfg="$(config_file_for "$name")"
  dim_type="overworld"
  spawn="null"
  mood=""
  spawn_biome_raw=""
  difficulty="Normal"
  if [[ -f "$cfg" ]]; then
    dim_type=$(jq -r '(.type // .overrides.type // "overworld")' "$cfg" 2>/dev/null || echo overworld)
    spawn=$(jq -c '(.spawn // .overrides.spawn // null)' "$cfg" 2>/dev/null || echo null)
    mood=$(jq -r '(.seedRoll.mood // .overrides.seedRoll.mood // empty)' "$cfg" 2>/dev/null || true)
    spawn_biome_raw=$(jq -r '((.seedRoll.spawnFilter // .overrides.seedRoll.spawnFilter // [])[0] // empty)' "$cfg" 2>/dev/null || true)
    difficulty=$(difficulty_label "$cfg")
  fi
  case "$name:$dim_type" in
    nether:*|*:*nether*) family="nether" ;;
    end:*|*:*end*|*:void) family="end" ;;
    *paradise*) family="paradise_lost" ;;
    *) family="overworld" ;;
  esac
  local marker="$OUT_DIR/maps/$name/.last-render"
  local ver
  ver=$(stat -c %Y "$marker" 2>/dev/null || stat -f %m "$marker" 2>/dev/null || echo 0)
  # Published from whatever committed render exists (see thumb_file_for) —
  # never rendered by this container.
  local thumb="" thumb_ver=0
  local low_src; low_src="$(thumb_file_for "$name" low)"
  if [[ -n "$low_src" ]]; then
    mkdir -p "$OUT_DIR/maps/$name"
    cp "$low_src" "$OUT_DIR/maps/$name/thumb.png"
    thumb="/maps/$name/thumb.png"
    thumb_ver=$(stat -c %Y "$low_src" 2>/dev/null || stat -f %m "$low_src" 2>/dev/null || echo 0)
  fi
  local high_src; high_src="$(thumb_file_for "$name" high)"
  [[ -n "$high_src" ]] && cp "$high_src" "$OUT_DIR/maps/$name/thumb_high.png"
  local theme="" spawn_biome=""
  [[ -n "$mood" ]] && theme="$(titlecase_slug "$mood")"
  [[ -n "$spawn_biome_raw" ]] && spawn_biome="$(titlecase_slug "$spawn_biome_raw")"
  jq -n --arg slug "$name" --arg type "$dim_type" --arg family "$family" \
    --argjson spawn "$spawn" --argjson ver "$ver" \
    --arg pretty "$(display_name "$name")" --arg typeLabel "$(type_label "$name" "$dim_type")" \
    --arg difficulty "$difficulty" --arg theme "$theme" --arg spawnBiome "$spawn_biome" \
    --arg thumb "$thumb" --argjson thumbVer "$thumb_ver" \
    '{slug: $slug, name: $pretty, type: $type, typeLabel: $typeLabel, family: $family,
      difficulty: $difficulty, theme: (if $theme == "" then null else $theme end),
      spawnBiome: (if $spawnBiome == "" then null else $spawnBiome end),
      spawn: $spawn, version: $ver, renderedAt: $ver,
      thumb: (if $thumb == "" then null else $thumb end),
      thumbVersion: (if $thumb == "" then null else $thumbVer end)}'
}

# Manifest consumed by the web shell (served no-cache). One entry per rendered
# dimension a player has actually been in, so the sidebar is a record of where
# people have gone rather than of what has been generated.
write_manifest() {
  tmp="$OUT_DIR/.manifest-entries"
  reg="$WORLD_DIR/visited-dimensions.json"
  : > "$tmp"
  for d in "$OUT_DIR"/maps/*/; do
    [[ -f "$d/unmined.map.properties.js" ]] || continue
    name=$(basename "$d")
    dimension_visited "$name" || continue
    manifest_entry "$name" >> "$tmp"
  done
  # Base worlds in progression order, then everything else in the order it was
  # first entered. A dimension the registry doesn't place falls back to name.
  order='[]'
  if [[ -f "$reg" ]] && jq -e 'type == "array"' "$reg" >/dev/null 2>&1; then
    order=$(cat "$reg")
  fi
  jq -s --argjson order "$order" '
      {generated: now | floor,
       dimensions: sort_by(
         ({overworld: 0, nether: 1, end: 2, paradise_lost: 3}[.slug] // 4),
         ( ({overworld: "minecraft:overworld", nether: "minecraft:the_nether",
             end: "minecraft:the_end"}[.slug] // (":" + .slug)) as $want
           | ( [ $order | to_entries[]
                 | select(.value == $want or (.value | endswith($want)))
                 | .key ] | first // 9999 ) ),
         .name)}' \
    "$tmp" > "$OUT_DIR/manifest.json.tmp"
  mv "$OUT_DIR/manifest.json.tmp" "$OUT_DIR/manifest.json"
  rm -f "$tmp"
}

# Install the web shell (index.html/app.js/app.css) and shared uNmINeD
# assets (lib/, unmined.js — identical in every map dir) at the web root.
install_shell() {
  if [[ -d "$WEBSHELL_DIR" ]]; then
    cp "$WEBSHELL_DIR"/index.html "$WEBSHELL_DIR"/app.js "$WEBSHELL_DIR"/app.css "$OUT_DIR/"
  fi
  for d in "$OUT_DIR"/maps/*/; do
    if [[ -d "$d/lib" && -f "$d/unmined.js" ]]; then
      rm -rf "${OUT_DIR:?}/lib"
      cp -r "$d/lib" "$OUT_DIR/lib"
      cp "$d/unmined.js" "$OUT_DIR/unmined.js"
      break
    fi
  done
}

render_all() {
  rendered=0
  # The reserved four. MC's own on-disk layout: region/ (overworld), DIM-1 (nether),
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
  for d in "$OUT_DIR"/maps/*/; do
    [[ -f "$d/unmined.map.properties.js" ]] || continue
    name=$(basename "$d")
    write_markers "$name"
  done
  install_shell
  write_manifest
  # The uNmINeD CLI writes its output 0600/0700; nginx serves this tree as an
  # unprivileged user and answers 403 for anything it cannot read.
  chmod -R a+rX "$OUT_DIR" 2>/dev/null || true
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
