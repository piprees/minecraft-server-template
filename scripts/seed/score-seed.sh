#!/usr/bin/env bash
# =============================================================================
# score-seed.sh - Score a Minecraft seed based on structure distances & biome
# =============================================================================
#
# Takes raw distances (blocks) and a spawn biome, outputs a numeric score.
# Closer structures = higher score. Green/open spawn biome = bonus points.
#
# Usage:
#   ./score-seed.sh <stronghold> <village> <fortress> <bastion> <portal> \
#                   <fort_x> <fort_z> <bast_x> <bast_z> <biome>
#
# Or set equivalent environment variables (STRONGHOLD_DIST, VILLAGE_DIST, etc.)
# and call without arguments.
#
# Score is printed to stdout. Breakdown is printed to stderr for debugging.
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# Configurable weights - must sum to 100 for the score to be a percentage.
# Adjust these to change what matters most in your world.
# ---------------------------------------------------------------------------
WEIGHT_STRONGHOLD=25          # Eye of Ender target - want it close for pacing
WEIGHT_VILLAGE=20             # Early gear, beds, trading - essential first stop
WEIGHT_FORTRESS=15            # Blaze rods for potions and the end portal
WEIGHT_BASTION=15             # Gold, piglin trading, gear upgrades
WEIGHT_FORT_BAST_PROXIMITY=15 # Fortress and bastion near each other saves Nether time
WEIGHT_SPAWN_BIOME=10         # A green, open spawn feels welcoming

# ---------------------------------------------------------------------------
# Distance caps - beyond this, a structure scores zero for that category.
# Overworld and Nether have different scales.
# ---------------------------------------------------------------------------
CAP_STRONGHOLD=2000 # blocks (overworld)
CAP_VILLAGE=2000    # blocks (overworld)
CAP_FORTRESS=1500   # blocks (nether - remember 1:8 ratio)
CAP_BASTION=1500    # blocks (nether)
CAP_FORT_BAST=1000  # blocks between fortress and bastion (nether)

# ---------------------------------------------------------------------------
# Parse arguments - positional args take precedence over env vars.
# Empty string or "0" means "not found" - scores zero for that category.
# ---------------------------------------------------------------------------
STRONGHOLD_DIST="${1:-${STRONGHOLD_DIST:-}}"
VILLAGE_DIST="${2:-${VILLAGE_DIST:-}}"
FORTRESS_DIST="${3:-${FORTRESS_DIST:-}}"
BASTION_DIST="${4:-${BASTION_DIST:-}}"
PORTAL_DIST="${5:-${PORTAL_DIST:-}}"
FORTRESS_X="${6:-${FORTRESS_X:-}}"
FORTRESS_Z="${7:-${FORTRESS_Z:-}}"
BASTION_X="${8:-${BASTION_X:-}}"
BASTION_Z="${9:-${BASTION_Z:-}}"
SPAWN_BIOME="${10:-${SPAWN_BIOME:-}}"

# ---------------------------------------------------------------------------
# score_structure - linear falloff from cap to zero.
#
#   points = max(0, cap - distance) * weight / cap
#
# Closer = more points. Not found (empty/0) = 0 points.
# Uses integer arithmetic scaled by 100 to avoid needing bc/awk for floats.
# ---------------------------------------------------------------------------
score_structure() {
  local dist="$1" cap="$2" weight="$3" label="$4"

  # Not found or zero - no points
  if [[ -z "$dist" || "$dist" == "0" ]]; then
    echo "  ${label}: 0.0 / ${weight} (not found)" >&2
    echo 0
    return
  fi

  # Beyond the cap - no points
  if ((dist >= cap)); then
    echo "  ${label}: 0.0 / ${weight} (${dist} blocks - beyond ${cap} cap)" >&2
    echo 0
    return
  fi

  # Linear score: (cap - dist) / cap * weight, scaled by 100 for precision
  local remaining=$((cap - dist))
  local score_x100=$((remaining * weight * 100 / cap))
  local whole=$((score_x100 / 100))
  local frac=$((score_x100 % 100))

  printf "  %s: %d.%02d / %d (%d blocks, %d remaining of %d cap)\n" \
    "$label" "$whole" "$frac" "$weight" "$dist" "$remaining" "$cap" >&2

  echo "$score_x100"
}

