#!/usr/bin/env bash
# client-defaults.sh - Diff or sync the pack's shipped client defaults against
# a live Prism Launcher instance.
#
# The source of truth for client defaults (keybinds, voice chat, sodium/iris/
# Distant Horizons tuning, per-mod client configs) is your source Prism
# instance - whatever is configured there SHOULD be what new players get. Shipped
# defaults live in modpack/overrides/configureddefaults/ and apply via the
# Configured Defaults mod: options.txt is merged (missing keys only),
# everything else copies only when the target file doesn't exist. NOTHING
# user-tunable may ship in modpack/overrides/ root - Prism re-applies raw
# overrides on every pack update, which is how keybinds and voice chat
# settings kept getting wiped (fixed 2026-07-02).
#
# --sync only refreshes files ALREADY shipped; adding a new config to the
# defaults is a deliberate, curated act (some files must never ship, e.g.
# NoChatReports/NCR-Encryption.json contains a chat encryption passphrase).
#
# options.txt is special-cased: fullscreenResolution is stripped (machine-
# specific). config/customsplashscreen/square_logo.png is skipped (pack art,
# not instance-derived).
#
# Usage:
#   ./scripts/client-defaults.sh --diff    # list drift; exit 1 if any
#   ./scripts/client-defaults.sh --sync    # refresh shipped defaults from the instance
#   PRISM_INSTANCE=/path/to/instance/minecraft ./scripts/client-defaults.sh --diff
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$PROJECT_DIR"

INSTANCE="${PRISM_INSTANCE:-$HOME/Library/Application Support/PrismLauncher/instances/Adventure Server/.minecraft}"
CD_DIR="modpack/overrides/configureddefaults"

MODE="${1:-}"
if [[ "$MODE" != "--diff" && "$MODE" != "--sync" ]]; then
  echo "Usage: $0 --diff | --sync    (see header)"
  exit 1
fi

if [[ ! -d "$INSTANCE" ]]; then
  echo "Prism instance not found: $INSTANCE"
  echo "Set PRISM_INSTANCE to the instance's minecraft/ directory."
  exit 1
fi

DRIFT=0
CHANGED=0

# options.txt: compare/sync against the instance copy minus machine-specific keys
strip_machine_keys() {
  grep -v "^fullscreenResolution:" "$1"
}

if [[ -f "$INSTANCE/options.txt" ]]; then
  TMP_OPTS="$(mktemp)"
  strip_machine_keys "$INSTANCE/options.txt" > "$TMP_OPTS"
  if ! cmp -s "$TMP_OPTS" "$CD_DIR/options.txt"; then
    if [[ "$MODE" == "--sync" ]]; then
      cp "$TMP_OPTS" "$CD_DIR/options.txt"
      echo "synced:  options.txt"
      CHANGED=$((CHANGED + 1))
    else
      echo "drift:   options.txt"
      DRIFT=$((DRIFT + 1))
    fi
  fi
  rm -f "$TMP_OPTS"
fi

# Every other shipped file: byte-compare against the instance's copy
while IFS= read -r shipped; do
  rel="${shipped#"$CD_DIR"/}"
  [[ "$rel" == "options.txt" ]] && continue
  [[ "$rel" == *"square_logo.png" ]] && continue
  src="$INSTANCE/$rel"
  if [[ ! -f "$src" ]]; then
    echo "missing in instance (shipped only): $rel"
    continue
  fi
  if ! cmp -s "$src" "$shipped"; then
    if [[ "$MODE" == "--sync" ]]; then
      cp "$src" "$shipped"
      echo "synced:  $rel"
      CHANGED=$((CHANGED + 1))
    else
      echo "drift:   $rel"
      DRIFT=$((DRIFT + 1))
    fi
  fi
done < <(find "$CD_DIR" -type f)

echo ""
if [[ "$MODE" == "--sync" ]]; then
  echo "$CHANGED file(s) refreshed from the instance."
  [[ $CHANGED -gt 0 ]] && echo "Review with git diff, then commit + push (pack rebuilds on deploy)."
  exit 0
fi
if [[ $DRIFT -eq 0 ]]; then
  echo "Shipped defaults match the instance."
  exit 0
fi
echo "$DRIFT file(s) drifted. Refresh with: $0 --sync"
exit 1
