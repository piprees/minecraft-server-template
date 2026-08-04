#!/usr/bin/env bash
# =============================================================================
# migrate-structure-identity.sh — one-time migration to exact structure identity
# =============================================================================
#
# Backs up the candidate bank, runs a rescore (the schemaVersion cache miss
# makes rescore BE the migration — every candidate is recomputed with exact
# per-position structure assignment and sidecar files), then verifies every
# candidate that had positions now carries schemaVersion 2 data.
#
# Template-only: NOT shipped in the stack bundle MANIFEST. Consumers run it
# by hand once after a platform release that introduces exact structure identity.
#
# Usage:
#   ./scripts/seed/migrate-structure-identity.sh --seedtest .seedtest --config config/custom-dimensions
#   ./scripts/seed/migrate-structure-identity.sh --seedtest .seedtest --config config/custom-dimensions --force
#   ./scripts/seed/migrate-structure-identity.sh --seedtest .seedtest --config config/custom-dimensions --non-interactive
#
# Gotchas:
#   - A full bank rescore takes hours at default_workers() = max(2, cpu_count * 2 // 3).
#     Ctrl+C is safe: _run_census checkpoints every 2000 results / 60s, so
#     re-running resumes from the last checkpoint.
#   - Refuses to run if a .bak from today already exists (guards against
#     accidental double-runs overwriting the pre-migration baseline). Pass
#     --force to override.
#   - The backup is a plain cp -R, not compressed. A large bank (200+ dims,
#     1000+ candidates each) is a few hundred MB.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SEEDTEST=""
CONFIG=""
FORCE=0
NON_INTERACTIVE=0
CENSUS_WORKERS=""

usage() {
  echo "Usage: $0 --seedtest <dir> --config <dir> [--force] [--non-interactive] [--census-workers N]"
  echo ""
  echo "One-time migration to exact structure identity (schemaVersion 2)."
  echo ""
  echo "Steps:"
  echo "  1. Back up <seedtest>/candidates/ to candidates.bak.<timestamp>/"
  echo "  2. Run score-dimensions.py rescore (the schema cache miss recomputes"
  echo "     every candidate with exact per-position structure assignment)"
  echo "  3. Verify every previously-populated candidate now carries v2 data"
  echo ""
  echo "Options:"
  echo "  --seedtest <dir>     The .seedtest directory (required)"
  echo "  --config <dir>       The config/custom-dimensions directory (required)"
  echo "  --census-workers N   Processes for the rescore (default: max(2, cpu_count * 2 / 3))"
  echo "  --force              Override the one-backup-per-day guard"
  echo "  --non-interactive    Skip confirmation prompts"
  echo "  -h, --help           Show this and exit"
}

for arg in "$@"; do
  case "$arg" in
    -h | --help)
      usage
      exit 0
      ;;
  esac
done

while [[ $# -gt 0 ]]; do
  case "$1" in
    --seedtest)     SEEDTEST="$2"; shift 2 ;;
    --config)       CONFIG="$2"; shift 2 ;;
    --force)        FORCE=1; shift ;;
    --non-interactive) NON_INTERACTIVE=1; shift ;;
    --census-workers) CENSUS_WORKERS="$2"; shift 2 ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$SEEDTEST" ]]; then
  echo "Error: --seedtest is required" >&2
  exit 1
fi
if [[ -z "$CONFIG" ]]; then
  echo "Error: --config is required" >&2
  exit 1
fi
if [[ ! -d "$SEEDTEST" ]]; then
  echo "Error: seedtest directory does not exist: $SEEDTEST" >&2
  exit 1
fi
if [[ ! -d "$CONFIG" ]]; then
  echo "Error: config directory does not exist: $CONFIG" >&2
  exit 1
fi

CANDIDATES_DIR="$SEEDTEST/candidates"
if [[ ! -d "$CANDIDATES_DIR" ]]; then
  echo "Error: no candidate bank at $CANDIDATES_DIR" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 1: Back up the candidate bank
# ---------------------------------------------------------------------------
TODAY="$(date '+%Y%m%d')"
TIMESTAMP="$(date '+%Y%m%d-%H%M%S')"
BACKUP_DIR="$SEEDTEST/candidates.bak.$TIMESTAMP"

# Guard: refuse if a backup from today already exists (unless --force).
if [[ "$FORCE" == 0 ]]; then
  for existing in "$SEEDTEST"/candidates.bak."$TODAY"*; do
    if [[ -d "$existing" ]]; then
      echo "Error: a backup from today already exists: $existing" >&2
      echo "  Pass --force to override (only if you know the existing backup" >&2
      echo "  is not the pre-migration baseline you need to verify against)." >&2
      exit 1
    fi
  done
fi

if [[ "$NON_INTERACTIVE" == 0 ]]; then
  echo "This will:"
  echo "  1. Back up $CANDIDATES_DIR to $BACKUP_DIR"
  echo "  2. Rescore every candidate (hours of CPU — Ctrl+C is safe)"
  echo "  3. Verify schemaVersion 2 data against the backup"
  echo ""
  printf "Proceed? [y/N] "
  read -r answer
  case "$answer" in
    y | Y | yes | YES) ;;
    *)
      echo "Aborted."
      exit 0
      ;;
  esac
fi

echo "Backing up $CANDIDATES_DIR -> $BACKUP_DIR"
cp -R "$CANDIDATES_DIR" "$BACKUP_DIR"
echo "  Done. $(find "$BACKUP_DIR" -name '*.json' | wc -l | tr -d ' ') store file(s) backed up."

# ---------------------------------------------------------------------------
# Step 2: Rescore (the migration)
# ---------------------------------------------------------------------------
echo ""
echo "Running rescore (schemaVersion cache miss triggers the migration)..."
echo "  Ctrl+C is safe — checkpoints every 2000 results / 60s."
echo ""

# set -e would exit the script on a non-zero return before capturing $?.
# Use if-not to keep the exit code while staying under set -e.
# shellcheck disable=SC2086
if ! python3 "$SCRIPT_DIR/score-dimensions.py" rescore \
  --config "$CONFIG" \
  --seedtest "$SEEDTEST" \
  ${CENSUS_WORKERS:+--census-workers "$CENSUS_WORKERS"}; then
  echo "ERROR: rescore failed" >&2
  echo "  The backup is at $BACKUP_DIR — re-run to resume." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 3: Verify
# ---------------------------------------------------------------------------
echo ""
echo "Verifying migration against the backup..."
python3 "$SCRIPT_DIR/verify-structure-identity.py" \
  --seedtest "$SEEDTEST" \
  --backup "$BACKUP_DIR"

exit $?