# ---------------------------------------------------------------------------
# score_proximity - how close fortress and bastion are to each other.
#
# Computed from their X/Z coordinates using Manhattan distance (close enough
# for Minecraft - no sqrt needed). Closer together = more points.
# ---------------------------------------------------------------------------
score_proximity() {
  local fx="$1" fz="$2" bx="$3" bz="$4" weight="$5" cap="$6"

  # Need all four coordinates to compute proximity
  if [[ -z "$fx" || -z "$fz" || -z "$bx" || -z "$bz" ]]; then
    echo "  Fort<>Bast: 0.0 / ${weight} (missing coordinates)" >&2
    echo 0
    return
  fi

  # Manhattan distance between the two structures
  local dx=$((fx - bx))
  local dz=$((fz - bz))
  # Absolute values (the || true prevents set -e from killing the script
  # when dx/dz is 0, since (( 0 )) returns exit code 1 in bash)
  ((dx < 0)) && dx=$((-dx)) || true
  ((dz < 0)) && dz=$((-dz)) || true
  local dist=$((dx + dz))

  if ((dist >= cap)); then
    echo "  Fort<>Bast: 0.0 / ${weight} (${dist} blocks apart - beyond ${cap} cap)" >&2
    echo 0
    return
  fi

  local remaining=$((cap - dist))
  local score_x100=$((remaining * weight * 100 / cap))
  local whole=$((score_x100 / 100))
  local frac=$((score_x100 % 100))

  printf "  Fort<>Bast: %d.%02d / %d (%d blocks apart)\n" \
    "$whole" "$frac" "$weight" "$dist" >&2

  echo "$score_x100"
}

