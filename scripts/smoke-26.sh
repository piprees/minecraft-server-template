#!/usr/bin/env bash
# smoke-26.sh - Boot the 26.3 stack from this checkout and check its boot log.
#
# Context: template-only; smoke-test-26.yml runs it, and it runs the same way
# on a workstation with Docker. The version comes from MC_VERSION_OVERRIDE
# (default 26.3), never from .minecraft-version, so the committed version and
# every 1.21.1 output stay as they are. The stack is the real one: the seed
# image built from docker/defaults-seed with the override as a build argument,
# the itzg image and Fabric loader from minecraft-versions.tsv, every mod of
# the version's list resolved by the seed and fetched by sync-mods.sh (T4),
# the version's config overlay (version_config_files) over the base config,
# and the in-house mods of the version's manifest (local-mods.sh). The 26.3
# mod reads v2 definitions from config/custom-dimensions/dimensions/, so the
# v2 set in mods/custom-dimensions-26/definitions/ replaces the 1.21.1 folder
# there, and mods/custom-dimensions-26/bridges/ fills
# config/custom-dimensions/bridges/ beside it (cutover-removals.md: the same
# move at the switch).
#
# Usage:
#   scripts/smoke-26.sh prepare      # .env, data/, config, datapacks, seed image, in-house mods
#   scripts/smoke-26.sh up           # seed, sync-mods, patch-mod-data, mc; waits for RCON (bounded)
#   scripts/smoke-26.sh check [out]  # boot-log gate; writes the triage list to out (default boot-26.txt)
#   scripts/smoke-26.sh down         # stops the stack and drops its volumes
#
# Gotchas:
#   - Run from a checkout with nothing else in data/: prepare writes the
#     repo-root .env and data/ (both git-ignored) and replaces data/mods'
#     in-house jars.
#   - The server runs offline-mode with no whitelist, as smoke-test.yml's
#     does, so a vanilla client can join under any name (scripts/e2e/vanilla-join.sh).
#   - DISCORD_BOT_TOKEN stays unset: dcintegration, where the version's list
#     carries it, must boot with its bridge off.
#   - MOD_CACHE_DIR (sync-mods.sh) keeps jars between runs; CI caches it.
#   - SEED_BUILD_FLAGS adds flags to the seed image build (a proxy's
#     --network host and --build-arg https_proxy=... on a workstation).
#   - SMOKE_MEMORY (default 4G) and SMOKE_WAIT (seconds, default 900) bound
#     the server and the wait.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
export MC_VERSION_OVERRIDE="${MC_VERSION_OVERRIDE:-26.3}"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"
export_mc_version
cd "$ROOT"

SEED_IMAGE="ghcr.io/piprees/minecraft-server-template/defaults-seed:smoke-$MC_VERSION"

