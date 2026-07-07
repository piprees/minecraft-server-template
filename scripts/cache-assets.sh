#!/usr/bin/env bash
# cache-assets.sh - Snapshot images, mods, and client bundles for offline use.
# Run after a successful boot + build-modpack.sh.
#
# Usage:
#   ./scripts/cache-assets.sh              # cache everything
#   ./scripts/cache-assets.sh --images     # Docker images only
#   ./scripts/cache-assets.sh --mods       # server + client mods only
#   ./scripts/cache-assets.sh --client     # client bundle only
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$PROJECT_DIR"

# --- load .env ----------------------------------------------------------------
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

MC_VERSION="${MC_VERSION:-1.21.1}"
PACK_VERSION="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
PACK_NAME="${BRAND_SLUG:-adventure}-${MC_VERSION}-v${PACK_VERSION}"

CACHE_DIR="$PROJECT_DIR/cache"
IMAGE_CACHE="$CACHE_DIR/images"
SERVER_MOD_CACHE="$CACHE_DIR/server-mods"
CLIENT_MOD_CACHE="$CACHE_DIR/client-mods"
CLIENT_BUNDLE_DIR="$CACHE_DIR/client-bundle"

# Compose file: bundle copy when running from a stack bundle, else the
# platform checkout's. Consumers have no docker-compose.yml of their own.
COMPOSE_SRC="$SCRIPT_DIR/../docker-compose.yml"
[[ -f "$COMPOSE_SRC" ]] || COMPOSE_SRC="$PROJECT_DIR/docker-compose.yml"

# Pack manifest: platform checkouts have it in modpack/; consumers extract
# it from the modpack-builder image (where it is baked as the default).
MANIFEST="$PROJECT_DIR/modpack/adventure.mrpack.json"
if [[ ! -f "$MANIFEST" ]]; then
  MANIFEST="$CACHE_DIR/.manifest.json"
  mkdir -p "$CACHE_DIR"
  docker run --rm --entrypoint cat \
    "${IMAGE_REGISTRY:-ghcr.io/piprees/minecraft-server-template}/modpack-builder:${IMAGE_TAG:-latest}" \
    /defaults/manifest.json > "$MANIFEST" 2> /dev/null || rm -f "$MANIFEST"
fi

# --- parse flags --------------------------------------------------------------
DO_IMAGES=0
DO_MODS=0
DO_CLIENT=0

if [[ $# -eq 0 ]]; then
  DO_IMAGES=1
  DO_MODS=1
  DO_CLIENT=1
else
  for arg in "$@"; do
    case "$arg" in
      --images) DO_IMAGES=1 ;;
      --mods) DO_MODS=1 ;;
      --client) DO_CLIENT=1 ;;
      *)
        echo "Unknown flag: $arg"
        exit 1
        ;;
    esac
  done
fi

