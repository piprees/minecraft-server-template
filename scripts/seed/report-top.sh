#!/usr/bin/env bash
# =============================================================================
# report-top.sh - Score seed measurements against a profile and report
# =============================================================================
#
# The judgement half of the measure/score split: applies a named profile
# (weights + directions + tier lists from scripts/seed/profiles/) to the
# long-format seed-measurements.csv banked by roll-seeds.sh, and writes a
# ranked markdown report. Re-run with a different --profile any time — the
# expensive boots are already done.
#
# Usage:
#   ./report-top.sh                                  # classic profile, world
#   ./report-top.sh 10                               # top 10
#   ./report-top.sh --profile overworld-natural      # v3 main-world taste
#   ./report-top.sh --profile dim-hard-overworld --target the_gauntlet
#   ./report-top.sh --profile classic --target all   # every measured target
#   ./report-top.sh 25 /path/to/seed-measurements.csv  # legacy positional
#
# Output: seed-report-<profile>[-<target>].md in the project root.
# Requires: python3 (report-time scoring; measurement stays bash-only).
#
# Gotchas: the pre-v3 wide seed-results.csv is not supported — re-roll with
# the v3 roll-seeds.sh to bank long-format measurements.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

TOP_N=25
PROFILE="classic"
TARGET="world"
CSV="$PROJECT_ROOT/seed-measurements.csv"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      PROFILE="$2"
      shift 2
      ;;
    --target)
      TARGET="$2"
      shift 2
      ;;
    --csv)
      CSV="$2"
      shift 2
      ;;
    [0-9]*)
      TOP_N="$1"
      shift
      ;;
    *)
      # legacy second positional: CSV path
      CSV="$1"
      shift
      ;;
  esac
done

if ! command -v python3 &> /dev/null; then
  echo "Error: python3 is required for report-time scoring." >&2
  exit 1
fi

if [[ ! -f "$CSV" ]]; then
  echo "Error: Measurements file not found at $CSV" >&2
  echo "Run roll-seeds.sh first to bank measurements." >&2
  exit 1
fi

# Don't silently overwrite - back up an existing report of the same name.
if [[ "$TARGET" == "world" || "$TARGET" == "all" ]]; then
  REPORT_FILE="$PROJECT_ROOT/seed-report-${PROFILE}.md"
else
  REPORT_FILE="$PROJECT_ROOT/seed-report-${PROFILE}-${TARGET}.md"
fi
if [[ -f "$REPORT_FILE" ]]; then
  backup="${REPORT_FILE%.md}-$(date +%Y%m%d-%H%M%S).md"
  cp "$REPORT_FILE" "$backup"
  echo "Backed up existing report to: $backup"
fi

python3 "$SCRIPT_DIR/score-report.py" \
  --profile "$PROFILE" \
  --csv "$CSV" \
  --target "$TARGET" \
  --top "$TOP_N" \
  --out "$REPORT_FILE"

echo ""
echo "Next steps:"
echo "  - Review $REPORT_FILE and pick winners BY HAND."
echo "  - World seed: set SEED= in .env (new worlds only - never reset-seed"
echo "    production without the full human ritual)."
echo "  - Dimension seed: edit that dimension's \"seed\" in"
echo "    config/multiverse_config.json and commit."
