#!/usr/bin/env bash
# local-mods.sh - Build and gate the in-house mods the selected version ships.
#
# Context: template-only (CI: release.yml, mod-build.yml, smoke-test-26.yml).
# The manifests are mods/local-mods[-<version>].manifest (server) and
# mods/client-mods[-<version>].manifest (client), picked by
# mc_local_mods_manifest / mc_client_mods_manifest in lib.sh, so
# MC_VERSION_OVERRIDE moves them with everything else. A row is
# bundle_name|gradle_project|loom_refmap: gradle_project is a Gradle root under
# mods/, the jar is the one named <bundle_name stem>-*.jar under any build/libs
# in it, and a refmap of "-" declares a non-remapping Loom build.
#
# Usage:
#   scripts/local-mods.sh build  <server|client> <out-dir> [--mod-version V] [--skip-tests]
#   scripts/local-mods.sh verify <server|client> <dir>
#   scripts/local-mods.sh rows   <server|client>
#
# Gotchas:
#   - Gradle reports BUILD SUCCESSFUL for an empty or unremapped jar, which
#     boots as a production crash loop. verify is the gate: compiled classes
#     (10 for a server mod, 1 for a client mod), the Loom refmap unless the
#     row says "-", and a client mod must declare environment client.
#   - A project with its own ./gradlew builds with it; otherwise `gradle`
#     on PATH (setup-gradle in CI). The JDK is the caller's: the version
#     table's java column.
#   - A missing client manifest means no client mods; a missing server
#     manifest is an error.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

usage() {
  sed -n '/^# Usage:/,/^# Gotchas:/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' >&2
  exit 2
}

manifest_for() {
  case "$1" in
    server) mc_local_mods_manifest "$ROOT" ;;
    client) mc_client_mods_manifest "$ROOT" ;;
    *) usage ;;
  esac
}

# rows <kind> - the manifest's data rows, one per line.
rows() {
  local manifest
  manifest="$(manifest_for "$1")"
  if [[ ! -f "$manifest" ]]; then
    [[ "$1" == client ]] && return 0
    echo "ERROR: missing local mods manifest: $manifest" >&2
    return 1
  fi
  grep -vE '^[[:space:]]*(#|$)' "$manifest" || true
}

build() {
  local kind="$1" out="$2" mod_version="" skip_tests=0 jar_name project refmap jars count
  shift 2
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --mod-version)
        mod_version="$2"
        shift 2
        ;;
      --skip-tests)
        skip_tests=1
        shift
        ;;
      *) usage ;;
    esac
  done
  mkdir -p "$out"
  out="$(cd "$out" && pwd)"
  rows "$kind" > "$out/.rows"
  while IFS='|' read -r jar_name project refmap; do
    [[ -n "$jar_name" && -n "$project" && -n "$refmap" ]] || {
      echo "ERROR: invalid manifest row: $jar_name|$project|$refmap" >&2
      return 1
    }
    [[ -d "$ROOT/mods/$project" ]] || {
      echo "ERROR: $jar_name: no project at mods/$project" >&2
      return 1
    }
    (
      cd "$ROOT/mods/$project"
      set -- build
      [[ -n "$mod_version" ]] && set -- "$@" -Pmod_version="$mod_version"
      [[ $skip_tests -eq 1 ]] && set -- "$@" -x test
      if [[ -x ./gradlew ]]; then ./gradlew "$@"; else gradle "$@"; fi
    )
    jars="$(cd "$ROOT/mods/$project" && find . -path '*/build/libs/*' -name "${jar_name%.jar}-*.jar" \
      ! -name '*-sources.jar' ! -name '*-dev.jar' | sort)"
    count="$(printf '%s' "$jars" | grep -c . || true)"
    [[ "$count" -eq 1 ]] || {
      echo "ERROR: $jar_name: expected one built jar in mods/$project, found $count" >&2
      [[ -z "$jars" ]] || printf '  %s\n' "$jars" >&2
      return 1
    }
    cp "$ROOT/mods/$project/${jars#./}" "$out/$jar_name"
    echo "Built $jar_name from mods/$project/${jars#./}"
  done < "$out/.rows"
  rm -f "$out/.rows"
}

verify() {
  local kind="$1" dir="$2" floor=10 failed=0 jar_name project refmap jar classes listing
  [[ "$kind" == client ]] && floor=1
  while IFS='|' read -r jar_name project refmap; do
    jar="$dir/$jar_name"
    if [[ ! -f "$jar" ]]; then
      echo "::error::$jar is missing"
      failed=1
      continue
    fi
    listing="$(unzip -l "$jar")"
    classes="$(printf '%s\n' "$listing" | grep -c '\.class$' || true)"
    if [[ "$refmap" != "-" ]] && ! printf '%s\n' "$listing" | grep -F "$refmap" > /dev/null; then
      echo "::error::$jar is missing $refmap — output is unremapped or empty"
      failed=1
      continue
    fi
    if [[ "$classes" -lt "$floor" ]]; then
      echo "::error::$jar contains only $classes classes — the build output looks empty"
      failed=1
      continue
    fi
    if ! printf '%s\n' "$listing" | grep -E '[[:space:]]fabric\.mod\.json$' > /dev/null; then
      echo "::error::$jar has no fabric.mod.json"
      failed=1
      continue
    fi
    # grep without -q: -q exits at the first match and the resulting SIGPIPE
    # upstream reads as a pipeline failure under pipefail.
    if unzip -p "$jar" fabric.mod.json | tr -d ' \n' | grep '"environment":"client"' > /dev/null; then
      if [[ "$kind" == server ]]; then
        echo "::error::$jar is a server mod but declares environment client"
        failed=1
        continue
      fi
    elif [[ "$kind" == client ]]; then
      echo "::error::$jar is in the client manifest but does not declare environment client"
      failed=1
      continue
    fi
    if [[ "$refmap" == "-" ]]; then
      echo "OK: $jar ($classes classes, non-remapping build)"
    else
      echo "OK: $jar ($classes classes, $refmap present)"
    fi
  done < <(rows "$kind")
  return "$failed"
}

[[ $# -ge 2 ]] || usage
cmd="$1"
shift
case "$cmd" in
  build)
    [[ $# -ge 2 ]] || usage
    build "$@"
    ;;
  verify)
    [[ $# -eq 2 ]] || usage
    verify "$@"
    ;;
  rows)
    [[ $# -eq 1 ]] || usage
    rows "$1"
    ;;
  *) usage ;;
esac