# =============================================================================
# 1. Docker image cache
# =============================================================================
if [[ $DO_IMAGES -eq 1 ]]; then
  echo "=== 1. Caching Docker images ==="
  mkdir -p "$IMAGE_CACHE"

  # Extract pinned image tags from the compose file. No mapfile - this
  # must run on macOS bash 3.2.
  # Expand the compose file's ${IMAGE_REGISTRY:-...}/${IMAGE_TAG:-...}
  # interpolations - grep returns them literally, and docker can't pull a
  # literal "${...}" reference (those images silently never got cached).
  REG="${IMAGE_REGISTRY:-ghcr.io/piprees/minecraft-server-template}"
  TAG="${IMAGE_TAG:-latest}"
  IMAGES=()
  while IFS= read -r line; do
    [[ -n "$line" ]] && IMAGES+=("$line")
  done < <(grep -E '^\s*image:' "$COMPOSE_SRC" 2> /dev/null \
    | sed -E 's/.*image:[[:space:]]*//' | tr -d '"' | tr -d "'" \
    | sed -e "s|\${IMAGE_REGISTRY:-[^}]*}|${REG}|" -e "s|\${IMAGE_TAG:-[^}]*}|${TAG}|" \
    | sort -u)

  # Fallback if grep found nothing
  if [[ ${#IMAGES[@]} -eq 0 ]]; then
    IMAGES=(
      "itzg/minecraft-server:2026.6.1-java21"
      "itzg/mc-backup:2026.6.2"
      "louislam/uptime-kuma:1.23.17"
      "cloudflare/cloudflared:2026.6.1"
      "nginx:1.27-alpine"
      "python:3.14-alpine"
      "alpine:3.24"
    )
  fi

  for img in "${IMAGES[@]}"; do
    safe_name="${img//\//-}"
    safe_name="${safe_name//:/-}"
    tarball="$IMAGE_CACHE/${safe_name}.tar"

    # Pull if not present locally
    img_id=$(docker image inspect "$img" --format '{{.Id}}' 2>/dev/null || true)
    if [[ -z "$img_id" ]]; then
      echo "  Pulling $img..."
      docker pull "$img" 2>/dev/null || { echo "  ⚠ Failed to pull $img"; continue; }
      img_id=$(docker image inspect "$img" --format '{{.Id}}' 2>/dev/null || true)
    fi

    # Store the image ID alongside the tarball for staleness checks
    id_file="$IMAGE_CACHE/${safe_name}.id"
    cached_id=""
    [[ -f "$id_file" ]] && cached_id=$(cat "$id_file")

    if [[ "$img_id" == "$cached_id" && -f "$tarball" ]]; then
      echo "  ✓ $img - already cached (unchanged)"
      continue
    fi

    echo "  Saving $img > $(basename "$tarball")"
    docker save "$img" -o "$tarball"
    echo "$img_id" > "$id_file"
    echo "    $(du -h "$tarball" | cut -f1) saved"
  done

  total_size=$(du -sh "$IMAGE_CACHE" 2> /dev/null | cut -f1)
  echo "  Image cache total: ${total_size}"
  echo ""
fi

# =============================================================================
# 2. Server mod cache
# =============================================================================
if [[ $DO_MODS -eq 1 ]]; then
  echo "=== 2. Caching server mod JARs ==="
  mkdir -p "$SERVER_MOD_CACHE"

  if [[ -d "$PROJECT_DIR/data/mods" ]] && ls "$PROJECT_DIR/data/mods/"*.jar &> /dev/null 2>&1; then
    # Sync mods - copy new/changed, preserve existing (don't delete old
    # versions yet, in case you want to roll back)
    count_before=$(ls "$SERVER_MOD_CACHE/"*.jar 2> /dev/null | wc -l | tr -d " " | xargs || echo 0)
    rsync -a --ignore-existing "$PROJECT_DIR/data/mods/"*.jar "$SERVER_MOD_CACHE/"

    count_after=$(ls "$SERVER_MOD_CACHE/"*.jar 2> /dev/null | wc -l | tr -d " " | xargs || echo 0)
    count_new=$((count_after - count_before))

    echo "  ✓ $(ls "$SERVER_MOD_CACHE/"*.jar | wc -l | tr -d " ") JARs cached"
    [[ $count_new -gt 0 ]] && echo "    ($count_new new)"

    total_size=$(du -sh "$SERVER_MOD_CACHE" 2> /dev/null | cut -f1)
    echo "  Server mod cache: ${total_size}"
  else
    echo "  ⚠ No mod JARs found in data/mods/"
    echo "    Boot the server first so itzg downloads the mods, then re-run."
  fi
  echo ""
fi

# =============================================================================
# 3. Client mod cache + offline bundles
# =============================================================================
if [[ $DO_CLIENT -eq 1 ]]; then
  echo "=== 3. Caching client mods & building offline bundles ==="

  if [[ ! -f "$MANIFEST" ]]; then
    echo "  ⚠ Manifest not found at $MANIFEST - skipping client cache."
  elif ! command -v python3 &> /dev/null; then
    echo "  ⚠ python3 required for client mod resolution - skipping."
  else
    mkdir -p "$CLIENT_MOD_CACHE" "$CLIENT_BUNDLE_DIR"

    # --- 3a. Download all client mod JARs from Modrinth ---
    echo "  Resolving and downloading client mod JARs..."

    # Extract all client mod slugs (required + optional)
    ALL_CLIENT_MODS=$(python3 -c "
import json
with open('$MANIFEST') as f:
    data = json.load(f)
cm = data.get('_clientMods', {})
for m in cm.get('required', []) + cm.get('optional', []):
    print(m)
")

    downloaded=0
    skipped=0
    failed=0
    updated=0

    while IFS= read -r slug; do
      [[ -z "$slug" ]] && continue

      # Resolve the latest version from Modrinth
      sleep 0.5
      MODRINTH_TMP=$(mktemp)
      curl -s --max-time 10 \
        "https://api.modrinth.com/v2/project/${slug}/version?game_versions=%5B%22${MC_VERSION}%22%5D&loaders=%5B%22fabric%22%5D" \
        -H "User-Agent: minecraft-adventure-server/cache-assets" \
        -o "$MODRINTH_TMP" 2>/dev/null || echo "[]" > "$MODRINTH_TMP"

      dl_info=$(python3 -c "
import json, sys
try:
    versions = json.load(open(sys.argv[1]))
    if not versions:
        sys.exit(1)
    v = versions[0]
    for f in v.get('files', []):
        if f.get('primary', False):
            print(f['url'])
            print(f['filename'])
            print(f['hashes'].get('sha512', ''))
            break
except:
    sys.exit(1)
" "$MODRINTH_TMP" 2>/dev/null) || true
      rm -f "$MODRINTH_TMP"

      if [[ -z "$dl_info" ]]; then
        echo "    ⚠ $slug - no ${MC_VERSION} Fabric build"
        failed=$((failed + 1))
        continue
      fi

      url=$(echo "$dl_info" | sed -n '1p')
      filename=$(echo "$dl_info" | sed -n '2p')
      want_hash=$(echo "$dl_info" | sed -n '3p')

      # Check if we already have this exact version (by sha512)
      if [[ -f "$CLIENT_MOD_CACHE/$filename" && -n "$want_hash" ]]; then
        got_hash=$(shasum -a 512 "$CLIENT_MOD_CACHE/$filename" | cut -d' ' -f1)
        if [[ "$got_hash" == "$want_hash" ]]; then
          skipped=$((skipped + 1))
          continue
        fi
      fi

      # Remove old versions of this mod (different filename = old version)
      for old in "$CLIENT_MOD_CACHE/${slug}"*.jar; do
        [[ -f "$old" && "$(basename "$old")" != "$filename" ]] && rm -f "$old"
      done

      if curl -sL --max-time 60 -o "$CLIENT_MOD_CACHE/$filename" "$url"; then
        if [[ -n "$want_hash" ]]; then
          got_hash=$(shasum -a 512 "$CLIENT_MOD_CACHE/$filename" | cut -d' ' -f1)
          if [[ "$got_hash" != "$want_hash" ]]; then
            echo "    ✗ $slug - hash mismatch after download"
            rm -f "$CLIENT_MOD_CACHE/$filename"
            failed=$((failed + 1))
            continue
          fi
        fi
        echo "    ✓ $slug > $filename"
        downloaded=$((downloaded + 1))
      else
        echo "    ✗ $slug - download failed"
        rm -f "$CLIENT_MOD_CACHE/$filename"
        failed=$((failed + 1))
      fi
    done <<< "$ALL_CLIENT_MODS"

    total_client=$(ls "$CLIENT_MOD_CACHE/"*.jar 2> /dev/null | wc -l | tr -d " " || echo 0)
    echo "  Client mods: $total_client cached ($downloaded new, $skipped unchanged, $failed failed)"
    echo ""

    # --- 3b. Build offline .mrpack (JARs in overrides/mods/) ---
    echo "  Building offline .mrpack..."
    WORK_DIR=$(mktemp -d)
    mkdir -p "$WORK_DIR/overrides/mods"

    # Copy all cached client mod JARs into overrides/mods/
    if ls "$CLIENT_MOD_CACHE/"*.jar &> /dev/null 2>&1; then
      cp "$CLIENT_MOD_CACHE/"*.jar "$WORK_DIR/overrides/mods/"
    fi

    # Write a minimal modrinth.index.json (no download URLs needed -
    # the JARs are baked into the pack via overrides/)
    FABRIC_LOADER_VERSION=$(docker exec mc cat /data/fabric-loader-version 2> /dev/null \
      || curl -s "https://meta.fabricmc.net/v2/versions/loader" | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['version'])" 2> /dev/null \
      || echo "0.16.14")

    cat > "$WORK_DIR/modrinth.index.json" << EOF
{
  "formatVersion": 1,
  "game": "minecraft",
  "versionId": "${PACK_NAME}-offline",
  "name": "Adventure Server Modpack (Offline)",
  "summary": "Private modded Minecraft ${MC_VERSION}. All mod JARs included - no network needed to install.",
  "files": [],
  "dependencies": {
    "minecraft": "${MC_VERSION}",
    "fabric-loader": "${FABRIC_LOADER_VERSION}"
  }
}
EOF

    OFFLINE_MRPACK="$CLIENT_BUNDLE_DIR/${PACK_NAME}-offline.mrpack"
    (cd "$WORK_DIR" && zip -r "$OFFLINE_MRPACK" modrinth.index.json overrides/)
    echo "  ✓ $OFFLINE_MRPACK"

    # --- 3c. Build USB-stick ZIP (plain mods/ folder + README) ---
    echo "  Building USB-stick bundle..."

    USB_WORK=$(mktemp -d)
    USB_MODS="$USB_WORK/${BRAND_SLUG:-adventure}-mods"
    mkdir -p "$USB_MODS/mods"

    if ls "$CLIENT_MOD_CACHE/"*.jar &> /dev/null 2>&1; then
      cp "$CLIENT_MOD_CACHE/"*.jar "$USB_MODS/mods/"
    fi

    cat > "$USB_MODS/HOW-TO-INSTALL.txt" << 'TXTEOF'
Adventure Server - Offline Modpack Installation
================================================

This folder contains all the mod files you need. No internet required.

STEPS:

1. Install Minecraft Java Edition (if you haven't already).

2. Install Fabric Loader for Minecraft 1.21.1:
   - Go to https://fabricmc.net/use/installer/
   - Download the Fabric Installer
   - Run it, select "Minecraft 1.21.1" and "Fabric Loader"
   - Click Install

3. Copy the mods:
   - Open your Minecraft folder:
     - Windows: press Win+R, type %appdata%\.minecraft, press Enter
     - Mac: open Finder, press Cmd+Shift+G, type ~/Library/Application Support/minecraft
     - Linux: open ~/.minecraft
   - If there's already a "mods" folder, back it up first (rename it to "mods-old")
   - Copy the "mods" folder from this USB stick into your .minecraft folder

4. Launch Minecraft:
   - Open the Minecraft launcher
   - Select the "fabric-loader-1.21.1" profile
   - Click Play

5. Connect to the server:
   - Multiplayer > Add Server
   - Server address: (ask the server admin for the address)

That's it! If a mod causes problems, the admin will tell you which file
to remove from the mods folder.
TXTEOF

    USB_ZIP="$CLIENT_BUNDLE_DIR/${PACK_NAME}-usb-bundle.zip"
    (cd "$USB_WORK" && zip -r "$USB_ZIP" ${BRAND_SLUG:-adventure}-mods/)
    echo "  ✓ $USB_ZIP"

    # Clean up temp dirs
    rm -rf "$WORK_DIR" "$USB_WORK"

    # Sizes
    echo ""
    client_cache_size=$(du -sh "$CLIENT_MOD_CACHE" 2> /dev/null | cut -f1)
    mrpack_size=$(du -h "$OFFLINE_MRPACK" 2> /dev/null | cut -f1)
    usb_size=$(du -h "$USB_ZIP" 2> /dev/null | cut -f1)
    echo "  Client mod cache: ${client_cache_size}"
    echo "  Offline .mrpack:  ${mrpack_size}"
    echo "  USB bundle:       ${usb_size}"
  fi
  echo ""
fi

# =============================================================================
# Summary
# =============================================================================
total_cache_size=$(du -sh "$CACHE_DIR" 2> /dev/null | cut -f1)

echo "=================================================================="
echo " Asset cache complete."
echo ""
echo " Cache location: $CACHE_DIR"
echo " Total size:     ${total_cache_size}"
echo ""
if [[ $DO_IMAGES -eq 1 ]]; then
  echo " Docker images:  cache/images/"
  echo "   Restore with: for f in cache/images/*.tar; do docker load -i \"\$f\"; done"
  echo "   Or use: ./scripts/dev-up.sh --offline (loads automatically)"
fi
if [[ $DO_MODS -eq 1 ]]; then
  echo ""
  echo " Server mods:    cache/server-mods/"
  echo "   Pre-seed with: cp cache/server-mods/*.jar data/mods/"
fi
if [[ $DO_CLIENT -eq 1 ]]; then
  echo ""
  echo " Client bundles: cache/client-bundle/"
  echo "   Offline .mrpack - import into Prism/MultiMC/Modrinth App (no network needed)"
  echo "   USB bundle      - hand to friends on a USB stick with HOW-TO-INSTALL.txt"
fi
echo "=================================================================="
