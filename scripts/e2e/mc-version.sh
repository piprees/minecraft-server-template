#!/usr/bin/env bash
# mc-version.sh - Which Minecraft the harness is measuring, for scripts/e2e/.
#
# Purpose: one answer to "is this a 26.x stack?", read the way a linked farm
#          reads it: MC_VERSION_OVERRIDE from the environment, else from the
#          consumer's .env, else this checkout's .minecraft-version.
# Context: sourced by lib.sh and by the scripts that run on one version only.
#          Template-only.
# Usage:   . "$(dirname "$0")/mc-version.sh"
#          e2e_is_26 && ...           # 0 on a 26.x stack
#          e2e_refuse_on_26 "why"     # exits 3, naming why, on a 26.x stack
# Gotchas: exit 3 is "does not apply here", distinct from a failure (1) and a
#          pass (0), and it clears any EXIT trap first so lib.sh's
#          incomplete-run guard cannot turn it into a failure.

e2e_mc_version() {
  local v="${MC_VERSION_OVERRIDE:-}" here
  if [ -z "$v" ] && [ -f "${CONSUMER_DIR:-$HOME/Projects/elfydd}/.env" ]; then
    v="$(sed -n "s/^[[:space:]]*MC_VERSION_OVERRIDE=//p" "${CONSUMER_DIR:-$HOME/Projects/elfydd}/.env" |
      head -1 | tr -d "'\"[:space:]")"
  fi
  if [ -z "$v" ]; then
    here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    v="$(tr -d '[:space:]' < "$here/../../.minecraft-version" 2>/dev/null)"
  fi
  printf '%s' "$v"
}

e2e_is_26() {
  case "$(e2e_mc_version)" in 26.*) return 0 ;; *) return 1 ;; esac
}

e2e_refuse_on_26() { # reason
  if e2e_is_26; then
    printf 'NOT APPLICABLE on Minecraft %s — %s\n' "$(e2e_mc_version)" "$1" >&2
    trap - EXIT
    exit 3
  fi
}