# ---------------------------------------------------------------------------
# score_biome - categorise spawn biome and award points.
#
# "Green/open" biomes get full marks - these are welcoming spawns with
# good visibility, passive mobs, and easy first-night survival.
# "OK" biomes get half marks - playable but not ideal.
# "Bad" biomes get zero - harsh, disorienting, or resource-poor starts.
# ---------------------------------------------------------------------------
score_biome() {
  local biome="$1" weight="$2"

  # Strip minecraft: or mod prefix for matching, but keep original for display
  local clean="${biome##*:}"

  local tier="bad"

  # --- Tier 1: Green/open - full points ---
  # Vanilla greens
  case "$clean" in
    plains | sunflower_plains | meadow | savanna | savanna_plateau | \
      forest | birch_forest | old_growth_birch_forest | flower_forest | \
      cherry_grove | sparse_jungle)
      tier="green"
      ;;
  esac

  # Terralith greens - these are the lush, open biomes added by the mod.
  # Check the full biome ID for mod-namespaced biomes.
  case "$biome" in
    terralith:blooming_valley | terralith:lush_valley | terralith:lavender_valley | \
      terralith:blooming_plateau | terralith:sakura_valley | terralith:sakura_grove | \
      terralith:temperate_highlands | terralith:brushland | terralith:steppe | \
      terralith:shrubland | terralith:orchid_swamp | terralith:alpine_grove | \
      terralith:lush_desert | terralith:arid_highlands | terralith:forested_highlands | \
      terralith:birch_taiga | terralith:shield | terralith:shield_clearing | \
      terralith:moonlight_valley | terralith:moonlight_grove)
      tier="green"
      ;;
  esac

  # --- Tier 2: OK - half points ---
  # Only check if not already matched as green (prevents downgrading)
  if [[ "$tier" == "bad" ]]; then
    # Survivable but not ideal - denser forests, taigas, jungles, plateaus
    case "$clean" in
      dark_forest | taiga | old_growth_pine_taiga | old_growth_spruce_taiga | \
        jungle | bamboo_jungle | windswept_hills | windswept_forest | \
        windswept_gravelly_hills | wooded_badlands | river | beach | \
        stony_shore | mangrove_swamp | snowy_plains | snowy_taiga | \
        grove | desert)
        tier="ok"
        ;;
    esac

    # Terralith OK biomes
    case "$biome" in
      terralith:cloud_forest | terralith:haze_mountain | terralith:rocky_mountains | \
        terralith:caldera | terralith:mirage_isles | terralith:granite_cliffs | \
        terralith:highlands | terralith:basalt_cliffs | terralith:hot_shrubland | \
        terralith:desert_canyon | terralith:desert_oasis | terralith:fractured_savanna | \
        terralith:red_oasis | terralith:savanna_badlands | terralith:savanna_slopes | \
        terralith:white_cliffs)
        tier="ok"
        ;;
    esac
  fi

  # --- Tier 3: Bad - zero points ---
  # Ocean, frozen, mushroom, deep dark, swamp, extreme peaks
  # These are the default (tier="bad"), so we don't need to list them all,
  # but here are the explicit ones for documentation:
  # ocean, deep_ocean, frozen_ocean, cold_ocean, lukewarm_ocean, warm_ocean,
  # swamp, mushroom_fields, deep_dark, frozen_peaks, jagged_peaks,
  # stony_peaks, ice_spikes, snowy_slopes, frozen_river, badlands,
  # eroded_badlands, the_void
  # (Any biome not matched above stays "bad")

  local score_x100=0
  case "$tier" in
    green)
      score_x100=$((weight * 100))
      printf "  Spawn biome: %d.00 / %d (%s - green/open, full marks)\n" \
        "$weight" "$weight" "$biome" >&2
      ;;
    ok)
      score_x100=$((weight * 50))
      printf "  Spawn biome: %d.00 / %d (%s - OK, half marks)\n" \
        "$((weight / 2))" "$weight" "$biome" >&2
      ;;
    bad)
      printf "  Spawn biome: 0.00 / %d (%s - bad spawn, zero marks)\n" \
        "$weight" "$biome" >&2
      ;;
  esac

  echo "$score_x100"
}

# ===========================================================================
# Main - compute and sum all category scores
# ===========================================================================
echo "--- Seed score breakdown ---" >&2

# Each score function returns points * 100 (for two decimal places)
s1=$(score_structure "$STRONGHOLD_DIST" "$CAP_STRONGHOLD" "$WEIGHT_STRONGHOLD" "Stronghold")
s2=$(score_structure "$VILLAGE_DIST" "$CAP_VILLAGE" "$WEIGHT_VILLAGE" "Village")
s3=$(score_structure "$FORTRESS_DIST" "$CAP_FORTRESS" "$WEIGHT_FORTRESS" "Fortress")
s4=$(score_structure "$BASTION_DIST" "$CAP_BASTION" "$WEIGHT_BASTION" "Bastion")
# Portal distance is tracked in the CSV for reference but not separately
# weighted - the six categories above already sum to 100. A nearby ruined
# portal is a nice bonus, not a scoring criterion.

s6=$(score_proximity "$FORTRESS_X" "$FORTRESS_Z" "$BASTION_X" "$BASTION_Z" \
  "$WEIGHT_FORT_BAST_PROXIMITY" "$CAP_FORT_BAST")
s7=$(score_biome "$SPAWN_BIOME" "$WEIGHT_SPAWN_BIOME")

# Sum the six weighted categories (each is score * 100)
total_x100=$((s1 + s2 + s3 + s4 + s6 + s7))
whole=$((total_x100 / 100))
frac=$((total_x100 % 100))

printf "  --------------------------------\n" >&2
printf "  TOTAL: %d.%02d / 100\n" "$whole" "$frac" >&2

# Output the final score to stdout (two decimal places)
printf "%d.%02d\n" "$whole" "$frac"
