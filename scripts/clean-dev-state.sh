#!/usr/bin/env bash
# clean-dev-state.sh - Delete every file git does not track, leaving a checkout
#                      with nothing derived in it.
#
# Context: Runs on a developer Mac, invoked as `./dev clean` in a consumer repo.
# Implemented as `git clean -xdf`, so the rule is exactly "if git does not track
# it, it goes" — there is no list of directories to fall out of date and no
# judgement about which stale artefact is worth keeping. Git is the backup.
# Never run against production; it is not in the ops surface.
#
# Usage:
#   clean-dev-state.sh [<consumer-dir>] [--platform DIR] [--dry-run]
#
#   <consumer-dir>  defaults to $CONSUMER_DIR, then the current directory
#   --platform DIR  also clean a platform checkout (auto-detected when the
#                   consumer is linked to one via `./dev link`)
#   --dry-run       list what would go, delete nothing
#
# What this destroys: the local world and player data, every diagnostic artefact,
# the seed roller's candidate bank and renders, the pulled stack bundle,
# downloaded mods, build output and every cache. All of it regenerates —
# `./dev pull` (or `./dev link`) restores the bundle, `./dev up` re-seeds config
# and re-downloads mods, and the world regenerates from the seed in .env.
#
# Gotchas: `.env` is the only exclusion — it is a credential file git does not
# have (recover with `./ops op-env`). Anything else you want to survive must be
# committed first: to git, an uncommitted report is indistinguishable from cruft.
# Must run on macOS bash 3.2 — no mapfile, no ${var,,}.
set -euo pipefail

CONSUMER=""
PLATFORM=""
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --platform) PLATFORM="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,29p' "${BASH_SOURCE[0]}"; exit 0 ;;
    -*) echo "Unknown argument: $1" >&2; exit 2 ;;
    *) CONSUMER="$1"; shift ;;
  esac
done

[[ -n "$CONSUMER" ]] || CONSUMER="${CONSUMER_DIR:-$(pwd)}"

# A linked consumer points .stack/current at a platform checkout's symlink farm,
# so that checkout's build output and caches are part of this dev environment.
if [[ -z "$PLATFORM" && -L "$CONSUMER/.stack/dev/stack/scripts" ]]; then
  linked="$(cd "$(dirname "$(readlink "$CONSUMER/.stack/dev/stack/scripts")")" && pwd)"
  [[ -d "$linked/.git" ]] && PLATFORM="$linked"
fi

clean_repo() {
  local dir="$1"
  local before after
  before=$(du -sk "$dir" 2>/dev/null | awk '{print $1}')
  before=${before:-0}

  echo "== $dir ($(( before / 1024 ))M)"
  if [[ $DRY_RUN -eq 1 ]]; then
    git -C "$dir" clean -xdn -e '.env' -e '.env.*' \
      | sed 's/^Would remove /  would remove  /'
    return 0
  fi

  git -C "$dir" clean -xdf -e '.env' -e '.env.*' | sed 's/^Removing /  removed  /'
  after=$(du -sk "$dir" 2>/dev/null | awk '{print $1}')
  after=${after:-0}
  echo "  $(( (before - after) / 1024 ))M freed"
}

if [[ ! -d "$CONSUMER/.git" ]]; then
  echo "Not a git repository: $CONSUMER" >&2
  exit 1
fi

clean_repo "$CONSUMER"
if [[ -n "$PLATFORM" && -d "$PLATFORM/.git" && "$PLATFORM" != "$CONSUMER" ]]; then
  clean_repo "$PLATFORM"
fi

if [[ $DRY_RUN -eq 0 ]]; then
  echo
  echo "Next: ./dev pull (or ./dev link) then ./dev up"
fi
