#!/usr/bin/env bash
# =============================================================================
# report-top.sh - Generate a markdown report of the best-scoring seeds
# =============================================================================
#
# Reads seed-results.csv, ranks by score, and outputs a markdown report
# with the top 25 seeds. Also computes the fortress-to-bastion distance
# from their coordinates for the table.
#
# Usage:
#   ./report-top.sh                # Reads ./seed-results.csv, writes report
#   ./report-top.sh 10             # Top 10 instead of top 25
#   ./report-top.sh 25 /path/to/seed-results.csv  # Custom CSV path
#
# Output: seed-report-top25.md in the project root.
# Requires: awk, sort (standard Unix tools - no Python/Ruby needed)
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
TOP_N="${1:-25}"

# Resolve paths relative to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

RESULTS_CSV="${2:-$PROJECT_ROOT/scripts/seed/seed-results.csv}"
REPORT_FILE="$PROJECT_ROOT/scripts/seed/seed-report-top${TOP_N}.md"

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
if [[ ! -f "$RESULTS_CSV" ]]; then
  echo "Error: Results file not found at $RESULTS_CSV" >&2
  echo "Run roll-seeds.sh first to generate seed results." >&2
  exit 1
fi

total_seeds=$(($(wc -l < "$RESULTS_CSV") - 1))
if ((total_seeds < 1)); then
  echo "Error: No seed results found in $RESULTS_CSV (only header present)" >&2
  exit 1
fi

# Don't silently overwrite - back up existing report
if [[ -f "$REPORT_FILE" ]]; then
  backup="${REPORT_FILE%.md}-$(date +%Y%m%d-%H%M%S).md"
  cp "$REPORT_FILE" "$backup"
  echo "Backed up existing report to: $backup"
fi

echo "Generating top-$TOP_N seed report from $total_seeds scored seeds..."

# ---------------------------------------------------------------------------
# Generate the markdown report using awk.
#
# CSV columns (1-indexed):
#   1: seed
#   2: stronghold_dist
#   3: village_dist
#   4: fortress_dist
#   5: bastion_dist
#   6: portal_dist
#   7: fortress_x
#   8: fortress_z
#   9: bastion_x
#  10: bastion_z
#  11: spawn_biome
#  12: score
# ---------------------------------------------------------------------------

# Sort by score (column 12) descending, take top N
# Then pipe into awk to produce the markdown table
{
  # --- Header ---
  cat << HEADER
# Seed Report - Top ${TOP_N} Adventure Seeds

**Generated:** $(date '+%Y-%m-%d %H:%M:%S')
**Seeds tested:** ${total_seeds}
**Server:** Fabric 1.21.1 with Terralith + Tectonic + Incendium + Nullscape

---

## Rankings

All distances are in blocks. Lower is better for structures; lower Fort<>Bast means the fortress and bastion are conveniently close together in the Nether. Score is out of 100.

| Rank | Seed | Score | Stronghold | Village | Fortress | Bastion | Portal | Fort<>Bast | Spawn Biome |
|-----:|------|------:|-----------:|--------:|---------:|--------:|-------:|----------:|-------------|
HEADER

  # Sort by score descending, take top N, add rank numbers
  tail -n +2 "$RESULTS_CSV" \
    | sort -t',' -k12 -rn \
    | head -n "$TOP_N" \
    | awk -F',' -v start=1 '
    {
      rank = NR

      seed = $1
      sh   = ($2 == "" ? "-" : $2)
      vi   = ($3 == "" ? "-" : $3)
      fo   = ($4 == "" ? "-" : $4)
      ba   = ($5 == "" ? "-" : $5)
      rp   = ($6 == "" ? "-" : $6)
      fx   = $7
      fz   = $8
      bx   = $9
      bz   = $10
      biome = ($11 == "" ? "unknown" : $11)
      score = $12

      # Compute fortress-to-bastion distance (Manhattan)
      if (fx != "" && fz != "" && bx != "" && bz != "") {
        dx = fx - bx
        dz = fz - bz
        if (dx < 0) dx = -dx
        if (dz < 0) dz = -dz
        fb_dist = dx + dz
      } else {
        fb_dist = "-"
      }

      # Strip minecraft: prefix from biome for readability
      sub(/^minecraft:/, "", biome)

      printf "| %d | `%s` | %s | %s | %s | %s | %s | %s | %s | %s |\n", \
        rank, seed, score, sh, vi, fo, ba, rp, fb_dist, biome
    }'

  # --- Footer with next steps ---
  cat << 'FOOTER'

---

## What next?

### Stage 2 - Visualise on BlueMap

Pick your top 2–3 seeds and boot each one with the full modpack to pre-generate and view on BlueMap:

```bash
# Set the seed in your .env and boot the local profile
echo "SEED=<your_seed>" >> .env
docker compose --profile local up -d

# Over RCON, pre-generate 1500 blocks around spawn
docker exec mc rcon-cli "chunky radius 1500"
docker exec mc rcon-cli "chunky start"

# Wait for pre-gen to finish, then check BlueMap at http://localhost:8100
```

Look for:
- A **green, open spawn** - not dumped in a swamp, ocean, or mountain
- **Village and stronghold** in pleasant terrain within exploring distance
- **Interesting variety** nearby - caves, rivers, hills, different biomes

### Stage 3 - Fly the finalists

For your top 1–2 seeds, join the server and explore:

```bash
# Op yourself and go spectator
/op <your_username>
/gamemode spectator

# Fly around spawn, check the village, find the stronghold
# Then hop to the Nether to see the fortress and bastion:
/execute in minecraft:the_nether run tp @s 0 100 0
```

### Lock in the winner

Once you've chosen, set the seed in `.env`:

```bash
SEED=<winning_seed>
```

Then generate the live world fresh with the full mod set. **Back it up immediately** - this is the world you don't want to lose.

> Remember: once players have built, the seed is effectively permanent. Terralith and Incendium can't be cleanly removed. Get this right now, while it's cheap.
FOOTER

} > "$REPORT_FILE"

echo ""
echo "Report written to: $REPORT_FILE"
echo ""
echo "Top 3 seeds:"
tail -n +2 "$RESULTS_CSV" \
  | sort -t',' -k12 -rn \
  | head -n 3 \
  | awk -F',' '{ printf "  #%d  seed=%-20s  score=%s\n", NR, $1, $12 }'
echo ""
