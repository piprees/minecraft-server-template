#!/usr/bin/env bash
# sync-mods.sh - Download managed mod/datapack files the seed expects but
# data/ lacks, from the seed-resolved CDN URL lists in the stack-mods volume.
#
# Context: MODS_FILE/DATAPACKS_FILE are empty by default (offline boots —
# itzg's per-URL freshness HEAD checks are skipped entirely), so fetching
# missing files is a host-side job that runs between the seed container and
# mc's start. When every file is already present this makes ZERO network
# requests — the normal case for every boot after the mod list last changed.
#
# Usage:
#   sync-mods.sh <data-dir> [stack-mods-volume]
#
#   data-dir           the consumer/server data directory (jars land in
#                      data/mods, datapacks in data/world/datapacks)
#   stack-mods-volume  optional; resolved from the ${CONTAINER_PREFIX:-}seed
#                      container's /out/mods mount when omitted
#
# Called by dev-up.sh (local), deploy.sh step 10b (production), and
# smoke-test.yml (CI). Idempotent. Exits non-zero if any required file
# cannot be fetched — booting without a worldgen mod corrupts chunks, so a
# missing jar must block the boot loudly.
#
# Offline cache: when mods-cache/server/ exists beside the repo root (platform
# checkouts and CI), a matching filename there is copied instead of downloaded,
# and every successful download is copied back into it. Production servers have
# no such directory and fall through to the CDN unchanged. Point MOD_CACHE_DIR
# elsewhere to relocate it, or set it empty to disable. Keep the cache in step
# with the pins using scripts/sync-mod-cache.sh.
#
# Gotchas: URL filenames are percent-encoded (%2B -> +, %20 -> space);
# itzg decodes them and so do we, or every boot re-downloads mismatched
# names. Must run on macOS bash 3.2 - no mapfile, no ${var,,}.
set -euo pipefail

SYNC_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SYNC_SCRIPT_DIR/lib.sh"

DATA_DIR="${1:?usage: sync-mods.sh <data-dir> [stack-mods-volume]}"
VOLUME="${2:-}"

if [[ -z "$VOLUME" ]]; then
  SEED_CONTAINER="${CONTAINER_PREFIX:-}seed"
  VOLUME=$(docker inspect "$SEED_CONTAINER" --format \
    '{{range .Mounts}}{{if eq .Destination "/out/mods"}}{{.Name}}{{end}}{{end}}' 2> /dev/null || true)
  if [[ -z "$VOLUME" ]]; then
    echo "ERROR: could not resolve the stack-mods volume from container '$SEED_CONTAINER'" >&2
    exit 1
  fi
fi

# One volume read for both lists (avoids a second container spin-up).
LISTS=$(docker run --rm -v "${VOLUME}:/m:ro" alpine sh -c \
  'cat /m/mods-urls.txt 2>/dev/null; echo "---DATAPACKS---"; cat /m/datapacks-urls.txt 2>/dev/null')
MOD_URLS="${LISTS%%---DATAPACKS---*}"
DATAPACK_URLS="${LISTS#*---DATAPACKS---}"

if [[ -z "$(echo "$MOD_URLS" | tr -d '[:space:]')" ]]; then
  echo "ERROR: stack-mods volume '$VOLUME' has no mods-urls.txt; did the seed run?" >&2
  exit 1
fi

FETCHED=0
FROM_CACHE=0
FAILED=0

# Offline jar cache. When present, a hit here means zero network for that file.
# Default: mods-cache/server/ beside the repo root this script was run from
# (platform checkouts and CI have it; a consumer/production server does not,
# and simply falls through to the CDN). Override or disable with MOD_CACHE_DIR.
# The shared cache (scripts/lib.sh) is always on and always created, so
# every consumer gets the same warm cache as platform checkouts and CI.
MOD_CACHE_DIR="$(mod_cache_dir)"

fetch_missing() {
  # $1 = newline-separated URL list, $2 = destination directory
  local urls="$1" dest_dir="$2" url name
  mkdir -p "$dest_dir"
  while IFS= read -r url; do
    url="$(echo "$url" | tr -d '[:space:]')"
    [[ -z "$url" ]] && continue
    case "$url" in \#*) continue ;; esac
    name="${url##*/}"
    # Percent-decode to itzg's on-disk filename (%2B -> +, %20 -> space).
    name=$(printf '%b' "${name//%/\\x}")
    [[ -f "$dest_dir/$name" ]] && continue
    # Cache hit: copy, don't download. Filenames in the cache are the same
    # percent-decoded CDN basenames computed above, so this is an exact match.
    if [[ -n "$MOD_CACHE_DIR" && -s "$MOD_CACHE_DIR/$name" ]]; then
      cp "$MOD_CACHE_DIR/$name" "$dest_dir/$name"
      echo "  cached:  $name"
      FROM_CACHE=$((FROM_CACHE + 1))
      continue
    fi
    if curl -fsSL --retry 3 --retry-delay 2 --max-time 120 \
      -o "$dest_dir/$name.part" "$url" && [[ -s "$dest_dir/$name.part" ]]; then
      mv "$dest_dir/$name.part" "$dest_dir/$name"
      echo "  fetched: $name"
      FETCHED=$((FETCHED + 1))
      # Seed the cache so the next machine/run doesn't need the CDN either.
      # Only when the cache already exists - never create it implicitly.
      [[ -n "$MOD_CACHE_DIR" ]] && cp "$dest_dir/$name" "$MOD_CACHE_DIR/$name" 2> /dev/null || true
    else
      rm -f "$dest_dir/$name.part"
      echo "  FAILED: $name ($url)" >&2
      FAILED=$((FAILED + 1))
    fi
  done <<< "$urls"
}

fetch_missing "$MOD_URLS" "$DATA_DIR/mods"
fetch_missing "$DATAPACK_URLS" "$DATA_DIR/world/datapacks"

if [[ $FAILED -gt 0 ]]; then
  echo "ERROR: $FAILED managed file(s) could not be downloaded - refusing to boot without them" >&2
  exit 1
fi
if [[ $FROM_CACHE -gt 0 ]]; then
  echo "  Restored $FROM_CACHE file(s) from the offline cache"
fi
if [[ $FETCHED -gt 0 ]]; then
  echo "  Downloaded $FETCHED missing managed file(s)"
else
  echo "  All managed mods/datapacks present - no network needed"
fi