prepare() {
  echo "==> Minecraft $MC_VERSION: image $MC_IMAGE_TAG, Fabric loader $FABRIC_LOADER_VERSION"
  mkdir -p data/mods data/config data/world/datapacks modpack-dist overlay backups .seed-rolling
  chmod 777 .seed-rolling
  cat > .env << EOF
MC_VERSION='$MC_VERSION'
MC_IMAGE_TAG='$MC_IMAGE_TAG'
FABRIC_LOADER_VERSION='$FABRIC_LOADER_VERSION'
MEMORY='${SMOKE_MEMORY:-4G}'
SEED='0'
RCON_PASSWORD='smoketest'
SERVER_PORT='25577'
VOICE_PORT='24454'
MOTD='CI smoke test $MC_VERSION'
DOMAIN='example.com'
ONLINE_MODE='FALSE'
ENABLE_WHITELIST='FALSE'
ENFORCE_WHITELIST='FALSE'
IMAGE_TAG='smoke-$MC_VERSION'
EOF

  echo "==> Config: the $MC_VERSION overlay, then the base (dev-up.sh's order)"
  (
    cd config
    version_config_files "$MC_VERSION" | while IFS= read -r f; do
      dest="$ROOT/data/config/${f#./versions/"$MC_VERSION"/}"
      mkdir -p "$(dirname "$dest")"
      cp "$f" "$dest"
    done
    platform_config_files | while IFS= read -r f; do
      dest="$ROOT/data/config/${f#./}"
      [[ -f "$dest" ]] && continue
      mkdir -p "$(dirname "$dest")"
      cp "$f" "$dest"
    done
  )
  local defs="mods/custom-dimensions-26/definitions"
  if [[ -d "$defs" ]]; then
    rm -rf data/config/custom-dimensions/dimensions
    mkdir -p data/config/custom-dimensions/dimensions
    cp "$defs"/*.json data/config/custom-dimensions/dimensions/
    echo "  v2 definitions: $(find data/config/custom-dimensions/dimensions -name '*.json' | wc -l | tr -d ' ') files"
  fi
  local bridges="mods/custom-dimensions-26/bridges"
  if [[ -d "$bridges" ]]; then
    rm -rf data/config/custom-dimensions/bridges
    mkdir -p data/config/custom-dimensions/bridges
    cp "$bridges"/*.json data/config/custom-dimensions/bridges/
    echo "  bridges: $(find data/config/custom-dimensions/bridges -name '*.json' | wc -l | tr -d ' ') files"
  fi

  echo "==> Datapacks (deploy.sh's sync and ownership filter)"
  for pack in config/datapacks/*/; do
    [[ -d "$pack" ]] || continue
    rm -rf "data/world/datapacks/$(basename "$pack")"
    cp -r "$pack" "data/world/datapacks/$(basename "$pack")"
  done
  python3 scripts/filter-datapacks.py data/world/datapacks "$(mc_mod_list .)" overlay
  # itzg chowns /data to its UID only when /data itself is foreign; a world
  # directory recreated by root under an already-chowned data/ stays root's.
  if [[ "$(id -u)" -eq 0 ]]; then chown -R "${SMOKE_UID:-1000}:${SMOKE_UID:-1000}" data; fi

  echo "==> Seed image $SEED_IMAGE"
  # shellcheck disable=SC2086 # SEED_BUILD_FLAGS is a word list
  docker build -q -t "$SEED_IMAGE" --build-arg MC_VERSION_OVERRIDE="$MC_VERSION" ${SEED_BUILD_FLAGS:-} \
    -f docker/defaults-seed/Dockerfile .
  docker run --rm --entrypoint cat "$SEED_IMAGE" /defaults/.minecraft-version | grep -qx "$MC_VERSION" || {
    echo "ERROR: the seed image does not carry $MC_VERSION" >&2
    return 1
  }

  echo "==> In-house mods ($(mc_local_mods_manifest . | sed 's|^\./||'))"
  local built
  built="$(mktemp -d)"
  "$SCRIPT_DIR/local-mods.sh" build server "$built" --skip-tests
  "$SCRIPT_DIR/local-mods.sh" verify server "$built"
  cp "$built"/*.jar data/mods/
  for j in "$built"/*.jar; do basename "$j"; done > data/mods/.local-mods-manifest
  rm -rf "$built"
}

up() {
  docker compose --profile local up -d seed
  docker wait seed > /dev/null
  "$SCRIPT_DIR/sync-mods.sh" "$ROOT/data"
  # The seed's manifest is the mod set: prune any cached jar it does not list.
  local vol manifest base pruned=0
  vol="$(docker volume ls -q | grep 'stack-mods$' | head -1)"
  manifest="$(docker run --rm -v "$vol":/m:ro --entrypoint cat "$SEED_IMAGE" /m/mods-manifest.txt)"
  for jar in data/mods/*.jar; do
    [[ -f "$jar" ]] || continue
    base="$(basename "$jar")"
    grep -qxF "$base" data/mods/.local-mods-manifest && continue
    if ! grep -qxF "$base" <<< "$manifest"; then
      rm -f "$jar"
      pruned=$((pruned + 1))
    fi
  done
  echo "  pruned $pruned jar(s) the seed does not list"
  python3 "$SCRIPT_DIR/patch-mod-data.py" "$ROOT/data/mods"
  docker compose --profile local up -d mc
  local waited=0 limit="${SMOKE_WAIT:-900}"
  while [[ $waited -lt $limit ]]; do
    if docker exec mc rcon-cli list 2> /dev/null | grep -q "players online"; then
      echo "  RCON answers after ${waited}s"
      return 0
    fi
    if [[ "$(docker inspect -f '{{.State.Running}}' mc 2> /dev/null)" != true ]]; then
      echo "ERROR: mc stopped" >&2
      docker logs mc --tail 80 >&2
      return 1
    fi
    sleep 15
    waited=$((waited + 15))
  done
  echo "ERROR: no RCON answer within ${limit}s" >&2
  docker logs mc --tail 80 >&2
  return 1
}

# check [out] - the boot gate. Every WARN and ERROR line goes into out, with
# its count, for the triage list; the gate fails on what breaks a boot.
check() {
  local out="${1:-boot-26.txt}" log failed=0 id
  log="$(mktemp)"
  docker logs mc > "$log" 2>&1
  {
    echo "# Boot log triage: Minecraft $MC_VERSION"
    echo "## Gate"
  } > "$out"
  gate() {
    if [[ "$1" == ok ]]; then echo "PASS $2" >> "$out"; else
      echo "FAIL $2" >> "$out"
      failed=1
    fi
  }
  grep -qE 'Done \([0-9.]+s\)! For help' "$log" && gate ok "server reached Done" || gate no "server reached Done"
  grep -qE "Loading Minecraft $MC_VERSION with Fabric Loader $FABRIC_LOADER_VERSION" "$log" &&
    gate ok "Minecraft $MC_VERSION on Fabric Loader $FABRIC_LOADER_VERSION" ||
    gate no "Minecraft $MC_VERSION on Fabric Loader $FABRIC_LOADER_VERSION"
  if grep -qE 'Incompatible mods? found|Mod resolution failed|FormattedException|Mixin apply .* failed|Could not execute entrypoint' "$log"; then
    gate no "no mod-loading failures"
  else
    gate ok "no mod-loading failures"
  fi
  grep -qE 'compiled [0-9]+ files into .*customdimensions|the pack in .*customdimensions is current' "$log" &&
    gate ok "custom-dimensions-26 pack installed at start-up" ||
    gate no "custom-dimensions-26 pack installed at start-up"
  # Fabric lists every loaded mod as "\t- <id> <version>" before the game
  # starts. An admin mod the version's list does not carry (a commented
  # "waiting" row) is reported, not required.
  local pair slug list
  list="$(mc_mod_list .)"
  for pair in customdimensions: spark:spark luckperms:luckperms ledger:ledger \
    essential_commands:essential-commands openpartiesandclaims:open-parties-and-claims \
    dcintegration:dcintegration; do
    id="${pair%%:*}"
    slug="${pair#*:}"
    if grep -qE "^[[:space:]]+- $id " "$log"; then
      gate ok "loaded: $id"
    elif [[ -n "$slug" ]] && ! grep -qE "^$slug:" "$list"; then
      echo "SKIP loaded: $id ($slug is not an active row of $(basename "$list"))" >> "$out"
    else
      gate no "loaded: $id"
    fi
  done
  if [[ -d data/crash-reports ]] && ls data/crash-reports/* > /dev/null 2>&1; then
    gate no "no crash reports"
  else
    gate ok "no crash reports"
  fi
  {
    echo "## WARN and ERROR lines (count, then the line with its timestamp stripped)"
    grep -E '/(WARN|ERROR|FATAL)\]' "$log" |
      sed -E 's/^\[[0-9:]+\] //; s/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/<uuid>/g' |
      sort | uniq -c | sort -rn || true
  } >> "$out"
  rm -f "$log"
  sed -n '/^## Gate/,/^## WARN/p' "$out" | sed '$d'
  echo "  triage list: $out ($(grep -cE '^ +[0-9]+ ' "$out" || true) distinct WARN/ERROR lines)"
  return "$failed"
}

down() {
  docker compose --profile local down -v
}

case "${1:-}" in
  prepare) prepare ;;
  up) up ;;
  check) check "${2:-}" ;;
  down) down ;;
  *)
    sed -n '/^# Usage:/,/^# Gotchas:/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' >&2
    exit 2
    ;;
esac
